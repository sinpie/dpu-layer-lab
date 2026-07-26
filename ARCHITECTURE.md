# DPULayerTest Architecture

> **Authority:** 현재 코드의 component 경계, runtime/data flow와 resource ownership
> **Audience:** maintainer, reviewer, 기능 확장 담당자, 코드 복구 담당자
> **Update when:** component 책임, 상위 실행 흐름, dependency 또는 resource ownership이 바뀔 때
> **Does not own:** 파일 목록, 허용 상태 전이, 작업 우선순위, 안전 규칙 원문, metric 세부 의미, BSP 구현 방법
> **Related:** [Documentation index](docs/INDEX.md), [AGENTS.md](AGENTS.md), [PLAN.md](PLAN.md),
> [PROJECT_MEMORY.md](PROJECT_MEMORY.md), [docs/SCENARIOS.md](docs/SCENARIOS.md),
> [docs/METRICS.md](docs/METRICS.md), [docs/REPOSITORY_MAP.md](docs/REPOSITORY_MAP.md),
> [docs/STATE_MACHINES.md](docs/STATE_MACHINES.md),
> [docs/HWC_CAPACITY_CALIBRATION.md](docs/HWC_CAPACITY_CALIBRATION.md),
> [docs/SYSTEM_INTEGRATION.md](docs/SYSTEM_INTEGRATION.md),
> [docs/RECONSTRUCTION.md](docs/RECONSTRUCTION.md)

## 시스템 목적과 경계

DPULayerTest는 Android display pipeline에 서로 다른 layer topology, producer pacing,
motion, pixel route와 교차 자원 부하를 적용하고 DPU underrun 또는 그 proxy를 관찰하는
lab app이다.

앱이 직접 통제하는 범위는 다음과 같다.

- app 소유 `SurfaceView`, `TextureView`, EGL/Canvas/MediaCodec producer
- app CPU·memory·GPU workload와 optional vendor NPU command
- display mode request, keep-screen-on, test Window의 system bar 격리
- catalog/queue/loop 실행과 telemetry/report 수집

portable app이 보장하지 않는 범위는 다음과 같다.

- 특정 layer가 반드시 HWC DEVICE plane에 배치되는지
- 실제 DPU plane 수나 format/rotation/scaling 제한
- SBWC allocation과 compression ratio
- DPU/GPU/DRAM clock 또는 governor 제어
- SELinux/DAC를 우회한 vendor node 접근

따라서 `HwcCompositionExpectation`은 실행 의도와 관측 계약이며 강제 API가 아니다.
metric의 exact/proxy 경계는 [docs/METRICS.md](docs/METRICS.md)가 authority다.

## Component 경계

- Activity/Compose는 입력, Window isolation과 표시를 소유하지만 plan 실행 권한은
  controller에 위임한다.
- Pure model/policy는 scenario 값, transition, safety budget과 traffic 계산을 Android
  lifecycle에서 분리한다.
- Controller는 plan/phase transaction, evidence, verdict와 cleanup owner를 하나로 묶는다.
- Renderer와 load subsystem은 실제 producer/worker를 만들고 generation/ticket
  acknowledgment로 controller에 상태를 돌려준다.
- Monitor/vendor adapter는 source·quality가 결속된 telemetry transaction만 게시한다.
- Report writer는 immutable run summary를 schema v2 completed JSON으로 발행한다.

현재 package와 tracked file의 정확한 위치는
[Repository map](docs/REPOSITORY_MAP.md)이 authority다.

## 상위 실행 흐름

```text
Compose UI 또는 explicit AutomationActivity
    ↓
ScenarioCatalog / custom PhaseSpec / ScenarioRunPlan snapshot
    ↓
ScenarioPlanPolicy + DeviceRenderSafety + ScenarioSafetyPolicy
    ↓
MainActivity Window isolation + LabController plan owner
    ↓
process-session HWC capacity advisory attempt/reuse
    ↓
scenario preflight → media/vendor capability → warm-up → exact baseline
    ↓
phase transaction
    ├─ LayerStageView physical producers
    ├─ LoadManager CPU/memory/NPU + GL producer
    ├─ display request / SBWC route
    └─ SystemMonitor + FrameTracker evidence
    ↓
cooldown → physical teardown → fresh terminal counter
    ↓
verdict → ReportWriter → compact plan result
    ↓
plan-wide Battery Saver restore → Window/SystemUI restore
```

## 입력과 UI

`MainActivity`는 launcher와 protected `AutomationActivity` alias의 실제 target이다.
Cold-start automation START는 decor attach와 첫 authoritative root Insets까지 보류하고
STOP은 readiness와 무관하게 즉시 처리한다. 실행 owner가 활성화되면 immutable display
ID/normalized physical dimensions를 capture하며 `DisplayManager.DisplayListener`,
configuration/start/focus 경계가 같은 snapshot과 달라지는 즉시
`SAFETY_ENVELOPE_CHANGED`로 중단한다.
automation 요청은 component class를 다시 확인하며, `START`에서만 extras를
unmarshal한다. `STOP`은 pending START보다 우선한다. 계약과 cap은
`engine/AutomationIntentContract.kt`에 있다.

Compose의 주요 section은 다음과 같다.

- Dashboard: 현재 capability와 metric overview, 목적별 빠른 시작
- Scenario catalog: saveable한 `테스트 선택`/`순서·반복·시간` 두 단계, 목적과
  category/pattern/load/condition facet, bounded vertical queue 구성
- Custom builder: 단일 scenario의 topology·format·motion·workload·transition 설정
- System: permission, codec, display mode, direct probe와 runtime protection 상태
- Running: 항상 접근 가능한 STOP, phase/plan 진행, 좌측 상단 HUD
- Result: 최신 run과 plan item별 verdict/report

UI는 실행 권한의 authority가 아니다. 모든 renderer 입력은 controller의 runtime safety
policy를 다시 통과한다.

Running HUD는 `LayerStageView`를 감싼 같은 Activity root 안에서 그리는 pure Compose다.
HUD 자체가 `SurfaceView`, `TextureView` 또는 별도 `SurfaceControl`을 만들지 않으므로
추가 physical producer와 SurfaceFlinger/HWC surface 수는 0이다.
`controlLayerIncluded=true`는 HUD 전용 layer가 있다는 뜻이 아니라 Compose를 담은 app
Window root가 화면에 남는다는 뜻이다. Running HUD는 동적 값을 하나의 immutable
snapshot 인자로 받고 그 snapshot 교체를 app-side 최대 1 Hz로 제한해 상위 renderer의
100 ms progress recomposition과 격리한다. 이 정책은 불필요한 app-side redraw를 줄일
뿐 root buffer update를 없애거나, Android public API 또는 platform-signed/privileged
app API로 root를 HWC `DEVICE`/`CLIENT` 중 하나로 강제하거나 HWC 관측에서 제외하는
보장이 아니다. Compose나 `TextureView`가 app-side에서 root buffer로 flatten된다는
사실도 HWC `CLIENT` composition의 증거가 아니다.

## 핵심 모델

`model/LabModels.kt`의 계층은 다음과 같다.

- `PhaseSpec`: duration, layer count, producer FPS, requested display Hz, backend, pixel route,
  buffer size, FIT/1:1 projection, fixed 0°/90° orientation, motion, workload, alpha/GL,
  transition, HWC expectation
- `ScenarioSpec`: metadata와 순서가 있는 phase 목록
- `ScenarioRunPlan`: 순서를 보존하는 scenario queue, whole-queue repeat, duration multiplier,
  source
- `PlanProgress`: queue/repeat 전체 진행, 요청 duration multiplier와 terminal reason
- `RunProgress`: 현재 stage/phase/target, transition fraction, producer generation/readiness
- `TelemetrySnapshot`: 값·단위·quality·source가 결속된 한 번의 telemetry transaction
- `RunSummary`: verdict, exact/proxy delta, peak, event와 sample

queue의 duplicate는 A/B/A를 표현하기 위해 의도적으로 유지한다. 외부 automation은
catalog preset만 사용할 수 있고 repeat 10, expanded run 40 상한을 가진다.
앱 UI plan의 수동 queue에는 임의의 고정 항목/expanded-run 상한이 없고 전체를 최대
10회 loop한다. Controller는 repeat를 펼친 목록을 만들지 않고 queue×repeat를 순차
순회하며, 같은 preset이 중복된 immutable execution copy는 공유한다.
`durationMultiplier`는 1/2/5/10/50/100 중 하나이며 controller가 immutable execution
copy를 만들 때 phase duration과 transition window/cycle에 한 번만 적용한다. 이후
device safety policy가 phase 10분/scenario 30분 상한을 명시적으로 적용한다.

### Layer 표시 크기

`LayerSizeProfile`은 `MotionProfile`과 직교하는 destination footprint 계약이다.

| 값 | 의미 |
|---|---|
| `FULL_SCREEN` | 기존 동작을 보존하는 기본 full-stage footprint |
| `SMALL_UNIFORM` | 동일한 작은 footprint |
| `MIXED_SIZES` | layer index에 따른 결정적 small/medium/large 혼합 |
| `GRADUAL_SMALL_TO_FULL` | phase progress에 따른 연속 확대 |
| `ABRUPT_SMALL_FULL` | bounded step으로 small/full 반복 |

이 값은 physical producer 수나 source buffer allocation을 줄인다는 뜻이 아니다.
invalid index/count 또는 non-finite fraction은 안전 예산을 작게 보이지 않도록
full-screen으로 처리한다.

`LayerStageView`는 full source buffer와 producer identity를 유지한 채 physical child의
destination scale/translation/crop을 적용하고 기존 motion transform과 합성한다.
Topology preparation/recovery는 dynamic waveform을 진행시키지 않고 static measured
origin을 고정한다. Prior explicit static origin이 있으면 full/small/mixed 값을
보존하고, 없을 때만 두 dynamic profile의 fraction-zero와 동등한
`SMALL_UNIFORM`을 사용한다. Dynamic progress의 authority는 controller의 pause-aware
`RunProgress.phaseElapsedMs`이며 renderer는 이를 monotonic anchor에 결속한다.
Preparation/recovery나 producer generation rebuild 뒤에는 frozen elapsed로 re-anchor해
진행을 이어가고 waveform을 0으로 reset하지 않는다. Allocation route 변경은 discrete
target route를 준비하면서도 active transition이 arm되기 전 measured size origin edge를
유지한다. Fresh baseline과 origin producer readiness가 확인된 뒤 첫 active tick에서
cyclic fraction이 0이어도 target size profile을 arm하고, 이후
`PULSE_BURST`/`TRIANGLE_WAVE` valley에서도 origin profile로 돌아가지 않는다.

Duration cap 뒤 `GRADUAL_SMALL_TO_FULL`은 최소 2개의 100 ms control window,
`ABRUPT_SMALL_FULL`은 8 step 전체의 8개 window를 확보해야 한다. 부족하면
다른 size waveform으로 축소하지 않고 safety policy가 거부한다.
Dynamic transform interval은 `min(producer interval, 100 ms)`이므로 저FPS producer에서도
최대 100 ms마다 geometry를 적용하고, fraction 1은 interval이 아직 지나지 않아도
강제로 적용한다.
Typed `DEVICE_ONLY`/`CLIENT_REQUIRED` evidence는 하나의 고정 target geometry를
관측해야 하므로 dynamic size profile과 결합한 phase도 preflight에서 거부한다.

Base transform이 실제 child에 적용될 때 `LayerStageView`는
`generation + phase + profile + semantic sample + layer count + stage dimensions` key가
달라진 경우 bounded geometry revision을 요청한다. 이후 두 번의
`Choreographer` callback/traversal opportunity가 지난 matching revision/profile만
`ProducerGenerationGate`에 applied로 acknowledge한다. Pending revision 동안 renderer는
last-applied base-size fraction을 고정해 한 revision이 서로 다른 geometry를 뜻하지 않게
한다. Controller의 pause-aware clock과 desired fraction은 계속 최신값으로 진행되며,
acknowledgment가 끝난 다음 apply 기회에는 오래된 중간값을 재생하지 않고 latest desired
하나만 적용한다. Gradual revision key는 origin/mid/exact endpoint의 semantic 3개,
abrupt key는 8 step으로 제한한다. 이 bounded key는 2-frame acknowledgment와 최소
200 ms gradual window에서도 30/60/120 fps coverage를 유지한다.

Producer activation과 typed HWC arm은 latest requested/applied revision 및 target
profile 일치를 요구한다. 이는 View transform apply에 대한 app-side evidence이며
physical HWC plane composition proof가 아니다. 같은 generation에서 applied가 확인된
`SMALL_UNIFORM` preparation이 dynamic fraction-zero와 실제 geometry가 같으면 controller는
target dynamic profile의 origin bit 하나만 equivalent evidence로 seed한다. Coverage
mask의 나머지는 active dynamic profile acknowledgment를 누적하며 gradual
origin/mid/end 또는 abrupt 8 step 전체를 요구한다. Phase 끝에 coverage가 부족하면
`LAYER_SIZE_COVERAGE_MISSING`과 `INCONCLUSIVE`, 충분하면 `LAYER_SIZE_COVERAGE` event다.

Centered horizontal stagger는 stage 폭과 profile scale을 함께 사용해 좁은 화면에서도
각 child가 최소 1 px 보이는 범위로 clamp한다.

`LayerTrafficEstimator`와 HUD는 일반 phase에서 `LayerSizeProfile` base scale의 area 합을
screen-equivalent와 physical producer당 평균 `%`로 별도 표시한다. `CAPACITY_TILES`는
예외적으로 explicit crop union 1 screen-equivalent와 평균 `100 / producer count`%를
표시한다. 5-column 기본 grid의 마지막 행이 불완전하면 그 행의 producer만 전체 폭으로
다시 분할하므로 safety clamp 뒤 6/12/16 producer에서도 실제 union과 같은 값이다. 이 값은
`MotionProfile` scale, overlap, clipping/crop, rotation과 off-screen loss를 제외한
geometry estimate이며 conservative
full-buffer read/write traffic이나 measured bus를 대체하지 않는다.

### Source buffer projection과 orientation

`BufferPresentation`은 source buffer allocation과 분리된 base projection 계약이다.
`FIT`은 고정 orientation까지 반영해 motion 전 전체 source를 aspect-ratio preserving
letterbox로 stage 안에 놓고, `PIXEL_1_TO_1_CROP`은 source 1 px를 display 1 px로 유지한
채 centered overflow를 stage에서 clip한다. `LayerOrientation`의 0°/90°는 motion과
별도이며 explicit primary에는 실제 buffer dimensions, overlay에는 display dimensions를
사용해 allocation-free scale을 계산한다.

Safety policy는 1:1에 `FULL_SCREEN`과 non-scaling motion만 허용하고
`CAPACITY_TILES`에는 FIT/0°만 허용한다. Projection/orientation 변경은 full source
graphics budget과 traffic estimate를 바꾸지 않는다. Discrete 변경은 fresh producer
generation/topology readiness를 다시 통과하므로 이전 geometry/first-buffer/HWC evidence를
이어 쓰지 않는다.

## Runtime orchestration summary

Plan owner publication, runner stage, producer generation, telemetry priority, workload
ticket과 terminal cleanup의 허용 전이는
[Runtime state machines](docs/STATE_MACHINES.md)가 authority다. Producer는 test Window의
status/navigation bar hidden acknowledgment 뒤에만 시작한다.

Process-session 최초 1회 HWC candidate의 topology, display scope, deadline, probe
serialization과 cleanup 조건은
[HWC capacity calibration](docs/HWC_CAPACITY_CALIBRATION.md)에 정의한다. Architecture
관점에서 이 calibration은 첫 scenario의 warm-up/baseline보다 앞에 있는 advisory
sub-transaction이며 이후 plan execution과 evidence budget을 오염시키지 않아야 한다.
Requested/actual candidate와 같은-sample app raw `T`는 해당 topology의 관측값일 뿐
workload plane ceiling, 보편적 HWC maximum 또는 renderer safety cap이 아니다.

Scenario-wide counter baseline은 warm-up 시간만 채웠다고 시작하지 않는다. Bounded
readiness window 안에서 warm-up topology publication과 matching geometry acknowledgment를
먼저 확인하고 generation을 activation한 뒤, preparation-era callback을 지운 이후의
모든 producer fresh first buffer를 확인해야 한다. Fresh baseline sample 완료 뒤에도
같은 topology/geometry/readiness를 다시 검사하며, 중간 변경은 baseline을 무효화하고
run을 fail-closed한다.

## Phase transaction

`runScenarioPhases()`는 phase마다 다음 경계를 유지한다.

1. target phase에 persistent safety를 적용한다.
2. allocation route가 바뀌면 generated/NPU load zero를 확인한다.
3. 이전 phase/target을 null로 게시하고 physical producer teardown barrier를 기다린다.
4. 필요한 vendor SBWC route를 적용한다.
5. 새 producer generation을 발행하고 topology transaction을 시작한다.
6. expected producer set publication과 모든 first buffer/heartbeat를 기다린다.
7. fresh counter sample 뒤 active phase clock, frame budget과 workload를 시작한다.
8. absolute-deadline 100 ms cadence로 transition과 runtime safety를 평가한다.
9. typed HWC target이면 serialized fresh DEVICE/CLIENT evidence를 수집한다.
10. coverage, producer fidelity와 transition semantics를 검증하고 phase를 종료한다.

topology가 pending으로 바뀌면 callback 경계에서 즉시 frame budget을 pause하고 cross-load를
0으로 내린다. 16 ms hand-off를 넘긴 producer를 즉시 교체하지 않고 process-wide
teardown lease를 bounded poll한다.

Whole-phase `LINEAR_RAMP`의 nominal deadline에서는 exact target과 단조 증가하는
`producerControlRevision`을 하나의 progress publication으로 보낸다. FrameTracker는
committed producer 전부가 그 revision을 적용한 fresh frame을 낸 경우에만 endpoint를
확정한다. Bounded proof hold 중 topology recovery가 발생하면 이전 증거를 버리고 fresh
first-buffer barrier 뒤 새 revision으로 endpoint를 재게시한다. Hold frame은 proof에는
쓰되 fidelity에는 넣지 않는다. Endpoint 적용 전에 monotonic time과 aggregate frame
counter를 한 번 샘플하고 actual/expected를 같은 observed publication boundary에서
seal하므로 늦은 control tick도 ratio 분자만 늘리지 않는다.

## Renderer topology와 generation

`LayerStageView`는 실제 BufferQueue-backed child를 소유한다.

- `INDEPENDENT_SURFACES`: logical layer마다 독립 physical producer
- `MIXED_SURFACE_TEXTURE`: Surface/Texture 혼합, 필요하면 GL tail
- `FLATTENED_TEXTURE`: display-sized RGBA producer 하나

여기서 physical producer는 generation에 결속된 BufferQueue/frame callback 단위다.
Pure Compose HUD/Activity root는 이 수에 들어가지 않고, `TextureView` producer는
physical producer에는 들어가지만 app Window root로 flatten되므로 독립 HWC layer와
일대일 대응하지 않는다. 따라서 HUD의 `PHYSICAL` 값으로 HWC plane 수나
DEVICE/CLIENT total을 계산하지 않는다.

topology 생성, child add, relay 연결과 runtime control은 하나의 transaction이다.
모두 성공하기 전에 expected topology를 publish하지 않는다. 실패/OOM에서는 callback
detach, stop request, shared deadline join, child removal 순서로 rollback한다.

producer callback은 다음 두 identity로 보호된다.

- generation token: 현재 phase topology의 세대
- physical producer ID: 동일 generation 안의 실제 BufferQueue producer
- producer control revision: 해당 frame이 실제 적용한 endpoint/control 세대

`topologyMissed`, `teardownFailed`, `teardownCompleted`는 현재 generation의 activation과
producer readiness를 내리고 geometry request/applied revision·profile·coverage를
지운다. 따라서 이전 geometry나 typed HWC sample은 다음 target의 증거가 아니다.
`topologyMissed`/`teardownFailed`는 새 generation이 필요하다. 정상 detach 뒤 reattach도
topology pending, 새 geometry acknowledgment, expected topology 재게시, activation,
모든 producer의 fresh first buffer, fresh HWC observation 순서를 다시 만족해야 한다.
Canvas/Texture/Video뿐 아니라 GL의 physical Surface/BufferQueue 재생성도 같은
discontinuity다. 같은 relay/generation을 재사용하더라도 lifecycle callback이 먼저
pending을 게시하고 geometry/HWC/first-buffer evidence를 폐기한다.

Frame hot path에는 per-frame lambda, boxed timestamp나 반복 buffer를 추가하지 않는다.
Canvas/EGL/codec worker가 실제 `finally`를 끝내기 전 backing surface를 UI thread에서
먼저 release하지 않는다.
Canvas/EGL/MediaCodec commit 실패는 producer revoke와 runtime failure publication을 먼저
수행하고 cleanup한다. VM fatal은 cleanup을 모두 시도한 뒤 원본 identity로 다시 던진다.
Thread 생성 전에 renderer transaction owner storage를 최대 producer 수만큼 선할당한다.
Thread-start/stop/release는 notification 실패와 독립적으로 detach, interrupt, quit,
shared-deadline join과 identity clear를 모두 시도하며, 불완전 rollback을 일반
`false`로 낮추지 않는다. Frame/deferred post와 expected-set callback도 fail-closed
transaction 안에 있고, callback 재진입 뒤에는 capture한 generation/callback/relay
identity가 모두 같은 경우에만 publication bookkeeping을 commit한다.
Primitive timestamp map은 두 backing array allocation이 모두 성공한 뒤에만 교체한다.
Producer control token은 모든 replacement/binding identity prepare가 성공한 뒤 2-phase로
commit한다. Stale binding이나 commit fatal은 전 relay revoke와 topology pending,
bounded renderer rollback을 수행한다. Decoder는 submit 직전 immutable control token을
bounded preallocated epoch+PTS queue에 결속한다. Submit 실패는 epoch+PTS+callback
identity exact rollback을 사용한다. EOS는 listener disable, callback-looper의 재사용
barrier를 이용한 flush 전후 drain, queue clear, overflow-safe epoch 증가, listener
재설치 순서이며 teardown도 같은 callback 경계를 닫는다.

## Workload subsystem

`LoadManager`는 application context와 Activity-free state를 사용한다.

- CPU worker: 12 ms fixed period, bounded batch
- memory worker: 10 ms fixed period, 재사용 working set과 256 KiB copy block
- memory prewarm: measured baseline 전에 allocation/page touch/ack, 최대 5초
- NPU: optional vendor/reflection adapter, bounded latest-wins command와 ordered zero
- GPU: `StressGlSurfaceView` 또는 flattened hardware canvas pass

positive load는 정확한 0 또는 `0.001`보다 커야 한다. start는 worker들을 모두
생성·등록한 뒤 transaction으로 commit하며 partial start는 bounded join한다.
unexpected local worker failure는 process-sticky latch로 남고 후속 run을 차단한다.
Low-memory working-set drop은 NPU zero publication보다 먼저 독립적으로 commit되므로
NPU adapter가 실패해도 buffer가 다시 pin되지 않는다. Reflection NPU의 waveform과
release zero는 같은 versioned latest-wins lane을 사용한다. 이전 ticket에서 계산한
waveform 값은 새 desired ticket을 확인하지 못하면 queue에 게시할 수 없고, release는
그 lane의 exact zero ticket acknowledgment를 기다린다. Cyclic transition의 양수→0
valley와 0→양수 re-attack은 semantic edge ticket으로 분리한다. Semantic apply는
setpoint가 같아도 CPU/memory profile restart와 별도로 fresh NPU ticket을 발행한다.
Controller는 각 edge의 matching ACK와 backend health를 확인한 뒤에만 transition
coverage를 기록하며, 같은 부호 안의 중간값만 single-slot latest-wins다.
Triangle은 zero-origin의 full-cycle 또는 zero-target의 half-cycle 경계를 관측 tick이
건너도 stable CPU/memory/GPU setpoint 위에서 NPU-only zero를 먼저 확인한다. Terminal
zero boundary는 zero ACK로 끝내고, 중간 경계이면 이후 positive re-attack을 새 ticket으로
확인한다. 여러 zero boundary를 놓친 경우 과거 command를 replay하지 않고 inconclusive로
닫는다.

## Telemetry data flow

`SystemMonitor`의 sample은 single-flight worker에서 한 transaction으로 수행된다.

1. CPU interval과 app CPU
2. system/app memory
3. display refresh와 SurfaceFlinger probe policy 결정
4. vendor v1 snapshot 및 optional v2 extension
5. allowlisted kernel probe
6. producer/generated byte interval counter
7. thermal, low-memory, Battery Saver와 vendor session state
8. 모든 read가 끝난 completion timestamp

vendor hardware counter가 유효하면 kernel fallback보다 우선한다. source/quality가
바뀐 interval은 baseline을 새로 잡고 graph gap/`N/A`를 유지한다. 세부 규칙은
[docs/METRICS.md](docs/METRICS.md)에 있다.
Outer sample failure는 CPU/rate뿐 아니라 kernel GPU/bus/DPU cumulative baseline도 함께
reset한다. Vendor capability getter는 v1 sample lane과 분리된 single-flight
no-backlog lane에서 deadline/session을 검증하며, HWC one-shot은 admission token을 먼저
닫고 이 lane까지 drain한 뒤 producer를 게시한다. Capability deadline은 signed
`nanoTime` wrap-safe이고, worker 반환 직전의 no-backlog hand-off rejection은 25ms
single deferred refresh로 복구한다. 각 getter 사이에는 current service-session을 다시
확인하며 executor/Handler admission의 fatal `Error`는 active query rollback 뒤
재전파한다.

active run은 periodic/typed path에서 새 SurfaceFlinger child process를 만들지 않는다.
예외는 process-session one-shot capacity calibration의 제한된 fallback뿐이다. 이
one-shot은 같은 telemetry transaction에서 vendor snapshot을 한 번 prefetch하고,
current-session nonnegative DEVICE/CLIENT 원자 쌍이면 SF를 생략한다. Pair가 없을 때만
SF fallback을 한 번 실행하며 vendor를 다시 읽지 않는다.

UI와 result가 표시하는 `HWC APP RAW D/C/T`는 한 completion boundary의 완전한
DEVICE/CLIENT 원자 쌍이며 `T = D + C`로 같은 sample에서만 계산한다. 이 pair에는
control/root 보정이나 workload producer identity 분리가 없고, `PHYSICAL` producer
count와도 별도다. 서로 다른 sample에서 D와 C의 개별 최대를 뽑아 합치지 않는다.
각 run의 `HWC_COUNT_SCOPE` event는 이 계약을
`APP_RAW_UNSEPARATED`, `controlLayerIncluded=true`, control/root subtraction 없음,
FrameTracker `PHYSICAL` 분리와 scoped BSP identity evidence 필요로 명시한다.

## Vendor와 BSP 경계

`VendorBridge`는 process singleton이며 다음을 분리된 bounded lane으로 처리한다.

- API v1 exact underrun, DPU/GPU/bus와 DEVICE/CLIENT snapshot
- API v2 optional GPU/DPU frequency
- NPU setpoint/health/ordered zero
- SBWC route
- API v3 Battery Saver performance session

Binder registration마다 service-session ID를 부여한다. API v2 결과와 HWC 원자 쌍,
SBWC acknowledgment, NPU ticket, performance ticket은 같은 session에 결속된다.
timeout은 disconnect와 같지 않으며, 실제 session 변경 시 이전 snapshot/ack를 폐기한다.

API v1 `getCompositionLayerCounts()`는 app display의 미분리 raw D/C pair일 뿐이다.
Control/root 또는 foreign layer를 workload layer에서 구분하거나 특정 producer의
composition type을 증명하지 못한다. Workload-only `DEVICE_ONLY`/`CLIENT_REQUIRED`
판정이 제품 acceptance에 필요하면 BSP broker가 display ID, owner UID, producer
generation/revision과 exact SurfaceFlinger/HWC layer identity를 같은 validate/present
boundary에 결속한 scoped typed evidence를 추가해야 한다. 이름 문자열이나 raw total의
차감으로 identity를 추정하지 않는다. Android app-facing API에는 특정 app layer를
CLIENT로 강제하거나 해당 layer의 최종 HWC composition type을 읽는 portable API가 없다.

Portable build는 implicit action discovery를 신뢰 경계로 사용하지 않는다.
`/product/etc/dpulayerlab/vendor_broker.conf`의 explicit component, permission owner와
owner/service signer SHA-256 trust root를 먼저 검증한다. Signature permission grant,
system service/exported/enabled/permission 계약과 signing lineage가 모두 맞을 때만
explicit bind한다. 누락·불일치·bind permission 거부는 typed permanent
`UNAVAILABLE`이며 reconnect loop를 만들지 않는다.

BSP 구현·permission·SELinux·Stable AIDL 규칙은
[docs/SYSTEM_INTEGRATION.md](docs/SYSTEM_INTEGRATION.md)를 따른다.

## Window와 performance isolation

test Window isolation은 process-wide token lease다.

- request 시 원래 status/navigation visibility mask를 저장한다.
- 둘 다 invisible이라는 Insets acknowledgment 전에는 producer를 시작하지 않는다.
- 확인 뒤 bar 재등장, focus loss, multi-window/PiP 진입은 측정 오염으로 중단한다.
- 종료 시 renderer teardown 뒤 원래 mask의 visible Insets acknowledgment까지 기다린다.
- Activity 재생성 중에도 process lease가 살아 있으면 새 Window가 hide 상태를 유지한다.

performance isolation은 typed vendor API v3의 `DISABLE_BATTERY_SAVER` 하나만 사용한다.
original state를 BEGIN 전에 capture하고 10초 lease를 2초마다 renew한다. END/death/expiry의
exact restore가 확인되지 않으면 보고서를 무효화하고 후속 plan을 차단한다. broker가
없을 때는 Battery Saver가 이미 OFF인 경우에만 app-only monitoring을 허용한다.

## 종료와 cleanup 순서

STOP, cancellation, exception과 정상 종료는 phase/target을 먼저 null로 게시하고
다음 순서를 따른다.

1. CPU/memory/NPU와 GL setpoint를 0으로 내리고 acknowledgment 확인
2. physical Surface/codec/EGL/Canvas producer teardown barrier
3. pinned media descriptor worker의 실제 `finally`와 close 확인
4. SBWC route를 linear/default로 reset
5. display mode와 wake flag 복구
6. serialized fresh terminal exact counter sample
7. verdict와 schema v2 report 발행
8. plan 종료 시 Battery Saver original state 복구
9. SystemUI original visibility 복구

timeout, enqueue 성공이나 `show()` 호출 성공만 cleanup 증거로 사용하지 않는다.
sticky cleanup latch가 남으면 같은 process에서 새 controller/plan을 시작하지 않는다.

## Report와 persistence

`ReportWriter`는 credential-encrypted `filesDir/reports`에
`dpu-layer-lab-*.json.part`를 쓰고 flush/fsync한 뒤 `.json`으로 rename한다.
publisher는 process 안에서 직렬화된다. 방금 게시한 파일을 보호하면서 managed completed
JSON만 최신 400개로 best-effort 보존한다.
이 retention은 수동 plan 길이와 독립된 저장 정책이다. 400회를 넘는 plan도 실행할 수
있지만 오래된 JSON은 실행 중 정리될 수 있으며 결과 UI는 실제 파일 존재 여부를
기준으로 공유 가능 상태를 표시한다.

report에는 device fingerprint와 vendor provenance가 포함되며 네트워크로 자동 전송하지
않는다. schema와 metric 의미는 [docs/METRICS.md](docs/METRICS.md), privacy와 사용자
공유 방법은 [README.md](README.md)를 따른다.

Result의 HWC peak는 보존된 sample 중 complete atomic pair만 후보로 삼아 같은
source/quality가 run 전체에서 유지될 때 한 sample의 `(D, C, T)` tuple을 선택한다.
`T`가 큰 tuple을 우선하고 동률일 때 `D`가 큰 tuple을 사용하며, 서로 다른 sample의
`max(D)`와 `max(C)`를 합성하지 않는다.

## 확장 지점

- 새 catalog preset: `ScenarioCatalog.kt`와 [docs/SCENARIOS.md](docs/SCENARIOS.md)
- 새 phase field: model → safety → controller interpolation → renderer/load → UI/report/test
- 새 metric: `Gauge`/`TelemetrySnapshot` → typed provider/parser → monitor merge →
  continuity/verdict → HUD/report/test → [docs/METRICS.md](docs/METRICS.md)
- 새 vendor API: AIDL 끝에 append, API version gate, session binding, bounded lane →
  [docs/SYSTEM_INTEGRATION.md](docs/SYSTEM_INTEGRATION.md)
- 새 backend thread/job: application context, explicit owner, bounded cancellation/join,
  process cleanup gate와 fault-injection test

안전 cap이나 exact/proxy 의미를 바꾸는 확장은 먼저 [AGENTS.md](AGENTS.md)의 변경 조건을
만족해야 한다.
