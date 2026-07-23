# Project Memory

이 문서는 DPU Layer Lab의 장기 설계 맥락을 보존하는 canonical project memory입니다.
구현을 바꾸면 코드, test, `README.md`, 이 문서를 함께 갱신합니다.

## 목적

- Android AP의 display composition 한계와 DPU underrun 징후를 재현 가능한 phase로
  탐색한다.
- layer 수, format, buffer 크기, transform, refresh/producer pacing과 CPU·memory·GPU·NPU
  교차 부하의 상관관계를 기록한다.
- portable APK에서 얻을 수 있는 값과 platform/vendor integration이 필요한 값을
  명확히 분리한다.
- exact hardware counter가 없을 때 사실처럼 추측하지 않고 proxy/inconclusive로 남긴다.
- 실험용 부하가 메모리 고갈, runaway worker 또는 지속 accelerator lease로 이어지지
  않게 한다.

## 핵심 설계 결정

1. **실제 BufferQueue를 사용한다.** 독립 합성 실험은 여러 `SurfaceView`, client/GPU
   비교군은 `TextureView`/flattened producer를 사용한다.
2. **시나리오는 phase의 순서다.** 각 phase가 layer/backend/format/size/motion/fps/Hz와
   cross-load setpoint를 완전히 기술한다.
3. **부하 상승 뒤 recovery를 둔다.** 부하 획득과 해제를 같은 run에서 관찰한다.
4. **입력과 실행 계획을 분리한다.** catalog/custom 입력을 그대로 render하지 않고
   runtime safety policy로 validate, clamp 또는 reject한다.
5. **메모리 budget은 triple buffering을 가정한다.** 총 RAM과 현재 available memory를
   함께 사용한 보수적 graphics budget이며, allocator/decoder 실제 사용량의 대체물은
   아니다.
6. **portable과 vendor 계측을 분리한다.** 공통 API는 Android service/앱 측정/proxy를
   제공하고, exact DPU/DDR/HWC/SBWC/NPU는 signature-protected AIDL broker를 사용한다.
7. **값마다 provenance를 유지한다.** `MetricQuality`와 `source` 없이 숫자를 노출하지
   않는다. unavailable은 `N/A`다.
8. **영상은 codec-to-Surface다.** SAF URI를 `MediaExtractor`/`MediaCodec`로 decode해
   primary Surface에 직접 출력한다.
9. **traffic은 별도 모델이다.** hardware counter와 합치지 않고 linear full-buffer
   estimate로만 표시한다.
10. **보고서는 로컬 우선이다.** app-scoped JSON으로 저장하며 사용자가 명시적으로
    공유할 때만 다른 앱에 URI 권한을 준다.

## 반드시 유지할 불변식

- hard cap은 layer 20, producer 120 fps, requested display 240 Hz다.
- scenario는 최대 128 phase이며 device 기본 envelope는 phase 10분, 전체 30분이다.
- 0 이하 duration/layer/FPS/Hz, NaN/무한대, 빈/중복 ID와 overflow 가능 입력은
  render 전에 reject하고, 범위를 벗어난 유한 workload는 clamp한다.
- 한 producer조차 graphics memory budget을 넘으면 실행하지 않는다.
- layer clamp/reject, low-RAM/power-save cap, thermal derate/abort, low-memory abort는
  event 또는 결과에서 식별 가능해야 한다.
- thermal `SEVERE` 이후의 layer/FPS/Hz/workload derating은 다음 phase에서 원래
  setpoint로 되돌아가지 않는다.
- thermal `CRITICAL` 이상과 `ActivityManager.MemoryInfo.lowMemory` 감지는 run을
  중단한다.
- 정상/중단/예외/Activity 종료 시 CPU·memory worker, codec, Surface, GL, NPU,
  compression request, wake flag를 해제한다.
- exact underrun은 monotonic vendor/kernel counter에서만 만든다.
- missed frame, HWC/GPU miss, producer stall은 proxy이며 exact DPU underrun으로
  승격하지 않는다.
- NPU adapter가 없으면 `UNSUPPORTED`이며 CPU fallback을 NPU로 표시하지 않는다.
- SBWC REQUIRED는 allocation/compression state를 검증할 provider가 없으면
  `UNSUPPORTED`다.
- report 또는 log에 key, token, keystore password를 기록하지 않는다.

## 현재 구현

- Compose 기반 scenario browser, system dashboard, running HUD, result 화면
- catalog 및 custom phase
- 독립 Surface, mixed Surface/Texture, flattened RGBA, GLES stress layer
- scroll/zoom/pan/rotate/parallax/storm/Z-order animation
- RGB8888/RGB565 pattern과 MediaCodec video Surface
- bounded CPU worker, 기기 등급에 따라 1~2개의 bounded memory-copy worker, GLES load,
  vendor NPU hook
- 1초 telemetry sample과 최근 60 sample HUD
- Android service, kernel allowlist, SurfaceFlinger parser, vendor AIDL 계측
- exact/proxy 기반 verdict
- JSON report와 FileProvider 공유
- host-side unit test와 Android lint/build

## 현재 한계

- DPU utilization, DDR busy, exact underrun, DEVICE/CLIENT layer와 SBWC state는
  BSP/vendor source 없이는 일반화할 수 없다.
- SurfaceFlinger text dump parser는 release/BSP별 형식 변화에 취약하다.
- graphics budget은 stride, tile, codec reference/private buffer를 완전히 반영하지 않는다.
- traffic estimate는 crop/cache/tiling/intermediate target/SBWC ratio를 반영하지 않는다.
- YUV/P010 실제 format은 입력 stream과 decoder output 정책에 달려 있다.
- codec capability는 sustained 또는 concurrent decode 가능성을 보장하지 않는다.
- requested display Hz는 실제 mode 전환을 보장하지 않는다.
- report에 build fingerprint와 선택 media의 이름/metadata가 포함될 수 있다.
- report 자동 retention/만료 정책은 아직 없다.
- vendor service는 샘플 계약만 있고 reference provider 구현은 이 저장소에 없다.

## 다음 작업

우선순위가 높은 후속 작업:

1. 실제 target BSP에서 platform-signed release 및 privapp permission 검증
2. vendor broker reference implementation과 VINTF-stable AIDL/version/hash 정책
3. broker의 NPU/SBWC lease token, heartbeat, client-death/timeout watchdog
4. HWC composition snapshot과 DPU/DDR counter의 display/sampling scope 명문화
5. 4K/8K/P010/SBWC 자산 manifest와 decoder output/allocation 검증
6. physical-device endurance, `MemoryInfo.lowMemory`, thermal, Activity lifecycle
   fault-injection test
7. report 목록/삭제 UI와 사용자 설정 가능한 retention 정책
8. runtime clamp/reject/derate event를 포함한 report schema versioning

## 검증 명령

PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='D:\Project\Android_SDK'

.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

안전 정책 또는 renderer를 바꿨다면 최소한 다음을 추가 확인합니다.

- 음수/0/NaN/무한대, cap 직전/직후 값의 unit test
- 1개 8K producer가 budget을 넘는 경우 reject
- layer clamp 시 logical/producer count와 report event 일치
- low-RAM, power-save, `MemoryInfo.lowMemory`, thermal SEVERE/CRITICAL state transition
- phase 전환, 사용자 stop, exception, Activity destroy 뒤 worker/codec/NPU 해제
- vendor source가 없을 때 DPU/GPU/bus가 `N/A`이고 proxy verdict만 생성되는지

실기기 stress test는 사용자가 대상 실험기와 실행 범위를 명시한 경우에만 수행합니다.

## 파일 지도

| 역할 | 경로 |
|---|---|
| Activity / display mode | `app/src/main/java/com/example/dpulayerlab/MainActivity.kt` |
| Compose UI / HUD | `app/src/main/java/com/example/dpulayerlab/ui/DpuLayerLabApp.kt` |
| 실행 상태와 안전 제어 | `app/src/main/java/com/example/dpulayerlab/engine/LabController.kt` |
| 입력 검증 / graphics budget | `app/src/main/java/com/example/dpulayerlab/model/ScenarioSafetyPolicy.kt` |
| device 안전 envelope | `app/src/main/java/com/example/dpulayerlab/engine/DeviceRenderSafety.kt` |
| scenario catalog | `app/src/main/java/com/example/dpulayerlab/engine/ScenarioCatalog.kt` |
| CPU/memory/NPU 부하 | `app/src/main/java/com/example/dpulayerlab/engine/LoadGenerators.kt` |
| report | `app/src/main/java/com/example/dpulayerlab/engine/ReportWriter.kt` |
| model / telemetry | `app/src/main/java/com/example/dpulayerlab/model/LabModels.kt` |
| traffic estimate | `app/src/main/java/com/example/dpulayerlab/model/LayerTrafficEstimator.kt` |
| BufferQueue / codec | `app/src/main/java/com/example/dpulayerlab/render/LayerStageView.kt` |
| GLES stress | `app/src/main/java/com/example/dpulayerlab/render/StressGlSurfaceView.kt` |
| system monitor | `app/src/main/java/com/example/dpulayerlab/monitor/SystemMonitor.kt` |
| kernel probes | `app/src/main/java/com/example/dpulayerlab/monitor/KernelSensorProvider.kt` |
| SurfaceFlinger parser | `app/src/main/java/com/example/dpulayerlab/monitor/SurfaceFlingerProbe.kt` |
| vendor Binder client | `app/src/main/java/com/example/dpulayerlab/vendor/VendorBridge.kt` |
| AIDL contract | `app/src/main/aidl/com/example/dpulayerlab/vendor/IDpuLabVendorService.aidl` |
| product integration | `system_integration/`, `docs/SYSTEM_INTEGRATION.md` |
| contributor rules | `AGENTS.md` |
