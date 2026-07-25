# DPULayerTest

![DPULayerTest icon](docs/assets/dpu-layer-lab-icon.png)

Android AP의 DPU underrun 재현·검출과 Hardware Composer 합성 한계 탐색을 위한
실험용 앱입니다. 독립 BufferQueue layer, 화면 변환, 고해상도 영상, CPU·메모리·GPU·NPU
교차 부하를 단계적으로 올리고 내리면서 계측값과 판정 근거를 JSON 보고서로 남깁니다.

일반 APK에서도 실행할 수 있습니다. Platform-signed `priv-app` 또는 vendor telemetry
service를 연결하면 DPU·DDR·HWC·SBWC·NPU와 같은 제품 전용 기능을 추가로 사용할 수
있습니다.

현재 launcher/Gradle project 표시 이름과 release 앱 버전은
**DPULayerTest 20260725_090252**(`versionCode 4`)입니다. Debug 변형은 화면과
보고서에 `20260725_090252-debug`로 표시됩니다. Version name은 KST build 시각을
`yyyyMMdd_HHmmss`로 고정한 형식입니다. 앱 상단과 실행 HUD에 같은 build version을
노출해 결과를 만든 바이너리를 현장에서 바로 식별할 수 있습니다.
소스 저장소는 계속 [sinpie/dpu-layer-lab](https://github.com/sinpie/dpu-layer-lab)을
사용합니다. 기존 제품 이미지와 자동화 harness를 깨지 않기 위해 package
`com.example.dpulayerlab`, START/STOP/SHOW action, `dpu-layer-lab-` report prefix,
Soong module/APK 통합 이름 `DpuLayerLab`은 이름을 바꾸지 않는 호환성 계약입니다.

> [!CAUTION]
> 이 앱은 의도적으로 발열, 메모리 대역폭 포화, 프레임 지연과 드라이버 경계 조건을
> 유발합니다. 일상용 단말이나 보존해야 하는 데이터가 있는 단말에서 실행하지 마세요.
> 전원 차단과 복구가 가능한 전용 실험기, 적절한 냉각, 콘솔/ADB 접근이 준비된 환경에서
> 사용해야 합니다. 앱의 safety cap은 우발적인 과부하를 줄이는 방어선이지, BSP·PMIC·패널
> 또는 실리콘의 안전을 보증하는 장치가 아닙니다.

## 주요 기능

- 최대 20개의 독립 `SurfaceView` BufferQueue layer
- 독립 Surface / Surface+Texture 혼합 / GPU flattened composition A/B
- layer별 scroll, zoom, pan, 임의 회전, parallax, alpha overlap, View/client Z-order
  swap proxy(physical HWC Z-order 변경의 증거는 아님)
- producer 30~120 fps 및 display 60/90/120 Hz 요청·실제 Hz 동시 기록
- RGB 8888/565, YUV/P010/SBWC selected-media decoder-to-Surface, SBWC vendor hook
- SAF로 선택한 로컬 4K/8K H.264/HEVC/AV1 영상을 `MediaExtractor` +
  `MediaCodec`에서 Surface로 직접 출력
- app-owned EGL thread와 SurfaceView를 사용한 GLES 2.0 3D cube/fragment 부하
- CPU 수치 연산, 메모리 copy 기반 bus 부하와 steady/pulse/ramp/saw generator profile
- layer/FPS/Hz/교차 부하 전체를 instant step, linear ramp, staircase, burst,
  triangle wave, soak/recovery로 바꾸는 phase transition
- fixed-period CPU/memory worker와 bounded latest-wins NPU 제어로 backlog 없이 부하를
  올렸다가 해제
- vendor AIDL 또는 제품 classpath adapter를 통한 NPU workload
- CPU, app CPU, memory/PSS, producer FPS, display Hz, thermal, GPU/bus/DPU dashboard
- plan 시작 전에 status/navigation bar가 실제로 숨겨졌는지 확인하는 test-only immersive
  fullscreen. Multi-window/PiP에서는 시작을 거부하고, 확인 뒤 bar 재등장이나 window
  focus 손실은 측정 오염으로 즉시 중단
- 실행 중 좌측 상단 build version, layer/DPU/CPU/GPU 숫자·점유율·60-sample 그래프
  (`observed/—P`는 topology commit 대기, `observed/expected P`는 commit 완료).
  각 gauge는 source/quality를 함께 표시하고 provenance 변경이나 unavailable 구간에서는
  선을 연결하지 않습니다.
- 결과 화면의 DPU/GPU/bus/produced FPS와 HWC DEVICE/CLIENT peak
- format·buffer size·layer 수·scanout Hz 기반 예상 traffic
- HWC DEVICE/CLIENT layer 파싱(`DUMP` 권한이 있을 때)
- exact underrun counter와 frame-deadline proxy를 분리한 판정
- thermal·low-memory·graphics-memory budget 기반 런타임 안전 정책
- 카테고리·변화 파형·예상 강도·부하/조건을 조합하는 시나리오 필터와 순서가 보존되는
  queue, plan 반복 및 실행/결과 진행률
- phase/event/telemetry를 포함한 로컬 JSON 결과와 명시적 공유

Backend/pixel route/buffer size 같은 topology가 바뀌는 경계는 검증된 target topology와
layer 수로 즉시 전환합니다. Producer FPS, requested Hz와 CPU/memory/GPU/NPU setpoint는
transition envelope에 따라 보간하며, layer 수 보간은 topology가 같을 때만 수행합니다.
Safety policy는 100 ms control cadence에서 실제 적용될 transition window를 다시
검증합니다. Ramp에는 중간 tick, staircase에는 각 level, soak/recovery에는 관측 가능한
attack/hold/release, pulse/triangle에는 최소 한 cycle이 있어야 합니다. `STEP`은 fresh
baseline 뒤 origin producer buffer를 generation 안에서 먼저 확인하고 다음 measured
active tick에서 target을 적용하며, 그 tick까지 실행할 시간이 없으면 `INCONCLUSIVE`입니다.
실행 loop도 이전 tick이 늦어졌을 때 busy catch-up하지 않는 absolute-deadline
fixed-period cadence를 사용합니다. Runtime coverage가 ramp 중간값, staircase의 모든
level, pulse ON/OFF, triangle 상승/하강, soak attack/hold/recovery를 실제로 관측하지
못하면 완료처럼 보이지 않고 `INCONCLUSIVE`입니다.
`floor`는 반복 파형인 pulse/triangle의 valley에만 적용합니다. STEP/linear/staircase/
soak 같은 one-shot transition에 0이 아닌 `floor`를 넣은 runnable plan은 의미가
모호하므로 safety policy가 거부합니다. 순수 evaluator의 defensive bounding만 잘못된
직접 호출이 origin을 건너뛰지 않도록 floor를 0으로 지웁니다.

### 테스트 전체 화면과 HWC layer

앱의 일반 탐색 화면은 system bar를 유지하지만, test plan을 시작하면 Window 단위
immersive session을 획득합니다. `statusBars`와 `navigationBars`가 모두 invisible인
Insets acknowledgment를 받은 뒤에만 warm-up Surface와 physical producer를 만듭니다.
이 상태는 queue/loop 전체, cooldown, 최종 counter sample과 producer teardown까지
유지합니다. STOP·실패·백그라운드 전환·Activity 종료 시에도 `show()` 반환만 믿지
않고 시작 전의 status/navigation visibility mask가 실제 Insets로 다시 관측될 때까지
token과 process-wide lease를 유지합니다. Hide가 부분 실패해도 cleanup token을
버리지 않으며 복원 미확인 동안 다음 plan을 차단합니다. Activity가 재생성돼도 새
Window는 이전 Window의 process-wide lease가 해제되기 전까지 system bar hide를
적극적으로 유지하고, matching release와 visible Insets를 확인한 뒤 일반 탐색 UI를
복구합니다. 새 Window의 hide가 100 ms 간격의 4회 확인에도 적용되지 않으면 무한
재시도하지 않고 원래 lease owner를 fail-closed 중단하며 다음 START도 계속 막습니다.

Android의 표준 immersive API는 사용자의 edge swipe를 영구 차단하지 않습니다. 최초
hide 대기 중 보이는 bar는 정상 전이지만, 숨김 확인 뒤 transient bar가 다시 보이거나
session 획득 뒤 notification shade/다른 overlay 때문에 window focus를 잃으면
`SYSTEM_UI_REVEALED`/`WINDOW_FOCUS_LOST` event를 남기고 현재 plan을 `ABORTED`로
종료합니다. 재현성 때문에 multi-window와 PiP에서는 plan을 시작하지 않으며 실행 중
해당 mode로 전환돼도 안전 중단합니다.

이 동작은 status bar, notification shade entry surface, navigation bar/taskbar가
HWC 후보를 차지하는 영향을 줄입니다. 그러나 좌측 상단 HUD를 포함한 앱 control
window/client target 한 장은 의도적으로 남고, 각 `SurfaceView`가 최종적으로
DEVICE/CLIENT 중 어디에 배치되는지는 여전히 HWC 정책이 결정합니다. Insets hidden은
SystemUI surface 제거 요청의 확인이지 physical overlay plane 배치의 증거가 아니므로
HUD의 DEVICE/CLIENT 또는 제품 typed snapshot으로 함께 확인해야 합니다.

### 테스트 중 성능 환경 격리

테스트 중 정책 변경 범위는 **Battery Saver의 임시 해제 하나**입니다. 일반 APK나
platform signing만으로 이 전역 정책을 바꿀 수 있다고 가정하지 않으며, 제품이
`IDpuLabVendorService` API v3의 typed performance-policy broker를 구현한 경우에만
변경을 요청합니다. 앱은 `BEGIN` 전에 원래 Battery Saver 상태를 별도로 보존합니다.
원래 상태가 켜짐이었다면 broker가 실행 중에 이를 꺼도 power-save safety envelope는
계속 적용되므로, 임시 정책 변경이 layer/FPS/memory 상한을 우회하지 않습니다.

API v3 session은 10초 bounded lease이며 앱은 정상 실행 중 2초 cadence로 갱신합니다.
Provider는 client Binder death, lease expiry 또는 명시적 `END`에서 **BEGIN 직전의
정확한 상태**로 복구해야 합니다. 원래 상태가 켜짐이면 종료 뒤 다시 켜져야 합니다.
앱은 command version과 session/service identity를 확인하고, 갱신·상태 확인·복구가
불명확하면 다음 plan으로 넘어가지 않습니다. Broker가 없더라도 Battery Saver가 이미
꺼져 있으면 app-only monitoring으로 실행할 수 있지만, 켜져 있거나 remote mutation
가능성이 남은 모호한 응답이면 producer를 시작하지 않습니다.
Timeout 뒤 같은 session에 더 높은 version의 `END`만 재시도된 경우에는 이미 실행
중이던 이전 `END`의 exact acknowledgment도 복구 증거로 사용할 수 있습니다. 단,
process restore latch가 비었고 renewal Job이 실제 종료됐으며, 직접 읽은
`PowerManager.isPowerSaveMode`가 BEGIN 전 상태와 일치해야 controller의 소유권을
해제합니다. 늦은 응답이나 UI 상태 문자열만으로 복구를 추정하지 않습니다.

Battery Saver 외 정책은 이 계약의 제어 대상이 아닙니다. Thermal 보호와
low-memory 중단은 항상 유지되고, DPU/GPU/CPU frequency·DVFS·devfreq governor를
쓰거나 고정하지 않습니다. Doze/device-idle도 강제로 해제하지 않으며 interactive이고
non-idle이라는 시작 조건을 확인한 뒤 실행 중 변화를 감시합니다. 해당 조건을
제어해야 하는 제품은 향후 별도의 typed BSP 계약과 원상복구·death/expiry 의미를 먼저
정의해야 하며, 현재 앱은 지원하지 않는 제어를 성공처럼 표시하지 않습니다.

## 런타임 안전 정책

모든 preset과 custom phase는 실제 renderer에 전달되기 전에 다시 검증됩니다.

- 절대 상한: layer 20개, producer 120 fps, requested display 240 Hz
- 비정상 입력: 빈/중복·과도하게 긴 ID/label, 0 이하 duration/layer/FPS/Hz,
  NaN/무한대, 128개를 넘는 phase는 실행 전에 거부합니다. 범위를 벗어난 유한
  workload는 0과 device 상한 사이로 clamp합니다.
- Workload setpoint는 정확한 0 또는 `0.001`보다 큰 값이어야 합니다.
  `0 < load <= 0.001`은 UI/보고서에는 양수지만 worker가 사실상 유휴가 되는 모호한 test가 되므로
  실행 전에 거부합니다.
- 기본 시간 상한: phase당 10분, scenario 전체 30분
- scenario 전체 시간 상한을 넘으면 앞쪽 phase만 남기는 방식으로 자르지 않습니다.
  모든 phase에 최소 1 ms를 예약하고 나머지를 phase당 상한이 먼저 반영된 duration에
  비례 배분하며, 줄어든 phase의 ramp/soak window와 pulse/triangle cycle도 같은 비율로
  줄입니다. 한 cycle 또는 soak의 attack/hold/recovery 의미를 유지할 수 없을 만큼
  짧아지면 실행을 거부합니다.
- 총 RAM 기준 기본 envelope는 `<3 GiB: 6 layer/60 fps`, `<6 GiB: 12/90`,
  `<8 GiB: 16/120`, `8 GiB 이상: 20/120`입니다. 메모리 부하는 같은 등급의 다른
  교차 부하보다 더 낮게 제한합니다.
- logical CPU가 2/4/6 core 이하이면 각각 `4/45`, `6/60`, `12/90` 상한을 독립적으로
  적용합니다. `goldfish`/`ranchu` 등 emulator는 RAM과 무관하게 최대 `4 layer/60 fps`,
  제한된 CPU·memory·GPU·NPU 부하와 128 MiB 이하 graphics budget을 사용합니다.
  8 GiB·8 core 이상 물리 기기는 pressure 신호가 없으면 기존 절대 상한 `20/120`을
  유지합니다. 이 분류는 성능 점수가 아니라 시작 전에 적용하는 보수적 hard envelope입니다.
- graphics budget: 기기 총 RAM과 실행 시점의 available memory를 기준으로
  triple-buffered layer 메모리를 보수적으로 계산합니다. GL producer는 RGBA color
  buffer뿐 아니라 driver가 24/32-bit로 올릴 수 있는 depth attachment도 보수적으로
  4 B/px로 잡아 color와 depth를 각각 triple buffering합니다.
- budget 안에 들어오도록 layer 수를 줄일 수 있으면 clamp하고 이벤트에 기록합니다.
- 단 하나의 producer buffer도 budget을 넘으면 해당 phase 또는 scenario를 실행하지
  않습니다.
- low-RAM, 적은 core 수, emulator, power-save, low-memory cap은 우선순위로 하나만
  고르지 않고 항목별 최솟값으로 합칩니다. 원본 preset은 바꾸지 않고 effective phase의
  layer/FPS/workload만 clamp하며 safety adjustment event에 기록합니다.
- `ActivityManager.MemoryInfo.lowMemory` 또는 memory-load allocation 실패가 감지되면
  실행 중인 test를 중단하고 memory working set도 즉시 버린 뒤 모든 부하를 해제합니다.
- memory workload가 있는 scenario는 계측 baseline 전에 bounded worker별 working set을
  사전 할당하고 page touch acknowledgment를 기다립니다. 이 prewarm은 generated traffic
  byte에 더하지 않고 완료 뒤 byte baseline을 초기화합니다. 할당 실패, timeout, 중단 또는
  worker acknowledgment 누락은 부하가 실행된 것처럼 계속하지 않고 plan을 fail-closed로
  중단합니다. 확인된 buffer는 run 동안 pin해 5초가 넘는 idle/settle 뒤에도 measured
  phase에서 재할당되지 않으며, run 종료·low-memory·명시적 drop에서만 해제합니다.
- 앱의 선제 thermal `SEVERE` 감속은 테스트 설정에서 선택할 수 있으며 기본값은
  **OFF**입니다. OFF이면 SEVERE에서도 앱이 요청 layer/FPS/Hz/workload를 임의로
  낮추지 않지만 Android/kernel thermal throttling은 그대로 동작합니다. ON이면 시작 전
  SEVERE를 거부하고, 실행 중에는 generated/NPU load ordered zero → 축소 workload
  ticket/acknowledgment → display 감속 acknowledgment 순서로 적용한 뒤 남은 phase에도
  유지합니다. 하나라도 실패하면 `THERMAL_DERATE_FAILED`로 중단합니다.
- thermal `CRITICAL` 이상이면 test를 즉시 중단합니다.
- 이 선택 설정은 plan 시작 시 immutable snapshot으로 고정되며 보호된 외부 Intent도
  현재 앱 설정을 그대로 사용합니다. `CRITICAL`, low-memory, local-worker failure,
  Battery Saver/display/device-idle/SystemUI 격리 무결성 중단은 선택 설정과 무관하게
  항상 활성입니다.
- `BEGIN` 전에 관측한 원래 Battery Saver가 켜져 있으면 v3 broker가 실행 중 이를
  임시 해제해도 보수적인 power-save envelope를 적용합니다. 실행 중 Battery Saver가
  예기치 않게 켜지거나 session continuity를 잃으면 기존 메모리/layer/FPS 승인을
  재사용하지 않고 `SAFETY_ENVELOPE_CHANGED` 또는 performance-isolation failure로
  즉시 중단합니다. 원래 상태의 exact restore가 확인되기 전에는 다음 plan을
  시작하지 않습니다.
- 실행 중 display ID 또는 정규화한 physical pixel dimensions가 바뀌면 작은 화면에서
  승인한 graphics envelope를 재사용하지 않고 `SAFETY_ENVELOPE_CHANGED`로 중단합니다.
  같은 display에서 가로/세로 축만 교환되는 회전은 같은 envelope로 봅니다.
- 마지막으로 완료된 telemetry evidence가 5초 이상 stale이면 run을 중단합니다. 단,
  single-flight lane에 이미 수락된 sample은 4초 operation timeout과 다음 500 ms
  watchdog tick까지의 bounded completion window만 보호하며, 그 deadline을 넘기면
  이전 성공 sample이 있더라도 중단합니다. Physical producer 중 하나라도 3초 안에
  buffer를 게시하지 못하거나 3초 동안 heartbeat가 끊겨도 중단합니다.
  Ramp/triangle에서 게시되지 않은 layer가 사라진 peak topology도 정상 완료로 세지
  않습니다.
- Phase마다 generation이 승인한 모든 physical producer의 실제 buffer 수를 합산하고,
  실제 적용한 `producer FPS × physical producer 수`를 시간 적분한 기대값과 비교합니다.
  Flattened backend는 logical layer 수와 무관하게 producer 한 개입니다. 기대값이
  30 frame 이상인데 실제가 70% 미만이면 `PRODUCER_RATE_SHORTFALL` event를 남기고,
  신뢰할 exact underrun 증가가 확인되지 않은 run은 `INCONCLUSIVE`로 판정합니다.
- producer callback은 warm-up/phase/cooldown마다 새 generation token으로 분리합니다.
  Buffer 게시 전에 immutable token을 capture하고 모든 physical producer ID가 먼저
  선언·게시됐는지 확인하므로 이전 Surface/codec의 늦은 frame이나 topology 게시 전
  단일 frame을 다음 phase의 시작 성공으로 세지 않습니다. Token과 producer heartbeat
  timestamp 저장은 frame hot path에서 객체를 매번 만들지 않습니다. Phase 시간,
  transition, 예상 frame budget과 `PHASE_START`는 실제 topology가 게시되고 fresh
  counter sample을 얻은 뒤에만 시작하며, 준비 중 CPU·memory·GPU·NPU 부하는 0입니다.
  실행 중 topology가 pending으로 바뀌는 callback 순간에도 physical frame counter까지
  기대값을 정산하고 CPU·memory·NPU 교차 부하와 display request를 즉시 안전값으로
  내립니다. 다음 100 ms poll까지 존재하지 않는 producer frame을 기대값에 더하거나
  교차 부하를 남기지 않습니다. Pending/미게시 topology는 HUD에서 fake `1P`가 아니라
  `—P`로 표시합니다.
- codec/Canvas/EGL의 짧은 UI hand-off를 넘긴 teardown은 process-wide lease로 넘겨
  main thread를 막지 않고 최대 5초 동안 복구합니다. 그동안 phase 시간/transition/
  예상 frame budget을 정지하고 교차 부하를 0으로 내리며, lease가 풀리면 같은
  generation의 실제 topology와 fresh first-buffer 관측으로 이어서 실행합니다. 5초
  deadline을 넘기면 전체 plan을 중단하고 해당
  thread가 실제 종료될 때까지 후속 plan을 차단합니다. GL은
  `GLSurfaceView`의 무기한 pause/detach wait 대신 app-owned EGL thread를 사용합니다.
  Texture Canvas loop가 시작된 뒤에는 `Surface` wrapper와 backing `SurfaceTexture`의
  최종 `release()`도 그 worker의 실제 `finally`가 소유합니다. 16 ms hand-off
  timeout에서 UI/framework가 같은 native producer를 먼저 해제해 `lock/unlock`과
  경쟁하지 않습니다.
  명시적으로 제거한 Surface/Texture/codec view는 늦은 lifecycle callback이 와도
  producer를 다시 시작하지 않으며, 제거된 producer의 늦은 teardown callback을 새
  generation 실패로 잘못 귀속하지 않습니다. Active producer의 실패만 relay에 결합된
  generation으로 기록합니다.
  이 실패는 `PRODUCER_TEARDOWN_UNCONFIRMED` terminal reason으로 JSON과 결과 UI에
  동일하게 표시됩니다.
- 선택 영상 preflight는 provider가 연 descriptor를 재사용 가능한 seekable
  `AssetFileDescriptor`로 pin합니다. Provider open은 5초, `MediaExtractor` 검사는 10초
  제한의 daemon worker에서 실행합니다. Process-wide single preflight lease는 descriptor
  open 직전부터 parser 종료까지 이어지고 worker마다 refcount hold를 갖습니다.
  Timeout/cancel은 run을 중단하지만 interruption만으로 worker 종료를 가정하지 않으며,
  각 worker의 실제 `finally`가 hold를 반납할 때까지 Activity 재생성을 포함한 후속 plan을
  차단합니다.
- Local CPU/memory worker도 manager별 process-wide lease에 등록합니다. Activity 종료의
  공용 join deadline을 넘긴 worker가 하나라도 살아 있으면 새 controller는 계측 UI만
  시작하고 새 plan은 거부하며, 마지막 worker의 실제 terminal path가 확인된 뒤에만
  다음 manager가 worker를 시작할 수 있습니다. 실행 중 예상하지 못한 worker 예외나
  외부 interrupt는 첫 class/detail을 bounded process latch에 남기고 모든 local worker를
  중단합니다. 이 경우 부하가 사라진 run을 성공으로 계속하지 않으며 process 재시작
  전에는 worker/plan 재시작을 허용하지 않습니다. Active run은
  `LOCAL_WORKER_FAILURE` event를 남기고 `ABORTED`로 끝납니다.
  Worker 여러 개를 시작하다 일부 `Thread.start()`가 실패해도 이미 시작한 worker를
  먼저 중단하고 bounded join하며, 실제 종료 전에는 같은 manager의 즉시 재시도도
  process lease가 거부합니다.
- 정상 완료, 사용자 중단, 예외, 화면 종료 모두에서 worker, codec, Surface, NPU load,
  SBWC request, wake flag를 해제하는 것이 불변식입니다. SBWC를 활성화한 뒤
  linear/default reset을 확인하지 못하면 남은 plan을 계속하지 않습니다.
- 정상 cooldown도 마지막 phase를 복사해 renderer를 유지하지 않습니다. 먼저 phase와
  target을 null로 게시하고 CPU/memory/GPU/NPU 부하를 내린 뒤 physical
  Surface/codec/EGL/Canvas producer teardown을 확인하며, 그 다음에만 SBWC/compression
  route를 linear/default로 reset합니다. Producer teardown 또는 compression reset 중
  하나라도 확인되지 않으면 남은 plan을 중단합니다.
- phase 사이 pixel/compression route가 바뀌어도 같은 순서를 적용합니다. 이전 load/NPU
  zero를 확인하고 producer teardown barrier를 통과한 뒤 vendor route를 설정하고 새
  generation을 게시합니다. Run warm-up은 route 적용 전 codec/SBWC allocation을 만들지
  않는 1-layer RGB/DISPLAY producer입니다. Activity destroy는 Compose/AndroidView
  teardown의 동기 증거가 아니므로 lifecycle `close()`에서는 compression reset을 항상
  생략합니다. 비선형 route가 active/unknown이면 process-wide sticky 상태로 보존하고,
  다음 controller가 producer-free 상태에서 linear recovery를 확인하기 전에는 실행하지
  않습니다. RGB-only 종료는 compression sticky 상태를 만들지 않습니다.
- STOP 또는 Activity pause는 취소 reason 유무와 관계없이 render phase를 먼저 제거하고
  CPU·memory·GPU·NPU setpoint와 display request를 즉시 안전값으로 내립니다. 취소된
  run의 `NonCancellable` cleanup/report가 완전히 끝나 `runJob` 소유권을 해제하기
  전에는 새 START를 받지 않습니다. 시작 coroutine은 lazy Job을 먼저 소유권으로
  게시한 뒤 실행하며, 종료한 Job은 자신이 아직 owner일 때만 그 소유권을 지웁니다.
  `COMPLETE`/`ABORTED`가 이미 게시되고 backend cleanup만 남은 ownership window의 늦은
  STOP/pause는 확정된 결과나 terminal reason을 다시 쓰지 않습니다.
- 예상하지 못한 실행 `Exception`은 cleanup 성공 여부와 무관하게 해당 run을
  `ABORTED`로 기록하고 남은 queue를 중단합니다. 기능 미지원과 명시적인 계측
  불충분만 각각 `UNSUPPORTED`/`INCONCLUSIVE`로 분류합니다.
  `ABORTED` run의 결과/report는 이력에 보존하지만 plan의 `completed` 수에는 포함하지
  않습니다.
- Plan 항목 경계에서는 NPU zero를 단순 enqueue한 것으로 해제 성공을 판단하지 않습니다.
  각 사용 backend의 이전 명령과 직렬화된 zero/stop 응답을 bounded 시간 안에 확인하지
  못하면 fail-closed로 plan을 중단합니다. Cleanup이 끝내 확인되지 않으면 process-wide
  latch가 후속 reflection adapter 초기화와 새 plan을 막습니다. Activity close의 최종
  stop 확인보다 먼저 시작된 release 결과가 늦게 돌아와 이 최종 확인을 덮지 못하도록
  close 경계로 결과 게시를 fence합니다. 종료 lane이 멈춰 격리한 closed vendor bridge를
  새 controller가 받더라도 이전 controller의 stop/reset 응답은 새 run의 cleanup
  증거로 재사용하지 않습니다.
- 양의 NPU setpoint도 enqueue만으로 적용됐다고 보지 않습니다. 최신 명령 ticket과
  acknowledgment가 일치한 뒤에만 measured phase를 진행하고, 실행 중 adapter health를
  계속 확인합니다. Positive apply timeout/거부/health 상실은
  `NPU_WORKLOAD_APPLY_FAILED`로 fail-closed합니다.
- YUV/P010/SBWC decoder phase는 선택·pin·검증된 media와 concrete hardware codec
  binding이 필수입니다. 선택 media가 없거나 fingerprint/binding이 불완전하면 RGBA
  procedural proxy로 대체하지 않고 실행 전에 거부합니다. Decoder source/capability
  FPS는 phase target뿐 아니라 직전 phase에서 transition 중 decoder topology에 도달할
  수 있는 FPS까지 검사합니다. Gradual transition은 직전 FPS 전체를, STEP 경계는
  `min(60, 직전 FPS)` 이상을 요구합니다.

이 정책의 memory 계산은 stride, allocator metadata, decoder private buffer, GPU tile
storage를 완전히 알 수 없으므로 보수적 휴리스틱입니다. “허용됨”은 해당 SoC가 지속적으로
처리할 수 있다는 성능 인증이 아닙니다.

### 메모리 소유권과 디버깅 기준

순간적인 부하 변화는 measured phase에서 buffer나 worker를 반복 생성하는 대신,
검증된 graphics budget 안에서 working set을 미리 할당·page-touch하고 worker와
buffer를 재사용해 만듭니다. Frame hot path는 per-frame 객체 할당을 피하고, setpoint
변경은 bounded latest-wins/fixed-period 경로를 사용합니다. 이 방식은 transition
응답성을 높이면서 GC와 allocator churn이 DPU 부하 결과를 왜곡하는 정도를 줄입니다.

Activity, renderer container, receiver, coroutine, worker, codec/EGL/Surface,
media descriptor와 vendor session에는 각각 명시적인 owner와 종료 증거가 있어야
합니다. Activity보다 오래 살 수 있는 backend는 application context 또는
Activity-free callback만 보유하고, detach/cancel 뒤 실제 terminal acknowledgment가
확인되기 전에는 process-wide cleanup gate가 다음 run을 막습니다. Timeout은 자원을
해제했다는 증거가 아니며, 미확인 cleanup을 성공으로 바꾸지 않습니다.
Renderer topology는 모든 child의 생성·`addView`·초기 control 적용이 끝난 뒤에만
한 번에 publish합니다. 중간 실패나 OOM에서는 만들어진 prefix의 frame/failure
callback을 먼저 끊고 모든 producer에 stop을 요청한 뒤 하나의 bounded deadline으로
회수합니다. Canvas/EGL이 네이티브 호출 안에서 늦게 끝나더라도 이미 capture한 frame
completion token의 callback을 detach할 수 있어 Activity/controller graph를 붙잡거나
제거된 generation에 frame을 귀속하지 않습니다.
주기 telemetry와 watchdog의 completion ticket 및 LAZY Job 두 개도 하나의 start
transaction으로 생성·게시·시작합니다. Partial start 실패 시 두 Job을 모두 취소하지만
ticket은 각 Job의 실제 completion callback 뒤에만 실패 완료됩니다. Fatal `Error`도
같은 rollback을 먼저 기록한 뒤 다시 throw합니다. 두 Job이 모두 active일 때만 기존
pair를 재사용하며, 한쪽의 비정상 종료는 다른 쪽도 중단하고 active run을 즉시
fail-closed합니다. 빠른 pause/resume은 이전 두 Job의 실제 completion 뒤 한 번만
재시작하고, lifecycle integrity 실패는 process 재시작 전 새 plan을 차단합니다.

Decoder 종료는 frame callback gate를 먼저 닫고 queue를 비운 뒤 duplicate FD/
`MediaExtractor`, listener, codec 순으로 해제합니다. Preflight master descriptor는
bounded 2회 close에도 실패하면 process-sticky 오류로 남아 이후 selected-media plan을
차단하고 report/UI에 cleanup 실패를 표시합니다. Memory copy worker는 8 ms bounded
burst의 copy loop 안에서 원자 카운터를 매 block 갱신하지 않고 burst당 한 번만
publish해, 실제 DRAM burst는 유지하면서 계측용 cache-line contention을 줄입니다.

Plan-wide Battery Saver `END`는 scenario의 terminal counter/producer teardown 이후에
발생하므로, 마지막 report를 다시 원자 발행해 `PERFORMANCE_RESTORE_CONFIRMED` 또는
`PERFORMANCE_RESTORE_FAILED`를 남깁니다. 첫 복원이 실패한 뒤 finalizer 재시도가
성공하면 두 사실을 모두 보존하며, 첫 cleanup boundary가 실패한 run의 `ABORTED`
판정을 성공으로 덮지 않습니다. Plan-wide 복원이 확인되지 않으면 이미 완료된 앞
scenario도 안전한 plan 종료를 증명할 수 없으므로 결과를 `ABORTED`로 바꾸고 기존
report 경로를 철회하며, managed JSON은 best-effort로 삭제합니다.
Renewal/health/service-session integrity가 한 번 깨진 경우에는 나중에 exact
Battery Saver 복원만 성공해도 같은 process의 새 plan을 허용하지 않습니다. Restore
결과 JSON 재발행이 실패한 경우도 메모리의 summary를 `ABORTED`로 고정해 finalizer가
이전 `CLEAN`/`PASSED` 내용을 다시 발행하지 못하게 합니다.

검토는 먼저 pure helper와 한 함수의 경계값·state transition·idempotent close를
unit test하고, 그 다음 전체 흐름에서 partial start, STOP/cancel, Activity 재생성,
provider disconnect/death/expiry, low-memory/thermal abort, producer teardown과
Battery Saver exact restore 순서를 조합해 확인합니다. Host 검증은 수명주기·소유권
회귀를 찾는 수단이며, 실제 BSP의 정책 복구와 DPU underrun 판정은 전용 실기기에서
별도로 검증해야 합니다.

## 시나리오

`20260725_090252` catalog에는 다음 **25개 preset**이 있습니다. Custom은 catalog preset 수에
포함하지 않습니다.

| 카테고리 | 대표 테스트 |
|---|---|
| Layer / HWC | HWC Plane Staircase, backend만 바꾸는 HWC ↔ GPU Composition Pivot, 4L DEVICE Candidate Burst, 20L CLIENT Fallback Candidate |
| Transform | 12-layer Transform Storm |
| Video / Format | 4K YUV + RGB Overlay, 8K30 YUV / 8K60 P010 Decoder Pressure, Linear ↔ SBWC |
| Refresh | 60 → 90 → 120 Hz baseline, mixed producer pacing |
| Resource | Fixed-topology Resource Pulse, NPU Cross-load |
| Load Transition | DPU-only Repeated Step Shock, Instant Isolated Contention, Instant Step & Burst, Topology + Load Combined Ramp, Continuous Fixed-topology Cross-load Ramp, Triangle Wave & Soak Recovery |
| Mixed | 사용자 custom 단일 phase |
| DVFS / Adaptive | Low-clock Single-layer Wake, Idle → Composition/4K Shock, Paired Mid-load Perturbation Matrix, Multidimensional Adaptive Underrun Hunt |
| Soak | mixed load/thermal regression cycle |

각 stress preset에는 부하를 다시 내리는 recovery phase가 포함됩니다. 런타임 안전 정책이
phase를 clamp하거나 거부할 수 있으므로, 보고서의 실행 event를 원래 preset과 함께
확인해야 합니다.
`DPU 4L DEVICE Candidate Burst`는 1L/30fps/60Hz에서 불투명 독립 Surface
4L/120fps/120Hz로, `DPU 20L CLIENT Fallback Candidate`는 같은 저부하 기준에서
mixed/alpha/GL 20L/120fps/120Hz로 STEP 전환합니다. 전자는 `DEVICE_ONLY`, 후자는
각 burst 직전 저부하 기준의 `DEVICE_ONLY`와 peak의 `CLIENT_REQUIRED`라는 typed 관측
계약을 phase에 보존합니다. 이는 HWC 배치를 강제하거나 제품별 plane 수를 보장하는
설정이 아닙니다. 4L도 단말의 DEVICE 한도라는 뜻이 아니라 보수적인 candidate입니다.
계약 phase는 target topology와 첫 buffer가 확인된 뒤 fresh 동일-snapshot
DEVICE/CLIENT 쌍을 수집합니다. 한 번의 fresh probe가 필요한 `DEVICE_ONLY` 구간은
최대 4초의 pre-target periodic sample mutex drain까지 포함해 최소 12초, 서로 다른
fresh sample 2회가 필요한 `CLIENT_REQUIRED` peak는 최소 16초를 사용합니다. Runtime
safety cap이 계약의 layer/FPS/display pacing/GL producer 또는 GPU pressure를 바꾸거나
expectation별 최소 duration 아래로 줄이면 다른 실험으로 조용히 실행하지 않고
preflight에서 거부합니다. Fresh 쌍이 조건을 충족하지 않거나 계측할 수 없으면 성공으로
추정하지 않고 `INCONCLUSIVE`입니다.
Target이 arm된 동안 일반 periodic telemetry는 mutex에 대기열을 만들지 않는
latest-wins try-lock/drop 방식으로 전환됩니다. CLIENT의 두 forced sample은 하나의
serialized ownership 안에서 수집하되 각 sample 사이에 cancellation, thermal contract,
fresh producer topology를 다시 확인하므로 periodic sample이 두 probe 사이를 선점하지
않습니다. Forced sample 자체가 전체 safety/exact telemetry를 갱신합니다.
`DPU-only Repeated Step Shock`는 CPU/memory/GPU/NPU cross-load 없이
1L/30fps/60Hz ↔ 12L/120fps/120Hz를 세 번 왕복해 DPU 요청 부하의 상승과 회복만
비교합니다.

사용자가 START한 plan은 첫 scenario 전에 전체 queue/repeat가 공유하는 HWC capacity
관측을 정확히 한 번 수행합니다. Runtime safety/graphics budget이 허용하는 범위에서 최대
20개의 30fps RGB 독립 Surface를 불투명 non-overlap tile로 준비하고, 모든 first
buffer와 topology publish를 확인한 뒤 100ms 안정화하고 fresh DEVICE/CLIENT snapshot을
한 번만 수집합니다. 준비 또는 원자 쌍이 불완전하면 재시도 부하를 만들지 않고 `N/A`로
남깁니다. 이어서 producer와 cross-load zero를 확인하고 teardown 뒤 3초 settle한 다음
각 scenario의 기존 1L warm-up과 fresh exact baseline을 시작하므로 calibration의 frame,
traffic, counter delta는 scenario evidence에 섞이지 않습니다.

이 값은 해당 opaque RGB/display/system-surface 조합의 “candidate에서 관측된 D/C”이지
보편적인 hardware maximum이 아닙니다. 같은 topology를 해석할 때 DEVICE/CLIENT 경계
참고값으로만 queue/repeat에 재사용하며 safety hard cap을 바꾸지 않습니다. format,
scale, transform, alpha 또는 display mode가 바뀐 typed phase는 fresh vendor evidence로
다시 판정합니다. `HWC Plane Staircase`도 1→2→4→6→8→12→8→4→1L의 별도 bounded
request sweep입니다.
8K60 preset은 decoder primary 한 장, RGB overlay 6장과 GL tail 한 장으로 총 8개의
physical layer를 구성합니다. 8K30 preset과 분리되어 있으므로 8K30-only 장치가 8K60
capability 때문에 불필요하게 거부되지 않습니다.

원인을 분리해 비교할 때는 topology/FPS/Hz/motion/alpha를 고정한
`Fixed-topology Resource Pulse`와 `Instant Isolated Contention`을 사용합니다. 전자는
동일한 기준 phase 전후에서 CPU, memory, GPU 자원을 한 종류씩 4초 완전 주기로 pulse하고,
후자는 같은 8-layer topology에서 순간적인 CPU, memory, GPU contention을 각각 켰다
끕니다. 두 시나리오 모두 축 사이에 명시적인 same-topology zero-load reference를 두어
이전 축이 다음 축의 transition origin으로 재적용되지 않습니다. 여러 축의 burst가
필요하면 별도 `Instant Step & Burst`를 사용합니다. Burst/triangle/soak envelope의
ON/OFF 동안 producer topology는 고정되고, 필요한 topology 전환은 앞선 zero-load
arming/reset phase에서만 수행합니다.
`Continuous Fixed-topology Cross-load Ramp`는 같은 topology를 유지하면서 cross-load만
천천히 올리고 내리므로 producer 재구성 구간을 점진 부하로 오해하지 않게 합니다.
`Paired Mid-load Perturbation Matrix`는 최대 부하가 아닌 중간 부하에서 각 변화 직전과
직후에 동일 reference를 다시 두며, `HWC ↔ GPU Composition Pivot`은 콘텐츠 수,
FPS/Hz, motion, alpha와 외부 workload를 고정하고 backend만 바꿉니다.

`Multidimensional Adaptive Underrun Hunt`는 layer 수, composition 경로와 memory 부하를
함께 올려 첫 경계를 빨리 찾는 **다변수 탐색**입니다. 이 결과만으로 특정 자원 하나를
원인으로 결론 내리면 안 되며, 경계를 찾은 뒤 위 fixed-topology/paired preset으로
분리 재현해야 합니다.

Custom에서 `Flattened Texture`를 고르면 logical layer를 한 개의 display-sized RGBA
physical producer에서 합성합니다. 이 backend에 고른 YUV/P010/SBWC 또는 4K/8K 입력은
decoder/buffer 부하로 가장하지 않고 DISPLAY/RGB로 정규화하며 UI label/tag에 남깁니다.
Flattened 1-layer에서도 GPU slider는 0이면 기본 draw만, `0.001` 초과면 intensity에
따라 1~8개의 bounded hardware-canvas 추가 pass를 실행하므로 1%와 100%가 같은 부하가
아닙니다.
독립/mixed backend에서 GPU load가 `0.001`보다 크면 크기와 관계없이 실제 GL producer를
구성합니다. 양수이지만 `0.001` 이하인 값은 거부합니다. Decoder 또는 explicit-size
primary와 GL이 함께 필요한 1-layer 요청은
2-layer(primary + GL)로 명시적으로 승격하고, graphics budget이 필수 GL producer를
수용하지 못하면 GPU 부하를 조용히 제거하지 않고 test를 거부합니다.
Adaptive hunt는 첫 boundary를 기록하면 남은 stress step을 건너뛰고 명시적인
`hunt-recover` phase를 반드시 실행합니다. Recovery phase 자체는 boundary 후보에서
제외되며, proxy threshold는 transition과 thermal derate를 포함해 phase 안에서 실제
적용한 producer FPS를 시간 적분해 계산합니다. 각 hunt boundary의 memory load는
`STEADY`로 유지해 phase 끝의 fresh boundary sample까지 같은 setpoint plateau를
측정하며, 주기 끝에서 0으로 돌아가는 saw/ramp 파형을 사용하지 않습니다.

DVFS preset의 settle 구간은 작은 layer/FPS/Hz 부하를 유지해 governor가 주파수를 낮출
기회를 준 뒤 composition/video/DRAM 부하를 순간적으로 올립니다. 앱은 DPU 주파수를
고정하거나 낮추지 않습니다. `dpu_frequency_hz`는 제품이 명시한 read-only counter일
뿐이며, 실제 주파수 변화는 BSP governor와 전원 정책의 결과입니다.

## 권장 사용 순서

1. **시스템** 탭에서 display mode, HardwareBuffer, 4K/8K decoder, direct sensor와 vendor
   adapter 연결 상태를 확인합니다.
2. YUV/P010/SBWC/4K/8K decoder test라면 **시나리오** 탭에서 실험용 로컬 영상을
   선택합니다. 선택·pin·preflight된 media가 없으면 decoder preset은 실행되지 않습니다.
3. 먼저 목적 카드의 **급격한 DPU 부하**, **DEVICE 후보 유지**,
   **CLIENT 전환 목표** 중 하나를 고릅니다. DEVICE 목적은 `CLIENT_REQUIRED` phase가
   섞인 preset을 제외합니다. DPU burst는 이름이나 tag가 아니라 1~2L/30fps 이하의
   저부하에서 최소 4L, layer delta 3 이상, 90fps/90Hz 이상으로 STEP하는 typed 값으로
   분류합니다.
4. 더 좁혀야 할 때만 접힌 **고급 필터**를 펼쳐 카테고리, 변화 파형, 예상 강도와
   부하/조건을 고릅니다. 같은 행의 복수 선택은 OR, 목적과 서로 다른 행은 AND입니다.
   필터 결과를 기존 queue 뒤에 **결과 추가**하거나 **결과로 교체**할 수 있고, 개별
   항목은 중복 추가·`←`/`→` 이동·`×` 제거가 가능합니다. 저장 상태에 더 이상 catalog에
   없는 ID가 있으면 복원 시 제거합니다.
5. 전체 queue 반복 횟수를 1~10회에서 선택합니다. queue × repeat로 확장한 총 run은
   40회를 넘을 수 없으며 UI가 허용 가능한 반복 상한을 함께 제한합니다.
   같은 화면의 **실행 보호 정책**에서 선택형 SEVERE 앱 감속 상태를 확인합니다.
   기본 OFF는 SEVERE에서 요청 부하를 유지한다는 뜻이며, CRITICAL/low-memory 등의
   필수 중단을 끄는 설정은 아닙니다.
6. 냉각 상태에서 `Baseline 60 → Max`, 다음 `Low-clock Single-layer Wake`와
   `Idle → Composition Shock`, `HWC Plane Staircase`를 실행합니다.
7. transform, resource pulse, composition pivot, gradual transition 순서로 실패 경계를
   좁힙니다. 최대 부하에서만 찾지 말고 `Paired Mid-load Perturbation Matrix`로 중간
   부하와 DVFS ramp 지연도 확인합니다.
8. 실행 화면의 queue/repeat 위치, phase transition, safety event와 좌측 상단 HUD를
   함께 봅니다. HUD는 앱 build version, 현재 logical/observed·expected physical
   layer, DPU/CPU/GPU 숫자와 60-sample 그래프, DPU read/producer write 예상 traffic을
   표시합니다. 숫자에는 source/quality가 붙고 provenance 전환·unavailable 구간은
   graph gap으로 보존됩니다.
   Typed HWC phase의 `RAW MATCH/WAIT/N/A`는 2.5초 이내의 동일 source·quality·timestamp
   DEVICE/CLIENT 쌍을 즉시 해석한 보조 표시입니다. Target topology 이후의 distinct
   fresh sample 수와 phase 간 방향성까지 확인하는 controller 최종 판정은 결과 event를
   사용하므로 `RAW MATCH`만으로 phase 성공을 확정하면 안 됩니다.
   `STOP`은 작은 화면/landscape의 스크롤 아래로 숨지 않도록 상단 실행 header에 항상
   표시됩니다.
9. 종료 후 run별 결과와 report를 확인하고 안전 clamp/reject/derate/abort event가
   있었는지 먼저 확인합니다.
10. `Exact underrun Δ`가 값이면 직접 counter 판정입니다. `Suspected proxy`만 증가한
   경우 DPU underrun으로 확정하면 안 됩니다.

영상이 선택되지 않은 YUV/P010/SBWC decoder 시나리오는 proxy로 실행하지 않고
preflight에서 거부합니다. SBWC `REQUIRED`와 NPU 시나리오는 실제 vendor adapter가
없으면 `UNSUPPORTED`로 끝납니다.

정확한 장시간 실험에서는 화면 녹화, 미러링, 무선 display와 개발자 GPU overlay를
끄세요. 이 기능들 자체가 composition 및 bus 부하를 바꿉니다.

### ADB / 외부 Intent 자동화

외부 harness는 반드시 explicit component(`-n`)로 전용 `AutomationActivity` alias를
호출합니다. Launcher인 `MainActivity`에 `START`를 직접 보내면 의도적으로 무시됩니다.
Alias의 intent-filter에는 `CATEGORY_DEFAULT`가 없으므로 일반 implicit activity
resolution으로 실행할 수 없습니다. 대상 activity는 `singleTask`이므로 실행 중인
instance에는 `onNewIntent`로 명령이 전달됩니다. Debug APK의 정확한 component 예시는
다음과 같습니다.

```powershell
# catalog preset 한 개를 2회 실행
adb shell am start -n `
  com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity `
  -a com.example.dpulayerlab.action.START `
  --es scenario_id baseline-display-modes `
  --ei repeat_count 2

# 순서를 보존한 preset plan
adb shell am start -n `
  com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity `
  -a com.example.dpulayerlab.action.START `
  --es scenario_ids "instant-isolated-contention,continuous-crossload-ramp" `
  --ei repeat_count 2

# 현재 전체 plan 중단 / UI만 표시
adb shell am start -n `
  com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity `
  -a com.example.dpulayerlab.action.STOP
adb shell am start -n `
  com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity `
  -a com.example.dpulayerlab.action.SHOW
```

Release component는
`com.example.dpulayerlab/com.example.dpulayerlab.AutomationActivity`입니다. Release
alias에는 `com.example.dpulayerlab.permission.CONTROL_TESTS`
(`signature|privileged`)가 걸립니다. 신뢰된 제품 harness는 이 permission을
`uses-permission`으로 요청하고 같은 signing trust 또는 제품의 privileged allowlist
정책을 충족해야 합니다. Debug manifest만 ADB lab 자동화를 위해 alias의 permission을
제거하며, release 보안 계약으로 간주하면 안 됩니다.

`scenario_ids`는 comma-separated 문자열 외에도 string array/list extra를 허용하며 입력
순서와 중복을 보존합니다.
8K preset은 독립 실행됩니다. 기존 catalog ID `8k-decoder-pressure`는 호환을 위해
유지하지만 이제 독립 8K30 YUV preset을 뜻하고, 8K60 10-bit P010은
`8k60-p010-pressure`를 사용합니다. 따라서 8K30-only harness는 기존 ID만 보내면
8K60 capability를 요구하지 않습니다.
`repeat_count`는 1~10, expanded plan은 최대 40회입니다. Catalog에 등록된 preset ID만
허용하므로 Intent로 custom workload나 safety cap을 주입할 수 없습니다. 요청은
`onStart` 이후에만 소비되고, 실행 중 추가 `START`는 기존 run을 교체하지 않고
사용자 오류로 거부됩니다. `STOP`은 현재 scenario만이 아니라 남은 plan 전체를
중단합니다. Activity가 아직 시작되지 않아 명령이 대기 중이어도 가장 최근 `STOP`이
모든 미실행 `START`를 폐기하므로 오래된 `STOP`과의 중복 제거 때문에 재시작되지 않습니다.
Extra는 `START`에서만 읽으므로 잘못 직렬화된 START payload가 뒤의 명시적 `STOP`
처리를 막지 않습니다.

앱 표시 이름이 DPULayerTest로 바뀌어도 위 component와
`com.example.dpulayerlab.action.START`, `.STOP`, `.SHOW` action 문자열은 버전과
무관하게 그대로입니다. 제품 harness는 launcher label을 파싱하지 말고 이 stable contract를
사용해야 합니다.

Cold start에서 신뢰된 vendor broker가 아직 bind/capability 조회 중이면 최대 2초 동안
비차단 대기합니다. 그때도 조회가 진행 중이면 adapter가 없다고 단정하지 않고 해당
항목을 `INCONCLUSIVE`로 기록한 뒤 다음 plan 항목으로 진행합니다.

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

GPU fallback은 값 형식을 path와 함께 고정합니다. Qualcomm KGSL은
`gpu_busy_percentage`를 우선하고 `gpubusy`는 누적 delta가 아니라 현재
`busy total` window의 비율로 계산합니다. Exynos Xclipse(AMD RDNA)는 표준 DRM
`card0/device/gpu_busy_percent` direct-percent ABI를 우선하며, Mali `utilization`은
구형/비-Xclipse Exynos 호환 후보로만 남기고 Mali devfreq clock은 Hz로 고정
해석합니다. MediaTek GED는 read-only module
parameter `gpu_loading`을 direct 0~100 값으로 사용합니다. MediaTek의 3-field
`loading blocking idle`와 `index frequency_kHz` 형식은
제품이 exact path를 typed config로 지정한 경우에만 사용하며 debugfs를 기본 탐색하지
않습니다. Samsung 공통 `/sys/kernel/gpu/gpu_busy` 호환 node는 Xclipse/GED/Mali
전용 ABI 뒤에서만 검사합니다. Legacy direct/cumulative 형식의 실제 선택도 source에
남겨 형식이 바뀌면 그래프와 peak continuity가 끊깁니다. 읽을 수 있으나 형식이 다른
값, 단위가 모호한 값, explicit config 실패는
추측하거나 다른 기본 경로로 조용히 우회하지 않고 source가 포함된 `N/A`입니다.

Locked Samsung BSP에서는 SELinux/DAC 때문에 위 read-only node도 앱에서 보이지 않을 수
있습니다. `IDpuLabVendorService` API v2는 GPU busy, GPU frequency Hz, DPU frequency
Hz를 추가하며 유효한 vendor 값(`HW counter`)을 kernel fallback보다 우선합니다. v2
optional getter는 v1/exact-counter 호출과 분리된 no-backlog lane에서 같은 전체
snapshot deadline 안에 읽습니다. 개별 v2 호출의 예외·timeout·busy는 같은 service
session의 v1 sample을 지우거나 다음 sample을 밀어내지 않으며, 해당 v2 값만 `N/A`로
남깁니다. 읽는 도중 service session이 바뀌면 snapshot 전체를 폐기합니다.
Exynos DECON, Qualcomm SDE, MediaTek DISP에 공통인 안정적 DPU busy sysfs ABI는 없으므로
DPU utilization은 제품 broker가 PMU/driver active-cycle을 제공하거나 명시적으로
검증된 probe가 있을 때만 표시합니다. GPU busy, HWC layer 수, 예상 traffic을 DPU
점유율로 대체하지 않습니다.

Exact counter baseline은 Surface/codec warm-up이 끝난 뒤 실제 scenario phase 직전에
잡습니다. 1초 HUD sampler와 같은 직렬화 lane에서 fresh sample을 완료한 값만 baseline으로
사용하며, run generation 이전에 요청된 in-flight sample은 다음 queue 항목의 sample/peak/
counter에 포함하지 않습니다. baseline 이후 source와 `MetricQuality`가 같고 값이 단조 증가하는지 계속
검사합니다. 연속성이 끊기거나 counter가 reset/regress하면
`EXACT_COUNTER_INVALIDATED`를 남기며, 양의 증가를 이미 관찰한 경우에는 그 증거를
보존합니다. 증가가 0인 `CLEAN` 판정은 baseline 이후 유효 sample이 하나 이상 있고
끝까지 연속성이 유지된 경우에만 가능합니다. 정상 verdict를 계산하기 전에는 마지막
physical producer의 teardown을 확인하고 serialized fresh terminal counter sample을 한 번
더 수집합니다. 이 sample 또는 periodic telemetry 실패는 telemetry gap으로 exact
continuity를 무효화합니다. Sample timestamp는 모든 counter/state read가 끝난 시각의
evidence이며 CPU utilization interval의 시작 시각과 분리됩니다. 마지막 완료 evidence가
5초 stale이면 run을 중단하되, 이미 수락된 sample의 예외는 위의 bounded
4초+watchdog-one-tick deadline까지만 유효합니다. Source/quality 변경이나 reset/regress도
continuity를 무효화합니다. 신뢰할 delta가 없으면 delta provenance도 이전 baseline
source를 남기지 않고 `N/A`/`UNAVAILABLE`입니다.
JSON report는 `schemaVersion: 2`이며
exact delta/source/quality, telemetry source, phase transition, event와 sample을
분리해 기록합니다. 유한하지 않은 숫자는 JSON의 `null`입니다.
연속성이 확인된 exact delta가 있으면 exact 판정이 우선합니다. 따라서 exact delta가
0이고 frame-deadline proxy가 증가했어도 verdict는 `CLEAN`이며, proxy 증가는 별도
`PROXY_SIGNAL` 보조 event/수치로 보존합니다.

Producer 실행 fidelity는 exact DPU counter와 별도입니다. Phase 시작부터 실제 적용된
producer FPS와 physical producer 수를 함께 적분하고 generation이 승인한 모든 producer
buffer를 합산합니다. 기대 aggregate가 30 frame 이상이고 actual/expected가 70% 미만이면
`PRODUCER_RATE_SHORTFALL`을 기록합니다. 이때 verified exact delta가 양수면
`UNDERRUN DETECTED`가 우선하고, 그렇지 않으면 frame 수가 부족한 부하를 정상 수행한
것처럼 판정하지 않기 위해 최종 verdict는 `INCONCLUSIVE`입니다.

Adaptive hunt의 boundary 판정은 activation baseline과 별도로 topology 준비 직전
`before`, active phase 종료 직후 `after` fresh sample을 직렬화해 사용합니다. 따라서
Surface 준비 중 증가와 마지막 telemetry tick 뒤의 tail 증가도 경계 증거에 포함합니다.
두 sample 사이 exact source/quality/단조 연속성이 끊기면 exact delta는 사용하지 않고,
동일 provenance의 proxy delta만 별도 기준으로 평가합니다.

결과 화면의 CPU/memory/generated traffic 및 DPU/GPU/bus/produced FPS/HWC
DEVICE·CLIENT peak는 유효 sample의 `MetricQuality`와 source가 run 동안 같을 때만
집계합니다. Source 또는 quality가 바뀐 구간을 하나의 peak로 합치지 않으며 `N/A`
(`source changed`)로 표시합니다. DPU/GPU/bus/FPS/HWC peak는 JSON에 별도 고정 peak
필드를 추가하는 대신 report에 보존된 run sample에서 계산합니다.

DEVICE/CLIENT layer count는 반드시 하나의 vendor snapshot 또는 하나의 SurfaceFlinger
dump에서 얻은 **원자 쌍**으로 선택합니다. Vendor의 한쪽 count와 SurfaceFlinger의 다른
쪽 count를 섞지 않으며, 완전하고 fresh한 vendor 쌍을 우선하고 그 다음 완전하고 fresh한
SurfaceFlinger 쌍을 사용합니다. 둘 다 불완전하거나 2.5초 freshness 범위를 넘으면 두
값 모두 `N/A`입니다. 선택한 쌍의 completion monotonic timestamp와 age는 HUD/report에
같이 보존됩니다.

SurfaceFlinger text dump 형식은 Android 및 BSP 버전에 따라 달라질 수 있습니다. 이
dump는 전체 telemetry sample과 별도 completion 시각과 age를 가집니다. Dashboard/idle
일반 모니터링은 3개 telemetry snapshot cadence로 bounded 재수집할 수 있고, plan-start
capacity 관측은 준비가 끝난 뒤 cache를 우회해 정확히 한 번 fresh fallback을 허용합니다.
Dump child는 800ms, 전체 telemetry는 4초로 제한됩니다. 같은 snapshot의 vendor 원자
쌍이 있으면 그것이 우선합니다.

실제 scenario active load에서는 periodic뿐 아니라 typed boundary도 SurfaceFlinger
child를 만들지 않고 현재 vendor service session의 fresh 원자 쌍만 사용합니다. 따라서
관측 프로세스가 target load를 교란하거나 calibration cache가 phase evidence로 재사용되지
않습니다. Vendor pair가 없으면 typed 판정과 untyped `HWC Plane Staircase`의 단계별
DEVICE/CLIENT는 `N/A`/`INCONCLUSIVE`이며, plan-start 참고값으로 채우지 않습니다.

HWC composition count를 위해 logcat을 읽거나 임의 sysfs/debugfs를 탐색하지 않습니다.
Portable 경로는 `dumpsys SurfaceFlinger --hwclayers`, 제품 경로는
`IDpuLabVendorService.compositionLayerCounts`뿐입니다. 별도 allowlist sysfs는 DPU/GPU/
bus/frequency/exact counter용이며 overlay plane maximum source로 사용하지 않습니다.
2.5초를 넘은 cached DEVICE/CLIENT/missed-frame evidence는 최신 sample 시각으로 다시
찍지 않고 `N/A` gap으로 남깁니다. 제품 판정에는 vendor typed API를 우선하세요.
CPU counter도
`HardwarePropertiesManager`와 `/proc/stat` 사이 source가 바뀌거나 read gap이 생긴
구간은 `N/A`로 두고 다음 같은-source interval부터 다시 계산합니다. 앱 layer 이름만
찾았지만 각 layer의 composition type을 모두 유일하게 분류하지 못한 dump도 0개로
간주하지 않고 DEVICE/CLIENT 모두 `N/A`로 둡니다.

### 예상 layer traffic

실행 HUD는 다음 선형 full-buffer 모델을 사용합니다.

```text
producer bytes/frame = Σ(각 실제 producer의 width × height × output B/px)
producer bytes/s     = producer bytes/frame × producer fps
DPU read bytes/s     = 직접 Surface 합 + mixed client target × 실제 display Hz
```

기준값은 RGBA 8888 4 B/px, RGB 565 2 B/px입니다. MediaCodec의 Surface output
allocation은 요청한 `YUV_420`/`P010`/`SBWC` route가 강제하지 않습니다. 선택 영상의
extractor MIME/profile이 명확한 8-bit 4:2:0일 때만 YUV420 1.5 B/px, 검증된 10-bit
4:2:0 descriptor일 때만 16-bit-plane 3 B/px 선형 기준을 사용합니다. VP9 Profile 2는
512자 이하 canonical `vp09.02.<level>.10...` codec string의 bit-depth 10 확인도 이
descriptor에 필요합니다. 외부/public
descriptor의 B/px가 유한한 양수가 아니거나 16 B/px를 넘으면 무효로 보고, 판별할 수
없으면 decoder primary를
포함한 aggregate bytes/frame·bytes/s는 `N/A`입니다. 같은 영상 descriptor는 route가
달라도 같은 base linear bytes를 사용하며 SBWC 압축률은 별도로 제외합니다.
`TextureView`, alpha procedural Surface와 GL 출력은 실제 BufferQueue 형식대로 RGBA
4 B/px로 계산합니다. YUV/P010/SBWC decoder phase는 선택 media가 없으면 실행되지
않으므로 RGBA proxy traffic을 표시하지 않습니다. mixed backend의 TextureView 여러 장은
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

SBWC는 실측 압축률을 portable API로 알 수 없어 선택 영상의 검증된 base linear
reference를 그대로 표시하고 `ratio 제외`라고 명시합니다. 실제 DPU/bus counter가 있으면
estimate와 별도 열로 비교해야 합니다.

모든 phase의 compression route 적용 결과는 `COMPRESSION_ROUTE` event로, 종료/reset
결과는 `COMPRESSION_RESET` event로 남습니다. `SBWC_REQUIRED`는 실제 상태를 확인할
adapter가 없으면 `UNSUPPORTED`이고, adapter가 route를 거부하거나 timeout이 나거나
활성 SBWC를 linear/default로 되돌렸다는 응답을 확인할 수 없으면 fail-closed로
`ABORTED` 처리해 다음 queue 항목을 실행하지 않습니다.

성공한 비선형 route는 그 명령을 확인한 vendor Binder session에 결속됩니다. 실제
disconnect/reconnect로 session ID가 없어지거나 바뀌면 provider watchdog이 route를
default로 되돌렸을 수 있으므로 즉시 `COMPRESSION_SESSION_CHANGED`로 중단합니다. 반면
느린 remote telemetry snapshot 자체의 timeout은 process-local registration ID와
분리되어 있어 session 단절로 오인하지 않습니다. Sanitized compression/NPU 상태와
session ID는 실시간 HUD, telemetry sample, JSON report에 함께 남습니다.
Route 전환 뒤에는 준비 단계뿐 아니라 모든 active control tick에서
layer/backend/pixel route/buffer size/alpha/GL의 discrete allocation topology를 target에
고정합니다. FPS와 workload 같은 연속 값만 transition envelope를 따르므로,
fraction-zero tick이 이미 해제한 이전 RGB/SBWC route를 다시 게시하지 않습니다.
선택 영상이 있으면 `SBWC_REQUIRED`도 YUV reference와 같은 codec-to-Surface 콘텐츠와
실제 track 크기를 사용하며, 압축 적용 여부는 위 vendor 검증과 별도로 추정하지 않습니다.

### 4K/8K/P010 입력 검증

선택 영상의 capability 판정에는 container MIME이 아니라 `MediaExtractor`가 읽은 실제
video track의 decoder MIME, encoded dimensions, crop으로 계산한 visible dimensions,
FPS, codec profile/string을 사용합니다. Crop은 horizontal pair와 vertical pair를
독립 처리합니다. 한 축의 pair가 모두 없으면 encoded frame의 그 축 전체를 사용하고,
각 pair 내부의 key가 하나만 있거나 좌표가 범위를 벗어나면 fail-closed합니다. 알려진
source FPS가 phase 요청 FPS보다 낮거나 FPS metadata가 없으면 실행하지 않습니다.
Hardware codec의 size/rate capability는 exact encoded dimensions와 source FPS,
decoder phase target 및 직전 transition origin에서 decoder topology가 실제 도달할 수
있는 FPS의 최댓값으로 검사합니다. Gradual transition은 직전 FPS 전체를, STEP은
`min(60, 직전 FPS)`의 boundary 요구를 포함하므로 낮은 output pacing을 선택해도 고FPS
source decode 요구를 숨기지 않습니다. 선택 영상을 실제
decoder path로 쓰는 P010 phase는 HEVC Main10, AV1 Main10, AVC High10처럼 코드가
명시적으로 인정하는 10-bit profile을 extractor가 확인해야 합니다. VP9 Profile 2는
10/12-bit 4:2:0을 함께 포괄하므로 512자 이하 canonical
`vp09.02.<level>.10...` codec string에서 bit-depth가 10으로 명시된 경우에만
허용합니다. Profile 또는 필요한 codec-string bit depth가 없거나,
8-bit/12-bit/malformed/conflicting 입력이면 거부합니다.
Dolby Vision은 `KEY_PROFILE`만으로 정확한 10/12-bit Surface layout을 확정할 수 없어
P010/3 B/px 근거로 사용하지 않습니다.
영상 미선택 시 P010/YUV/SBWC decoder 화면을 RGBA visual proxy로 대체하지 않습니다.
선택 media 검증도 decoder의 지속 thermal 성능이나 실제 output allocation이
P010/SBWC라는 사실까지 보장하지는 않습니다.

Precheck는 위 size/rate를 만족하는 hardware decoder의 구체적인 codec name을
결정합니다. P010 phase가 있을 때만 extractor profile을 codec의 advertised
`profileLevels`와 exact match하고, 일반 YUV/SBWC는 size/rate 검사를 유지합니다.
URI/MIME/codec name과 encoded/visible dimensions, source FPS, profile, codec string,
P010 verification은 하나의 immutable fingerprint로 renderer에 전달됩니다. 실제 decode도
`MediaCodec.createByCodecName()`으로 같은 codec을 사용하고 URI를 다시 열어 fingerprint를
재검증합니다. Binding이 없거나 MIME/encoded·visible dimensions/FPS/profile/codecs가
바뀌거나 crop pair가 유효하지 않으면 procedural proxy로 대체하지 않고
fail-closed합니다. 선택한 codec name은 `MEDIA_SOURCE` event와 JSON report에 남습니다.

Source `MediaFormat.KEY_MAX_WIDTH/HEIGHT`는 pair가 모두 없거나 두 값이 각각 encoded
width/height와 정확히 같을 때만 고정 해상도 track으로 인정합니다. 한 key만 있거나
encoded 값보다 크거나 작은 adaptive declaration은 render 전에 거부합니다. Renderer는
같은 pinned descriptor에서 이 pair까지 fingerprint로 재검증한 뒤, fixed-resolution
실험에 adaptive-playback allocation hint가 남지 않도록 `MediaCodec.configure()` 직전에
두 `KEY_MAX_*`를 모두 제거합니다.

Encoded width/height의 64 px ceiling은 오직 graphics-memory budget과 decoder output
allocation guard에 사용합니다. Codec capability는 exact encoded size/rate로 검사하고
`KEY_MAX_*` 값이나 adaptive capability로 ceiling을 전달하지 않습니다. Decoder output의
encoded dimensions가 ceiling을 넘거나 crop이 유효하지 않거나 visible resolution이
동적으로 바뀌면 fail-closed합니다. Metadata가 없으면 실행을 거부하고, GL tail 때문에
layer를 1개로 clamp할 때는 실험이 GL-only로 바뀌지 않도록 primary를 유지합니다.

## 빌드와 검증

요구 환경:

- Android Studio Narwhal Feature Drop 2025.1.2 이상 또는 AGP 8.12를 지원하는 후속 버전
- JDK 17
- Android SDK 36
- Android Gradle Plugin 8.12.2
- Gradle wrapper 8.13

### Android Studio 프로젝트

저장소 루트를 Android Studio에서 열면 `settings.gradle.kts`의 `DPULayerTest` 프로젝트와
`:app` 모듈을 Gradle wrapper로 가져옵니다. SDK/JDK 절대 경로, `local.properties`,
사용자별 `.idea` 상태는 저장소에 포함하지 않습니다. Android Studio의 Gradle JDK는
JDK 17로 설정하고 SDK 36을 설치한 뒤 Sync합니다.

VCS에 공유되는 Run/Debug configuration은 다음 두 개입니다.

- `DPULayerTest - Debug APK`: `:app:assembleDebug`
- `DPULayerTest - Release APK (unsigned)`: `:app:assembleRelease`

두 configuration은 Android Studio 상단 configuration 목록에서 선택해 같은 Gradle
task를 재현합니다. Debug 결과는 Android debug key로 자동 서명되며
`app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. Release configuration은
제품 키를 참조하지 않고 의도적으로
`app/build/outputs/apk/release/app-release-unsigned.apk`만 생성합니다. 따라서 Release
구성을 기기에 직접 Run/Install하는 구성으로 바꾸거나 저장소에 signing 경로·암호를
추가하지 마세요. 앱을 실행·디버깅할 때는 Build Variants에서 `debug`를 선택하고
Android Studio가 만든 `app` Android App configuration을 사용합니다. Android App
configuration은 현재 선택한 build variant를 사용하므로, 공유된 두 Gradle
configuration이 변형별 APK 생성을 명확히 고정합니다.

Windows PowerShell 예:

```powershell
$env:JAVA_HOME='<JDK_17_HOME>'
$env:ANDROID_HOME='<ANDROID_SDK_ROOT>'

.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

산출물:

- debug: `app/build/outputs/apk/debug/app-debug.apk`
- release: `app/build/outputs/apk/release/app-release-unsigned.apk`

### `20260725_090252` 릴리스 산출물의 의미

- release tag는 `v20260725_090252`입니다.
- `DPULayerTest-20260725_090252-debug.apk`는 Android debug key로 서명되어 바로 설치 가능한
  **전용 lab/개발용** APK입니다. Explicit automation alias에는 debug manifest에서
  `CONTROL_TESTS` permission이 제거되어 있으므로 ADB 사용이 쉽지만, 신뢰 경계가 열린
  이 동작을 제품 release 보안으로 간주하거나 일반 사용자 단말에 배포하면 안 됩니다.
- `DPULayerTest-20260725_090252-release-unsigned.apk`는 제품 빌드/서명 파이프라인
  입력을 위한 **서명되지 않은 통합 산출물**입니다. 그대로 설치 가능한 최종 제품
  APK가 아닙니다.
- 실제 제품 APK는 secure product build 환경에서 platform/product key로 서명하고
  `priv-app` permission allowlist와 SELinux/Binder 정책을 함께 검증해야 합니다.
  Platform signing만으로 vendor node 접근 권한이 생기지는 않습니다.
- GitHub Release에는 위 두 APK와 `SHA256SUMS.txt`만 올립니다. 저장소나 release에는
  platform key, certificate, keystore, password 또는
  signing token을 넣지 않습니다. 배포한 APK와 `SHA256SUMS.txt`만 공개 검증
  산출물로 취급합니다.

이 timestamp build에서 41개 suite의 host unit test **575개**가 실패·오류·skip 없이
통과했고, `lintDebug`는 error 0개(버전/도구 업데이트 알림 warning 6개)로
통과했습니다. `assembleDebug`와 `assembleRelease`도 성공했습니다. 이번 작업에서는
emulator/실기기 stress를 자동 실행하지 않았으며, host 결과는 exact DPU counter가
있는 실기기 underrun 검증을 대체하지 않습니다.

Debug build는 package suffix가 `.debug`이므로 제품의 release privapp allowlist와
동일하게 취급되지 않습니다. 실제 system integration 검증은 release package로
수행하세요.

### Platform signing / 제품 이미지 포함

제품에서는 `system_integration/product/Android.bp`를 사용해 Soong이 platform
certificate로 서명하도록 하는 방식을 권장합니다. 외부 signing이 필요하면 secure
build machine에서 실행합니다.

```powershell
apksigner sign `
  --key '<PLATFORM_KEY_PATH>' `
  --cert '<PLATFORM_CERT_PATH>' `
  --out DPULayerTest-platform.apk `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

Platform key, certificate, keystore password 또는 vendor signing material을 저장소와
`dist/`에 넣지 마세요. 출력 파일 이름은 바꿀 수 있지만 제품 호환성을 위해 Soong module
`DpuLayerLab`과 package `com.example.dpulayerlab`은 그대로 유지합니다. Platform
signing/`priv-app` 배치만으로 vendor node의 DAC 또는
SELinux 접근이 생기지 않습니다. 최소 권한의 system broker와 typed Binder API를
사용하는 제품 통합 절차는 [docs/SYSTEM_INTEGRATION.md](docs/SYSTEM_INTEGRATION.md)를
참고하세요.

## 보고서 개인정보와 보존

- 새 보고서는 credential-encrypted app internal files의 `reports/`에 JSON으로 저장됩니다.
  Android 10의 legacy external-storage 앱도 직접 읽거나 바꿀 수 없습니다. 이전 개발
  버전이 external app-scoped `reports/`에 만든 파일은 자동 import하지 않습니다.
- 앱에는 network upload 경로가 없으며, 사용자가 **공유**를 누른 경우에만 선택한 앱에
  임시 read URI 권한을 줍니다. 공유 대상은 canonical internal `files/reports` 안에
  실제 존재하고 앱의 완료 파일명(`dpu-layer-lab-…json`) 검증을 통과한 managed
  report로 제한합니다. Traversal, 외부/foreign JSON과 누락 파일은 거부합니다.
- JSON에는 제조사/모델/device, Android 버전, **build fingerprint**, 실행 시각,
  telemetry, event가 들어갑니다.
- 선택한 영상의 표시 이름, MIME, codec profile/string, 해상도, FPS, 길이 같은
  metadata가 `MEDIA_SOURCE` event에 포함될 수 있습니다. 영상 본문은 보고서에
  복사하지 않습니다.
- `allowBackup=false`이며 cloud backup, device-to-device transfer와 legacy backup
  rule에서 internal/external files, database, shared preferences, device-protected
  storage를 포함한 앱 데이터 전체를 명시적으로 제외합니다. 다른 제품으로 복원된
  stale `probe_paths.conf`가 exact counter provenance를 오염시키지 않습니다.
- 앱이 `dpu-layer-lab-` prefix로 발행한 완료 `.json` 보고서는 최근 200개를
  보존합니다. 새 보고서를 fsync/rename으로 발행한 뒤 best-effort로 오래된 앱 보고서만
  정리하므로, retention 실패가 방금 생성한 보고서를 실패로 바꾸지 않습니다. 발행
  중이거나 crash 흔적인 `.part` 파일과 `reports/` 안의 unrelated `.json`은 자동 정리
  대상이 아닙니다.
- 외부 공유 전 fingerprint, 영상 이름, 제품 식별 정보와 사내 counter source 이름이
  포함되어도 되는지 반드시 검토하세요.

## 구조

```text
AutomationActivity alias ── explicit protected control ──┐
                                                        ▼
MainActivity
└─ Compose UI / live HUD
   └─ LabController
      ├─ ScenarioCatalog + runtime safety policy
      ├─ LayerStageView
      │  ├─ SurfaceView / TextureView / app-owned EGL producer
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

- YUV/P010/SBWC decoder 경로는 선택·pin·검증된 영상과 concrete hardware codec
  binding이 필요하며, 영상이 없을 때 procedural proxy로 대체하지 않습니다.
- codec capability 선언은 동시 instance, 지속 thermal 성능 또는 특정 8K stream의
  정상 재생을 보장하지 않습니다.
- SBWC 선택·검증은 vendor gralloc/codec adapter가 필요합니다.
- NPU는 vendor service 또는 실제 accelerator adapter가 필요합니다. CPU fallback을
  NPU로 표시하지 않습니다.
- 20 layer는 앱의 hard cap이지 SoC의 overlay plane 수가 아닙니다. Plan-start 1회
  관측도 safety-approved candidate topology의 D/C 결과일 뿐이고 실제 배치는 HWC 정책,
  format, transform, alpha, scale, secure/HDR 조건에 따라 달라집니다.
- Plan-start 관측은 보편적인 maximum을 계산하거나 후속 safety cap을 변경하지 않습니다.
  Active load에서는 SurfaceFlinger dump를 억제하므로 fresh vendor snapshot이 없는 typed/
  untyped phase의 HWC count는 `N/A`일 수 있습니다.
- View/client Z-order swap은 앱 content의 client-side ordering proxy이며 physical HWC
  plane의 Z-order가 바뀌었다는 증거가 아닙니다. 실제 배치는 typed vendor/HWC snapshot으로
  확인해야 합니다.
- requested refresh는 힌트/선호 mode이며, 실제 display Hz는 패널 mode와 시스템 정책에
  의해 달라질 수 있습니다.
- DPU frequency는 명시된 제품 counter에서 읽기만 하며 앱이 governor frequency를
  강제로 낮추거나 고정하지 않습니다.
- portable 앱만으로 AP 전체 bus 점유율 또는 DPU active cycle을 알 수 없습니다.
- 표준 immersive mode는 transient system-bar swipe 자체를 막지 못하며, 앱은 이를
  감지하면 측정 오염으로 중단합니다. 실행 HUD 때문에 앱 client target 한 장은 남습니다.
- exact counter라도 counter reset/wrap, display scope와 sampling interval은 vendor
  계약에서 명확히 정의해야 합니다.
- 화면 녹화, profiler, ADB tracing 자체가 측정 대상에 영향을 줄 수 있습니다.
