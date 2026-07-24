# Repository Working Rules

이 파일은 사람과 coding agent가 DPULayerTest를 수정할 때 따르는 canonical repository
instruction입니다. 장기 설계 맥락은 `PROJECT_MEMORY.md`, 사용자-facing 설명은
`README.md`를 먼저 확인합니다.

Launcher와 Gradle project의 표시 이름은 `DPULayerTest`이고 canonical remote는
`https://github.com/sinpie/dpu-layer-lab`이다. 제품 호환성 계약인 package
`com.example.dpulayerlab`, automation component/action, `dpu-layer-lab-` report
prefix, Soong module/APK 이름 `DpuLayerLab`은 별도 migration 요구 없이 바꾸지 않는다.
현재 release version은 `20260724_111816`(`versionCode 3`), debug version은
`20260724_111816-debug`이며 `yyyyMMdd_HHmmss`는 KST build 시각이다. release tag는
`v20260724_111816`이다. Release asset은
`DPULayerTest-20260724_111816-debug.apk`,
`DPULayerTest-20260724_111816-release-unsigned.apk`, `SHA256SUMS.txt` 이름을 사용한다.

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
  explicit 4K/8K buffer로 표시하지 않는다. Custom의 `0.001` 초과 GPU load는 실제
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
- thermal SEVERE derating은 이후 phase에도 유지한다. 기존 generated/NPU load의
  ordered zero 확인 → reduced workload ticket/acknowledgment → display 감속
  acknowledgment 순서를 지키며, 하나라도 실패하면 `THERMAL_DERATE_FAILED`로 중단한다.
- loop, thread, buffer allocation, codec dequeue, Binder call에는 상한이나
  cancellation 경로가 있어야 한다.
- CPU/memory 부하는 fixed-period bounded worker와 재사용 buffer를 유지한다. NPU/vendor
  control은 bounded latest-wins로 처리하며 오래된 setpoint backlog를 만들지 않는다.
- 양의 NPU setpoint도 latest command ticket과 acknowledgment가 일치한 뒤에만 적용
  완료로 본다. Active phase 동안 backend health를 확인하고 apply timeout/거부/health
  상실은 `NPU_WORKLOAD_APPLY_FAILED`로 fail-closed한다.
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
  append/replace는 catalog 순서와 40-run cap을 지키고, queue의 중복·명시적 이동은
  보존하되 복원된 unknown preset ID는 표시/index/실행 전에 제거한다.
- 외부 control은 explicit `AutomationActivity` alias에서만 처리한다. Release의
  `CONTROL_TESTS`(`signature|privileged`) 보호, debug-only permission 제거,
  `CATEGORY_DEFAULT` 부재와 direct `MainActivity` START 무시를 유지한다.
- producer callback은 generation token과 physical producer ID로 분리한다. 게시 전
  immutable token capture, expected topology 선언 전 readiness 금지, 모든 producer
  first buffer/heartbeat와 peak topology 확인을 유지한다. Frame hot path에 per-frame
  lambda/boxed timestamp allocation을 다시 추가하지 않는다.
- topology pending 중 fake expected producer를 게시하지 않는다. 실제 relay set을
  commit한 뒤 같은 generation에 한 번 게시하고, 그 전에는 phase clock/transition/
  workload/frame budget을 시작하지 않는다. Activation은 fresh counter sample 뒤에
  수행하고 preparation first-buffer를 active startup 성공으로 세지 않는다.
  HUD의 expected count는 unpublished/pending/process-lease 동안 0(`—P`)으로 투영하고
  frame-budget용 committed count와 분리한다.
- active topology-pending callback에서 callback timestamp/physical total까지 expected
  frame budget을 즉시 정산·pause하고 교차 부하를 0으로 내린다. 다음 controller
  poll까지 이전 producer count를 계속 적분하거나 부하를 유지하지 않으며
  commit/restart 뒤에만 resume한다.
- Transition은 duration cap 반영 뒤 실제 window를 100 ms control cadence로 검증한다.
  Ramp 중간 tick, staircase의 각 level, pulse/triangle 한 cycle, soak의 최소 attack
  2 tick/hold 1 tick/recovery 2 tick을 보존할 수 없으면 reject한다. `STEP`은 fresh
  baseline과 origin producer buffer가 확인된 뒤 measured active tick에서 target을
  적용하고, post-ready tick 없이 끝난 phase는 `INCONCLUSIVE`다.
  실행 loop는 absolute-deadline fixed period로 늦은 tick을 busy catch-up하지 않는다.
  Runtime coverage가 ramp 중간값, staircase 전 level, pulse ON/OFF, triangle 상승/하강,
  soak attack/hold/recovery를 관측하지 못하면 `INCONCLUSIVE`다.
- Transition `floor`는 pulse/triangle의 반복 valley에만 허용한다. STEP/linear/
  staircase/soak에 nonzero floor가 있는 runnable plan은 reject한다. 순수 evaluator는
  hostile direct call에서만 defensive하게 0으로 지워 origin sample을 건너뛰지 않는다.
- 16 ms producer hand-off를 넘기면 새 codec/EGL/Canvas replacement를 만들지 않고
  process-wide lease를 bounded poll한다. 5초 안의 transient drain은 phase active time과
  frame budget에서 제외하고 교차 부하를 0으로 유지한다. 연속 recovery deadline을
  넘긴 뒤에만 sticky failure로 만들며, 실제 thread 종료까지 후속 plan을 차단한다.
  해당 event를 report/result/UI의 terminal reason으로 유지한다. Child lifecycle
  teardown failure는 active relay의 generation으로만 귀속하고 disabled relay의 늦은
  callback은 무시한다.
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
- exact counter baseline은 warm-up 뒤에 잡고 source/quality/monotonic continuity를
  유지한다. Baseline은 fresh sample barrier로 획득하고 이전 run에서 시작된 in-flight
  sample은 새 run에 귀속하지 않는다. 양의 delta 증거는 보존하되 0-delta `CLEAN`은
  baseline 뒤 sample과 끝까지 이어진 연속성이 있을 때만 허용한다.
- 정상 verdict는 최종 physical producer teardown을 확인한 뒤 serialized fresh terminal
  counter sample까지 성공한 후에만 계산한다. 이 sample 또는 periodic telemetry 실패는
  telemetry gap으로 exact continuity를 무효화하고, 5초 stale은 run도 중단한다.
  Source/quality 변경 또는 reset/regress도 continuity를 무효화한다. 신뢰할 exact
  delta가 없으면 report/UI의 delta source와 quality도 `N/A`/`UNAVAILABLE`이어야 한다.
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
- Binder가 반환하는 NPU/compression status는 HUD/sample/report에 넣기 전에 최대
  256자로 제한하고 whitespace/control/format 문자를 정규화한다.
- run peak는 유효 범위 안의 sample 중 같은 `MetricQuality`와 source가 유지된 경우에만
  집계한다. 도중 provenance가 바뀐 CPU/memory/generated traffic/DPU/GPU/bus/produced
  FPS/HWC DEVICE·CLIENT peak는 합치지 않고 `N/A`로 표시한다.
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
  `dpu-layer-lab-` prefix와 앱 파일명 형식이 확인된 `.json`만 최신 200개로 best-effort
  보존하되 방금 발행한 파일과 `.part`/unrelated `.json`은 삭제하지 않는다.

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
10. tracked 파일에 secret, APK, report, local path가 없다.
11. 보고서는 internal `files/reports`만 사용하고 FileProvider로만 공유한다. 공유할
    파일은 canonical internal directory 안에 실제 존재하며 managed completed
    `dpu-layer-lab-…json` 이름을 통과해야 한다. Traversal, foreign/missing file은
    거부한다.
12. cloud backup/device-to-device/legacy rule에서 모든 app data domain이 제외된다.
