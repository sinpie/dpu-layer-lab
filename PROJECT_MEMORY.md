# Project Memory

이 문서는 DPULayerTest의 장기 설계 맥락을 보존하는 canonical project memory입니다.
구현을 바꾸면 코드, test, `README.md`, 이 문서를 함께 갱신합니다.

0.2.0에서 launcher와 Gradle project 표시 이름은 `DPULayerTest`다. Canonical GitHub
저장소는 `sinpie/dpu-layer-lab`이며, 기존 제품 통합과 report consumer를 위해 package
`com.example.dpulayerlab`, automation action/component, `dpu-layer-lab-` report prefix,
Soong module/APK 이름 `DpuLayerLab`은 stable compatibility identifier로 유지한다.

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
3. **부하의 파형과 phase 전환을 분리한다.** `LoadShape`는 CPU/memory/GPU/NPU generator
   내부의 steady/pulse/ramp/saw modulation이고, `TransitionSpec`은 이전 phase에서
   목표 phase로 layer/FPS/Hz/교차 부하 전체를 step/ramp/staircase/burst/triangle/
   soak-recovery로 이동시키는 bounded envelope다. Topology가 다르면 검증된 target
   topology/layer count를 즉시 적용한다. FPS/Hz/load는 계속 보간하고 layer count는
   같은 topology에서만 보간한다. Safety policy는 100 ms control cadence에서 실제
   transition window를 검증해 ramp 중간 tick, staircase level, cyclic one-cycle과
   soak attack/hold/recovery를 관측할 수 없는 phase를 reject한다. `STEP`은 fresh
   baseline과 origin producer buffer 뒤의 measured active tick에서 target을 적용한다.
   `floor`는 pulse/triangle 반복 valley 전용이다. STEP/linear/staircase/soak는
   nonzero floor가 있는 runnable plan을 policy에서 reject한다. 순수 evaluator는
   hostile direct call에서도 origin을 보존하도록 defensive하게 floor를 0으로 지운다.
4. **부하 상승 뒤 recovery를 둔다.** 부하 획득과 해제를 같은 run에서 관찰한다.
   Adaptive hunt는 boundary 이후 남은 stress step 대신 명시적 recovery로 이동하고,
   recovery 자체는 boundary 판정에서 제외한다. Proxy threshold는 phase 안에서 실제
   적용한 producer FPS를 적분하므로 transition과 thermal derate를 반영한다. Hunt
   boundary의 memory setpoint는 `STEADY` plateau로 유지해 phase-end fresh sample이
   부하가 내려간 파형 끝을 측정하지 않게 한다.
5. **입력과 실행 계획을 분리한다.** catalog/custom 입력을 그대로 render하지 않고
   runtime safety policy로 validate, clamp 또는 reject한다. 전체 duration cap은 앞
   phase부터 소진하는 방식이 아니라 모든 phase의 최소 시간을 예약한 뒤 phase당 상한이
   먼저 반영된 duration 비율로 배분한다. Phase가 짧아지면 transition window/cycle도
   함께 비례 축소해 원래 attack/hold/release 및 반복 횟수의 의미를 가능한 범위에서
   보존한다.
6. **메모리 budget은 triple buffering을 가정한다.** 총 RAM과 현재 available memory를
   함께 사용한 보수적 graphics budget이며, allocator/decoder 실제 사용량의 대체물은
   아니다. Device hard envelope는 총 RAM 등급, logical CPU core 수, emulator build
   signal, low-RAM, power-save와 low-memory cap을 각각 계산한 뒤 항목별 최솟값으로
   합친다. 따라서 큰 RAM 하나만으로 20 layer/120 fps/최대 교차 부하를 허용하지 않는다.
   GL producer는 RGBA color 외에 driver가 24/32-bit로 확장할 수 있는 depth attachment를
   보수적 4 B/px로 별도 계산하고 color/depth 모두 triple buffering한다.
7. **portable과 vendor 계측을 분리한다.** 공통 API는 Android service/앱 측정/proxy를
   제공하고, exact DPU/DDR/HWC/SBWC/NPU는 signature-protected AIDL broker를 사용한다.
8. **값마다 provenance를 유지한다.** `MetricQuality`와 `source` 없이 숫자를 노출하지
   않는다. unavailable은 `N/A`다.
9. **영상은 codec-to-Surface다.** SAF URI를 `MediaExtractor`/`MediaCodec`로 decode해
   primary Surface에 직접 출력한다. YUV/P010/SBWC selected-media route는 같은 decoder
   primary 계약과 실제 track 크기를 공유하되, SBWC REQUIRED의 압축 상태는 별도 vendor
   adapter가 검증해야 한다. Capability 판정은 container MIME이 아니라 실제 video track
   MIME/FPS/profile을 사용하고, selected-media P010 decoder path는 검증된 10-bit
   profile만 허용한다. VP9 Profile 2는 10/12-bit를 함께 포괄하므로 512자 이하 canonical
   `vp09.02.<level>.10...` codec string의 bit-depth 10까지 확인해야 한다. Malformed,
   12-bit 또는 서로 충돌하는 VP9 entry는 fail-closed한다. Capability는 exact encoded
   dimensions와 `max(source FPS, phase FPS)`를 사용한다. URI/MIME/codec name,
   encoded/visible dimensions, FPS, profile, codecs/P010 상태를 immutable fingerprint로
   넘기고 renderer에서 다시 확인한다. Crop은 horizontal/vertical pair를 독립 처리하고,
   한 축의 pair가 모두 없으면 그 축 전체를 사용한다. Lone key나 범위 오류는
   fail-closed한다. Provider가 연 seekable `AssetFileDescriptor`를 pin해 preflight와
   renderer가 같은 open file description의 dup을 사용한다. Provider open 5초와
   `MediaExtractor` 검사 10초는 daemon worker에서 bounded하며, descriptor open부터
   parser 종료까지 process-wide single refcount lease를 유지한다. Timeout/cancel 뒤에도
   worker의 실제 `finally`가 hold를 반납할 때까지 후속 plan을 차단한다.
   Source `KEY_MAX_WIDTH/HEIGHT` pair는 absent 또는 encoded exact pair만 허용하고,
   renderer 재검증 뒤 `MediaCodec.configure()` 직전에 두 key를 제거한다. 64 px ceiling은
   graphics budget/output allocation guard에만 사용한다.
   Metadata/fingerprint가 없거나 바뀌고, output crop이 유효하지 않거나 visible
   resolution이 바뀌거나 allocation ceiling을 넘으면 fail-closed한다.
10. **traffic은 별도 모델이다.** hardware counter와 합치지 않고 linear full-buffer
   estimate로만 표시한다. Selected decoder primary의 B/px는 요청 route가 아니라
   extractor MIME/profile에서 검증한 8-bit YUV420(1.5) 또는 10-bit P010(3.0) descriptor를
   사용하며 판별 불가이거나 B/px가 non-finite/0 이하/16 초과이면 aggregate traffic은
   `N/A`다. VP9 Profile 2는 canonical `vp09.02.<level>.10...`에서 bit-depth 10이
   확인된 경우만 3.0 B/px로 사용하고, Dolby Vision이나 4:2:2/4:4:4 계열 VP9
   Profile 3을 P010 layout으로 추정하지 않는다. SBWC ratio는 포함하지 않는다.
11. **보고서는 내부 저장소 우선이다.** credential-encrypted `files/reports`에 JSON을
    저장하며 사용자가 명시적으로 공유할 때만 FileProvider URI 권한을 준다. 과거
    external 보고서는 자동 import하지 않는다.
12. **외부 자동화는 보호된 alias만 허용한다.** Explicit `AutomationActivity` Intent는
    preset ID와 bounded repeat만 전달하며 custom workload, phase 또는 safety 값을
    주입하지 못한다. Launcher `MainActivity`에 직접 보낸 control action은 무시한다.
13. **부하 worker는 backlog를 만들지 않는다.** CPU/memory는 bounded fixed-period
    worker와 재사용 buffer를 사용하고 NPU control은 bounded latest-wins로 합친다.
    Memory workload가 있는 plan은 계측 전에 worker별 buffer 할당/page touch를
    acknowledgment하는 bounded prewarm을 실행한다. Prewarm byte는 generated traffic에
    포함하지 않으며 allocation/timeout/cancel/ack 실패는 fail-closed다.
14. **DVFS는 관찰 대상이다.** settle/shock scenario는 governor가 낮은 clock을 선택할
    기회를 주지만 앱이 DPU frequency를 쓰거나 고정하지 않는다.
15. **실험 선택은 직교 facet과 ordered queue다.** Catalog의 카테고리·변화 파형·예상
    강도·부하/조건은 같은 facet 안에서 OR, facet 사이에서 AND로 결합한다. Filter
    결과는 catalog 순서로 queue에 append/replace하고, queue는 중복과 명시적 이동을
    보존하되 복원된 unknown ID는 실행 index를 만들기 전에 제거한다. Repeat는 1~10,
    expanded plan은 40 run 상한이다.

## 반드시 유지할 불변식

- hard cap은 layer 20, producer 120 fps, requested display 240 Hz다.
- scenario는 최대 128 phase이며 device 기본 envelope는 phase 10분, 전체 30분이다.
- 전체 duration을 줄일 때 모든 phase를 최소 1 ms로 남기고 나머지를 phase당 상한이
  먼저 반영된 duration에 비례 배분한다. 축소 뒤 pulse/triangle 한 cycle 또는 soak의
  attack/hold/recovery를 표현할 수 없으면 의미가 다른 test로 실행하지 않고 reject한다.
- 0 이하 duration/layer/FPS/Hz, NaN/무한대, 빈/중복 ID와 overflow 가능 입력은
  render 전에 reject하고, 범위를 벗어난 유한 workload는 clamp한다.
- 한 producer조차 graphics memory budget을 넘으면 실행하지 않는다. Multi-layer GL-tail
  phase가 1 layer로 clamp될 때는 GL-only로 바뀌지 않도록 primary를 보존한다. GL
  color와 보수적 depth attachment는 각각 triple-buffered budget에 포함한다.
- `FLATTENED_TEXTURE`는 logical layer 수와 무관한 display-sized RGBA 단일 physical
  producer이며 decoder route 또는 explicit 4K/8K buffer라고 보고하지 않는다. Custom
  입력의 incompatible route/size는 UI label/tag와 함께 DISPLAY/RGB로 정규화한다.
- Flattened 1-layer의 GPU intensity도 실제 hardware-canvas work를 바꿔야 한다. 0은
  기본 pass, 0 초과는 intensity에 따라 bounded 1~8 extra pass이며 NaN/무한대는 0이다.
- Custom에서 0보다 큰 GPU load는 독립/mixed backend topology에 실제 GL producer를
  포함한다.
  Decoder/explicit-size primary와 GL이 모두 필요하면 요청 1 layer도 2 layer로 명시적으로
  승격하며, safety budget이 필수 GL producer를 제거해야 하는 경우에는 GPU load를
  수행한 것처럼 보이지 않도록 reject한다.
- layer clamp/reject, RAM/core/emulator/low-RAM/power-save cap, thermal derate/abort,
  low-memory abort는 event 또는 결과에서 식별 가능해야 한다. Device envelope는 원본
  catalog scenario를 수정하지 않고 effective copy와 safety adjustment에만 적용한다.
- 비절전 envelope로 실행 중 Battery Saver가 새로 켜지면 이미 승인한 envelope를
  재사용하지 않고 `SAFETY_ENVELOPE_CHANGED`로 plan을 중단한다. 후속 start는 현재
  Battery Saver 상태를 다시 capture해 처음부터 검증한다.
- 실행 중 display ID 또는 정규화한 physical pixel dimensions가 달라지면
  `SAFETY_ENVELOPE_CHANGED`로 중단한다. 같은 ID/physical dimensions의 축 교환은
  graphics envelope를 바꾸지 않는다.
- thermal `SEVERE` 이후의 layer/FPS/Hz/workload derating은 다음 phase에서 원래
  setpoint로 되돌아가지 않는다. Workload/display 감속 중 하나라도 확인되지 않으면
  `THERMAL_DERATE_FAILED`로 중단한다.
- thermal `CRITICAL` 이상과 `ActivityManager.MemoryInfo.lowMemory` 감지는 run을
  중단한다.
- 정상/중단/예외 시 CPU·memory worker, codec, Surface, GL, NPU, compression request,
  wake flag를 해제한다. Activity lifecycle close는 renderer teardown을 동기 증명할 수
  없으므로 compression reset을 호출하지 않고, 비선형 active/unknown 상태만 sticky
  recovery latch로 넘긴다.
- CPU/memory loop는 fixed period, bounded batch/buffer/worker count와 cancellation을
  유지한다. NPU의 reflection/Binder 제어는 bounded queue와 latest-wins를 유지해 phase
  변경이 오래된 request backlog 뒤에 갇히지 않게 한다.
- Local CPU/memory worker는 process-wide owner lease에 등록한다. Activity close의
  bounded join을 넘긴 worker가 있으면 다른 manager의 worker 시작과 새 plan을
  fail-closed로 막고, 마지막 worker의 실제 terminal path에서만 lease를 해제한다.
  실행 중 예상하지 못한 worker exception/interrupt는 first-wins bounded failure를
  process에 고정하고 모든 local worker를 중단한다. 이 latch는 같은 process에서
  clear하지 않으며 후속 worker/plan 시작을 막는다. Active run에는
  `LOCAL_WORKER_FAILURE`를 남기고 `ABORTED`로 종료한다.
- Partial `Thread.start()` 실패는 running=false를 먼저 게시하고 NEW worker를 등록
  해제하며 시작된 worker를 interrupt/unpark한 뒤 bounded join한다. Registered worker가
  실제 종료하기 전에는 같은 owner의 재획득도 거부해 구/신 worker overlap을 막는다.
- low-memory 중단은 재사용하던 memory worker buffer를 즉시 버린다. 정상 phase 전환은
  buffer를 재사용해 allocation 자체가 측정 부하를 왜곡하지 않게 한다.
- Memory workload plan은 warm-up/baseline 전에 모든 memory worker의 working-set
  allocation과 page touch acknowledgment를 bounded 대기한다. Prewarm 중 copy loop는
  정지하고 page-touch byte를 traffic counter에 넣지 않으며 완료 직후 byte baseline을
  reset한다. 확인된 buffer는 run-scoped pin으로 유지해 긴 idle 뒤 재할당을 막고,
  run 종료/low-memory/명시적 drop에서만 해제한다. Allocation failure 또는 ack timeout을
  단순 저부하 실행으로 계속하지 않는다.
- Run 경계 NPU 해제는 backend별 single-lane ordered zero/stop acknowledgment를
  bounded 시간 안에 확인해야 한다. Enqueue 성공만으로 cleanup을 성공 처리하지 않는다.
- 미확인 NPU cleanup은 process-wide latch로 후속 adapter 초기화와 새 plan을 차단한다.
  Activity close가 남긴 최종 cleanup 증거는 close 전에 시작된 release의 늦은 결과보다
  우선하며, stale `false`가 최종 확인을 다시 오염시키지 못한다. 종료 lane이 멈춰
  closed singleton을 격리한 경우에도 과거 stop/reset 응답을 새 controller의 cleanup
  증거로 반환하지 않는다.
- 예상하지 못한 실행 `Exception`은 cleanup이 성공해도 `ABORTED`이며 남은 plan을
  진행하지 않는다. `INCONCLUSIVE`는 명시적으로 분류한 계측/capability 불충분에만 쓴다.
- `ABORTED` artifact는 결과 이력/report에 보존하되 `PlanProgress.completedRuns`에는
  포함하지 않는다. Finalize 중 도착한 cancellation도 completed 갱신보다 먼저 확인한다.
- producer callback은 generation token과 physical producer ID로 분리하고 buffer 게시
  전에 immutable token을 capture한다. Expected topology가 먼저 선언되기 전에는
  generation을 ready로 만들지 않으며, 모든 producer first buffer/heartbeat와 peak
  topology 실현을 확인한다. Frame hot path는 token을 재사용하고 primitive timestamp
  storage를 사용해 telemetry 자체의 allocation/GC traffic을 피한다.
- topology request와 실제 expected-set publication, active phase activation은 서로
  다른 시점이다. Publication 전에는 phase clock/transition/workload/frame budget을
  시작하지 않으며, activation 직전 fresh counter sample 뒤 first-buffer 관측을
  새로 시작한다. 같은 generation의 반복 expected publication은 최초 publication
  epoch를 바꾸지 않는다.
- active topology가 pending으로 바뀌는 callback은 그 timestamp와 aggregate physical
  frame total로 expected budget을 즉시 정산·pause하고 교차 부하를 0으로 내린다.
  Controller의 다음 100 ms poll 경계까지 이전 producer count를 적분하거나 부하를
  유지해서는 안 되며, commit/restart 뒤에만 resume한다. Unpublished/topology-pending/
  process-lease 상태의 HUD expected count는 0(`—P`)이고 committed count와 분리한다.
- `STEP` target은 fresh baseline 뒤 origin topology의 generation-scoped buffer가
  확인된 다음 measured 100 ms control tick에서 적용한다. Duration cap 이후의 실제
  transition window가 cadence상 중간/level/cycle/attack-hold-release를 표현하지 못하면
  실행하지 않는다.
- Phase fidelity는 primary 하나가 아니라 generation이 승인한 모든 physical producer
  frame을 합산한다. 실제 적용한 `FPS × physical producer count` 적분값이 30 frame
  이상이고 actual이 70% 미만이면 `PRODUCER_RATE_SHORTFALL`을 남긴다.
  `FLATTENED_TEXTURE`의 physical count는 1이며, verified exact underrun 증가가 없으면
  shortfall run은 `INCONCLUSIVE`다.
- codec/Canvas/EGL의 16 ms UI hand-off를 넘긴 thread는 background lease로 실제
  종료까지 추적한다. 5초 안의 transient drain은 topology pending 상태에서 latest-wins
  rebuild 또는 동일 Surface restart로 복구하며, active phase clock과 frame budget은
  정지하고 교차 부하는 0으로 내린다. 연속 recovery episode가 5초를 넘긴 경우에만
  sticky teardown failure로 만들고 후속 plan을 차단한다. 명시적으로 제거된 child view는
  늦은 Surface/Texture lifecycle callback으로 producer를 재시작할 수 없다. Teardown
  failure는 active producer relay에 결합된 generation으로만 귀속하며, disabled relay의
  늦은 callback이 이후 generation의 sticky failure를 만들 수 없다.
- exact underrun은 warm-up 뒤 baseline을 잡은 monotonic vendor/kernel counter에서만
  만든다. Baseline은 직렬화된 fresh sample barrier로 획득하고 run generation 이전의
  in-flight sample은 다음 항목에 귀속하지 않는다. source/quality가 바뀌거나 값이
  reset/regress하면 연속성을 무효화한다.
- 정상 verdict를 계산하기 전에 physical producer teardown barrier 뒤의 serialized
  fresh terminal counter sample을 수집한다. Terminal/periodic sample 실패는 telemetry
  gap으로 exact continuity를 무효화하고, 5초 stale은 run도 중단한다. Source/quality
  변경 또는 reset/regress도 continuity를 무효화하며, 신뢰할 delta가 없으면
  source/quality도 `N/A`/`UNAVAILABLE`로 기록한다.
- exact 양의 delta는 관찰된 증거로 보존하되, delta 0은 baseline 뒤 유효 sample과
  끝까지 유지된 연속성이 있을 때만 `CLEAN` 근거가 된다.
- 신뢰 가능한 exact delta가 있으면 verdict는 exact를 우선한다. Exact 0과 proxy 증가가
  함께 있어도 `CLEAN`이고 proxy는 별도 보조 signal/event로 보존한다.
- Adaptive boundary는 topology preparation 전/active phase 종료 직후의 serialized
  fresh sample 쌍으로 판정해 setup 및 tail 증가를 포함한다. Activation baseline은
  phase attribution에 계속 사용하며, boundary sample의 exact source/quality/단조
  continuity가 다르면 exact boundary delta를 폐기한다.
- Adaptive Hunt의 boundary memory load는 `STEADY`여야 하며 phase-end sample 전에
  setpoint가 내려가는 cyclic generator shape로 바꾸지 않는다.
- CPU/memory/generated traffic/DPU/GPU/bus/produced FPS/HWC DEVICE·CLIENT peak는
  유효 범위와 동일 source/quality가 유지된 sample에서만 집계한다. Provenance가 바뀌면
  서로 다른 source의 값을 합치지 않고 `N/A`로 둔다.
- missed frame, HWC/GPU miss, producer stall은 proxy이며 exact DPU underrun으로
  승격하지 않는다.
- NPU adapter가 없으면 `UNSUPPORTED`이며 CPU fallback을 NPU로 표시하지 않는다.
- SBWC REQUIRED는 allocation/compression state를 검증할 provider가 없으면
  `UNSUPPORTED`다.
- 모든 compression route/reset 결과를 event에 남긴다. 적용 거부/timeout 또는 활성
  SBWC의 linear/default reset 미확인은 fail-closed `ABORTED`이며 다음 plan run을
  진행하지 않는다.
- 정상 cooldown도 phase/target null 게시와 generated-load zero, physical producer
  teardown confirmation을 compression linear/default reset보다 먼저 수행한다. 마지막
  SBWC/decoder/GL phase를 neutral cooldown처럼 복사하거나 producer가 붙은 상태에서
  allocation route를 reset하지 않는다.
- inter-phase pixel/compression route 변경도 load/NPU zero 확인, phase/target null,
  renderer teardown barrier, vendor route 설정, 새 producer generation 순서를 지킨다.
  Warm-up은 항상 1-layer RGB/DISPLAY portable producer다. Activity close는 renderer
  lease 관찰 여부와 무관하게 compression reset을 생략한다. 비선형 route 증거가 있을
  때만 sticky cleanup latch가 후속 controller를 fail-closed recovery로 보내며,
  RGB-only teardown 지연은 compression latch를 만들지 않는다.
- 비선형 route 적용 acknowledgment는 vendor Binder service session ID와 결속한다.
  Active SBWC 중 실제 disconnect/reconnect로 process-local registration ID가
  없어지거나 바뀌면 즉시 `COMPRESSION_SESSION_CHANGED`로 중단한다. Remote telemetry
  snapshot timeout은 registration continuity와 분리해 고부하 오탐 중단을 막는다.
- RGB/SBWC route 전환 뒤 모든 active tick은 target의 discrete allocation topology
  (layer/backend/pixel route/buffer size/alpha/GL)를 유지한다. Fraction-zero transition
  origin은 FPS/workload 같은 연속 값에만 사용하며 이전 allocation route를 재게시하지
  않는다.
- 외부 plan은 repeat 10회, expanded run 40회 상한을 유지한다. 실행 중 `START`는
  active plan을 교체하지 않으며 `STOP`은 plan 전체를 취소한다. 시작 전 queue의 최신
  `STOP`은 기존 중복 여부와 무관하게 모든 미실행 명령을 supersede한다.
- Release automation alias는 `CONTROL_TESTS`(`signature|privileged`)로 보호하고
  `CATEGORY_DEFAULT`를 추가하지 않는다. Debug manifest의 permission 제거는 ADB lab
  automation 전용이다.
- report schema는 v2다. exact source/quality와 transition/event/sample을 보존하고
  non-finite 수는 `null`로 직렬화한다.
- report 또는 log에 key, token, keystore password를 기록하지 않는다.

## 현재 구현

- DPULayerTest 0.2.0 launcher/Gradle project와 stable
  `com.example.dpulayerlab`/`DpuLayerLab` 제품 통합 identifier
- Compose 기반 scenario browser, system dashboard, running HUD, result 화면. 실행
  header의 STOP은 compact/landscape에서도 상단에 유지한다.
- 22개 catalog preset 및 custom phase. Fixed-topology resource isolation,
  instant isolated contention, continuous cross-load ramp, paired mid-load reference,
  backend-only composition pivot과 다변수 adaptive hunt의 용도를 구분한다.
- 카테고리/변화 파형/예상 강도/부하·조건의 OR-within/AND-across facet filter,
  filtered append/replace, 중복·이동이 가능한 ordered queue, restored unknown-ID
  sanitize, repeat 1~10과 expanded 40-run cap
- 독립 Surface, mixed Surface/Texture, flattened RGBA, app-owned EGL stress layer
- scroll/zoom/pan/rotate/parallax/storm/Z-order animation
- RAM tier, logical core 수, emulator/`goldfish`/`ranchu`, power-save와 low-memory를
  독립적으로 합성하는 device hard envelope
- RGB8888/RGB565 pattern과 실제 track MIME/FPS/profile을 검사하는 MediaCodec video
  Surface, VP9 codec-string bit depth까지 확인하는 selected-media P010 10-bit gate,
  seekable pinned AFD, bounded provider/parser daemon과 process-wide refcount lease,
  immutable track fingerprint, strict fixed `KEY_MAX_*` pair와 configure 전 key 제거,
  64 px graphics/output allocation ceiling
- custom flattened DISPLAY/RGB 정규화, non-zero GPU의 실제 GL topology 및
  8K60 decoder primary + RGB overlay 6개 + GL tail의 8-layer preset
- fixed-period bounded CPU worker, 기기 등급에 따라 1~2개의 bounded memory-copy
  worker, 측정 전 allocation/page-touch prewarm과 fail-closed acknowledgment,
  GLES load, latest-wins vendor/reflection NPU hook
- telemetry/compression/NPU vendor lane 분리, reconnect desired-setpoint 복구,
  run-boundary ordered NPU zero 확인, reflection constructor/runtime-timeout process latch
- steady/pulse/ramp/saw generator shape와 step/ramp/staircase/burst/triangle/
  soak-recovery phase transition, 100 ms actual-window validation과 measured STEP
- 저부하 settle 뒤 single-layer/composition/4K shock, 중간 부하 perturbation과
  `STEADY` memory plateau를 쓰는 adaptive boundary preset
- 1초 telemetry sample과 최근 60 sample HUD. 좌측 상단에 layer/DPU/CPU/GPU
  숫자·그래프와 linear full-buffer 예상 DPU read/producer write traffic을 표시한다.
- stable-provenance DPU/GPU/bus/produced FPS/HWC DEVICE·CLIENT result peak
- Android service, kernel allowlist, SurfaceFlinger parser, vendor AIDL 계측
- post-warmup baseline/continuity가 적용된 exact/proxy verdict
- physical producer aggregate frame 적분과 `PRODUCER_RATE_SHORTFALL` fidelity verdict
- ordered scenario plan/repeat 실행과 보호된 explicit `AutomationActivity`
  START/STOP/SHOW Intent 계약
- cooldown에서 physical producer teardown을 확인한 뒤에만 compression route reset
- schema v2 JSON report와 FileProvider 공유
- Binder vendor NPU/compression status는 HUD/sample/report에 보존하기 전에 256자로
  제한하고 whitespace/control/format 문자를 정규화하며, current service session도
  함께 보존
- cloud backup/device-to-device/legacy backup에서 모든 app data domain 제외
- host-side unit test와 Android lint/build

## 현재 한계

- DPU utilization, DDR busy, exact underrun, DEVICE/CLIENT layer와 SBWC state는
  BSP/vendor source 없이는 일반화할 수 없다.
- SurfaceFlinger text dump parser는 release/BSP별 형식 변화에 취약하다.
- graphics budget은 stride, tile, codec reference/private buffer를 완전히 반영하지 않는다.
- RAM/core/emulator 기반 envelope는 알 수 없는 HWC plane 수나 sustained SoC 성능을
  대신하는 capability score가 아니며, 제품별 실측 경계보다 의도적으로 보수적일 수 있다.
- 선택 decoder의 encoded 크기는 64 px alignment ceiling으로 budget/output guard에
  반영하지만 실제 stride/slice height와 decoder private/reference allocation은 BSP가
  노출하지 않으면 정확히 알 수 없다.
- Remote provider 또는 native extractor가 cancellation/interruption에 즉시 응답한다는
  보장은 없다. 앱은 timeout 뒤 worker가 실제 `finally`에 도달할 때까지 process-wide
  media preflight lease로 후속 plan을 막지만 해당 외부 구현을 강제 종료하지는 못한다.
- traffic estimate는 crop/cache/tiling/intermediate target/SBWC ratio를 반영하지 않는다.
- P010 입력은 extractor의 10-bit profile로 gate하지만 실제 decoder output format과
  allocation은 decoder/BSP 정책에 달려 있다. YUV/P010/SBWC route 자체를 decoder
  output B/px의 증거로 사용하지 않는다.
- codec capability는 sustained 또는 concurrent decode 가능성을 보장하지 않는다.
- requested display Hz는 실제 mode 전환을 보장하지 않는다.
- DPU frequency counter는 read-only이고, settle 구간이 실제 clock 하강을 보장하지 않는다.
- report에 build fingerprint와 선택 media의 이름/metadata가 포함될 수 있다.
- `dpu-layer-lab-` prefix와 앱 파일명 형식이 확인된 완료 report만 최근 200개로
  process-serialized best-effort retention한다. 방금 발행한 파일은 보호하고 `.part`와
  unrelated `.json`은 건드리지 않으며, 사용자 설정형 만료 정책은 아직 없다.
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
7. report 목록/삭제 UI와 사용자 설정 가능한 retention/만료 정책
8. schema v2 consumer/migration test와 장기 report 호환성 정책

## 검증 명령

PowerShell:

```powershell
$env:JAVA_HOME='<JDK_17_HOME>'
$env:ANDROID_HOME='<ANDROID_SDK_ROOT>'

.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

마지막 회귀 기준(2026-07-24)은 clean build에서 28 suite/310 test,
failure/error/skip 0, lint error 0입니다. Lint warning 6개는 Android Gradle
Plugin/의존성의 새 버전 알림입니다. Debug/release APK의 package/version, debug
certificate/unsigned release, zipalign도 확인했습니다. Emulator에서는 22개 catalog
조합/queue UI와 live HUD, explicit automation baseline 완주, 2-loop plan 실행 중 STOP의
`ABORTED · 1/2 runs · 2 loops`, schema v2 report의 compression/session/NPU field,
crash/ANR 0건을 확인했습니다. Exact DPU/SBWC/HWC/NPU 판정은 이 회귀 기준에 포함되지
않으며 platform-signed target BSP에서 별도로 검증해야 합니다.

안전 정책 또는 renderer를 바꿨다면 최소한 다음을 추가 확인합니다.

- 음수/0/NaN/무한대, cap 직전/직후 값의 unit test
- 1개 8K producer가 budget을 넘는 경우 reject
- scenario 전체 duration cap이 모든 phase에 비례 배분되고 transition/cycle도 함께
  축소되며 100 ms cadence의 실제 window에서 의미를 유지할 수 없는 짧은
  transition은 reject되는지
- STEP이 fresh baseline/origin buffer 뒤 measured tick에서 target을 적용하고 그 tick을
  실행할 시간이 없으면 `INCONCLUSIVE`인지, noncyclic transition의 floor가 0인지
- custom flattened 입력이 DISPLAY/RGB 단일 producer로 표시되고, non-zero GPU 요청의
  GL producer가 보존되거나 budget 부족으로 명시적으로 reject되는지, flattened
  1-layer intensity가 bounded extra draw pass를 실제로 바꾸는지
- layer clamp 시 logical/producer count와 report event 일치
- low-RAM, power-save, `MemoryInfo.lowMemory`, thermal SEVERE/CRITICAL state transition
- runtime display identity/physical-size 변경 중단과 thermal derate 적용 실패 중단
- phase 전환, 사용자 stop, exception, Activity destroy 뒤 worker/codec/NPU 해제
- local worker Throwable/active interrupt가 first-wins latch와
  `LOCAL_WORKER_FAILURE`/`ABORTED`를 만들고 같은 process의 후속 plan을 차단하는지
- partial worker start 실패가 기존 worker 종료 전 same-owner retry/overlap을 막는지
- memory workload prewarm이 모든 worker의 allocation/page touch를 baseline 전에
  확인하고 byte counter를 reset하는지, allocation/ack timeout이 plan을 중단하는지
- compression adapter 거부/timeout/연결 상실과 linear reset 실패가 plan을 fail-closed
  중단하고 각 route 결과 event를 남기는지, physical producer teardown 뒤에만
  compression reset하는지
- producer generation 변경 전 frame이 다음 phase startup guard를 만족하지 않는지
- 실제 video track MIME, encoded/visible dimensions, FPS/profile/codecs/P010
  fingerprint가 runtime에도 일치하고, horizontal/vertical crop pair를 독립 검증하며,
  source `KEY_MAX_*`가 absent/exact pair인지 확인한 뒤 configure 전에 제거하는지,
  codec rate가 max(source/phase FPS)이고 output crop/dynamic resolution/64 px
  graphics/output allocation ceiling을 fail-closed하는지
- provider open/parser timeout·cancel 뒤 daemon의 실제 `finally`까지 process-wide
  refcount lease가 다음 plan을 막고 pinned AFD가 seekable인지
- GL color와 보수적 depth를 각각 triple buffering한 budget 경계
- aggregate physical actual/expected가 30 frame 이상에서 70% 미만일 때 event와
  exact-positive 우선/그 외 `INCONCLUSIVE` 판정, flattened physical count 1
- unpublished/pending/process-lease HUD가 fake expected `1P` 대신 `—P`인지
- compact/landscape 실행 화면에서도 상단 STOP과 layer/DPU/CPU/GPU 그래프 및 예상
  traffic이 보이는지
- Adaptive Hunt boundary가 `STEADY` memory plateau를 유지하는지
- exact counter의 post-warmup baseline, source/quality 변화, reset/regress, 0-delta
  continuity, post-teardown terminal sample, telemetry gap과 invalid delta provenance,
  stable-source peak 및 report schema v2 직렬화
- `AutomationActivity` explicit 호출만 허용되고 direct `MainActivity` START와 implicit
  resolution이 거부되며 plan/repeat 상한을 유지하는지
- catalog facet의 OR-within/AND-across 의미, filtered append/replace 순서와 cap,
  queue move/duplicate 및 restored unknown-ID sanitize가 일치하는지
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
