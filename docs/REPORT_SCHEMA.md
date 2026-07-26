# Report schema v2

> **Authority:** JSON report의 field 이름, type, nullability, 단위, provenance와 consumer 규칙
> **Audience:** report consumer, data analyst, app maintainer, source 복구 담당자
> **Update when:** `ReportWriter`, `RunSummary`, `TelemetrySnapshot`, filename/retention 또는 schema version이 바뀔 때
> **Does not own:** metric의 물리적 해석, verdict algorithm, FileProvider 제품 배치
> **Related:** [Documentation index](INDEX.md), [METRICS.md](METRICS.md),
> [EXTERNAL_CONTRACTS.md](EXTERNAL_CONTRACTS.md), [TESTING.md](TESTING.md),
> [RECONSTRUCTION.md](RECONSTRUCTION.md)

Machine authority는
`app/src/main/java/com/example/dpulayerlab/engine/ReportWriter.kt`다. 이 문서는 source
유실 시 consumer 호환 구조를 재생성하기 위한 schema 설명이다.

## 파일과 발행

Filename:

```text
dpu-layer-lab-yyyyMMdd-HHmmss-SSS-<safeScenarioId>[-<collision>].json
```

- UTF-8 JSON
- internal `files/reports`
- `.json.part` write/flush/fsync 뒤 rename
- scenario ID는 `[A-Za-z0-9._-]`, 최대 80자
- collision suffix는 1~999
- newest 400 managed completed report 보존(40-entry UI queue × 10 whole-queue loops)
- FileProvider 공유는 canonical internal completed file이면서 현재 controller의
  `lastReportFile` 또는 plan result history에 publish된 경로만 허용

Plan-wide performance restore를 포함하도록 마지막 report를 교체할 때는 replacement를
원자 publish한 뒤 obsolete managed report 삭제를 확인하고, 그 다음 400개 retention을
적용한다. Obsolete 삭제가 확인되지 않으면 같은 transaction의 prune을 건너뛰어 다른
plan report를 먼저 잃지 않는다.

## 공통 type 규칙

- timestamp `*EpochMs`: Unix epoch milliseconds
- timestamp `tMs`, `*MonotonicMs`: run 시작 monotonic 원점을 뺀 signed milliseconds
- duration/age `*Ms`: milliseconds
- utilization/CPU/memory `*Percent`, `cpu`, `gpuBusy`, `busBusy`, `dpuBusy`: percent
- frequency `*Mhz`: MHz
- `generatedBandwidthGbps`: decimal gigabits/second
- memory `*Mb`: MiB로 계산한 표시값
- count/delta: integer
- unavailable numeric과 non-finite float: JSON `null`
- unavailable source: empty string
- unavailable quality: `"UNAVAILABLE"`
- enum: Kotlin enum의 stable uppercase `name`

모든 gauge는 value와 함께 `Quality`와 `Source`를 보존한다. Value가 null/non-finite,
source가 비었거나 quality가 unavailable이면 report quality도 `UNAVAILABLE`이다.

Report의 event, sample과 evidence timestamp는 하나의 run-relative 축을 사용한다.
Run 시작 전에 수집됐지만 freshness가 유효한 cached HWC/SF evidence는
`*EvidenceMonotonicMs`가 음수일 수 있다. 유효 evidence의 age는
`sample.tMs - evidenceMonotonicMs == evidenceAgeMs` 관계를 유지한다.

## Top-level

| Field | Type | Null | 의미 |
|---|---|---|---|
| `schemaVersion` | integer | no | 현재 `2` |
| `appVersion` | string | no | 실제 `BuildConfig.VERSION_NAME` |
| `scenarioId` | string | no | 실행한 effective scenario ID |
| `scenarioName` | string | no | 사용자 표시 이름 |
| `verdict` | enum string | no | `RunVerdict.name` |
| `startedEpochMs` | integer | no | run 시작 wall clock |
| `finishedEpochMs` | integer | no | run 종료 wall clock |
| `controlLayerIncluded` | boolean | no | 항상 true, pure Compose HUD를 포함한 app Window root가 display에 남음; HUD 전용 extra Surface를 뜻하지 않음 |
| `device` | object | no | build/device identity |
| `exactUnderrunDelta` | integer | yes | verified exact delta |
| `exactUnderrunSource` | string | no | unavailable이면 empty |
| `exactUnderrunQuality` | enum string | no | unavailable이면 `UNAVAILABLE` |
| `suspectedUnderrunDelta` | integer | no | proxy delta |
| `telemetrySources` | object | no | 마지막 sample의 source/quality |
| `peaks` | object | no | stable-source aggregate peak |
| `phases` | array | no | effective phase contract |
| `events` | array | no | ordered event |
| `samples` | array | no | ordered telemetry sample |

### `device`

| Field | Type |
|---|---|
| `manufacturer`, `model`, `device`, `release`, `fingerprint` | string |
| `sdk` | integer |

Fingerprint는 privacy-sensitive device identity다. Report를 저장소에 commit하거나
자동 upload하지 않는다.

## `telemetrySources`

각 entry는 다음 object다.

```json
{"quality":"MEASURED","source":"example"}
```

Key:

- `exactUnderrun`
- `cpu`, `appCpu`
- `memoryUsed`, `memoryAvailable`, `appPss`
- `display`, `producedFps`
- `suspectedUnderrun`
- `gpu`, `gpuFrequency`
- `memoryBus`
- `dpu`, `dpuFrequency`
- `hwcDeviceLayers`, `hwcClientLayers`
- `surfaceFlingerMiss`
- `generatedBandwidth`

이 object는 값 자체를 반복하지 않는다. 마지막 sample의 provenance 요약이다.

## `peaks`

| Field | Type | 단위 |
|---|---|---|
| `cpuPercent` | number/null | % |
| `memoryUsedPercent` | number/null | % |
| `generatedBandwidthGbps` | number/null | Gbps |

다른 peak가 필요하면 consumer가 `samples`에서 source/quality 연속성을 확인해 계산한다.
서로 다른 provenance segment의 max를 조용히 합치지 않는다.

HWC peak는 complete same-sample atomic pair만 후보로 삼는다. 각 후보의 `T`는 그
sample의 `D+C`이고, 가장 큰 `T`, 동률이면 가장 큰 `D` 순서로 tuple 하나를 선택한다.
서로 다른 sample의 `max(D)`와 `max(C)`를 조합하지 않으며 run 중 pair source/quality가
바뀌면 peak를 `N/A`로 처리한다. `T`는 현재 schema의 독립 serialized field가 아니다.

## `phases[]`

| Field | Type | 의미 |
|---|---|---|
| `id` | string | phase ID |
| `durationMs` | integer | plan 시간 배율 materialization과 safety cap 뒤 effective duration |
| `layers` | integer | active logical layer |
| `producerFps` | number/null | requested producer pacing |
| `requestedDisplayHz` | number/null | requested display pacing |
| `backend` | enum string | `LayerBackend` |
| `pixelRoute` | enum string | `PixelRoute` |
| `bufferSize` | enum string | `BufferSize` |
| `bufferPresentation` | enum string | `FIT` 또는 centered `PIXEL_1_TO_1_CROP` |
| `layerOrientation` | enum string | 고정 0°/90° orientation |
| `motion` | enum string | `MotionProfile` |
| `layerSizeProfile` | enum string | `LayerSizeProfile` |
| `motionSemantics` | enum string | typed motion semantics |
| `physicalHwcZOrderChange` | boolean | 현재 View swap은 false |
| `alphaOverlap` | boolean | overlap/alpha pressure |
| `includeGlLayer` | boolean | GL producer/tail |
| `hwcCompositionExpectation` | enum string | none/device/client contract |
| `workloads` | object | CPU/MEM/GPU/NPU/shape |
| `transition` | object | transition envelope |

`workloads`:

- `cpu`, `memory`, `gpu`, `npu`: normalized 0~1 number
- `shape`: `LoadShape.name`

`transition`:

- `mode`: `TransitionMode.name`
- `durationMs`, `cycleMs`, `stepCount`
- `dutyCycle`, `floor`

`transition.durationMs`와 `cycleMs`도 plan 시간 배율 materialization과 safety proportional
adjustment 뒤의 effective 값이다.

## `events[]`

| Field | Type | 의미 |
|---|---|---|
| `tMs` | integer | run-relative event timestamp |
| `type` | string | stable event type |
| `message` | string | bounded human-readable detail |

`PLAN_POSITION` message에는 `run`, `repeat`, `queue`, 요청 `durationMultiplier`가 함께
기록된다. 이는 사람이 감사할 수 있는 bounded hint이고 별도 typed JSON field가 아니다.
Machine consumer는 실제 실행 시간을 `phases[].durationMs`와 sample/event timestamp에서
읽어야 하며 message parsing에 계약을 걸면 안 된다.

각 run의 `HWC_COUNT_SCOPE` event는 현재 HWC count 해석을 다음과 같이 고정한다.

- scope는 `APP_RAW_UNSEPARATED`
- `controlLayerIncluded=true`이며 control/root subtraction은 없음
- FrameTracker `PHYSICAL` BufferQueue producer count는 별도 값
- workload-scoped attribution에는 typed BSP layer identity evidence가 필요

이는 stable event type의 계약이고 `message`를 새 machine-readable schema처럼 임의
확장해 parsing하라는 뜻은 아니다.

Warm-up baseline gate는 다음 event로 감사한다.

- `WARMUP_READY`: topology/geometry commit, generation activation과 post-activation
  all-producer fresh first buffer가 bounded window 안에서 확인됨
- `WARMUP_READINESS_TIMEOUT`: 위 readiness가 deadline 안에 충족되지 않아 baseline 전
  run 중단
- `WARMUP_BASELINE_INVALIDATED`: fresh baseline sample 중 topology/geometry/readiness가
  바뀌어 baseline 폐기와 run 중단

Consumer는 모르는 event type을 무시할 수 있어야 하며 known event의 의미를 문자열
pattern만으로 추론하지 않는다.

## `samples[]`

### Core/system

| Value field | Type | 단위 | 동반 field |
|---|---|---|---|
| `tMs` | integer | run-relative ms | 없음 |
| `cpu` | number/null | % | `cpuQuality`, `cpuSource` |
| `appCpu` | number/null | % | `appCpuQuality`, `appCpuSource` |
| `memoryUsed` | number/null | % | `memoryUsedQuality`, `memoryUsedSource` |
| `memoryAvailableMb` | number/null | MiB | `memoryAvailableQuality`, `memoryAvailableSource` |
| `appPssMb` | number/null | MiB | `appPssQuality`, `appPssSource` |
| `displayHz` | number/null | Hz | `displayHzQuality`, `displayHzSource` |
| `producedFps` | number/null | fps | `producedFpsQuality`, `producedFpsSource` |

### Underrun과 frame proxy

| Field | Type | 의미 |
|---|---|---|
| `missedFrames` | integer | app/frame proxy cumulative |
| `missedFramesQuality`, `missedFramesSource` | enum/string | proxy provenance |
| `suspectedUnderruns` | integer | suspected proxy cumulative |
| `suspectedUnderrunQuality`, `suspectedUnderrunSource` | enum/string | proxy provenance |
| `exactUnderruns` | integer/null | hardware/kernel exact cumulative |
| `exactUnderrunQuality`, `exactUnderrunSource` | enum/string | exact provenance |

### GPU, bus와 DPU

| Value field | 단위 | 동반 field |
|---|---|---|
| `gpuBusy` | % | `gpuBusyQuality`, `gpuBusySource` |
| `gpuFrequencyMhz` | MHz | `gpuFrequencyQuality`, `gpuFrequencySource` |
| `busBusy` | % | `busBusyQuality`, `busBusySource` |
| `dpuBusy` | % | `dpuBusyQuality`, `dpuBusySource` |
| `dpuFrequencyMhz` | MHz | `dpuFrequencyQuality`, `dpuFrequencySource` |

모든 value는 number/null이다.

### HWC composition

| Field | Type | 의미 |
|---|---|---|
| `hwcDeviceLayers`, `hwcClientLayers` | integer/null | unpartitioned app-display raw same-snapshot pair |
| `hwcDeviceLayersQuality`, `hwcClientLayersQuality` | enum | pair quality |
| `hwcDeviceLayersSource`, `hwcClientLayersSource` | string | pair source |
| `hwcCompositionEvidenceMonotonicMs` | integer/null | run-relative pair completion; 음수 가능 |
| `hwcCompositionEvidenceAgeMs` | integer/null | sample 시점 age |
| `surfaceFlingerHwcMissed`, `surfaceFlingerGpuMissed` | integer/null | SF proxy |
| `surfaceFlingerMissQuality`, `surfaceFlingerMissSource` | enum/string | proxy provenance |
| `surfaceFlingerEvidenceMonotonicMs` | integer/null | run-relative SF completion; 음수 가능 |
| `surfaceFlingerEvidenceAgeMs` | integer/null | sample 시점 age |

DEVICE/CLIENT source와 quality는 서로 같아야 usable pair다. Consumer가 한쪽만 다른
source와 결합하면 안 된다. Evidence timestamp도 같고 freshness가 유효한 complete
pair에서만 `HWC APP RAW T=D+C`를 계산한다.

이 pair는 control/root 보정이나 workload producer identity partition을 제공하지 않는다.
`controlLayerIncluded=true`는 pure Compose HUD를 포함한 Activity/app Window root가
display에 남는다는 뜻이다. HUD 전용 extra SF/HWC surface는 0이지만 1 Hz 갱신으로 root가
다시 그려질 수 있다. 이 1 Hz는 immutable HUD snapshot을 상위 100 ms recomposition과
격리하는 app-side redraw 정책이며, app API로 root를 `DEVICE`/`CLIENT`에 강제하거나
raw count에서 제외하는 보장이 아니다. `PHYSICAL`은 별도
BufferQueue/frame-callback producer 수이므로 HWC total이나 workload plane count로
해석하지 않는다.

### Generated load와 safety

| Field | Type | 단위/의미 |
|---|---|---|
| `generatedBandwidthGbps` | number/null | app memory worker Gbps |
| `generatedBandwidthQuality`, `generatedBandwidthSource` | enum/string | provenance |
| `thermalStatus` | integer | Android thermal status |
| `thermal` | string | display label |
| `memoryLow` | boolean | system/app allocation low-memory |
| `powerSaveMode` | boolean | sample 시 Battery Saver |
| `vendorServiceSession` | integer/null | provider registration continuity |
| `compressionState` | string | sanitized bounded status |
| `npuState` | string | sanitized bounded status |

## 대표 구조 예

아래는 필드 관계를 보여 주는 축약 예다. 실제 writer는 위 표의 모든 sample field를
출력한다.

```json
{
  "schemaVersion": 2,
  "appVersion": "yyyyMMdd_HHmmss-debug",
  "scenarioId": "example",
  "scenarioName": "Example",
  "verdict": "INCONCLUSIVE",
  "startedEpochMs": 0,
  "finishedEpochMs": 1,
  "controlLayerIncluded": true,
  "device": {
    "manufacturer": "",
    "model": "",
    "device": "",
    "sdk": 36,
    "release": "",
    "fingerprint": ""
  },
  "exactUnderrunDelta": null,
  "exactUnderrunSource": "",
  "exactUnderrunQuality": "UNAVAILABLE",
  "suspectedUnderrunDelta": 0,
  "telemetrySources": {},
  "peaks": {
    "cpuPercent": null,
    "memoryUsedPercent": null,
    "generatedBandwidthGbps": null
  },
  "phases": [],
  "events": [],
  "samples": []
}
```

`telemetrySources`는 실제 report에서 빈 object가 아니라 정의된 모든 provenance key를
포함한다. 현재 저장소에는 versioned golden JSON fixture가 없다. Fixture를 추가할 때는
실제 `ReportWriter` 출력으로 만들고 `ReportWriterMathTest`의 schema assertion과 함께
검증한다.

## Consumer 규칙

1. `schemaVersion`을 먼저 확인한다.
2. unknown top-level/event/enum은 원본을 보존하고 fail-open parsing할 수 있지만 known
   field의 단위나 null 의미를 바꾸지 않는다.
3. unavailable numeric을 0으로 채우지 않는다.
4. exact delta가 usable하면 proxy보다 우선한다.
5. source/quality 변경 전후의 peak·trend를 하나의 연속 segment로 합치지 않는다.
6. HWC D/C 한쪽만 있는 sample을 완전한 pair로 만들지 않는다.
7. HWC peak는 같은 sample의 atomic `(D,C,T)` tuple로 선택하며 독립 `max(D)`와
   `max(C)`를 조합하지 않는다.
8. HWC app raw D/C/T에서 root 상수를 차감하거나 `PHYSICAL` producer 수와 비교해
   workload plane ceiling을 추론하지 않는다. Workload attribution에는 scoped typed BSP
   layer identity evidence가 필요하다.
9. workload-only linear traffic reference는 현재 schema sample의 measured bus와 합치지 않는다.
10. fingerprint와 status string을 외부 공유 전에 privacy 검토한다.
11. 이전 schema v2 report에 `bufferPresentation`/`layerOrientation`이 없으면 명시적인
   FIT 또는 90° 증거로 추정하지 않고 projection/orientation을 `UNKNOWN`으로 취급한다.

## Schema migration

- 기존 field 의미를 바꾸지 않는다.
- incompatible type/unit/nullability 변경은 `schemaVersion`을 증가시킨다.
- 새 optional field는 consumer가 무시할 수 있게 추가한다.
- app writer, unit test, `METRICS.md`, 이 문서와 존재하는 외부 consumer fixture를 같은
  변경에서 갱신한다.
- filename/report prefix 변경은 [External contracts](EXTERNAL_CONTRACTS.md)의 migration을
  따른다.
