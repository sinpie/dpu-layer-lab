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
automation 요청은 component class를 다시 확인하며, `START`에서만 extras를
unmarshal한다. `STOP`은 pending START보다 우선한다. 계약과 cap은
`engine/AutomationIntentContract.kt`에 있다.

Compose의 주요 section은 다음과 같다.

- Dashboard: 현재 capability와 metric overview, 목적별 빠른 시작
- Scenario catalog: 목적 선택, category/pattern/load/condition facet, queue 구성
- Custom builder: 단일 scenario의 topology·format·motion·workload·transition 설정
- System: permission, codec, display mode, direct probe와 runtime protection 상태
- Running: 항상 접근 가능한 STOP, phase/plan 진행, 좌측 상단 HUD
- Result: 최신 run과 plan item별 verdict/report

UI는 실행 권한의 authority가 아니다. 모든 renderer 입력은 controller의 runtime safety
policy를 다시 통과한다.

## 핵심 모델

`model/LabModels.kt`의 계층은 다음과 같다.

- `PhaseSpec`: duration, layer count, producer FPS, requested display Hz, backend, pixel route,
  buffer size, motion, workload, alpha/GL, transition, HWC expectation
- `ScenarioSpec`: metadata와 순서가 있는 phase 목록
- `ScenarioRunPlan`: 순서를 보존하는 scenario queue, repeat와 source
- `PlanProgress`: queue/repeat 전체 진행과 terminal reason
- `RunProgress`: 현재 stage/phase/target, transition fraction, producer generation/readiness
- `TelemetrySnapshot`: 값·단위·quality·source가 결속된 한 번의 telemetry transaction
- `RunSummary`: verdict, exact/proxy delta, peak, event와 sample

queue의 duplicate는 A/B/A를 표현하기 위해 의도적으로 유지한다. 외부 automation은
catalog preset만 사용할 수 있고 repeat 10, expanded run 40 상한을 가진다.

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
표시한다. 이 값은
`MotionProfile` scale, overlap, clipping/crop, rotation과 off-screen loss를 제외한
geometry estimate이며 conservative
full-buffer read/write traffic이나 measured bus를 대체하지 않는다.

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

## Renderer topology와 generation

`LayerStageView`는 실제 BufferQueue-backed child를 소유한다.

- `INDEPENDENT_SURFACES`: logical layer마다 독립 physical producer
- `MIXED_SURFACE_TEXTURE`: Surface/Texture 혼합, 필요하면 GL tail
- `FLATTENED_TEXTURE`: display-sized RGBA producer 하나

topology 생성, child add, relay 연결과 runtime control은 하나의 transaction이다.
모두 성공하기 전에 expected topology를 publish하지 않는다. 실패/OOM에서는 callback
detach, stop request, shared deadline join, child removal 순서로 rollback한다.

producer callback은 다음 두 identity로 보호된다.

- generation token: 현재 phase topology의 세대
- physical producer ID: 동일 generation 안의 실제 BufferQueue producer

`topologyMissed`, `teardownFailed`, `teardownCompleted`는 현재 generation의 activation과
producer readiness를 내리고 geometry request/applied revision·profile·coverage를
지운다. 따라서 이전 geometry나 typed HWC sample은 다음 target의 증거가 아니다.
`topologyMissed`/`teardownFailed`는 새 generation이 필요하다. 정상 detach 뒤 reattach도
topology pending, 새 geometry acknowledgment, expected topology 재게시, activation,
모든 producer의 fresh first buffer, fresh HWC observation 순서를 다시 만족해야 한다.

Frame hot path에는 per-frame lambda, boxed timestamp나 반복 buffer를 추가하지 않는다.
Canvas/EGL/codec worker가 실제 `finally`를 끝내기 전 backing surface를 UI thread에서
먼저 release하지 않는다.

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

active run은 periodic/typed path에서 새 SurfaceFlinger child process를 만들지 않는다.
예외는 process-session one-shot capacity calibration의 제한된 fallback뿐이다. 이
one-shot은 같은 telemetry transaction에서 vendor snapshot을 한 번 prefetch하고,
current-session nonnegative DEVICE/CLIENT 원자 쌍이면 SF를 생략한다. Pair가 없을 때만
SF fallback을 한 번 실행하며 vendor를 다시 읽지 않는다.

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
JSON만 최신 200개로 best-effort 보존한다.

report에는 device fingerprint와 vendor provenance가 포함되며 네트워크로 자동 전송하지
않는다. schema와 metric 의미는 [docs/METRICS.md](docs/METRICS.md), privacy와 사용자
공유 방법은 [README.md](README.md)를 따른다.

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
