# Repository Working Rules

이 파일은 사람과 coding agent가 DPULayerTest를 수정할 때 따르는 canonical repository
instruction입니다. 장기 설계 맥락은 `PROJECT_MEMORY.md`, 사용자-facing 설명은
`README.md`를 먼저 확인합니다. 전체 문서 읽기 순서와 단일 책임은 `docs/INDEX.md`,
사용자 요구 추적은 `docs/REQUIREMENTS.md`가 authority다. 현재 component/data flow는
`ARCHITECTURE.md`, 허용 상태 전이는 `docs/STATE_MACHINES.md`, 파일 위치는
`docs/REPOSITORY_MAP.md`, 작업 상태는 `PLAN.md`, 복구 순서는
`docs/RECONSTRUCTION.md`를 사용한다. Scenario/metric/report/test/release 계약은 각각
`docs/SCENARIOS.md`, `docs/METRICS.md`, `docs/REPORT_SCHEMA.md`,
`docs/TESTING.md`, `docs/RELEASE.md`가 authority다.

Launcher와 Gradle project의 표시 이름은 `DPULayerTest`이고 canonical remote는
`https://github.com/sinpie/dpu-layer-lab`이다. 제품 호환성 계약인 package
`com.example.dpulayerlab`, automation component/action, `dpu-layer-lab-` report
prefix, Soong module/APK 이름 `DpuLayerLab`은 별도 migration 요구 없이 바꾸지 않는다.
현재 release version은 `20260727_005420`(`versionCode 10`), debug version은
`20260727_005420-debug`이며 tag는 `v20260727_005420`이다.
`yyyyMMdd_HHmmss`는 KST build 시각이다. Release asset은
`DPULayerTest-20260727_005420-debug.apk`,
`DPULayerTest-20260727_005420-release-unsigned.apk`, `SHA256SUMS.txt` 이름을 사용한다.

## 기본 작업 규칙

- 사용자 변경과 unrelated dirty worktree를 보존한다.
- source 수정에는 작은 patch를 사용하고, generated output은 source와 섞지 않는다.
- app 동작, safety policy, report schema 또는 계측 의미가 바뀌면 test와 문서를 함께
  갱신한다.
- 실제 BSP에 종속된 가정은 portable code에 숨기지 말고 adapter/typed contract로
  격리한다.
- 오류를 삼켜 성공처럼 보이게 하지 않는다. unsupported/unavailable/proxy를 구분한다.
- APK, capture, report, signing material과 local SDK 경로를 commit하지 않는다.
- Release asset의 debug APK는 설치 가능한 lab-only 산출물이며 automation alias
  permission이 제거되어 있다. `release-unsigned` APK는 제품 서명 파이프라인 입력일
  뿐 최종 설치 산출물이 아니다. Platform key/certificate/keystore/token은 release
  asset이나 저장소에 넣지 않고 secure product environment에서만 사용한다.

## 빌드

기준 환경은 JDK 17, SDK 36, AGP 8.12.2, Gradle wrapper 8.13이다.
Android Studio는 Narwhal Feature Drop 2025.1.2 이상 또는 AGP 8.12를 지원하는 후속
버전을 사용한다. VCS-shared `DPULayerTest - Debug APK`와
`DPULayerTest - Release APK (unsigned)` configuration은 각각
`:app:assembleDebug`, `:app:assembleRelease`를 실행한다. Release configuration에
local/platform signing 경로나 credential을 추가하지 않는다.

```powershell
$env:JAVA_HOME='<JDK_17_HOME>'
$env:ANDROID_HOME='<ANDROID_SDK_ROOT>'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

환경별 SDK/JDK 절대 경로를 Gradle source나 tracked 파일에 넣지 않는다.

## 안전 불변식

- renderer 입력은 반드시 runtime safety policy를 통과해야 한다.
- 비절전 envelope로 실행 중 Battery Saver가 켜지면 현재 run을
  `SAFETY_ENVELOPE_CHANGED`로 중단하고, 새 plan에서 현재 상태를 다시 검증한다.
- Test-time power policy는 API v3 typed broker의
  `DISABLE_BATTERY_SAVER` 하나만 허용한다. BEGIN 전에 original Saver 상태를 capture해
  restore authority와 safety input으로 보존하며, original ON이면 임시 해제 중에도
  power-save cap을 유지한다. 10초 lease를 2초 cadence로 renew하고 client death,
  expiry, idempotent END에서 exact prior-state restore를 확인한다. Restore 미확인,
  stale/late command, renewal/health/session failure가 있으면 후속 plan을 차단한다.
  같은 session의 더 높은 command가 전부 END retry일 때만 이미 in-flight인 이전 END의
  exact acknowledgment로 restore latch를 충족할 수 있다. Controller owner는 process
  latch clear, renewal 실제 종료와 직접 읽은 Saver의 original-state 일치를 모두
  확인한 뒤에만 해제한다.
- Broker가 없을 때는 Battery Saver가 이미 OFF인 경우만 app-only monitoring을 허용한다.
  Saver ON 또는 remote mutation 가능성이 남은 모호한 응답을 성공으로 낮추지 않는다.
  Platform signing만으로 전역 power policy 접근이 생긴다고 가정하지 않는다.
- Saver 때문에 시작이 거부된 오류의 설정 action은 performance-policy exact restore,
  run finalizer와 Test Window/SystemUI 복구가 끝날 때까지 이동을 defer한다. 전용 Battery
  Saver 설정을 먼저 열고 일반 설정으로 fallback하되 앱이 policy를 직접 변경하지 않는다.
  오류 message/action은 하나의 notice identity로 결속하고 stale snackbar consume이 새
  오류를 지우지 않게 한다. Background 전환 중 pending navigation을 잃지 않으며 defer
  timeout 또는 설정 Activity 실행 실패에서는 같은 recovery action을 다시 제공한다.
- 앱 선제 thermal SEVERE derating은 선택형이고 기본 OFF다. 설정은 plan 시작 시
  immutable snapshot으로 고정하며 외부 Intent extra로 우회하지 않는다. OFF이면
  SEVERE에서도 앱 setpoint를 유지하고 Android/kernel thermal mitigation에 맡긴다.
  Battery Saver suppression 중에도 thermal CRITICAL, low-memory, local-worker failure,
  power/display/SystemUI 격리 무결성 fail-safe는 항상 유지한다. Doze/device-idle은
  typed BSP 계약이 없으므로 강제로 해제하지 않고 active이면 거부/중단한다.
  DPU/GPU/CPU DVFS, devfreq governor와 frequency write/lock을 추가하지 않는다.
- Battery Saver는 system-wide policy이므로 API v3 provider는 policy scope 전체에서
  single lease로 직렬화하거나 하나의 original baseline과 active refcount를 공유한다.
  겹치는 client/session이 임시 OFF 상태를 새 baseline으로 저장하면 안 되며, 마지막
  lease의 END/death/expiry에서만 원래 상태를 복구한다.
- 실행 중 display ID/physical dimensions가 바뀌면 같은 event로 중단한다. 정규화한
  dimensions가 같은 단순 축 교환만 허용한다.
- hard cap(layer 20, producer 120 fps, requested display 240 Hz)을 늘리려면 명시적인
  요구, budget 근거, boundary test와 문서 변경이 모두 필요하다.
- graphics memory는 최소 triple buffering을 가정하고 총/available RAM을 함께
  고려한다. 선택 decoder는 실제 encoded dimensions를 각 축 64 px까지 올림한 allocation
  ceiling으로 budget을 계산하고 metadata가 없거나 한 producer가 budget을 넘으면
  reject한다. GL producer는 RGBA color와 보수적 4 B/px depth attachment를 각각
  triple buffering한 budget에 포함한다.
- 전체 duration cap을 넘으면 앞 phase부터 잘라내지 말고 모든 phase에 최소 1 ms를
  예약한 뒤 나머지를 phase당 상한이 먼저 반영된 duration에 비례 배분한다. 줄어든
  phase의 ramp/soak window와 pulse/triangle cycle도 비례 조정하고,
  attack/hold/recovery 또는 한 cycle의 의미를 보존할 수 없으면 reject한다.
- `FLATTENED_TEXTURE`는 display-sized RGBA 단일 physical producer다. Decoder route나
  explicit non-DISPLAY buffer로 표시하지 않는다. Custom의 `0.001` 초과 GPU load는 실제
  GPU-backed producer를 가져야 하며, primary+GL topology가 graphics budget에 들어오지
  않으면 GPU 부하를 조용히 제거하지 말고 reject한다.
  Flattened 1-layer intensity도 policy-approved `0.001` 초과 값에서 1~8의 bounded
  extra hardware-canvas pass로 실제 draw work를 바꿔야 하며 non-finite intensity는
  0으로 처리한다.
- 모든 workload는 정확한 0 또는 `0.001`보다 큰 값만 허용한다. `0 < load <= 0.001`은
  표시상 양수와 실제 worker idle의 의미가 달라지므로 reject한다.
- low-RAM/power-save cap을 우회하지 않는다.
- `ActivityManager.MemoryInfo.lowMemory`와 thermal CRITICAL 이상은 active run을
  중단한다.
- 선택형 선제 thermal SEVERE derating이 ON이면 이후 phase에도 유지한다. 기존
  generated/NPU load의 ordered zero 확인 → reduced workload ticket/acknowledgment →
  display 감속 acknowledgment 순서를 지키며, 하나라도 실패하면
  `THERMAL_DERATE_FAILED`로 중단한다.
- loop, thread, buffer allocation, codec dequeue, Binder call에는 상한이나
  cancellation 경로가 있어야 한다.
- Activity 수명보다 오래 살 수 있는 backend job/thread/receiver/Binder callback은
  Activity/inner callback을 강하게 보유하지 않고 application context 또는
  Activity-free holder를 사용한다. Receiver callback은 unregister 전에 detach하며,
  unregister·join·process termination·descriptor close의 terminal 증거가 없으면
  cleanup coordinator가 다음 controller/run을 차단한다. Timeout이나 중복 close를
  성공 증거로 바꾸지 않는다.
- 서로 의존하는 completion ticket/LAZY Job 묶음은 모두 생성·publish·start된 뒤에만
  transactional commit한다. Partial start 실패에서는 생성된 Job을 모두 cancel하고
  ticket은 실제 Job completion 뒤에 실패 완료한다. OOM/ThreadDeath를 포함한 fatal
  `Error`도 rollback을 먼저 시도·기록한 뒤 rethrow한다.
  Telemetry monitor/watchdog는 둘 다 active인 pair만 재사용한다. 한쪽 unexpected
  completion은 sibling cancel과 active run fail-closed를 수행하고 process-sticky
  lifecycle failure로 후속 controller/plan을 차단한다. Pause/resume은 두 identity의
  실제 completion 뒤 single pending restart만 허용한다.
- 순간 부하 성능은 graphics budget 안의 prewarm, page touch, pinned/reused buffer와
  fixed-period/latest-wins 제어로 확보한다. Measured/frame hot path에 반복 buffer,
  lambda, boxed timestamp 또는 불필요한 객체 할당을 추가하지 않는다.
- CPU/memory 부하는 fixed-period bounded worker와 재사용 buffer를 유지한다. NPU/vendor
  control은 bounded latest-wins로 처리하며 오래된 setpoint backlog를 만들지 않는다.
- Low-memory working-set drop은 NPU zero publication보다 먼저 pin 해제/drop generation/
  prewarm 취소를 commit하고 worker를 깨운다. NPU adapter가 예외를 던져도 이 memory
  release를 되돌리거나 생략하지 않는다. Reflection NPU waveform과 ordered zero는 같은
  versioned single-slot lane을 사용하며 새 desired ticket 뒤의 오래된 positive waveform을
  적용하지 않는다.
- 양의 NPU setpoint도 latest command ticket과 acknowledgment가 일치한 뒤에만 적용
  완료로 본다. Active phase 동안 backend health를 확인하고 apply timeout/거부/health
  상실은 `NPU_WORKLOAD_APPLY_FAILED`로 fail-closed한다.
- Pulse/triangle 등 cyclic transition의 NPU 양수→0 valley와 0→양수 re-attack은 각각
  semantic edge다. Matching latest-command zero/positive ticket의 bounded acknowledgment와
  backend health를 확인하기 전에는 해당 transition coverage를 인정하지 않는다. Zero
  edge에서 이전 positive acknowledgment를 지우며, 같은 부호 안의 중간 checkpoint만
  backlog 없는 latest-wins로 유지한다. Semantic apply는 동일 setpoint라도 CPU/memory
  profile restart와 독립된 fresh NPU ticket을 발행해 adapter-level ordered release가
  supersede한 request를 재사용하지 않는다. `TRIANGLE_WAVE`는 zero-origin이면 full-cycle,
  zero-target이면 half-cycle zero 경계를 jitter로 건너뛰어도 NPU-only exact zero를 먼저
  확인하고 positive re-attack을 새 ticket으로 확인한다. Phase 종료가 해당 zero 경계이면
  positive 상태로 끝내지 않고 terminal zero ACK를 확인하며, 여러 zero 경계를 건너뛴
  경우 backlog를 replay하지 않고 `INCONCLUSIVE`로 끝낸다.
- Memory workload는 measured baseline 전에 bounded working-set allocation/page-touch
  prewarm과 worker acknowledgment를 완료한다. Prewarm byte는 generated traffic에서
  제외하고 완료 뒤 counter를 reset하며, allocation/timeout/cancel/ack 실패를 저부하
  성공으로 삼지 않는다.
- 실행 중 예상하지 못한 local worker exception/interrupt는 first-wins bounded
  process latch로 남기고 모든 local worker를 중단한다. 같은 process에서 latch를
  clear하거나 worker/plan을 재시작하지 않는다. Active run은
  `LOCAL_WORKER_FAILURE` event와 `ABORTED` 결과로 끝낸다.
- Partial worker start 실패 뒤 이미 시작된 worker를 bounded join하고, registered worker가
  실제 종료하기 전에는 same-owner lease 재획득도 거부한다.
- low-memory abort에서는 memory working set을 즉시 버린다. NPU cleanup은 이전 command와
  같은 lane의 ordered zero/stop acknowledgment를 확인하며 enqueue만 성공으로 보지
  않는다. Telemetry와 safety control lane을 다시 합치지 않는다.
- 외부 Intent automation은 catalog preset만 허용하며 repeat 10회, expanded plan
  40회 상한과 실행 중 START 거부를 유지한다. 시작 전 최신 STOP은 모든 미실행 START를
  폐기하며 기존 STOP과의 중복 제거보다 우선한다. Extra unmarshalling은 START에서만
  수행해 malformed START payload가 STOP 처리를 막지 않게 한다.
- UI catalog facet은 같은 행 OR/서로 다른 행 AND 의미를 유지한다. Filtered
  append/replace는 catalog 순서를 지키고, 수동 실행 목록에는 임의의 고정 항목/expanded
  run 상한을 두지 않는다. 목록의 중복·명시적 이동은 보존하되 복원된 unknown preset
  ID는 표시/index/실행 전에 제거한다. 앱 UI plan은 이 목록을 통째로 1~10회 loop하며
  외부 Intent의 expanded 40-run cap은 별도 계약으로 유지한다. Repeat를 펼친 전체
  목록을 만들지 않고 queue×repeat를 순차 실행하며, 중복 preset의 immutable
  materialization은 같은 copy를 재사용한다. Duration multiplier는
  1/2/5/10/50/100만 허용하고 immutable execution copy의 phase duration과 transition
  window/cycle에 정확히 한 번 적용한 뒤 기존 duration safety cap을 통과시킨다.
- 외부 control은 explicit `AutomationActivity` alias에서만 처리한다. Release의
  `CONTROL_TESTS`(`signature|privileged`) 보호, debug-only permission 제거,
  `CATEGORY_DEFAULT` 부재와 direct `MainActivity` START 무시를 유지한다.
- producer callback은 generation token과 physical producer ID로 분리한다. 게시 전
  immutable token capture, expected topology 선언 전 readiness 금지, 모든 producer
  first buffer/heartbeat와 peak topology 확인을 유지한다. Frame hot path에 per-frame
  lambda/boxed timestamp allocation을 다시 추가하지 않는다. Canvas/EGL native call을
  가로질러 local completion token이 남을 수 있으므로 relay update/disable은 token의
  callback을 분리해 제거된 generation 보고와 Activity/controller 강한 참조를 막는다.
- Renderer topology 생성/add/control은 하나의 transaction이다. 전부 성공하기 전에는
  expected topology를 publish하지 않고, partial failure/OOM은 relay callback detach →
  모든 생성 producer stop request → 하나의 shared bounded deadline join → child 제거
  순서로 rollback한 뒤 fatal error는 다시 throw한다.
- topology pending 중 fake expected producer를 게시하지 않는다. 실제 relay set을
  commit한 뒤 같은 generation에 한 번 게시하고, 그 전에는 phase clock/transition/
  workload/frame budget을 시작하지 않는다. Scenario warm-up은 topology publication과
  matching geometry acknowledgment 뒤 generation을 activation하고 preparation-era
  callback을 지운다. 그 뒤 committed producer 전부의 fresh first buffer를 bounded하게
  다시 확인하기 전에는 scenario-wide counter baseline을 수집하지 않는다.
  HUD의 expected count는 unpublished/pending/process-lease 동안 0(`—P`)으로 투영하고
  frame-budget용 committed count와 분리한다.
- active topology-pending callback에서 callback timestamp/physical total까지 expected
  frame budget을 즉시 정산·pause하고 교차 부하를 0으로 내린다. 다음 controller
  poll까지 이전 producer count를 계속 적분하거나 부하를 유지하지 않으며
  commit/restart 뒤에만 resume한다.
- `topologyMissed`, `teardownFailed`, `teardownCompleted`는 현재 generation의 producer
  readiness, geometry acknowledgment/coverage와 typed HWC evidence를 fail-closed로
  무효화한다. `topologyMissed`/`teardownFailed`는 새 generation 없이는 복구하지 않는다.
  정상 teardown 뒤 reattach도 topology pending → 새 geometry revision/profile
  acknowledgment → expected topology 재게시 → activation → 모든 producer의 fresh
  first buffer 순서를 다시 거친 뒤 fresh HWC evidence를 수집해야 한다.
- Transition은 duration cap 반영 뒤 실제 window를 100 ms control cadence로 검증한다.
  Ramp 중간 tick, staircase의 각 level, pulse/triangle 한 cycle, soak의 최소 attack
  2 tick/hold 1 tick/recovery 2 tick을 보존할 수 없으면 reject한다. `STEP`은 fresh
  baseline과 origin producer buffer가 확인된 뒤 measured active tick에서 target을
  적용하고, post-ready tick 없이 끝난 phase는 `INCONCLUSIVE`다.
  실행 loop는 absolute-deadline fixed period로 늦은 tick을 busy catch-up하지 않는다.
  Runtime coverage가 ramp 중간값, staircase 전 level, pulse ON/OFF, triangle 상승/하강,
  soak attack/hold/recovery를 관측하지 못하면 `INCONCLUSIVE`다.
- Whole-phase `LINEAR_RAMP`는 nominal deadline에서 exact target을 새
  `producerControlRevision`으로 한 번 게시하고 committed physical producer 전부의 같은
  revision frame을 bounded hold 안에 확인해야 한다. Topology recovery가 끼면 기존
  endpoint evidence를 폐기하고 fresh first buffer 뒤 더 큰 revision으로 재게시한다.
  Revision mismatch/timeout은 `INCONCLUSIVE`이며, endpoint 증명 hold에서 생긴 frame은
  endpoint 적용 전 한 번 샘플한 동일 publication boundary에서 actual/expected를 함께
  seal해 producer fidelity를 부풀리지 않는다. 늦은 control tick도 두 경계를 다르게
  자르지 않으며 이후 proof frame은 fidelity window에 포함하지 않는다.
- `LayerSizeProfile`은 source buffer가 아닌 destination transform/crop 계약이다.
  `FULL_SCREEN`이 기본이고 small/mixed/dynamic profile도 physical producer의 full
  source allocation과 conservative full-buffer traffic budget을 줄이지 않는다.
  Controller-owned pause-aware `phaseElapsedMs`를 dynamic size clock의 authority로
  사용하고 preparation/recovery 및 producer generation rebuild는 그 elapsed anchor로
  re-anchor해 진행률을 이어간다. Topology preparation은 dynamic waveform을 진행시키지
  않고 static measured origin을 고정한다. Prior explicit static origin이 없을 때만
  `SMALL_UNIFORM`을 두 dynamic profile의 fraction-zero equivalent로 사용하고, prior
  full/small/mixed origin과 allocation route preparation의 measured size edge는 baseline
  전에 소비하지 않는다. Fresh baseline과 origin producer readiness 뒤 첫 active
  tick에서 cyclic fraction이 0이어도 target size profile을 arm하고, 이후
  pulse/triangle valley에서도 이전 profile로 되돌리지 않는다. Duration cap 뒤
  `GRADUAL_SMALL_TO_FULL`은 최소 2×100 ms window,
  `ABRUPT_SMALL_FULL`은 8 step 전체의 8×100 ms window가 없으면 reject한다.
  Dynamic transform apply cadence는 producer FPS와 독립적으로 최대 100 ms이고 final
  fraction 1 sample은 강제한다.
  실제 base geometry apply마다 generation-scoped bounded revision을 request하고 두 번의
  후속 Choreographer callback/traversal opportunity 뒤 matching revision/profile만
  acknowledge한다. 한 revision의 acknowledgment가 끝날 때까지 last-applied base-size
  fraction을 고정하되 controller clock과 latest desired fraction은 계속 진행한다.
  Acknowledgment 뒤 다음 apply 기회에는 중간값 backlog를 재생하지 않고 최신 desired만
  적용한다. Gradual revision key는 origin/mid/exact endpoint 3개로 제한하고 abrupt는
  8개 step key를 유지해 최소 200 ms gradual window에서도 30/60/120 fps의 required
  coverage를 보존한다. Producer activation과 typed HWC arm은 이를 요구하지만 app-side
  geometry apply evidence를 physical HWC composition proof로 표현하지 않는다.
  같은 generation에서 applied가 확인된 `SMALL_UNIFORM` preparation은 두 dynamic
  profile의 fraction-zero와 실제 geometry가 같을 때에만 해당 dynamic profile의 origin
  coverage bit 하나를 equivalent evidence로 seed할 수 있다. Mid/end 또는 abrupt의
  나머지 step coverage를 대체하지 않는다.
  Active coverage는 gradual의 origin/mid/end, abrupt의 8 step 전체를 요구한다.
  누락은 `LAYER_SIZE_COVERAGE_MISSING` event와 `INCONCLUSIVE`, 성공은
  `LAYER_SIZE_COVERAGE` event로 남긴다. 좁은 stage의 centered scale-aware horizontal
  stagger는 각 layer의 최소 1 px visibility를 보존한다.
  `BufferPresentation.FIT`은 고정 0°/90° orientation을 반영해 motion 전 source 전체를
  aspect-preserving letterbox하고, `PIXEL_1_TO_1_CROP`은 source/display pixel 1:1의
  centered overflow crop이다. 고정 orientation은 motion과 별도다. 1:1은
  `FULL_SCREEN`과 non-scaling motion만, `CAPACITY_TILES`는 FIT/0°만 허용한다.
  Projection·orientation은 full source allocation, graphics budget과 full-buffer
  traffic을 줄이지 않으며 discrete 변경은 fresh producer generation/readiness를 다시
  요구한다.
  HUD의 destination screen-equivalent footprint는 `LayerSizeProfile`의 base scale만
  합하고 MotionProfile scale, overlap, crop/clipping과 off-screen loss를 제외한다.
  단 `CAPACITY_TILES`는 explicit crop-union scope로 합계 1 screen-equivalent와 평균
  `100 / physical producer count`%를 보고한다.
  이를 measured bus나 full-buffer read/write traffic으로 표현하지 않는다.
- Transition `floor`는 pulse/triangle의 반복 valley에만 허용한다. STEP/linear/
  staircase/soak에 nonzero floor가 있는 runnable plan은 reject한다. 순수 evaluator는
  hostile direct call에서만 defensive하게 0으로 지워 origin sample을 건너뛰지 않는다.
- Typed `DEVICE_ONLY`/`CLIENT_REQUIRED` phase는 관측 계약이며 HWC 경로 강제가 아니다.
  Fresh composition evidence 동안 target geometry가 고정돼야 하므로 dynamic
  `LayerSizeProfile`을 함께 사용한 typed phase는 reject한다.
  Safety clamp가 계약 phase의 layer topology, producer FPS, display pacing, GL producer
  또는 GPU pressure를 바꾸면 다른 실험으로 축소하지 말고 reject한다. 3초 first-buffer
  readiness, 최대 4초 pre-target periodic sample mutex drain, probe당 4초 bounded fresh
  composition과 post-target 관측 tick을 위해 `DEVICE_ONLY`는 최소 12초, distinct fresh
  sample 2회를 요구하는 `CLIENT_REQUIRED`는 최소 16초다.
  현재 `HWC APP RAW D/C/T`는 control/root 보정과 workload identity partition이 없는
  `APP_RAW_UNSEPARATED` 원자쌍이다. Pure Compose HUD가 extra SF/HWC surface를 만들지
  않아도 Activity root는 raw scope에 남으며, public/privileged app API로 특정 app
  layer나 root를 DEVICE/CLIENT로 강제하거나 count에서 제외할 수 없다. Workload-only
  acceptance에는 display/session, generation/revision과 exact HWC layer identity를 같은
  validate/present boundary에 결속한 scoped typed BSP evidence가 필요하다.
- Typed HWC target arm 중 periodic telemetry는 latest-wins try-lock/drop으로 처리해
  forced probe 앞에 waiter를 쌓지 않는다. 필요한 forced sample은 같은 serialized
  ownership에서 수집하되 각 sample 사이 cancellation/thermal contract/fresh producer
  count와 topology revision을 재검증한다. Forced full telemetry가 safety/exact
  continuity를 갱신하며 모든 terminal/cancel/error/phase-finally는 identity-matched
  priority owner를 해제한다.
- 앱 process session의 최초 승인된 START는 첫 scenario 전에 HWC capacity 관측을
  정확히 한 번 시도한다. 요청 topology는 20L/30fps/60Hz independent opaque RGB DISPLAY
  `CAPACITY_TILES`이며 runtime safety/graphics budget이 실제 candidate를 줄일 수 있으므로
  requested=20과 actual candidate를 UI/event/report에서 구분한다. Topology publication과
  matching geometry acknowledgment 뒤 activation하고 preparation callback을 지운 뒤
  post-activation 모든 first buffer, 100ms 안정화와 single fresh DEVICE/CLIENT sample까지
  producer-active 전체 구간은 하나의 absolute 6000ms deadline 안에 있어야 한다. 모든
  terminal path에서 producer/generated
  load zero, teardown과 counter drain을 확인한다. Cleanup-confirmed non-cancelled
  경로만 deadline 밖의 3초 settle과 direct safety recheck 뒤 기존 1L scenario
  warm-up/fresh baseline을 시작한다. Renderer target handoff 전에 실패하면 actual
  candidate는 N/A다.
  계측 priority를 먼저 획득해 periodic telemetry를 drop하고 기존 local sample,
  SurfaceFlinger child와 vendor v1/v2 lane을 실제 completion까지 drain한다. Sample 뒤
  동일 generation의 topology/geometry revision, discontinuity serial과 fresh heartbeat를
  재검증하고 teardown 뒤에도 같은 quiescence barrier를 통과해야 한다. Barrier 실패는
  process-sticky telemetry lifecycle failure다. 격리 중 watchdog timestamp를 성공으로
  갱신하지 말고 pause/resume grace를 분리한다. Vendor/SF 없는 direct
  thermal/power/low-memory 검사는 producer readiness 대기에서 control cadence로
  유지하며 pre-drain 또는 composition sample에 별도 병렬 poll을 추가하지 않는다.
  Calibration vendor snapshot은 v1 한 번만 사용하고 optional v2는 생략한다. V1 원자
  D/C가 없을 때는 actual vendor worker quiescence를 bounded 확인한 뒤에만 SF fallback을
  시작하며, 미확인이면 두 probe를 겹치지 않고 N/A로 끝낸다.
  성공 `OBSERVED_AT_CANDIDATE`와 실패·timeout·취소의 terminal `UNAVAILABLE`은 같은 process의
  이후 scenario/repeat/START와 Activity 재생성에서 재사용하며, 반복 20-layer burst를
  만들지 않는다. SharedPreferences/disk에 저장하지 않는다. Display ID 또는 정규화한
  physical dimensions가 바뀌면 projection을 N/A로 무효화하되 같은 process에서 다시
  측정하지 않고 process 재시작을 요구한다. 단순 width/height 축 교환은 재사용한다.
  결과는 matching topology의 advisory boundary일 뿐 universal maximum, renderer safety
  cap, workload plane ceiling 또는 typed phase evidence가 아니다. Raw D/C/T에서 root
  상수를 차감하거나 candidate/`PHYSICAL` 수로 workload composition을 추론하지 않는다.
- Active run은 periodic/typed 모두 SurfaceFlinger child process를 만들지 않는다. Typed
  boundary는 같은 현재 vendor session의 fresh 원자 쌍만 사용하고 없으면 INCONCLUSIVE다.
  Session calibration의 `CALIBRATION_ONESHOT`은 vendor snapshot을 최대 한 번 prefetch하고
  같은 session의 nonnegative DEVICE/CLIENT 원자 쌍이면 SurfaceFlinger를 생략한다. 그렇지
  않을 때만 SF fallback을 한 번 허용하며 vendor snapshot을 두 번 호출하지 않는다.
  Session calibration cache를 phase evidence로 재사용하지 않는다. HWC count를 logcat이나
  임의 sysfs/debugfs plane 탐색으로 추론하지 않는다.
- HUD의 typed HWC `HWC APP RAW D/C/T`와 `현재값 일치/불일치/없음`은 2.5초 이내 동일
  source·quality·timestamp DEVICE/CLIENT pair와 그 pair의 `T=D+C`를 사용한 보조 해석일
  뿐이다. Pair가 없으면 반복 N/A 대신 bounded availability reason을 표시하되 Target
  readiness, distinct sample 수와 cross-phase 방향성을 확인하는 controller 최종 판정처럼
  표시하지 않는다. 각 run은 `HWC_COUNT_SCOPE` event로
  `APP_RAW_UNSEPARATED`, `controlLayerIncluded=true`, root subtraction 없음,
  FrameTracker `PHYSICAL` 분리와 scoped BSP evidence 필요를 남긴다.
- 16 ms producer hand-off를 넘기면 새 codec/EGL/Canvas replacement를 만들지 않고
  process-wide lease를 bounded poll한다. 5초 안의 transient drain은 phase active time과
  frame budget에서 제외하고 교차 부하를 0으로 유지한다. 연속 recovery deadline을
  넘긴 뒤에만 sticky failure로 만들며, 실제 thread 종료까지 후속 plan을 차단한다.
  해당 event를 report/result/UI의 terminal reason으로 유지한다. Child lifecycle
  teardown failure는 active relay의 generation으로만 귀속하고 disabled relay의 늦은
  callback은 무시한다. 시작된 Texture Canvas loop의 `Surface` wrapper와 backing
  `SurfaceTexture`는 worker의 실제 `finally`가 release하며 UI/framework hand-off
  timeout 경로에서 먼저 release하지 않는다.
- Canvas/EGL/MediaCodec frame-commit 및 native draw 경로의 일반 실패는 producer를 먼저
  revoke하고 runtime failure를 한 번 게시한 뒤 cleanup으로 진행한다. `ThreadDeath`와
  `VirtualMachineError`는 모든 native cleanup을 시도한 뒤 원 오류를 다시 던지며 cleanup/
  notification 실패로 대체하지 않는다. Producer timestamp map은 두 backing array
  expansion이 모두 성공한 뒤 원자적으로 교체해 두 번째 allocation OOME가 기존
  generation evidence를 손상시키지 않게 한다. Decoder output token은
  `releaseOutputBuffer()` 전에 bounded preallocated epoch+PTS queue에 결속하고 실패 시
  epoch+PTS+callback identity로 정확히 rollback한다. EOS는 listener를 내리고 재사용
  callback-looper barrier를 flush 전후 bounded drain한 뒤 queue clear → overflow-safe
  epoch 증가 → listener 재설치 순서를 사용한다. Teardown도 callback과 직렬화해 loop
  PTS가 stale revision을 재사용하지 않게 한다.
- Canvas/Texture/Video/GL의 physical Surface 또는 BufferQueue가 같은 generation에서
  재생성돼도 lifecycle signal을 먼저 topology pending으로 게시하고 geometry/HWC/
  first-buffer evidence를 지운다. 새 producer는 fresh geometry acknowledgment와 forced
  expected-set 재게시 뒤의 first buffer만 readiness로 인정한다.
- Renderer thread-start 실패 callback은 알림 예외와 무관하게 detach, stop/interrupt,
  callback-looper quit/join과 owner clear를 모두 시도한다. 일반 rollback 실패도
  `false` 성공처럼 낮추지 않으며 VM fatal 우선순위를 보존해 재전파한다. 이미 만들어진
  child의 transaction owner slot은 child 생성 전에 bounded하게 선할당하고 registration
  allocation gap을 허용하지 않는다. Process lifecycle owner set은 64개로 bounded
  fail-closed이며 owner ID를 wrap해 재사용하지 않는다.
- Frame/deferred scheduling과 expected-set callback은 renderer mutation transaction에
  포함한다. 동기 callback 재진입은 transaction-owned suppression depth와 capture한
  generation/callback/relay identity로 검증하며 release가 다른 transaction의 suppression
  token을 초기화하거나 callback 뒤 stale publication bookkeeping을 commit하지 않는다.
- 여러 producer의 control revision token 교체는 모든 replacement와 binding identity를
  mutation 없이 준비한 뒤 stale binding이 없을 때만 commit한다. Prepare 실패는 기존
  token을 전부 보존하고, commit 중 fatal/identity 실패는 모든 relay revoke, topology
  pending/evidence clear와 bounded child stop/join rollback 뒤 원 fatal을 재전파한다.
- STOP/pause는 cancellation reason 존재 여부와 관계없이 phase/target을 먼저 null로
  게시하고 local/NPU setpoint와 display request를 즉시 안전값으로 내린다. 취소된
  runJob의 NonCancellable finalizer가 소유권을 해제하기 전 새 START를 허용하지 않는다.
  Main.immediate run Job은 lazy 상태로 owner를 먼저 게시한 뒤 시작하고, finalizer는
  identity가 일치하는 자기 owner만 해제한다.
- 실행 UI의 STOP은 compact/landscape에서도 상단 header에 항상 보여야 한다. 좌측 상단
  HUD의 build version, layer/DPU/CPU/GPU 숫자·그래프와 예상
  DPU-read/producer-write traffic은 unavailable/provenance 및 pending `—P` 의미를
  숨기지 않는다. Gauge source/quality를 표시하고 provenance 변경/unavailable 경계를
  graph gap으로 유지한다.
  `PHYSICAL` layer 값은 requested/logical `activeLayers`가 아니라 commit된 expected/
  observed physical producer count를 사용한다. Unpublished/topology-pending/process-lease
  동안 값과 history를 null gap(`—P`)으로 유지하고 logical count를 표시하면 별도 label로
  구분한다. Running HUD는 Activity root의 pure Compose로 유지하고 HUD 전용
  `SurfaceView`/`TextureView`/`SurfaceControl`을 만들지 않아 extra physical producer와
  SF/HWC surface가 0이어야 한다. 동적 HUD 값은 하나의 immutable snapshot 인자로
  전달하고 그 교체를 app-side 최대 1 Hz로 제한해 상위 renderer의 100 ms recomposition과
  격리한다. 이 redraw 정책에서도 root는 다시 그려질 수 있으며 HWC composition type을
  강제/제외할 수 없다. `PHYSICAL`에는 root/HUD가 없고 `TextureView` BufferQueue
  producer가 들어갈 수 있으므로 HWC APP RAW total과 별도다.
- Test Window의 immersive hide는 status/navigation bar가 모두 invisible이라는 Insets
  acknowledgment 전에는 producer를 시작하지 않는다. 종료 시 `show()` 요청 성공만으로
  token을 해제하지 않고 원래 bar visibility mask의 Insets acknowledgment까지
  process-wide lease를 유지한다. 부분 hide 실패도 cleanup token을 controller에
  넘기며, 복원 미확인/focus loss/post-confirmation reveal에서는 새 plan을 fail-closed로
  차단한다. 재생성된 Activity의 local IDLE Window도 이전 process lease가 active이면
  system bar hide를 유지하고 matching release와 visible Insets 확인 뒤에만 일반 UI를
  복구한다. Foreign Window hide는 100 ms 간격의 4회 verification/attempt로 제한하고,
  끝내 확인되지 않으면 원래 lease owner에 fail-closed 오염 신호를 보내며 busy
  retry하지 않는다. Owner Activity close에서는 matching failure callback만 분리하고
  sticky process lease 자체는 해제하지 않는다.
  RESTORING/RESTORE_FAILED focus gain에서 bar를 다시 숨기지 않는다.
- Test plan은 status/navigation bar가 모두 hidden으로 확인된 tokenized Window
  isolation 안에서만 producer를 시작한다. 최초 hide pending 중 visible Insets는
  허용하지만 확인 뒤 system bar 재등장 또는 window focus loss는 측정 오염으로
  fail-closed 중단한다. Multi-window/PiP에서는 시작을 거부하고 queue/loop, terminal
  sample과 renderer teardown까지 isolation을 유지한 뒤 모든 종료 경로에서 같은 token의
  system bar만 복구한다.
- SBWC route 적용/해제 결과는 모두 event로 남긴다. 활성 SBWC의 linear/default reset을
  확인하지 못하거나 adapter가 거부/timeout되면 fail-closed로 plan을 중단한다.
- 정상 cooldown에서도 phase/target과 generated load를 먼저 제거하고 physical
  Surface/codec/EGL/Canvas producer teardown을 확인한 뒤 compression route를
  linear/default로 reset한다. 마지막 renderer phase를 cooldown에 복사하지 않는다.
- inter-phase pixel/compression route 변경도 load/NPU zero 확인 → phase/target null →
  renderer teardown barrier → vendor route → 새 producer generation 순서를 사용한다.
  Warm-up은 vendor route 설정 전에 1-layer RGB/DISPLAY producer만 만든다.
- Activity destroy는 Compose/AndroidView teardown의 동기 증거가 아니다. Lifecycle
  `close()`에서는 producer lease 관찰 여부와 무관하게 compression reset을 호출하지
  않는다. 비선형 route가 active/unknown일 때만 sticky cleanup latch를 유지하고,
  RGB-only renderer 지연으로 compression latch를 세우지 않는다.
- 성공한 비선형 route는 acknowledgment를 반환한 vendor service session에 결속한다.
  Active SBWC에서 process-local registration이 없어지거나 바뀌면 fail-closed로
  중단한다. Remote snapshot timeout/null은 registration continuity와 별개이므로
  Binder disconnect로 오인하지 않는다.
- Allocation route를 바꾼 phase의 모든 active control tick은 target의 discrete
  layer/backend/pixel route/buffer size/alpha/GL topology를 유지한다. Fraction-zero
  origin은 FPS/workload 등 연속 값만 제공하며 이전 route를 다시 게시하면 안 된다.
- 모든 종료 경로에서 CPU/memory worker, codec, Surface, GL, vendor NPU/SBWC state,
  wake flag를 해제한다.
- NPU ordered zero/adapter close가 미확인이면 process-wide latch를 유지하고 후속
  reflection 초기화와 새 plan을 허용하지 않는다. Close의 최종 확인은 close 전에 시작된
  release의 늦은 결과보다 우선해야 한다.
- Selected-media preflight는 seek 가능한 pinned `AssetFileDescriptor` 하나를 authority로
  사용한다. Provider open은 5초, `MediaExtractor` 검사는 10초 제한의 daemon worker에서
  실행하고 descriptor open 전부터 parser 종료까지 process-wide single refcount lease를
  유지한다. Timeout/cancel 후 root가 반환되어도 worker hold는 실제 `finally`까지 남겨
  Activity 재생성과 후속 plan을 차단한다.
- 연결된 실기기에서 stress scenario를 자동 실행하지 않는다. 사용자가 대상 실험기와
  실행 범위를 명시해야 한다.

## 계측 정확성

- 숫자는 `MetricQuality`와 source를 유지한다.
- DPU busy/exact underrun은 검증된 vendor 또는 kernel source만 사용한다.
- Vendor broker는 product read-only config의 explicit component, permission owner,
  owner/service signer SHA-256 trust root를 검증한 뒤에만 bind한다. Signature permission
  grant, system/exported/enabled service와 exact service permission이 모두 맞아야 한다.
  누락·불일치·bind permission 거부는 permanent `UNAVAILABLE`이며 implicit discovery나
  Activity별 reconnect loop로 우회하지 않는다.
- Built-in exact underrun kernel 후보는 DPU-scoped
  `/sys/class/dpu/dpu0/{underrun_count,underrun_cnt}`뿐이다. Generic DRM underrun node를
  자동 exact로 승격하지 않는다. Custom probe는 key별 sysfs namespace와 canonical
  regular/readable attribute를 통과해야 하며 `/proc`, traversal, control/whitespace
  path는 거부한다.
- exact counter baseline은 단순 warm-up delay 뒤가 아니라 bounded readiness 뒤에 잡고
  source/quality/monotonic continuity를 유지한다. Expected topology가 published되어
  pending/missed가 아니고 matching geometry가 acknowledged된 뒤 activation하고,
  preparation callback을 지운 이후 committed producer 전부의 fresh first buffer를
  확인해야 한다. Baseline은 fresh sample barrier로 획득하고 직후 같은
  topology/geometry/readiness를 재검증하며, 바뀌면 baseline을 폐기하고 fail-closed한다.
  이전 run에서 시작된 in-flight sample은 새 run에 귀속하지 않는다. 양의 delta 증거는
  보존하되 0-delta `CLEAN`은 baseline 뒤 sample과 끝까지 이어진 연속성이 있을 때만
  허용한다.
- 정상 verdict는 최종 physical producer teardown을 확인한 뒤 serialized fresh terminal
  counter sample까지 성공한 후에만 계산한다. 이 sample 또는 periodic telemetry 실패는
  telemetry gap으로 exact continuity를 무효화한다. Sample evidence timestamp는 모든
  counter/state read가 끝난 시각이며 CPU interval 시작과 분리한다. 마지막 완료
  evidence의 5초 stale은 run을 중단하되, 이미 수락된 single-flight sample은 4초
  operation timeout과 다음 500 ms watchdog tick까지의 bounded deadline만 보호한다.
  SystemMonitor 종료는 local sample worker 완료 확인이 LoadManager/vendor teardown보다
  먼저여야 하며, 이 확인이 실패하면 worker가 참조할 수 있는 dependency를 닫지 않고
  process cleanup gate를 sticky failure로 유지한다. Source/quality 변경 또는
  reset/regress도 continuity를 무효화한다. 신뢰할 exact delta가 없으면 report/UI의
  delta source와 quality도 `N/A`/`UNAVAILABLE`이어야 한다.
- 신뢰 가능한 exact delta가 있으면 exact verdict가 proxy보다 우선한다. Exact 0과
  proxy 증가를 `SUSPECTED_PROXY`로 내리지 말고 proxy를 보조 event/수치로만 보존한다.
- adaptive boundary는 topology preparation 직전과 active phase 종료 직후의 serialized
  fresh sample을 사용한다. Setup/tail 증가를 포함하되 exact source/quality/monotonic
  continuity가 바뀌면 exact boundary delta를 사용하지 않는다.
- missed frame, `Choreographer`, SurfaceFlinger HWC/GPU miss, producer stall은
  `PROXY`이며 exact underrun으로 표현하지 않는다.
- Producer fidelity는 generation이 승인한 모든 physical buffer 수를 실제 적용한
  `producer FPS × physical producer count` 시간 적분과 비교한다.
  `FLATTENED_TEXTURE`는 count 1이다. 기대 aggregate가 30 frame 이상이고 actual이 70%
  미만이면 `PRODUCER_RATE_SHORTFALL`을 남기며, verified exact delta가 양수가 아닌 run은
  `INCONCLUSIVE`로 판정한다.
- source가 없거나 parse가 불확실하면 0이 아니라 `N/A`/`UNAVAILABLE`을 반환한다.
- GPU probe는 encoding과 frequency unit이 결속된 typed path만 사용한다. KGSL
  `gpubusy` window pair를 cumulative delta로 해석하지 않고, explicit probe 실패를
  generic fallback으로 숨기지 않으며 read gap 뒤 cumulative baseline을 재사용하지
  않는다. Exynos Xclipse는 AMD-RDNA DRM direct-percent ABI와 결속하고 Mali path를
  Xclipse로 오인하지 않는다. Xclipse/GED/Mali 고정 ABI를 Samsung 공통 SKI 후보보다
  먼저 검사하고 legacy direct/cumulative 실제 encoding을 provenance에 남긴다.
  GED debugfs는 기본 탐색하지 않는다. Vendor GPU/DPU
  frequency 확장은 AIDL 끝에 append한 API v2 getter만 사용하고 `apiVersion >= 2`
  gate와 값 범위 검증을 유지한다. Optional v2 Binder 호출은 v1/exact-counter 호출과
  분리된 bounded no-backlog lane과 하나의 전체 snapshot deadline을 사용한다. 개별 v2
  실패는 같은 service session의 v1 snapshot을 지우지 않지만 session 변경은 snapshot
  전체를 폐기한다.
- Binder가 반환하는 NPU/compression status는 HUD/sample/report에 넣기 전에 최대
  256자로 제한하고 whitespace/control/format 문자를 정규화한다.
- run peak는 유효 범위 안의 sample 중 같은 `MetricQuality`와 source가 유지된 경우에만
  집계한다. 도중 provenance가 바뀐 CPU/memory/generated traffic/DPU/GPU/bus/produced
  FPS/HWC DEVICE·CLIENT peak는 합치지 않고 `N/A`로 표시한다. HWC peak는 complete
  same-sample pair의 `(D,C,T=D+C)` tuple 중 T가 가장 크고 동률이면 D가 큰 하나를
  선택하며 서로 다른 sample의 `max(D)`와 `max(C)`를 결합하지 않는다.
- traffic은 linear full-buffer `ESTIMATED` 모델이다. 실측 bus 점유율과 합치거나 capacity
  판정에 사용하지 않는다. Selected decoder B/px는 route에서 추론하지 말고 검증된
  MIME/profile descriptor만 사용하며, bit depth/chroma가 불명확하면 aggregate를
  `N/A`로 유지한다. Descriptor B/px는 finite, 0 초과, 16 이하만 허용한다. SBWC
  compression ratio는 포함하지 않는다.
- YUV/P010/SBWC decoder phase는 선택·pin·검증된 media와 concrete hardware codec
  binding이 필수다. 선택 media가 없거나 binding/fingerprint가 불완전하면 procedural
  RGBA proxy로 대체하지 않고 fail-closed한다.
- decoder phase는 실제 track width/height/FPS metadata가 모두 있어야 한다. FPS가
  없거나 tolerance를 포함해 phase target 및 reachable transition FPS에 미달하면
  fail-closed로 거부한다. Gradual transition은 직전 FPS 전체, STEP은
  `min(60, 직전 FPS)` boundary를 포함한다.
- decoder capability의 size는 exact encoded dimensions, rate는
  source/decoder-phase/reachable-transition FPS의 최댓값으로 검사한다. 낮은 phase
  pacing이 고FPS source 또는 transition origin의 decode 요구를 숨기지 않게 한다.
- VP9 Profile 2는 10/12-bit 4:2:0을 함께 포괄하므로 profile만으로 P010을 허용하지
  않는다. Extractor의 512자 이하 canonical `vp09.02.<level>.10...` codec string에서
  bit-depth 10이 명시적으로 확인될 때만 P010 gate와 3 B/px linear reference에
  사용한다. Malformed/12-bit/conflicting VP9 entry는 fail-closed한다.
- selected-media decoder precheck는 concrete hardware codec name을 결정한다. P010
  phase만 extractor profile과 codec advertised profile을 exact-match하며 일반 YUV/SBWC는
  size/rate를 확인한다. URI/MIME/codec name immutable binding과 `createByCodecName`을
  사용하고 stale/missing binding은 proxy fallback 없이 fail-closed한다.
- immutable media binding에는 encoded/visible dimensions, source FPS, profile,
  codecs string, P010 verification도 포함한다. Renderer는 URI를 다시 열어 이
  fingerprint를 재검증한다. Crop은 horizontal pair와 vertical pair를 독립 처리하고
  한 축의 pair가 모두 없으면 그 축 전체를 사용한다. Pair 중 lone key, 범위 오류,
  fingerprint 변경, output visible resolution 변경 또는 64 px alignment allocation
  ceiling 초과는 fail-closed한다.
- 64 px ceiling은 graphics budget과 decoder output allocation 검증에만 사용한다.
  Source `MediaFormat.KEY_MAX_WIDTH/HEIGHT`는 둘 다 없거나 encoded width/height와
  정확히 같은 pair일 때만 허용한다. Partial/크거나 작은 pair는 reject하고 renderer가
  같은 pinned descriptor에서 재검증한 뒤 `MediaCodec.configure()` 직전에 두 key를
  제거한다. Codec capability에는 exact encoded size/rate를 사용한다.
- Adaptive Hunt boundary의 memory load는 phase-end fresh sample까지 `STEADY` plateau로
  유지하고 cyclic reset 파형으로 바꾸지 않는다.
- SBWC REQUIRED는 실제 allocation/compression state를 확인하지 못하면 성공으로
  처리하지 않는다.
- NPU adapter가 없을 때 CPU 연산으로 대체해 NPU 사용이라고 표시하지 않는다.
- View/client Z-order swap은 client ordering proxy이고 physical HWC plane Z-order
  변경의 증거가 아니다. Typed motion semantics와
  `physicalHwcZOrderChange=false` report 의미를 유지한다.
- counter의 monotonicity, reset/wrap, display scope, sampling interval을 test하고
  report/source에 보존한다.
- `dpu_frequency_hz`는 명시된 제품 path의 read-only Hz counter다. 앱에 DPU frequency
  write/lock/governor override를 추가하지 않는다.
- report schema v2의 exact provenance, transition/event/sample 의미와 non-finite
  `null` 직렬화를 유지한다.
- report 발행은 process 안에서 직렬화하고 temp write/fsync/rename 뒤 수행한다. 완료
  `dpu-layer-lab-` prefix와 앱 파일명 형식이 확인된 `.json`만 최신 400개로 best-effort
  보존하되 방금 발행한 파일과 `.part`/unrelated `.json`은 삭제하지 않는다.
  마지막 report의 performance-restore 결과 교체는 replacement publish → obsolete
  managed report 삭제 확인 → 400개 prune 순서의 같은 transaction이다. Obsolete 삭제가
  확인되지 않으면 다른 plan report를 잃지 않도록 그 transaction의 prune을 건너뛴다.
  Plan-wide Battery Saver restore가 실패하면 앞서 완료된 plan item도 `ABORTED`로
  무효화하고 report path를 철회하며 managed completed JSON만 best-effort 삭제한다.

## 금지사항

- platform signing만으로 SELinux/DAC 또는 vendor node 접근이 가능하다고 가정하지 않는다.
- 앱 domain 전체에 광범위한 `/sys`/debugfs read/write 권한을 권장하지 않는다.
- 임의 sysfs/debugfs path 탐색, root 명령 또는 SELinux 우회를 portable app에 넣지 않는다.
- 무제한 layer/buffer/thread 생성, busy loop 또는 blocking Binder getter를 추가하지 않는다.
- thermal/low-memory abort를 “벤치마크 연속성”을 이유로 비활성화하지 않는다.
- background network upload, analytics, 영상 본문 수집을 명시적 요구와 privacy 설계 없이
  추가하지 않는다.
- platform key, `*.pk8`, `*.pem`, keystore, password, token, device report를 commit하지
  않는다.
- build artifact를 source commit에 포함하지 않는다.

## 완료 정의

변경은 다음을 만족해야 완료다.

1. 요청 동작과 실패/취소/수명주기 edge case가 구현됐다.
2. safety cap과 계측 provenance가 유지된다.
3. 관련 boundary/unit test가 추가 또는 갱신됐다.
4. `testDebugUnitTest`와 `lintDebug`가 통과한다.
5. `assembleDebug`와 release 요청이 있으면 `assembleRelease`가 통과한다.
6. renderer/load 변경은 종료 후 resource가 남지 않는지 검토했다.
7. 사용자-facing 의미가 바뀌면 `README.md`, 장기 결정이 바뀌면
   `PROJECT_MEMORY.md`, BSP 계약이 바뀌면 `docs/SYSTEM_INTEGRATION.md`를 갱신했다.
8. automation 변경은 alias 보안/direct-main-ignore/implicit-resolution, plan 상한과
   busy START/STOP 경계를 test했다.
9. counter/compression/media/producer 변경은 provenance·continuity, fail-closed reset,
   terminal sample, stable-source peak, track MIME/FPS/profile, crop/visible-dimension
   검증과 fingerprint, fixed `KEY_MAX_*` pair/configure 전 제거, pinned AFD와
   provider/parser refcount lease, exact-size capability와 64 px allocation ceiling,
   generation·all-producer·teardown race, aggregate producer-rate boundary test를
   갱신했다.
10. power/isolation 변경은 original Battery Saver ON/OFF, power-save cap 보존,
    BEGIN/renew/health/END, stale/late command, death/expiry, exact restore와
    system-wide overlapping-client arbitration을 함수 단위와 전체 run/cancel/Activity
    재생성 흐름에서 검증했다.
11. HWC capacity session 변경은 one-shot claim/terminal reuse, requested/actual,
    topology/geometry commit → activation → post-activation all-first-buffer readiness,
    6000ms total producer deadline, vendor-prefetch/SF-fallback, Activity 재생성과
    display-scope projection을 test했다. `SESSION_HWC_CAPACITY_*`와
    `HWC_COUNT_SCOPE` event, app raw D/C/T same-sample tuple, PHYSICAL 분리 및
    workload-plane ceiling 미추론도 검증했다.
12. tracked 파일에 secret, APK, report, local path가 없다.
13. 보고서는 internal `files/reports`만 사용하고 FileProvider로만 공유한다. 공유할
    파일은 canonical internal directory 안에 실제 존재하며 managed completed
    `dpu-layer-lab-…json` 이름을 통과해야 한다. Traversal, foreign/missing file은
    거부한다.
14. cloud backup/device-to-device/legacy rule에서 모든 app data domain이 제외된다.
