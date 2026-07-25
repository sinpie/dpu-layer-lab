# UI information architecture와 실행 HUD 사양

> **Authority:** 화면 구조, 주요 사용자 흐름, 상태별 표시, 실행 HUD와 compact/landscape 불변식
> **Audience:** UI 개발자, 시험 UX reviewer, source 복구 담당자
> **Update when:** navigation, catalog/queue/builder/run/result 구조, HUD metric 또는 상태 표시가 바뀔 때
> **Does not own:** scenario 정의, metric 물리 의미, safety algorithm, theme 세부 색상
> **Related:** [Documentation index](INDEX.md), [README.md](../README.md),
> [REQUIREMENTS.md](REQUIREMENTS.md), [SCENARIOS.md](SCENARIOS.md),
> [METRICS.md](METRICS.md), [STATE_MACHINES.md](STATE_MACHINES.md)

UI의 목표는 시험자가 다음 네 질문에 한 화면 흐름으로 답할 수 있게 하는 것이다.

1. 무엇을 시험할 것인가?
2. 어떤 순서와 반복으로 실행할 것인가?
3. 지금 실제로 무엇이 실행되고 있는가?
4. 결과와 증거 source가 무엇인가?

## Navigation

| Section | 목적 | 실행 중 노출 |
|---|---|---|
| 대시보드 | device 상태, 주요 목적 quick start, 최근 결과 | 실행 전 |
| 시나리오 | 목적/facet 필터, preset 상세, queue/repeat | 실행 전 |
| 커스텀 | bounded custom phase 조합 | 실행 전 |
| 시스템 | capability, permission, display/codec/sensor | 실행 전 |
| 실행 | fullscreen renderer와 HUD | active run에서 자동 전환 |
| 결과 | scenario/plan verdict와 report 공유 | terminal에서 자동 전환 |

실행 화면은 immersive이며 일반 top/bottom navigation을 숨긴다. STOP은 navigation bar가
아니라 실행 HUD header 안에 있어 compact/landscape에서도 항상 보인다.

### Test Window 격리

- status bar와 navigation bar가 모두 hidden이라는 Insets acknowledgment 전에는 physical
  producer를 시작하지 않는다.
- 시작 전 visibility mask는 token에 보존하고 STOP·실패·Activity 재생성에서도 실제
  Insets가 원래 상태로 관측될 때까지 process-wide lease를 유지한다.
- 실행 중 bar 재등장, notification shade/overlay에 따른 focus loss,
  multi-window/PiP 전환은 fail-closed 중단 사유다.
- 복원 acknowledgment가 끝나지 않으면 일반 탐색 화면처럼 표시하거나 다음 START를
  허용하지 않는다.

## 기본 흐름

```mermaid
flowchart LR
    D["Dashboard"] --> P["목적 선택"]
    P --> C["Catalog filter"]
    C --> Q["Queue 순서·중복·repeat"]
    Q --> V["Validation preview"]
    V --> R["Running fullscreen + HUD"]
    R --> O["Result overview"]
    O --> S["Scenario detail/report share"]
    O --> D
```

Custom builder는 catalog queue와 별도의 single custom scenario를 만든다. Selected-media가
필요한 preset은 실행 전에 media card에서 SAF URI를 선택한다.

## Dashboard

필수 정보:

- 실제 build version
- direct exact counter / privileged proxy / portable proxy mode
- AP CPU, app CPU, system memory와 available memory, producer FPS
- DPU busy+provenance, GPU busy+frequency, measured memory bus+generated traffic
- 각 metric의 quality color와 N/A 상태
- HWC DEVICE/CLIENT와 permission 상태
- `급격한 DPU 부하`, `DEVICE 후보 유지`, `CLIENT 전환 목표` quick start
- 최근 result

Metric value가 unavailable이면 0 대신 N/A를 표시한다.

## Catalog

### 목적 중심 선택

상단 quick card:

- 급격한 DPU 부하
- DEVICE 후보 유지
- CLIENT 전환 목표

각 목적은 예상 입력 변화, 검증 badge와 결과에서 볼 evidence를 함께 설명한다.

### Facet

같은 행의 선택은 OR, 서로 다른 행은 AND다.

- category
- transition/change pattern
- expected intensity
- workload
- condition/format
- composition target

Filtered result는 catalog 원래 순서를 유지한다. `결과로 교체`와 `queue에 추가`를
분리한다.

### Queue와 loop

- 항목 순서와 duplicate를 보존
- 항목별 remove와 explicit move
- catalog 순서로 reset
- repeat count와 expanded run 수
- 현재 예상 duration
- unknown restored ID 자동 제거
- 40-run cap 안에서 repeat 조정

실행 전 preview는 input change, composition target, verification을 요약한다.

## Custom builder

Custom UI는 hard cap 안에서 다음을 구성한다.

- layer 수, producer FPS, requested Hz
- backend, pixel route, buffer size
- motion과 layer size profile
- alpha/GL
- CPU/memory/GPU/NPU setpoint와 shape
- transition mode/duration/cycle/step/duty/floor

`CAPACITY_TILES`는 internal calibration용 motion이므로 일반 custom selector에서 제외한다.
Selected-media가 필요한 route는 media와 codec preflight 없이 실행하지 않는다.

## Running screen

### Layout

```text
┌────────────────────────────────────────────┐
│ Scenario · QUEUE x/y · LOOP x/y · STAGE   │
│ Layer size · BUILD version        [STOP]   │
│ Plan / phase progress                       │
│ LAYERS  value + graph + PHYSICAL            │
│ DPU     value + graph + source/quality      │
│ CPU     value + graph + source/quality      │
│ GPU     value + graph + source/quality      │
│ DPU-read / producer-write traffic           │
└────────────────────────────────────────────┘

┌────────────────────────────────────────────┐
│ transition · current→target · next phase    │
│ safety/performance/calibration status       │
└────────────────────────────────────────────┘
```

HUD는 display cutout inset을 적용하고 좌측 상단에 둔다. Renderer 전체 화면을 차지하지만
HUD는 control layer로 남으며 report의 `controlLayerIncluded=true`와 일치한다.

### 필수 live metric

| Metric | 값 | Graph |
|---|---|---|
| LAYERS | requested logical layer + observed/expected physical producer | 최근 60 sample |
| DPU | busy % 또는 N/A | provenance segment별 gap |
| CPU | AP CPU % 또는 N/A | provenance segment별 gap |
| GPU | busy % 또는 N/A | provenance segment별 gap |

각 metric은 source/quality label을 숨기지 않는다. Gauge provenance가 바뀌거나 unavailable인
경계에서 graph 선을 연결하지 않는다.

Producer count 표기:

- `observed/—P`: topology expected set 미게시
- `observed/expected P`: committed expected set 게시
- pending/process lease에서는 expected를 0으로 투영

### Traffic

- DPU read와 producer write를 별도 표시
- frame bytes와 GB/s/Gbps를 구분
- RGB B/px, decoder descriptor와 full source buffer 기반
- destination screen-equivalent 합계와 producer당 평균 footprint
- `CAPACITY_TILES`는 crop union 1 screen-equivalent
- SBWC compression ratio를 추정값에 적용하지 않음
- measured bus utilization과 합치지 않음

### Progress와 상태

- queue/repeat/current/next scenario
- phase index, elapsed/duration
- current→target layer/FPS/size/workload
- transition mode/segment/fraction
- next phase/scenario
- safety clamp/thermal derate/memory low
- performance isolation
- process-session HWC calibration requested/actual/result
- HWC expectation은 `RAW MATCH`, `RAW WAIT`, `RAW N/A` 보조 표시

RAW 상태는 controller의 distinct-sample/coverage verdict를 대체하지 않는다.

### Compact/landscape

- STOP은 header에서 제거하지 않음
- LAYERS/DPU/CPU/GPU를 2-column compact layout으로 유지
- detail panel은 bounded height와 vertical scroll
- plan/phase progress를 한 줄로 압축
- metric source/quality를 생략하지 않음
- renderer와 HUD가 status/navigation bar inset을 다시 만들지 않음

## Result

Plan overview:

- completed/total, clean/suspected/inconclusive/aborted count
- repeat/queue/run index
- scenario별 verdict와 terminal reason

Scenario detail:

- exact underrun delta/source/quality
- suspected proxy delta
- peak CPU/memory/generated traffic
- stable-source peak DPU/GPU/bus/produced FPS
- peak HWC DEVICE/CLIENT와 provenance
- sample 수
- report path/share

Exact evidence가 없으면 proxy를 exact로 승격하지 않는다. Result color만으로 verdict를
전달하지 않고 label과 terminal reason을 함께 표시한다.

## System

- runtime protection policy
- DUMP/vendor/NPU/SBWC capability
- display modes
- codec capability
- direct sensor label/source/value
- product integration 안내

Unavailable 기능에 “활성” toggle을 제공하지 않는다.

## Empty/error/loading

| 상태 | 표시 |
|---|---|
| telemetry 아직 없음 | value N/A + source unavailable |
| catalog filter 0건 | filter summary와 reset action |
| queue 비어 있음 | 실행 disabled + 추가 안내 |
| media 필요/미선택 | requirement와 선택 action |
| plan rejected | 현재 화면 유지 + snackbar/terminal reason |
| topology pending | `—P`, phase clock 시작 전 준비 상태 |
| cleanup sticky | process restart 필요 reason |
| report 없음 | share disabled |

이전 result가 있을 때 새 START가 reject됐다고 old result와 rejected plan metadata를
합치지 않는다.

## 접근성·사용성 checklist

- STOP과 주요 action에 text label 유지
- color 외에 label/icon/text로 상태 구분
- 작은 화면에서 중요한 control이 scroll 밖으로 사라지지 않음
- 긴 scenario/source/error text는 ellipsis 또는 scroll
- 숫자에 unit 포함
- N/A, pending, proxy, exact 표현을 일관되게 사용
- destructive/reset action과 run action을 시각적으로 구분
- 목적 선택 → queue → run → result가 세 번 이하의 주요 decision으로 이어짐

## UI 회귀 test

관련 source:
`app/src/main/java/com/example/dpulayerlab/ui/DpuLayerLabApp.kt`

관련 suite:

- `DpuLayerLabAppMathTest`
- `RendererContainerRememberOwnerTest`
- `LayerTrafficEstimatorTest`
- `ScenarioClassifierTest`
- `ScenarioQueueEditorTest`
- `MainActivityMathTest`
- `TestWindowIsolationTest`
- `AppVersionTest`

변경 뒤 compact/landscape STOP, graph provenance gap, `—P`, requested/actual calibration,
queue duplicate/order, Window hide/restore acknowledgment와 result-old-state 분리를
반드시 재검토한다.
