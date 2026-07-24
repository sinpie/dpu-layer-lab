# DPULayerTest

![DPULayerTest icon](docs/assets/dpu-layer-lab-icon.png)

Android AP의 DPU underrun 재현·검출과 Hardware Composer 합성 한계 탐색을 위한
실험용 앱입니다. 독립 BufferQueue layer, 화면 변환, 고해상도 영상, CPU·메모리·GPU·NPU
교차 부하를 단계적으로 올리고 내리면서 계측값과 판정 근거를 JSON 보고서로 남깁니다.

일반 APK에서도 실행할 수 있습니다. Platform-signed `priv-app` 또는 vendor telemetry
service를 연결하면 DPU·DDR·HWC·SBWC·NPU와 같은 제품 전용 기능을 추가로 사용할 수
있습니다.

현재 launcher/Gradle project 표시 이름과 앱 버전은 **DPULayerTest 0.2.0**입니다.
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
- layer별 scroll, zoom, pan, 임의 회전, parallax, alpha overlap, Z-order swap
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
- 실행 중 좌측 상단 layer/DPU/CPU/GPU 숫자·점유율·60-sample 그래프
  (`observed/—P`는 topology commit 대기, `observed/expected P`는 commit 완료)
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
`floor`는 반복 파형인 pulse/triangle의 valley에만 적용합니다. STEP/linear/staircase/
soak 같은 one-shot transition에 0이 아닌 `floor`를 넣은 runnable plan은 의미가
모호하므로 safety policy가 거부합니다. 순수 evaluator의 defensive bounding만 잘못된
직접 호출이 origin을 건너뛰지 않도록 floor를 0으로 지웁니다.

## 런타임 안전 정책

모든 preset과 custom phase는 실제 renderer에 전달되기 전에 다시 검증됩니다.

- 절대 상한: layer 20개, producer 120 fps, requested display 240 Hz
- 비정상 입력: 빈/중복·과도하게 긴 ID/label, 0 이하 duration/layer/FPS/Hz,
  NaN/무한대, 128개를 넘는 phase는 실행 전에 거부합니다. 범위를 벗어난 유한
  workload는 0과 device 상한 사이로 clamp합니다.
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
- thermal `SEVERE`부터 layer/FPS/Hz와 CPU·memory·GPU·NPU 부하를 줄이며, 이
  derating은 뒤 phase에도 유지됩니다. Workload 또는 display 감속 적용을 확인하지
  못하면 일부만 감속된 상태로 계속하지 않고 `THERMAL_DERATE_FAILED`로 중단합니다.
- thermal `CRITICAL` 이상이면 test를 즉시 중단합니다.
- 시작 시 Battery Saver가 켜져 있으면 보수적인 power-save envelope를 적용합니다.
  더 넓은 envelope로 실행 중 Battery Saver가 새로 켜지면 기존 메모리/layer/FPS
  승인을 재사용하지 않고 `SAFETY_ENVELOPE_CHANGED`로 즉시 중단합니다. 다시 시작할 때
  현재 전원 상태로 전체 plan을 재검증합니다.
- 실행 중 display ID 또는 정규화한 physical pixel dimensions가 바뀌면 작은 화면에서
  승인한 graphics envelope를 재사용하지 않고 `SAFETY_ENVELOPE_CHANGED`로 중단합니다.
  같은 display에서 가로/세로 축만 교환되는 회전은 같은 envelope로 봅니다.
- telemetry가 5초 이상 멈추거나 physical producer 중 하나라도 3초 안에 buffer를
  게시하지 못하거나 3초 동안 heartbeat가 끊기면 run을 중단합니다. Ramp/triangle에서
  게시되지 않은 layer가 사라진 peak topology도 정상 완료로 세지 않습니다.
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

이 정책의 memory 계산은 stride, allocator metadata, decoder private buffer, GPU tile
storage를 완전히 알 수 없으므로 보수적 휴리스틱입니다. “허용됨”은 해당 SoC가 지속적으로
처리할 수 있다는 성능 인증이 아닙니다.

## 시나리오

0.2.0 catalog에는 다음 **22개 preset**이 있습니다. Custom은 catalog preset 수에
포함하지 않습니다.

| 카테고리 | 대표 테스트 |
|---|---|
| Layer / HWC | HWC Plane Staircase, backend만 바꾸는 HWC ↔ GPU Composition Pivot |
| Transform | 12-layer Transform Storm |
| Video / Format | 4K YUV + RGB Overlay, 8K30 YUV / 8K60 P010 Decoder Pressure, Linear ↔ SBWC |
| Refresh | 60 → 90 → 120 Hz baseline, mixed producer pacing |
| Resource | Fixed-topology Resource Pulse, NPU Cross-load |
| Load Transition | Instant Isolated Contention, Instant Step & Burst, Topology + Load Combined Ramp, Continuous Fixed-topology Cross-load Ramp, Triangle Wave & Soak Recovery |
| Mixed | 사용자 custom 단일 phase |
| DVFS / Adaptive | Low-clock Single-layer Wake, Idle → Composition/4K Shock, Paired Mid-load Perturbation Matrix, Multidimensional Adaptive Underrun Hunt |
| Soak | mixed load/thermal regression cycle |

각 stress preset에는 부하를 다시 내리는 recovery phase가 포함됩니다. 런타임 안전 정책이
phase를 clamp하거나 거부할 수 있으므로, 보고서의 실행 event를 원래 preset과 함께
확인해야 합니다.
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
Flattened 1-layer에서도 GPU slider는 0이면 기본 draw만, 0 초과면 intensity에 따라
1~8개의 bounded hardware-canvas 추가 pass를 실행하므로 1%와 100%가 같은 부하가 아닙니다.
독립/mixed backend에서 GPU load가 0보다 크면 크기와 관계없이 실제 GL producer를
구성합니다. Decoder 또는 explicit-size primary와 GL이 함께 필요한 1-layer 요청은
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
2. YUV/P010/4K/8K test라면 **시나리오** 탭에서 실험용 로컬 영상을 선택합니다.
3. **카테고리 · 부하 · 조건 조합**에서 카테고리, 변화 파형, 예상 강도와
   부하/조건을 고릅니다. 같은 행의 복수 선택은 OR, 서로 다른 행은 AND입니다.
   부하/조건에는 CPU/memory/GPU/NPU, RGB/YUV/P010/SBWC, 4K/8K,
   scroll/zoom/rotate, high refresh와 DVFS가 포함됩니다.
4. 필터 결과를 기존 queue 뒤에 **결과 추가**하거나 **결과로 교체**합니다. 개별 항목을
   중복 추가할 수 있고 `←`/`→`로 순서를 옮기거나 `×`로 한 항목만 제거할 수 있습니다.
   저장 상태에 더 이상 catalog에 없는 ID가 있으면 UI와 실행 index가 어긋나지 않도록
   복원 시 제거합니다.
5. 전체 queue 반복 횟수를 1~10회에서 선택합니다. queue × repeat로 확장한 총 run은
   40회를 넘을 수 없으며 UI가 허용 가능한 반복 상한을 함께 제한합니다.
6. 냉각 상태에서 `Baseline 60 → Max`, 다음 `Low-clock Single-layer Wake`와
   `Idle → Composition Shock`, `HWC Plane Staircase`를 실행합니다.
7. transform, resource pulse, composition pivot, gradual transition 순서로 실패 경계를
   좁힙니다. 최대 부하에서만 찾지 말고 `Paired Mid-load Perturbation Matrix`로 중간
   부하와 DVFS ramp 지연도 확인합니다.
8. 실행 화면의 queue/repeat 위치, phase transition, safety event와 좌측 상단 HUD를
   함께 봅니다. HUD는 현재 logical/observed·expected physical layer, DPU/CPU/GPU
   숫자와 60-sample 그래프, DPU read/producer write 예상 traffic을 표시합니다.
   `STOP`은 작은 화면/landscape의 스크롤 아래로 숨지 않도록 상단 실행 header에 항상
   표시됩니다.
9. 종료 후 run별 결과와 report를 확인하고 안전 clamp/reject/derate/abort event가
   있었는지 먼저 확인합니다.
10. `Exact underrun Δ`가 값이면 직접 counter 판정입니다. `Suspected proxy`만 증가한
   경우 DPU underrun으로 확정하면 안 됩니다.

영상이 선택되지 않은 YUV 시나리오는 `PROXY_FALLBACK` event를 기록합니다. SBWC
`REQUIRED`와 NPU 시나리오는 실제 vendor adapter가 없으면 `UNSUPPORTED`로 끝납니다.

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
`com.example.dpulayerlab.action.START`, `.STOP`, `.SHOW` action 문자열은 0.2.0에서
그대로입니다. 제품 harness는 launcher label을 파싱하지 말고 이 stable contract를
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
continuity를 무효화하고, 5초 stale은 run도 중단합니다. Source/quality 변경이나
reset/regress도 continuity를 무효화합니다. 신뢰할 delta가 없으면 delta provenance도
이전 baseline source를 남기지 않고 `N/A`/`UNAVAILABLE`입니다.
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

SurfaceFlinger text dump의 DEVICE/CLIENT layer count는 Android 및 BSP 버전에 따라
형식이 달라질 수 있습니다. 제품 판정에는 vendor typed API를 우선하세요.

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
영상이 없는 YUV/P010 procedural 화면과 `TextureView`, alpha procedural Surface, GL
출력은 실제 BufferQueue 형식대로 RGBA 4 B/px로 계산합니다. mixed backend의 TextureView 여러 장은
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
Hardware codec의 size/rate capability는 exact encoded dimensions와
`max(source FPS, decoder phase FPS)`로 검사하므로, 낮은 output pacing을 선택해도 고FPS
source decode 요구를 숨기지 않습니다. 선택 영상을 실제
decoder path로 쓰는 P010 phase는 HEVC Main10, AV1 Main10, AVC High10처럼 코드가
명시적으로 인정하는 10-bit profile을 extractor가 확인해야 합니다. VP9 Profile 2는
10/12-bit 4:2:0을 함께 포괄하므로 512자 이하 canonical
`vp09.02.<level>.10...` codec string에서 bit-depth가 10으로 명시된 경우에만
허용합니다. Profile 또는 필요한 codec-string bit depth가 없거나,
8-bit/12-bit/malformed/conflicting 입력이면 거부합니다.
Dolby Vision은 `KEY_PROFILE`만으로 정확한 10/12-bit Surface layout을 확정할 수 없어
P010/3 B/px 근거로 사용하지 않습니다.
영상 미선택 시의 P010 화면은 앞서
설명한 RGBA visual proxy입니다. 이
검증도 decoder의 지속 thermal 성능이나 실제 output allocation이 P010/SBWC라는
사실까지 보장하지는 않습니다.

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

- JDK 17
- Android SDK 36
- Android Gradle Plugin 8.12.2
- Gradle wrapper 8.13

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

### 0.2.0 릴리스 산출물의 의미

- `DPULayerTest-v0.2.0-debug.apk`는 Android debug key로 서명되어 바로 설치 가능한
  **전용 lab/개발용** APK입니다. Explicit automation alias에는 debug manifest에서
  `CONTROL_TESTS` permission이 제거되어 있으므로 ADB 사용이 쉽지만, 신뢰 경계가 열린
  이 동작을 제품 release 보안으로 간주하거나 일반 사용자 단말에 배포하면 안 됩니다.
- `DPULayerTest-v0.2.0-release-unsigned.apk`는 제품 빌드/서명 파이프라인 입력을 위한
  **서명되지 않은 통합 산출물**입니다. 그대로 설치 가능한 최종 제품 APK가 아닙니다.
- 실제 제품 APK는 secure product build 환경에서 platform/product key로 서명하고
  `priv-app` permission allowlist와 SELinux/Binder 정책을 함께 검증해야 합니다.
  Platform signing만으로 vendor node 접근 권한이 생기지는 않습니다.
- GitHub Release나 저장소에는 platform key, certificate, keystore, password 또는
  signing token을 넣지 않습니다. 배포한 APK와 `SHA256SUMS.txt`만 공개 검증
  산출물로 취급합니다.

2026-07-24 기준으로 `clean testDebugUnitTest lintDebug assembleDebug assembleRelease`를
한 번에 실행해 28 suite/310 test가 failure/error/skip 없이 통과했습니다. Lint error는
0개이며 warning 6개는 빌드 도구/의존성의 새 버전 알림뿐입니다. Debug APK는
`com.example.dpulayerlab.debug`/`0.2.0-debug`와 Android debug certificate를,
release APK는 `com.example.dpulayerlab`/`0.2.0`과 unsigned 상태를 확인했으며 두 APK
모두 zipalign 검증을 통과했습니다.

Android emulator 스모크에서는 `DPULayerTest` cold launch, 대시보드와 22개 catalog
조합/queue UI, 실시간 layer·DPU·CPU·GPU 그래프와 예상 traffic HUD를 확인했습니다.
Debug 전용 explicit automation alias로 baseline 완주와 `resource-pulse` 2-loop plan
실행 중 `STOP`의 `ABORTED · 1/2 runs · 2 loops` 전환, schema v2 internal report의
compression/session/NPU field, process crash/ANR 0건을 확인했습니다. Emulator 결과의
`SUSPECTED / PROXY`는 exact DPU counter가 없는 환경에서 의도된 판정이며 실기기
underrun 검증을 대체하지 않습니다.

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
  임시 read URI 권한을 줍니다.
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
- DPU frequency는 명시된 제품 counter에서 읽기만 하며 앱이 governor frequency를
  강제로 낮추거나 고정하지 않습니다.
- portable 앱만으로 AP 전체 bus 점유율 또는 DPU active cycle을 알 수 없습니다.
- exact counter라도 counter reset/wrap, display scope와 sampling interval은 vendor
  계약에서 명확히 정의해야 합니다.
- 화면 녹화, profiler, ADB tracing 자체가 측정 대상에 영향을 줄 수 있습니다.
