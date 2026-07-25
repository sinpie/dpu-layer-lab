# Product requirements와 traceability

> **Authority:** 사용자 목적을 안정적인 요구사항 ID로 정의하고 구현·시나리오·검증까지 연결하는 추적표
> **Audience:** product owner, 시험 설계자, maintainer, coding agent, reviewer
> **Update when:** 사용자 목표, 지원 범위, 주요 UI/automation/report 동작 또는 acceptance가 바뀔 때
> **Does not own:** 세부 component 구조, safety 수치 원문, metric algorithm, BSP 배치 절차
> **Related:** [Documentation index](INDEX.md), [README.md](../README.md),
> [AGENTS.md](../AGENTS.md), [ARCHITECTURE.md](../ARCHITECTURE.md),
> [SCENARIOS.md](SCENARIOS.md), [METRICS.md](METRICS.md),
> [TESTING.md](TESTING.md)

이 문서는 DPULayerTest를 다시 구현할 때 “무엇을 만족해야 하는가”를 잃지 않기 위한
요구사항 원장이다. 세부 수치나 알고리즘은 링크된 authority를 사용한다. 요구사항을
삭제하거나 의미를 축소하려면 사용자 승인과 관련 test·문서 변경이 필요하다.

## 제품 목표

DPULayerTest는 Android AP의 display pipeline에 제어 가능한 physical producer와
교차 자원 부하를 적용해 다음 조건을 재현·관찰하는 실험 도구다.

1. 지속적인 고부하뿐 아니라 저전력·저주파 상태에서 갑자기 커지는 DPU 요청
2. HWC `DEVICE` 범위 안의 변화와 `CLIENT` GPU fallback 경계
3. layer 수, 크기, format, motion, FPS, display Hz와 다른 bus 사용자의 조합
4. exact underrun counter가 있을 때의 검증과 없을 때의 정직한 proxy/N/A
5. 반복 가능한 plan, 실시간 진행, 결과 보고서와 외부 automation

## 기능 요구사항

| ID | 요구사항 | 구현 authority | 대표 검증 |
|---|---|---|---|
| FR-DISPLAY-001 | 1~20개의 독립 BufferQueue producer를 만들고 제거할 수 있어야 한다. | `render/LayerStageView.kt`, `model/ScenarioSafetyPolicy.kt` | `LayerStageViewMathTest`, `ProducerGenerationGateTest` |
| FR-DISPLAY-002 | independent, mixed, flattened GPU composition topology를 구분해야 한다. | `LabModels.kt`, `LayerStageView.kt` | `LabModelsTest`, `ScenarioCatalogTest` |
| FR-DISPLAY-003 | scroll, zoom, rotation, pan, parallax, alpha와 client Z-order proxy를 제공해야 한다. | `LayerStageView.kt`, `SCENARIOS.md` | `LayerStageViewMathTest`, catalog tests |
| FR-SIZE-001 | full, small, mixed, gradual, abrupt layer destination 크기를 지원해야 한다. | `LabModels.kt`, `LayerStageView.kt` | `LayerStageViewMathTest`, `LayerTrafficEstimatorTest` |
| FR-FORMAT-001 | RGB8888/565와 검증된 decoder-to-Surface YUV/P010/SBWC route를 구분해야 한다. | `VideoDecoderSelection.kt`, `LabController.kt` | `VideoDecoderSelectionTest`, controller tests |
| FR-MEDIA-001 | 선택한 4K/8K media의 MIME, dimensions, FPS, profile, codec binding을 fail-closed 검증해야 한다. | `VideoDecoderSelection.kt`, `LabController.kt` | `VideoDecoderSelectionTest`, `LabControllerMathTest` |
| FR-PACING-001 | producer FPS와 requested/actual display Hz를 독립적으로 제어·기록해야 한다. | `LabModels.kt`, `LabController.kt`, `FrameTracker.kt` | model/controller/frame tests |
| FR-TRANSITION-001 | STEP, linear, staircase, pulse, triangle, soak/recovery를 bounded cadence로 실행해야 한다. | `LoadTransitionEvaluator.kt`, `LabController.kt` | `LoadTransitionEvaluatorTest`, controller tests |
| FR-LOAD-001 | CPU와 memory/bus load를 bounded fixed-period worker로 올리고 내릴 수 있어야 한다. | `LoadGenerators.kt` | load manager/thread/prewarm tests |
| FR-LOAD-002 | GLES workload와 실제 GL-backed producer를 통해 GPU 부하를 줄 수 있어야 한다. | `StressGlSurfaceView.kt`, `LayerStageView.kt` | renderer policy/math tests |
| FR-LOAD-003 | vendor NPU adapter가 있을 때만 NPU load로 표시하고 latest-wins acknowledgment를 확인해야 한다. | `LoadGenerators.kt`, `VendorBridge.kt` | `LoadManagerNpuControlTest`, vendor tests |
| FR-HWC-001 | DEVICE/CLIENT 기대 조건과 실제 fresh evidence를 분리해 판정해야 한다. | `LabController.kt`, `SystemMonitor.kt` | controller/system monitor tests |
| FR-HWC-002 | process-session의 최초 승인된 START에서 첫 scenario 전에 20-layer candidate를 한 번만 관측하고 terminal 결과를 재사용해야 한다. | `HwcCapacityCalibrationSession.kt`, `LabController.kt` | `HwcCapacityCalibrationSessionTest`, controller tests |
| FR-SCENARIO-001 | baseline, burst, gradual, DEVICE 후보, CLIENT 목표, video/format, resource 조합 preset을 제공해야 한다. | `ScenarioCatalog.kt` | `ScenarioCatalogTest` |
| FR-PLAN-001 | 사용자가 선택한 순서와 중복을 보존하고 scenario/repeat 진행을 loop할 수 있어야 한다. | `ScenarioQueueEditor.kt`, `LabController.kt` | queue/plan/controller tests |
| FR-UI-001 | 목적 중심 선택, 세부 facet, queue 구성, 현재/다음 항목, 중지와 결과를 직관적으로 보여야 한다. | `ui/DpuLayerLabApp.kt` | `DpuLayerLabAppMathTest` |
| FR-UI-002 | 실행 중 좌측 상단 HUD에 version, layer, DPU, CPU, GPU와 예상 traffic을 provenance와 함께 표시해야 한다. | `DpuLayerLabApp.kt`, `LayerTrafficEstimator.kt` | UI/traffic tests |
| FR-UI-003 | Dashboard에서 AP/app CPU, system memory·available memory, measured memory bus, generated traffic, producer FPS와 display 상태를 N/A와 구분해 확인할 수 있어야 한다. | `DpuLayerLabApp.kt`, `SystemMonitor.kt` | UI/system monitor tests |
| FR-WINDOW-001 | test Window는 status/navigation bar hidden acknowledgment 뒤에만 producer를 시작하고, STOP·실패·Activity 재생성에서도 시작 전 visibility를 실제 Insets로 확인해 복구해야 한다. | `TestWindowIsolation.kt`, `MainActivity.kt` | isolation/activity tests |
| FR-PERF-001 | 실행 중 Battery Saver만 typed API v3 bounded lease로 임시 해제할 수 있고, broker가 없으면 Saver가 이미 OFF인 경우에만 app-only monitoring으로 실행해야 한다. 원래 상태, lease/renewal과 exact restore acknowledgment를 확인하며 복구 실패는 후속 START를 차단해야 한다. | `PerformanceEnvironment.kt`, `VendorBridge.kt` | performance/vendor tests |
| FR-PERF-002 | 앱의 선제 thermal SEVERE derating은 plan-start immutable 선택값이며 기본 OFF여야 한다. OFF에서는 app setpoint를 유지하고 Android/kernel throttling에 맡기며, Intent로 값을 우회하지 못한다. CRITICAL·low-memory abort는 항상 유지해야 한다. | `PerformanceEnvironment.kt`, `LabController.kt` | controller/performance tests |
| FR-AUTO-001 | explicit Intent로 SHOW, START, STOP, scenario ID 목록과 반복 횟수를 제어해야 한다. | `AutomationIntentContract.kt`, manifest | automation/main activity tests |
| FR-REPORT-001 | phase/event/sample/verdict와 provenance를 schema v2 JSON으로 원자 발행·공유해야 한다. | `ReportWriter.kt`, `METRICS.md` | `ReportWriterMathTest` |
| FR-SYSTEM-001 | platform-signed priv-app와 vendor AIDL/probe extension을 지원하되 portable fallback을 유지해야 한다. | `SYSTEM_INTEGRATION.md`, `VendorBridge.kt` | vendor/capability tests |

## 비기능 요구사항

| ID | 요구사항 | 검증·근거 |
|---|---|---|
| NFR-SAFETY-001 | layer, FPS, Hz, duration, memory와 plan 반복은 hard cap을 넘지 않아야 한다. | `AGENTS.md`, `ScenarioSafetyPolicyTest` |
| NFR-SAFETY-002 | thermal CRITICAL, low-memory, safety envelope 변경은 active run을 중단해야 한다. | controller/safety tests |
| NFR-EFFICIENCY-001 | 의도한 load 외의 allocation, polling, Binder/dumpsys와 worker backlog를 최소화해야 한다. | fixed-period/latest-wins 설계와 lifecycle tests |
| NFR-LIFECYCLE-001 | 모든 producer/codec/EGL/worker/vendor state에는 bounded cancel·teardown과 sticky failure가 있어야 한다. | `STATE_MACHINES.md`, cleanup/recovery tests |
| NFR-METRIC-001 | unavailable·unsupported·proxy를 0 또는 exact로 위장하지 않아야 한다. | `METRICS.md`, monitor/controller tests |
| NFR-METRIC-002 | source/quality 변경은 peak와 graph continuity를 끊어야 한다. | metric/UI tests |
| NFR-SECURITY-001 | release automation은 signature/privileged permission과 explicit alias로 보호해야 한다. | manifest, automation tests |
| NFR-SECURITY-002 | report, media, key, token과 local SDK path를 commit·backup·analytics로 유출하지 않아야 한다. | backup rules, `.gitignore`, repository checks |
| NFR-REPRO-001 | JDK/SDK/Gradle/AGP와 build variant가 추적 가능해야 한다. | `TESTING.md`, `RELEASE.md`, Gradle source |
| NFR-REPRO-002 | source 유실 시 dependency 순서와 checkpoint로 재생성할 수 있어야 한다. | `RECONSTRUCTION.md`, `REPOSITORY_MAP.md` |

## 반드시 구분할 개념

| 혼동하면 안 되는 항목 | 올바른 의미 |
|---|---|
| Android View 수 vs physical layer | renderer가 게시한 실제 physical producer count가 계측 기준 |
| `DEVICE` vs “DPU 최대 plane 수” | 한 snapshot의 composition type이며 보편적 maximum이 아님 |
| GPU busy vs `CLIENT` | GPU utilization과 HWC client composition은 서로 다른 증거 |
| estimated traffic vs bus utilization | 전자는 full-buffer 선형 모델, 후자는 provider가 있을 때의 실측값 |
| missed frame vs exact underrun | missed frame은 proxy, exact underrun은 검증된 monotonic counter |
| selected YUV/P010/SBWC vs RGBA proxy | decoder binding이 없으면 해당 format test는 실행하지 않음 |
| NPU unavailable vs CPU 대체 | NPU adapter가 없으면 NPU는 unavailable이며 CPU로 위장하지 않음 |
| requested 20L vs actual candidate | runtime safety가 줄인 실제 시도 layer 수를 별도로 표시 |

## 완료 traceability

기능 변경의 완료 조건은 다음 순서로 확인한다.

1. 해당 요구사항 ID와 사용자-visible 의미를 확인한다.
2. source의 실행 경로와 failure/cancel/cleanup 경로를 함께 수정한다.
3. 요구사항 row에 연결된 unit/boundary test를 갱신한다.
4. `AGENTS.md`의 safety·provenance 불변식을 다시 대조한다.
5. `testDebugUnitTest`, `lintDebug`, `assembleDebug`를 통과한다.
6. 변경된 authority 문서와 [Documentation index](INDEX.md)의 갱신 표를 따른다.

연결된 실기기 stress 실행은 host gate가 아니며 사용자가 대상 장비와 범위를 명시한
경우에만 별도 acceptance로 수행한다.
