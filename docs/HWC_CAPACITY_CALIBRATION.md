# Process-session HWC capacity calibration

> **Authority:** 최초 1회 20-layer HWC candidate 관측의 scope, 순서, 결과 의미와 재사용 계약
> **Audience:** display 시험 설계자, controller/renderer maintainer, metric reviewer
> **Update when:** calibration topology, deadline, sample source, scope, teardown 또는 UI/event 의미가 바뀔 때
> **Does not own:** 일반 typed HWC phase, 보편적 plane 수, safety hard cap, BSP provider 구현
> **Related:** [Documentation index](INDEX.md), [AGENTS.md](../AGENTS.md),
> [STATE_MACHINES.md](STATE_MACHINES.md), [METRICS.md](METRICS.md),
> [SCENARIOS.md](SCENARIOS.md), [TESTING.md](TESTING.md)

이 calibration은 scenario마다 실행하지 않는다. App process에서 최초로 승인된 START의
첫 scenario에 들어가기 직전에 정확히 한 번 시도하고, 성공·실패·취소 모두 terminal
결과로 저장한다. 이후 scenario, repeat, START와 Activity 재생성은 같은 결과를
재사용한다.

## 목적과 비목적

목적:

- system bar가 숨겨진 test Window에서 많은 opaque RGB producer를 한 번 게시
- 같은 candidate topology의 fresh DEVICE/CLIENT 원자 쌍 관찰
- 뒤의 scenario가 참고할 수 있는 process-local advisory boundary 제공

비목적:

- 모든 format/scale/transform/display에 통용되는 HWC plane maximum 결정
- ScenarioSafetyPolicy hard cap 자동 변경
- catalog의 `DEVICE_ONLY`/`CLIENT_REQUIRED` typed evidence 대체
- 반복적인 1→20 layer 탐색 또는 binary search
- logcat/sysfs/debugfs를 임의 탐색해 plane 수 추론

## 요청 topology

| 항목 | 요청 |
|---|---|
| logical/physical candidate | 20 layer / 20 independent Surface producer |
| producer pacing | 30 fps |
| display request | 60 Hz |
| backend | `INDEPENDENT_SURFACES` |
| pixel route | `RGB_8888` |
| buffer size | `DISPLAY` |
| motion | `CAPACITY_TILES` |
| destination | non-overlap crop union 1 screen-equivalent; partial final row도 full-width 재분할 |
| alpha | opaque |
| GL tail | 없음 |
| CPU/memory/generated GPU/NPU cross-load | 모두 0 |

Runtime safety와 graphics budget은 actual candidate layer 수를 20보다 줄일 수 있다.
UI/event/report는 항상 requested `20L`과 actual candidate를 별도로 표시한다.

## Scope와 lifetime

Store:
`app/src/main/java/com/example/dpulayerlab/engine/HwcCapacityCalibrationSession.kt`

Scope key:

- physical display ID
- width/height 축 순서를 정규화한 short/long physical dimensions

단순 portrait/landscape 축 교환은 같은 scope다. Display ID 또는 normalized dimensions가
바뀌면 기존 result를 새 display에 적용하지 않고 `UNAVAILABLE` projection을 반환한다.
같은 process에서 두 번째 측정은 하지 않으며 새 측정은 process restart 뒤에만 가능하다.

Disk, SharedPreferences와 report에서 다음 process의 calibration을 복원하지 않는다.

## 실행 순서

```mermaid
flowchart TD
    A["첫 START · fullscreen/performance precheck"] --> CLAIM["process claim"]
    CLAIM --> P["typed telemetry priority + capability admission token"]
    P --> D["periodic drop/pause · local/SF/vendor/capability pre-drain"]
    D --> S["safety-approved actual candidate"]
    S --> G["producer generation · CAPACITY_TILES geometry"]
    G --> R["all first buffer + fresh heartbeat + 100ms stabilize"]
    R --> V["v1 vendor D/C snapshot 1회"]
    V -->|complete current-session pair| Q["post-sample topology continuity"]
    V -->|null/partial| B["actual vendor worker quiescence ≤500ms"]
    B -->|confirmed| F["SurfaceFlinger fallback 1회"]
    B -->|unconfirmed| N["terminal UNAVAILABLE"]
    F --> Q
    Q --> Z["phase/target null 즉시"]
    Z --> T["load zero · renderer teardown · counter drain"]
    T --> I["local/SF/vendor/capability post-drain"]
    I --> CANCEL{"run cancellation 없음?"}
    CANCEL -->|yes| W["cancellable 3s zero-load settle"]
    CANCEL -->|STOP/cancel| ABANDON["terminal UNAVAILABLE publish"]
    W --> E["measured terminal result publish"]
    E --> RELEASE["final drain · admission/priority release"]
    ABANDON --> RELEASE
    RELEASE --> H["기존 1L scenario warm-up/fresh baseline"]
```

Priority를 먼저 획득한 뒤 periodic telemetry를 drop하고 기존 worker를 drain한다.
Calibration sample은 optional vendor v2 GPU/frequency transaction을 생략한다. V1
snapshot이 원자 D/C를 제공하지 못하면 실제 vendor telemetry worker가 끝난 뒤에만
SurfaceFlinger child를 시작한다.
Capability retry/discovery admission token은 pre-drain 전에 획득하고 final post-drain
뒤에만 identity-matched release한다. 그 사이 도착한 service callback/retry는 하나의
deferred refresh로 합쳐지고 20L candidate가 내려간 뒤 Handler에 게시된다.

Producer readiness를 기다리는 구간은 100ms control cadence로 direct
thermal/power/low-memory를 확인한다. Pre-drain과 bounded composition sample 자체에는
별도의 100ms poll을 병렬로 추가하지 않으며 cancellation과 전체 deadline으로 제한한다.

## Deadline

Topology 준비, 모든 first buffer, 100ms stabilization, single calibration composition
transaction과
post-sample validation은 하나의 최대 6000ms producer-active deadline 안에서 끝나야 한다.

- readiness poll은 stabilization+snapshot completion reserve를 남기는 범위로 clamp한다.
- 100ms stabilization 전체와 snapshot reserve를 확보할 수 없으면 target을 즉시 null로
  내리고 `UNAVAILABLE`로 끝낸다.
- sample 직후 20L target을 null로 내려 validation 지연이 load를 연장하지 않게 한다.
- renderer teardown과 worker quiescence는 deadline 밖이어도 mandatory다. 3000ms
  zero-load settle은 cleanup-confirmed 정상 진행에서만 수행하는 cancellable 단계다.
- deadline을 넘으면 값이 늦게 도착해도 `OBSERVED_AT_CANDIDATE`로 수락하지 않는다.

## 수락 조건

Result를 observed로 수락하려면 다음이 모두 참이어야 한다.

1. actual candidate가 claim에 기록됨
2. expected/observed physical producer count가 candidate와 일치
3. 같은 generation의 topology가 published/ready이고 pending/missed가 아님
4. geometry requested/applied revision과 profile이 일치
5. sample 전후 topology revision, publication timestamp와 discontinuity serial이 같음
6. 모든 producer heartbeat가 fresh
7. runtime/teardown failure와 renderer cleanup pending이 없음
8. D/C가 같은 source·quality·timestamp의 완전한 fresh pair
9. vendor service pair이면 snapshot session이 현재 registration과 같음
10. sample/validation이 producer-active deadline 안에 완료

한 항목이라도 실패하면 0이나 추정 max가 아니라 terminal `UNAVAILABLE`이다.

## 결과 모델

| Status | 의미 |
|---|---|
| `PENDING` | 아직 process claim을 시도하지 않은 UI 상태 |
| `OBSERVED_AT_CANDIDATE` | actual candidate에서 fresh D/C pair를 관측 |
| `UNAVAILABLE` | reject, timeout, STOP, source/topology/teardown 실패의 terminal 결과 |

주요 field:

- `candidateLayers`: safety-approved actual candidate 또는 producer handoff 전 실패의 null
- `observedDeviceLayers`, `observedClientLayers`
- `source`, `quality`, `evidenceMonotonicMs`
- calibration display ID와 normalized dimensions
- bounded detail

UI 예:

```text
HWC capacity · session 1회 · 요청 20L · 실제 후보 12L에서 D8/C4
```

Report event:

- `SESSION_HWC_CAPACITY_CALIBRATION`
- `SESSION_HWC_CAPACITY_REUSE_GUIDANCE`

Reuse guidance는 matching opaque RGB/crop topology의 advisory일 뿐 safety cap이 아니다.

## Cleanup과 scenario 격리

모든 terminal path는 먼저 다음 cleanup barrier를 확인한다.

- phase와 target null
- CPU/memory/generated GPU/NPU cross-load zero
- 모든 physical producer teardown
- calibration producer frame counter drain
- generated traffic counter drain
- local SystemMonitor worker actual completion
- SurfaceFlinger lane와 dumpsys child 종료
- vendor v1/v2 executor lane completion
- vendor capability lane actual completion과 새 discovery/retry admission 차단

위 cleanup이 모두 확인되고 run이 취소되지 않은 경로만 cancellable 3초 zero-load
settle과 마지막 direct thermal/power/low-memory recheck를 수행한 뒤 scenario warm-up으로
이동한다. STOP/cancel은 mandatory cleanup을 생략하지 않지만 optional settle은 즉시
건너뛰고 process-session을 terminal `UNAVAILABLE`로 닫아 두 번째 20L burst 없이
Window/performance owner를 복구한다.

Barrier 미확인은 telemetry lifecycle을 process-sticky failure로 만들고 후속 START를
차단한다. Calibration frame, traffic와 exact counter delta는 첫 scenario의 fresh
baseline/peak에 포함하지 않는다.

## 변경·복구 checkpoint

관련 source:

- `engine/HwcCapacityCalibrationSession.kt`
- `engine/LabController.kt`
- `monitor/SystemMonitor.kt`
- `monitor/FrameTracker.kt`
- `monitor/SurfaceFlingerProbe.kt`
- `vendor/VendorBridge.kt`
- `render/LayerStageView.kt`
- `ui/DpuLayerLabApp.kt`

관련 test:

- `HwcCapacityCalibrationSessionTest`
- `LabControllerMathTest`
- `ProducerGenerationGateTest`
- `SystemMonitorMathTest`
- `SurfaceFlingerProbeTest`
- `VendorBridgeStateTest`

최소 회귀 항목:

- process당 claim 1회와 terminal reuse
- orientation reuse/display change N/A
- requested 20과 safety-clamped actual 분리
- exact fixed topology와 zero cross-load
- vendor timeout 뒤 SF overlap 금지
- capability retry/service callback이 admission token과 pre/post drain 사이에 끼어들지 않음
- cancel 중 pre/post drain과 identity-matched priority release
- deadline 직전 poll/stabilization clamp와 STOP 시 optional settle 생략
- safety-clamped partial final row의 non-overlap full crop union
- post-sample deadline/topology discontinuity 거부
- teardown/counter drain 뒤 첫 scenario 시작
