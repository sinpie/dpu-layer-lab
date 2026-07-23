# DPU Layer Lab

Android AP의 DPU underrun 재현·검출과 Hardware Composer 합성 한계 탐색을 위한
실험용 앱입니다. 독립 BufferQueue layer, 화면 변환, 고해상도 영상, CPU·메모리·GPU·NPU
교차 부하를 단계적으로 올리고 내리면서 계측값과 판정 근거를 JSON 보고서로 남깁니다.

일반 APK에서도 실행할 수 있습니다. Platform-signed `priv-app` 또는 vendor telemetry
service를 연결하면 DPU·DDR·HWC·SBWC·NPU와 같은 제품 전용 기능을 추가로 사용할 수
있습니다.

> [!CAUTION]
> 이 앱은 의도적으로 발열, 메모리 대역폭 포화, 프레임 지연과 드라이버 경계 조건을
> 유발합니다. 일상용 단말이나 보존해야 하는 데이터가 있는 단말에서 실행하지 마세요.
> 전원 차단과 복구가 가능한 전용 실험기, 적절한 냉각, 콘솔/ADB 접근이 준비된 환경에서
> 사용해야 합니다. 앱의 safety cap은 우발적인 과부하를 줄이는 방어선이지, BSP·PMIC·패널
> 또는 실리콘의 안전을 보증하는 장치가 아닙니다.

## 주요 기능

- 최대 20개의 독립 `SurfaceView` BufferQueue layer
- 독립 Surface / Surface+Texture 혼합 / GPU flattened composition A/B
- layer별 scroll, zoom, pan, 임의 회전, parallax, alpha overlap, Z-order swap
- producer 30~120 fps 및 display 60/90/120 Hz 요청·실제 Hz 동시 기록
- RGB 8888/565, YUV decoder-to-Surface, P010 콘텐츠, SBWC vendor hook
- SAF로 선택한 로컬 4K/8K H.264/HEVC/AV1 영상을 `MediaExtractor` +
  `MediaCodec`에서 Surface로 직접 출력
- GLES 2.0 3D cube/fragment 부하
- CPU 수치 연산, 메모리 copy 기반 bus 부하, pulse/ramp/saw/steady profile
- vendor AIDL 또는 제품 classpath adapter를 통한 NPU workload
- CPU, app CPU, memory/PSS, producer FPS, display Hz, thermal, GPU/bus/DPU dashboard
- 실행 중 좌측 상단 layer/DPU/CPU/GPU 숫자·점유율·60-sample 그래프
- format·buffer size·layer 수·scanout Hz 기반 예상 traffic
- HWC DEVICE/CLIENT layer 파싱(`DUMP` 권한이 있을 때)
- exact underrun counter와 frame-deadline proxy를 분리한 판정
- thermal·low-memory·graphics-memory budget 기반 런타임 안전 정책
- phase/event/telemetry를 포함한 로컬 JSON 결과와 명시적 공유

## 런타임 안전 정책

모든 preset과 custom phase는 실제 renderer에 전달되기 전에 다시 검증됩니다.

- 절대 상한: layer 20개, producer 120 fps, requested display 240 Hz
- 비정상 입력: 빈/중복·과도하게 긴 ID/label, 0 이하 duration/layer/FPS/Hz,
  NaN/무한대, 128개를 넘는 phase는 실행 전에 거부합니다. 범위를 벗어난 유한
  workload는 0과 device 상한 사이로 clamp합니다.
- 기본 시간 상한: phase당 10분, scenario 전체 30분
- graphics budget: 기기 총 RAM과 실행 시점의 available memory를 기준으로
  triple-buffered layer 메모리를 보수적으로 계산합니다.
- budget 안에 들어오도록 layer 수를 줄일 수 있으면 clamp하고 이벤트에 기록합니다.
- 단 하나의 producer buffer도 budget을 넘으면 해당 phase 또는 scenario를 실행하지
  않습니다.
- low-RAM 기기 또는 power-save 상태에서는 FPS와 교차 부하 상한을 더 낮춥니다.
- `ActivityManager.MemoryInfo.lowMemory` 또는 memory-load allocation 실패가 감지되면
  실행 중인 test를 중단하고 모든 부하를 해제합니다.
- thermal `SEVERE`부터 layer/FPS/Hz와 CPU·memory·GPU·NPU 부하를 줄이며, 이
  derating은 뒤 phase에도 유지됩니다.
- thermal `CRITICAL` 이상이면 test를 즉시 중단합니다.
- telemetry가 5초 이상 멈추거나 phase primary producer가 3초 안에 frame을 만들지
  못하면 run을 중단해 정지한 worker/decoder 위에 부하가 계속 쌓이지 않게 합니다.
- 정상 완료, 사용자 중단, 예외, 화면 종료 모두에서 worker, codec, Surface, NPU load,
  wake flag를 해제하는 것이 불변식입니다.

이 정책의 memory 계산은 stride, allocator metadata, decoder private buffer, GPU tile
storage를 완전히 알 수 없으므로 보수적 휴리스틱입니다. “허용됨”은 해당 SoC가 지속적으로
처리할 수 있다는 성능 인증이 아닙니다.

## 시나리오

| 카테고리 | 대표 테스트 |
|---|---|
| Layer / HWC | HWC Plane Staircase, HWC ↔ GPU Composition Pivot |
| Transform | 12-layer Transform Storm |
| Video / Format | 4K YUV + RGB Overlay, 8K Decoder Pressure, Linear ↔ SBWC |
| Refresh | 60 → 90 → 120 Hz baseline, mixed producer pacing |
| Resource | CPU / Memory / GPU Pulse, NPU Cross-load |
| Adaptive | layer/memory staircase underrun hunt |
| Soak | mixed load/thermal regression cycle |

각 stress preset에는 부하를 다시 내리는 recovery phase가 포함됩니다. 런타임 안전 정책이
phase를 clamp하거나 거부할 수 있으므로, 보고서의 실행 event를 원래 preset과 함께
확인해야 합니다.

## 권장 사용 순서

1. **시스템** 탭에서 display mode, HardwareBuffer, 4K/8K decoder, direct sensor와 vendor
   adapter 연결 상태를 확인합니다.
2. YUV/P010/4K/8K test라면 **시나리오** 탭에서 실험용 로컬 영상을 선택합니다.
3. 냉각 상태에서 `Baseline 60 → Max`, 다음 `HWC Plane Staircase`를 실행합니다.
4. transform, resource pulse, composition pivot 순서로 실패 경계를 좁힙니다.
5. 안전 clamp/reject/derate/abort event가 있었는지 먼저 확인한 뒤 결과를 해석합니다.
6. `Exact underrun Δ`가 값이면 직접 counter 판정입니다. `Suspected proxy`만 증가한
   경우 DPU underrun으로 확정하면 안 됩니다.

영상이 선택되지 않은 YUV 시나리오는 `PROXY_FALLBACK` event를 기록합니다. SBWC
`REQUIRED`와 NPU 시나리오는 실제 vendor adapter가 없으면 `UNSUPPORTED`로 끝납니다.

정확한 장시간 실험에서는 화면 녹화, 미러링, 무선 display와 개발자 GPU overlay를
끄세요. 이 기능들 자체가 composition 및 bus 부하를 바꿉니다.

## 계측값 해석

Android 공통 API에는 범용 DPU utilization, underrun counter, DDR bandwidth, overlay
plane 수, SBWC 강제 API가 없습니다. 앱은 출처와 품질을 다음처럼 구분합니다.

| 품질 | 의미 |
|---|---|
| `HW counter` | vendor AIDL이 제공한 하드웨어 누적값/active cycle |
| `Kernel` | allowlist에 등록되고 읽기 가능한 sysfs 값 |
| `System` | Android system service 또는 SurfaceFlinger dump |
| `Measured` | 앱이 직접 센 frame/cpu/copy byte |
| `Estimated` | 명시된 모델로 계산한 값 |
| `Proxy` | frame deadline miss 등 간접 징후 |
| `Unavailable` | 신뢰할 source가 없어 `N/A` |

`DPU busy`와 exact underrun은 vendor counter 또는 허용된 kernel node가 있을 때만
표시합니다. SurfaceFlinger의 missed frame, 앱 producer stall, `Choreographer` deadline
miss는 DPU underrun의 **proxy**일 뿐입니다. CPU scheduling, GC, codec, GPU, thermal
throttling 또는 다른 프로세스도 같은 현상을 만들 수 있습니다. Exact counter가 없으면
최종 verdict도 `SUSPECTED / PROXY` 또는 `INCONCLUSIVE` 범위를 벗어나지 않습니다.

SurfaceFlinger text dump의 DEVICE/CLIENT layer count는 Android 및 BSP 버전에 따라
형식이 달라질 수 있습니다. 제품 판정에는 vendor typed API를 우선하세요.

### 예상 layer traffic

실행 HUD는 다음 선형 full-buffer 모델을 사용합니다.

```text
producer bytes/frame = Σ(각 실제 producer의 width × height × output B/px)
producer bytes/s     = producer bytes/frame × producer fps
DPU read bytes/s     = 직접 Surface 합 + mixed client target × 실제 display Hz
```

기준값은 RGBA 8888 4 B/px, RGB 565 2 B/px입니다. YUV 420 1.5 B/px와 P010 3 B/px는
선택한 영상이 decoder-to-Surface 경로를 실제로 사용할 때 primary에만 적용합니다.
영상이 없는 YUV/P010 procedural 화면과 `TextureView`, alpha, GL 출력은 실제
BufferQueue 형식대로 RGBA 4 B/px로 계산합니다. mixed backend의 TextureView 여러 장은
한 개의 display-sized RGBA client target read로, flattened backend는 논리 layer 수와
별개로 한 개의 RGBA producer로 계산합니다. explicit 4K/8K 크기는 현재 renderer와
같이 primary producer에 적용합니다. HUD/control layer와 시스템 UI traffic은 제외합니다.

이 값은 capacity 또는 실제 bus counter가 아닙니다. 다음 항목은 포함하지 않습니다.

- stride/alignment, tiling, padding, allocator/codec metadata
- crop, occlusion, damage region, caching, prefetch
- scaling/rotation에 따른 내부 read 및 intermediate target
- HWC CLIENT target 전환과 GPU read/write
- decoder reference frame과 private buffer
- panel DSC와 vendor SBWC의 실제 압축률

SBWC는 base format과 실측 압축률을 portable API로 알 수 없어 32-bit linear reference를
표시하고 `ratio 제외`라고 명시합니다. 실제 DPU/bus counter가 있으면 estimate와 별도
열로 비교해야 합니다.

## 빌드와 검증

요구 환경:

- JDK 17
- Android SDK 36
- Android Gradle Plugin 8.12.2
- Gradle wrapper 8.13

Windows PowerShell 예:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='D:\Project\Android_SDK'

.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

산출물:

- debug: `app/build/outputs/apk/debug/app-debug.apk`
- release: `app/build/outputs/apk/release/app-release-unsigned.apk`

Debug build는 package suffix가 `.debug`이므로 제품의 release privapp allowlist와
동일하게 취급되지 않습니다. 실제 system integration 검증은 release package로
수행하세요.

### Platform signing / 제품 이미지 포함

제품에서는 `system_integration/product/Android.bp`를 사용해 Soong이 platform
certificate로 서명하도록 하는 방식을 권장합니다. 외부 signing이 필요하면 secure
build machine에서 실행합니다.

```powershell
apksigner sign `
  --key C:\secure-keys\platform.pk8 `
  --cert C:\secure-keys\platform.x509.pem `
  --out DpuLayerLab-release.apk `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

Platform key, certificate, keystore password 또는 vendor signing material을 저장소와
`dist/`에 넣지 마세요. Platform signing/`priv-app` 배치만으로 vendor node의 DAC 또는
SELinux 접근이 생기지 않습니다. 최소 권한의 system broker와 typed Binder API를
사용하는 제품 통합 절차는 [docs/SYSTEM_INTEGRATION.md](docs/SYSTEM_INTEGRATION.md)를
참고하세요.

## 보고서 개인정보와 보존

- 보고서는 app-scoped external files의 `reports/`에 JSON으로 저장됩니다.
- 앱에는 network upload 경로가 없으며, 사용자가 **공유**를 누른 경우에만 선택한 앱에
  임시 read URI 권한을 줍니다.
- JSON에는 제조사/모델/device, Android 버전, **build fingerprint**, 실행 시각,
  telemetry, event가 들어갑니다.
- 선택한 영상의 표시 이름, MIME, 해상도, FPS, 길이 같은 metadata가 `MEDIA_SOURCE`
  event에 포함될 수 있습니다. 영상 본문은 보고서에 복사하지 않습니다.
- `allowBackup=false`이고 backup/transfer rule에서도 앱 데이터를 제외합니다.
- 현재 자동 만료/개수 제한은 없습니다. 사용자가 파일 또는 앱 데이터를 삭제하거나
  앱을 제거할 때까지 보고서가 남을 수 있습니다.
- 외부 공유 전 fingerprint, 영상 이름, 제품 식별 정보와 사내 counter source 이름이
  포함되어도 되는지 반드시 검토하세요.

## 구조

```text
MainActivity
└─ Compose UI / live HUD
   └─ LabController
      ├─ ScenarioCatalog + runtime safety policy
      ├─ LayerStageView
      │  ├─ SurfaceView / TextureView / GLES
      │  └─ MediaExtractor + MediaCodec → Surface
      ├─ LoadManager
      │  ├─ bounded CPU / memory workers
      │  └─ vendor AIDL or NPU classpath adapter
      ├─ SystemMonitor
      │  ├─ Android services / FrameTracker
      │  ├─ allowlisted kernel probes / SurfaceFlinger
      │  └─ VendorBridge
      └─ ReportWriter
```

주요 파일:

- 시나리오: `app/src/main/java/com/example/dpulayerlab/engine/ScenarioCatalog.kt`
- 실행/안전: `app/src/main/java/com/example/dpulayerlab/engine/LabController.kt`
- 입력 검증/budget: `app/src/main/java/com/example/dpulayerlab/model/ScenarioSafetyPolicy.kt`
- device 안전 envelope: `app/src/main/java/com/example/dpulayerlab/engine/DeviceRenderSafety.kt`
- 부하 발생기: `app/src/main/java/com/example/dpulayerlab/engine/LoadGenerators.kt`
- multi-layer/codec: `app/src/main/java/com/example/dpulayerlab/render/LayerStageView.kt`
- traffic 모델: `app/src/main/java/com/example/dpulayerlab/model/LayerTrafficEstimator.kt`
- portable/vendor 계측: `app/src/main/java/com/example/dpulayerlab/monitor/`
- vendor client: `app/src/main/java/com/example/dpulayerlab/vendor/VendorBridge.kt`
- AIDL 계약: `app/src/main/aidl/com/example/dpulayerlab/vendor/IDpuLabVendorService.aidl`

장기 설계 메모는 [PROJECT_MEMORY.md](PROJECT_MEMORY.md), 기여 및 agent 작업 규칙은
[AGENTS.md](AGENTS.md)를 참고하세요.

## 알려진 한계

- 실제 YUV/P010 경로는 선택 영상의 codec과 decoder가 해당 output을 지원해야 합니다.
  영상이 없을 때 procedural 화면은 시각적 proxy입니다.
- codec capability 선언은 동시 instance, 지속 thermal 성능 또는 특정 8K stream의
  정상 재생을 보장하지 않습니다.
- SBWC 선택·검증은 vendor gralloc/codec adapter가 필요합니다.
- NPU는 vendor service 또는 실제 accelerator adapter가 필요합니다. CPU fallback을
  NPU로 표시하지 않습니다.
- 20 layer는 앱의 hard cap이지 SoC의 overlay plane 수가 아닙니다. 실제 DEVICE/CLIENT
  배치는 HWC 정책, format, transform, alpha, scale, secure/HDR 조건에 따라 달라집니다.
- requested refresh는 힌트/선호 mode이며, 실제 display Hz는 패널 mode와 시스템 정책에
  의해 달라질 수 있습니다.
- portable 앱만으로 AP 전체 bus 점유율 또는 DPU active cycle을 알 수 없습니다.
- exact counter라도 counter reset/wrap, display scope와 sampling interval은 vendor
  계약에서 명확히 정의해야 합니다.
- 화면 녹화, profiler, ADB tracing 자체가 측정 대상에 영향을 줄 수 있습니다.
