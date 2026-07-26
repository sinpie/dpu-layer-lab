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
| FR-SIZE-002 | 실제 primary producer 버퍼는 1K, 2K/1080p, 4K, 8K 선택을 제공하고, 해상도·교차 부하를 함께 올리고 내리는 catalog sweep을 제공해야 한다. 각 단계는 graphics-memory safety budget을 통과해야 하며 더 작은 해상도로 조용히 대체하지 않는다. | `LabModels.kt`, `ScenarioCatalog.kt`, `ScenarioSafetyPolicy.kt` | `ScenarioCatalogTest`, `ScenarioSafetyPolicyTest` |
| FR-SIZE-003 | source buffer를 종횡비 보존 FIT 또는 centered 1:1 crop으로 투영하고 motion과 별도인 고정 0°/90° orientation을 지원해야 한다. 2K/4K/8K의 90° FIT와 8K FIT/1:1을 비교할 수 있어야 하며 projection은 full allocation/budget/traffic을 줄이지 않는다. | `LabModels.kt`, `LayerStageView.kt`, `ScenarioSafetyPolicy.kt` | `LayerStageViewMathTest`, `ScenarioSafetyPolicyTest` |
| FR-FORMAT-001 | RGB8888/565와 검증된 decoder-to-Surface YUV/P010/SBWC route를 구분해야 한다. | `VideoDecoderSelection.kt`, `LabController.kt` | `VideoDecoderSelectionTest`, controller tests |
| FR-MEDIA-001 | 선택한 4K/8K media의 MIME, dimensions, FPS, profile, codec binding을 fail-closed 검증해야 한다. | `VideoDecoderSelection.kt`, `LabController.kt` | `VideoDecoderSelectionTest`, `LabControllerMathTest` |
| FR-PACING-001 | producer FPS와 requested/actual display Hz를 독립적으로 제어·기록해야 한다. | `LabModels.kt`, `LabController.kt`, `FrameTracker.kt` | model/controller/frame tests |
| FR-TRANSITION-001 | STEP, linear, staircase, pulse, triangle, soak/recovery를 bounded cadence로 실행하고, whole-phase linear endpoint는 모든 committed producer의 exact control revision frame으로 증명해야 한다. | `LoadTransitionEvaluator.kt`, `LabController.kt`, `FrameTracker.kt` | `LoadTransitionEvaluatorTest`, controller/producer tests |
| FR-LOAD-001 | CPU와 memory/bus load를 bounded fixed-period worker로 올리고 내릴 수 있어야 한다. | `LoadGenerators.kt` | load manager/thread/prewarm tests |
| FR-LOAD-002 | GLES workload와 실제 GL-backed producer를 통해 GPU 부하를 줄 수 있어야 한다. | `StressGlSurfaceView.kt`, `LayerStageView.kt` | renderer policy/math tests |
| FR-LOAD-003 | vendor NPU adapter가 있을 때만 NPU load로 표시하고 latest-wins acknowledgment를 확인해야 한다. | `LoadGenerators.kt`, `VendorBridge.kt` | `LoadManagerNpuControlTest`, vendor tests |
| FR-HWC-001 | DEVICE/CLIENT 기대 조건과 실제 fresh evidence를 분리해 판정해야 한다. 실행 HUD는 complete atomic tuple만 `HWC APP RAW D/C/T`로 freshness·provenance와 함께 표시하고, pair가 없으면 반복 N/A 대신 bounded availability reason을 표시·report sample에 보존해야 한다. 이 reason은 verdict가 아니다. Control/root와 workload producer scope가 분리되지 않았음을 HUD와 run별 `HWC_COUNT_SCOPE` event에 보존해야 한다. 결과 peak도 독립 D/C 최댓값을 합치지 않고 complete tuple의 최대 T, 동률이면 D가 큰 tuple 하나를 선택해야 한다. | `LabController.kt`, `SystemMonitor.kt`, `DpuLayerLabApp.kt`, `ReportWriter.kt` | controller/system monitor/UI/report tests |
| FR-HWC-002 | process-session의 최초 승인된 START에서 첫 scenario 전에 20-layer candidate를 한 번만 관측하고 terminal 결과를 재사용해야 한다. | `HwcCapacityCalibrationSession.kt`, `LabController.kt` | `HwcCapacityCalibrationSessionTest`, controller tests |
| FR-HWC-003 | Raw D/C/T 또는 capacity candidate와의 차이로 physical producer ceiling을 추론하지 않아야 한다. 특정 workload producer의 HWC assignment를 주장하려면 display/CRTC scope와 committed producer의 per-layer identity에 결속된 BSP evidence가 필요하며, 그 계약이 없으면 app/display raw observation으로만 표현해야 한다. | `SYSTEM_INTEGRATION.md`, `METRICS.md`, `PLAN.md` | scoped-provider contract test와 device acceptance(향후 P0) |
| FR-SCENARIO-001 | baseline, burst, gradual, DEVICE 후보, CLIENT 목표, video/format, resource 조합 preset을 제공해야 한다. | `ScenarioCatalog.kt` | `ScenarioCatalogTest` |
| FR-SCENARIO-002 | 해상도 외 축을 고정한 1K↔8K resolution-only A/B와 동일 8K allocation에서 FIT↔1:1만 바꾸는 presentation-only A/B/A를 제공해 결합 sweep 결과를 분리 검증할 수 있어야 한다. | `ScenarioCatalog.kt` | `ScenarioCatalogTest` |
| FR-SCENARIO-003 | `dpu-only-repeat-shock`의 stable ID는 유지하되 사용자-facing 의미는 generated CPU/memory/GPU/NPU setpoint가 0인 `Display-pipeline Repeated Step Shock`이어야 한다. Canvas draw, buffer post와 producer memory write가 남으므로 DPU 단일축 또는 producer 비용 0으로 표현하지 않아야 한다. | `ScenarioCatalog.kt`, `SCENARIOS.md` | `ScenarioCatalogTest` |
| FR-PLAN-001 | 사용자가 선택한 순서와 중복을 보존하고 queue 전체가 끝난 뒤 첫 scenario로 돌아가는 repeat loop를 1~10회 실행할 수 있어야 한다. 수동 UI queue에는 임의의 고정 항목/expanded-run 상한을 두지 않고 repeat를 펼치지 않은 채 순차 실행하며, 외부 Intent만 기존 40-run 상한을 유지한다. | `ScenarioQueueEditor.kt`, `LabController.kt` | queue/plan/controller tests |
| FR-PLAN-002 | 실행 직전 1×/2×/5×/10×/50×/100× 시간 배율을 선택하고 각 phase duration·transition window·cycle에 정확히 한 번 적용해야 한다. 기존 phase/scenario safety cap과 예상 시간 제외 범위를 명시해야 하며 외부 Intent로 우회하지 않는다. | `LabModels.kt`, `LabController.kt`, `ui/DpuLayerLabApp.kt` | `ScenarioPlanPolicyTest`, `DpuLayerLabAppMathTest` |
| FR-UI-001 | 목적 중심 선택, 세부 facet, queue 구성, 실행 직전 전체-loop 반복·시간 배율 review, 현재/다음 항목, 중지와 결과를 직관적으로 보여야 한다. | `ui/DpuLayerLabApp.kt` | `DpuLayerLabAppMathTest` |
| FR-UI-002 | 실행 중 좌측 상단 HUD에 version, layer, DPU, CPU, GPU와 workload-only linear scanout-input/producer-write reference를 provenance와 함께 표시해야 한다. 상위 renderer recomposition과 분리된 immutable HUD state로 동적 text/progress 교체를 최대 1 Hz로 제한하되 topology/safety 경계는 즉시 반영해야 한다. HUD subtree는 pure Compose로 추가 Surface를 만들지 않아야 하지만 Activity root의 HWC assignment를 강제·증명한다고 표현하지 않아야 한다. | `DpuLayerLabApp.kt`, `LayerTrafficEstimator.kt` | UI/traffic tests |
| FR-UI-003 | Dashboard에서 AP/app CPU, system memory·available memory, measured memory bus, generated traffic, producer FPS와 display 상태를 N/A와 구분해 확인할 수 있어야 한다. | `DpuLayerLabApp.kt`, `SystemMonitor.kt` | UI/system monitor tests |
| FR-WINDOW-001 | test Window는 status/navigation bar hidden acknowledgment 뒤에만 producer를 시작하고, STOP·실패·Activity 재생성에서도 시작 전 visibility를 실제 Insets로 확인해 복구해야 한다. | `TestWindowIsolation.kt`, `MainActivity.kt` | isolation/activity tests |
| FR-PERF-001 | 실행 중 Battery Saver만 typed API v3 bounded lease로 임시 해제할 수 있고, broker가 없으면 Saver가 이미 OFF인 경우에만 app-only monitoring으로 실행해야 한다. 원래 상태, lease/renewal과 exact restore acknowledgment를 확인하며 복구 실패는 후속 START를 차단해야 한다. | `PerformanceEnvironment.kt`, `VendorBridge.kt` | performance/vendor tests |
| FR-UI-004 | Battery Saver가 켜져 테스트를 시작할 수 없으면 명시적 설정 action을 제공하되 cleanup·performance 원상복구·Window 복구 전 navigation을 defer해야 한다. 전용 Battery Saver 설정을 먼저 열고 처리할 수 없으면 일반 설정으로 fallback하며, 앱이 설정을 직접 변경하거나 typed broker 계약을 우회하지 않는다. Background 전환 중 pending 요청을 보존하고 defer/launch 실패에는 action을 다시 제공한다. 복귀 후 새 plan에서 상태를 다시 검증하고 stale snackbar consume이 새 notice를 지우지 않아야 한다. | `LabController.kt`, `DpuLayerLabApp.kt`, `MainActivity.kt` | controller/UI/activity tests |
| FR-PERF-002 | 앱의 선제 thermal SEVERE derating은 plan-start immutable 선택값이며 기본 OFF여야 한다. OFF에서는 app setpoint를 유지하고 Android/kernel throttling에 맡기며, Intent로 값을 우회하지 못한다. CRITICAL·low-memory abort는 항상 유지해야 한다. | `PerformanceEnvironment.kt`, `LabController.kt` | controller/performance tests |
| FR-AUTO-001 | explicit Intent로 SHOW, START, STOP, scenario ID 목록과 반복 횟수를 제어해야 한다. | `AutomationIntentContract.kt`, manifest | automation/main activity tests |
| FR-REPORT-001 | phase/event/sample/verdict와 provenance를 schema v2 JSON으로 원자 발행·공유해야 한다. | `ReportWriter.kt`, `METRICS.md` | `ReportWriterMathTest` |
| FR-SYSTEM-001 | platform-signed priv-app와 vendor AIDL/probe extension을 지원하되 portable fallback을 유지하고, explicit broker component·permission owner·signer trust가 검증될 때만 vendor service를 사용해야 한다. | `SYSTEM_INTEGRATION.md`, `VendorBridge.kt` | vendor/capability tests |

## 비기능 요구사항

| ID | 요구사항 | 검증·근거 |
|---|---|---|
| NFR-SAFETY-001 | layer, FPS, Hz, duration, memory와 source별 plan 반복/run 상한은 hard cap을 넘지 않아야 한다. | `AGENTS.md`, `ScenarioSafetyPolicyTest`, `ScenarioPlanPolicyTest` |
| NFR-SAFETY-002 | thermal CRITICAL, low-memory, safety envelope 변경은 active run을 중단해야 한다. | controller/safety tests |
| NFR-EFFICIENCY-001 | 의도한 load 외의 allocation, polling, Binder/dumpsys와 worker backlog를 최소화해야 한다. | fixed-period/latest-wins 설계와 lifecycle tests |
| NFR-LIFECYCLE-001 | 모든 producer/codec/EGL/worker/vendor state에는 bounded cancel·teardown과 sticky failure가 있어야 한다. | `STATE_MACHINES.md`, cleanup/recovery tests |
| NFR-METRIC-001 | unavailable·unsupported·proxy를 0 또는 exact로 위장하지 않아야 한다. | `METRICS.md`, monitor/controller tests |
| NFR-METRIC-002 | source/quality 변경은 peak와 graph continuity를 끊어야 한다. | metric/UI tests |
| NFR-METRIC-003 | scenario-wide exact baseline은 warm-up topology publish, matching geometry, generation activation과 모든 expected producer의 fresh first buffer를 bounded하게 확인한 뒤 serialized fresh sample로 설정해야 한다. 고정 delay만으로 readiness를 대신하거나 baseline 수집 중 바뀐 topology를 수락하지 않아야 한다. | `METRICS.md`, `LabControllerMathTest` |
| NFR-SECURITY-001 | release automation은 signature/privileged permission과 explicit alias로 보호해야 한다. | manifest, automation tests |
| NFR-SECURITY-002 | report, media, key, token과 local SDK path를 commit·backup·analytics로 유출하지 않아야 한다. | backup rules, `.gitignore`, repository checks |
| NFR-REPRO-001 | JDK/SDK/Gradle/AGP와 build variant가 추적 가능해야 한다. | `TESTING.md`, `RELEASE.md`, Gradle source |
| NFR-REPRO-002 | source 유실 시 dependency 순서와 checkpoint로 재생성할 수 있어야 한다. | `RECONSTRUCTION.md`, `REPOSITORY_MAP.md` |

## 반드시 구분할 개념

| 혼동하면 안 되는 항목 | 올바른 의미 |
|---|---|
| Android View 수 vs physical layer | renderer가 게시한 실제 physical producer count가 계측 기준 |
| `DEVICE` vs “DPU 최대 plane 수” | 한 snapshot의 composition type이며 보편적 maximum이 아님 |
| HWC APP RAW `D/C/T` vs workload producer count | `T=D+C`인 한 atomic tuple이며 control/root scope가 분리되지 않아 producer ceiling이나 per-layer assignment가 아님 |
| pure Compose HUD vs HWC layer 없음 | HUD subtree가 추가 Surface를 만들지는 않지만 Activity root/window layer는 남고 assignment는 HWC가 결정 |
| GPU busy vs `CLIENT` | GPU utilization과 HWC client composition은 서로 다른 증거 |
| scanout-input reference vs bus utilization | 전자는 workload producer만 포함한 full-buffer 선형 모델, 후자는 provider가 있을 때의 실측값 |
| missed frame vs exact underrun | missed frame은 proxy, exact underrun은 검증된 monotonic counter |
| selected YUV/P010/SBWC vs RGBA proxy | decoder binding이 없으면 해당 format test는 실행하지 않음 |
| NPU unavailable vs CPU 대체 | NPU adapter가 없으면 NPU는 unavailable이며 CPU로 위장하지 않음 |
| requested 20L vs actual candidate | runtime safety가 줄인 실제 시도 layer 수를 별도로 표시 |
| generated cross-load 0 vs DPU-only | 명시적 generator가 idle일 뿐 Canvas draw, buffer post와 producer write 비용은 남음 |

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
