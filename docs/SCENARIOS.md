# Scenario 계약과 catalog

> **Authority:** `ScenarioSpec`/`PhaseSpec` 의미, catalog preset, facet, transition과 신규 scenario 작성 계약
> **Audience:** 시험 설계자, 앱 개발자, scenario reviewer, automation 작성자
> **Update when:** catalog ID·phase·요구사항, scenario model, classifier/facet 또는 custom builder가 바뀔 때
> **Does not own:** hard safety invariant, metric 판정, BSP probe 구현, 현재 작업 우선순위
> **Related:** [Documentation index](INDEX.md), [README.md](../README.md), [ARCHITECTURE.md](../ARCHITECTURE.md),
> [AGENTS.md](../AGENTS.md), [METRICS.md](METRICS.md),
> [REQUIREMENTS.md](REQUIREMENTS.md), [UI_SPEC.md](UI_SPEC.md),
> [HWC_CAPACITY_CALIBRATION.md](HWC_CAPACITY_CALIBRATION.md),
> [TESTING.md](TESTING.md), [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)

코드 authority는
`app/src/main/java/com/example/dpulayerlab/engine/ScenarioCatalog.kt`,
`app/src/main/java/com/example/dpulayerlab/model/LabModels.kt`,
`app/src/main/java/com/example/dpulayerlab/model/ScenarioClassifier.kt`와
`app/src/main/java/com/example/dpulayerlab/model/ScenarioSafetyPolicy.kt`다.

## 모델 용어

### Phase

`PhaseSpec`은 하나의 목표 실험 구간이다.

| 필드 | 의미 |
|---|---|
| `id` | phase 내부에서 bounded·유일한 machine identifier |
| `label` | UI·event·report에 표시할 phase 이름 |
| `durationMs` | safety duration cap이 반영되기 전 요청 시간 |
| `activeLayers` | logical content 수; backend에 따라 physical producer 수와 다를 수 있음 |
| `producerFps` | 각 physical producer의 목표 pacing |
| `requestedDisplayHz` | Android에 요청할 display refresh |
| `backend` | independent, mixed Surface/Texture, flattened GPU 경로 |
| `pixelRoute` | RGB/YUV/P010/SBWC 입력 계약 |
| `bufferSize` | primary source/allocation 검증 크기 |
| `bufferPresentation` | source를 stage에 투영하는 base `FIT`/`PIXEL_1_TO_1_CROP` 계약 |
| `layerOrientation` | motion과 별도인 고정 0°/90° base orientation |
| `motion` | scroll/zoom/rotate/parallax 등의 View transform |
| `layerSizeProfile` | physical child의 destination footprint |
| `workloads` | CPU, memory, GPU, NPU normalized setpoint |
| `alphaOverlap` | alpha blending/overlap pressure를 요구하는지 |
| `includeGlLayer` | physical GPU-backed GL tail producer를 포함하는지 |
| `transition` | 이전 phase의 연속 값에서 현재 목표로 접근하는 envelope |
| `hwcCompositionExpectation` | `NONE`, `DEVICE_ONLY`, `CLIENT_REQUIRED` 관측 계약 |

### Scenario

`ScenarioSpec`은 순서가 있는 phase 목록과 ID, 목적 설명, category, risk, tag,
requirement를 묶는다. `durationMs`는 phase duration 합이며 preflight, warm-up,
cooldown, report I/O는 포함하지 않는다.

### Plan

`ScenarioRunPlan`은 queue 순서와 repeat를 보존한다. 같은 scenario를 여러 번 넣는 것은
A/B/A 실험을 위해 허용된다. `repeatCount=N`은 전체 queue를 처음부터 끝까지 N회
실행한다. `N > 1`이면 각 회차 경계에서 마지막 scenario 다음에 첫 scenario로 돌아가며,
1은 전체 queue 한 번이다. 앱 UI는 최대 40-entry queue × 10 loop = 400 run이다.
외부 Intent automation은 기존 expanded 40-run 상한을 유지한다.

`durationMultiplier`는 실행 직전 `1×, 2×, 5×, 10×, 50×, 100×` 중 하나를 고른다.
각 phase의 duration, transition window와 cycle에 immutable execution snapshot을 만들 때
정확히 한 번 함께 적용해 ramp/soak/cyclic 의미를 보존한다. 그 뒤 기존 phase 10분,
scenario 30분 safety cap이 명시적 adjustment 또는 reject를 수행할 수 있다. 예상 시간은
phase 합계이며 preflight, warm-up, cooldown, report I/O를 포함하지 않는다. 이 옵션은
외부 Intent extra로 노출하지 않는다.

## Backend와 physical producer

| Backend | physical topology |
|---|---|
| `INDEPENDENT_SURFACES` | layer마다 독립 BufferQueue-backed Surface |
| `MIXED_SURFACE_TEXTURE` | Surface와 TextureView를 혼합하고 optional GL tail 사용 |
| `FLATTENED_TEXTURE` | logical layer를 display-sized RGBA producer 하나로 합성 |

`FLATTENED_TEXTURE`의 logical layer 수를 HWC plane 수로 해석하면 안 된다. GPU load가
양수인 custom independent/mixed scenario에는 실제 GPU-backed tail이 필요하다. 선택한
primary media 또는 explicit buffer와 GL tail이 모두 필요한 1L 요청은 2L로 명시적으로
승격될 수 있다.

## LayerSizeProfile

### 1K~8K 실제 버퍼 sweep

`resolution-load-sweep` preset은 destination footprint와 별도로 primary producer의 실제
`BufferSize`를 `1K → 2K/1080p → 4K → 8K → 4K → 2K/1080p → 1K` 순서로 바꾼다.
상승 구간에서는 memory/CPU 교차 부하를 단계적으로 높이고 하강 구간에서는 낮춰,
해상도와 부하 증가·복구의 결합을 한 plan에서 관찰한다. 8K peak는 한 physical
producer로 제한하며 각 phase는 기존 triple-buffer graphics-memory budget을 그대로
통과해야 한다. Budget을 넘으면 해상도를 축소하지 않고 plan을 거부한다.

### Source buffer projection과 고정 orientation

`BufferSize`는 `DISPLAY`, `HD_1K`(1024×576), `FHD`(2K/1080p, 1920×1080),
`UHD_4K`, `UHD_8K`를 제공한다. `BufferPresentation.FIT`은 고정 0°/90° orientation을
먼저 반영한 source aspect ratio를 보존해 motion 전 전체 source가 stage 안에
letterbox되게 한다. `PIXEL_1_TO_1_CROP`은 source 1 px를 display 1 px로 두고 stage 밖
overflow를 중앙 crop한다.

고정 `LayerOrientation`은 motion과 별도인 base transform이다. 1:1 의미를 흐리지 않도록
`PIXEL_1_TO_1_CROP`은 `FULL_SCREEN`과 non-scaling motion만 허용하며, capacity calibration의
`CAPACITY_TILES`는 FIT/0°만 허용한다. Projection·orientation·crop은 full source
allocation, conservative graphics-memory budget이나 full-buffer traffic estimate를
줄이지 않는다. 일반 FIT 뒤 motion은 추가 transform일 수 있지만
`rotated-resolution-fit-matrix`의 90° parallax/zoom은 현재 letterbox slack과 1.0 이하
zoom으로 제한해 전체 buffer가 계속 보이게 한다.

`LayerSizeProfile`은 source buffer 크기나 producer 수가 아니라 destination footprint를
선택한다. `MotionProfile`과 독립적이므로 small layer도 scroll/zoom/rotate할 수 있다.

| Profile | footprint 계약 | 주요 목적 |
|---|---|---|
| `FULL_SCREEN` | 모든 기존 phase의 기본값, full stage | 회귀 호환성과 최대 overlap |
| `SMALL_UNIFORM` | 모든 child가 동일한 작은 크기 | plane density와 occlusion 감소 |
| `MIXED_SIZES` | index별 small/medium/large 혼합 | scaling·visible-area 조합 |
| `GRADUAL_SMALL_TO_FULL` | phase fraction 0→1에 따라 작은 크기에서 full로 확대 | 점진적인 destination geometry 변화 |
| `ABRUPT_SMALL_FULL` | phase 안의 bounded step에서 small/full 교대 | destination 면적의 순간 변화 |

현재 모델의 결정적 크기 함수는 invalid index/count 또는 non-finite progress에서
full-screen을 반환한다. 이는 malformed input이 graphics budget과 traffic estimate를
작게 보이게 하지 않기 위한 보수적 failure value다.

현재 구현은 `PhaseSpec`, catalog/custom/UI/report까지 typed profile을 전파하고,
renderer가 source buffer를 반복 재할당하지 않은 채 physical child의 destination
scale/translation/crop을 변경한다. Producer generation, topology identity와 readiness는
geometry 표시만으로 가짜 producer를 재게시하지 않는다.

Topology preparation/recovery는 dynamic waveform을 진행시키지 않고 static measured
origin을 고정한다. Prior explicit static origin이 있으면 full/small/mixed profile을
보존한다. 없을 때만 `SMALL_UNIFORM`을 사용하며, 이 geometry는
`GRADUAL_SMALL_TO_FULL`과 `ABRUPT_SMALL_FULL`의 fraction-zero와 동등하지만 active
profile 자체의 apply acknowledgment는 아니다. 다만 같은 producer generation에서
matching `SMALL_UNIFORM` applied acknowledgment가 확인되면 controller는 geometry
동등성을 근거로 target dynamic profile의 origin coverage bit 하나만 seed한다.
Mid/end 또는 abrupt의 나머지 step coverage는 대신하지 않는다. Dynamic progress의
authority는 controller가 pause를
제외해 계산한 `phaseElapsedMs`다. Renderer는 preparation/recovery와 producer generation
rebuild에서 이 frozen elapsed에 다시 anchor하므로 준비 시간이 waveform을 소비하거나
rebuild가 0부터 재시작하지 않는다.

Allocation route가 바뀌면 target backend/pixel/buffer route를 discrete하게 준비하면서도
active transition이 시작되기 전 measured origin의 size profile을 유지한다. Fresh
baseline과 origin producer readiness 뒤 첫 active tick에서 cyclic fraction이 0이어도
target size profile을 arm한다. 한 번 arm되면 continuous FPS/workload가 `PULSE_BURST` 또는
`TRIANGLE_WAVE` valley로 내려가도 size profile은 origin으로 되돌아가지 않는다.

Duration cap 뒤 `GRADUAL_SMALL_TO_FULL`은 최소 2×100 ms,
`ABRUPT_SMALL_FULL`은 8 step 전체를 위한 8×100 ms를 확보해야 한다. 이 window가
부족하면 safety policy가 reject한다.
Dynamic transform은 producer FPS가 낮더라도 최대 100 ms마다 적용하고, fraction 1
terminal sample은 interval과 무관하게 강제로 적용한다.

실제 base geometry apply는 generation 안의 bounded revision을 요청한다. 이후 두 번의
`Choreographer` callback/traversal opportunity 뒤 matching revision과 profile을
acknowledge한다. Pending revision 동안 base size는 last-applied fraction에 고정되지만
controller의 pause-aware desired clock은 계속 진행한다. ACK 뒤에는 누적된 중간
fraction을 재생하지 않고 최신 desired 하나만 적용한다. Gradual revision key는
origin/mid/exact endpoint의 semantic 3개이고 abrupt는 8개 step을 유지한다. 이 규칙은
2-frame ACK와 최소 200 ms gradual window에서도 30/60/120 fps required coverage를
보존한다.

Producer activation과 typed HWC target arm은 matching revision/profile을 기다린다.
이는 app-side destination transform apply evidence이지 physical HWC composition
evidence가 아니다. Gradual은 matching preparation-equivalent origin bit와 active
profile acknowledgment를 합쳐 origin/mid/end, abrupt는 8개 step 전체가 coverage에
있어야 한다. 누락은 `LAYER_SIZE_COVERAGE_MISSING` event와
`INCONCLUSIVE`, 충족은 `LAYER_SIZE_COVERAGE` event로 기록한다.

Centered scale-aware horizontal stagger는 narrow stage에서도 각 layer가 최소 1 px
보이도록 translation을 clamp한다.

`LayerTrafficEstimator`의 linear full-buffer 모델은 crop/occlusion/partial update를
제외한다. footprint가 작다는 이유로 producer allocation traffic을 자동 축소하지 않는다.
HUD는 일반 phase에서 `LayerSizeProfile` base scale만 합한 destination area를
screen-equivalent와 physical producer당 평균 `%`로 별도 표시한다. `CAPACITY_TILES`는
명시적인 예외로 crop union 합계 1 screen-equivalent와 평균 `100 / producer count`%를
표시한다. 이 값은 다른 `MotionProfile` scale, overlap/clipping/crop/rotation과
off-screen loss를 포함하지 않는다.

## Motion 의미

- `STATIC`: 추가 View transform 없음
- `SCROLL`, `ZOOM_PAN`, `ROTATE`, `PARALLAX`, `TRANSFORM_STORM`: app View transform
- `Z_ORDER_SWAP`: `translationZ` 기반 client ordering proxy
- `CAPACITY_TILES`: process-session HWC capacity one-shot candidate 전용 non-overlap crop

`Z_ORDER_SWAP`은 physical HWC plane Z-order가 바뀌었다는 증거가 아니다. report의
`physicalHwcZOrderChange`는 계속 `false`다.

## Transition

`TransitionSpec`은 phase 사이의 layer, FPS, requested Hz와 workload 연속 값 envelope다.
allocation route, backend, pixel format, layer-size의 discrete contract를 보간하지 않는다.

| Mode | 의미 | 최소 관측 계약 |
|---|---|---|
| `STEP` | fresh origin 뒤 target 즉시 적용 | measured active target tick |
| `LINEAR_RAMP` | origin→target 선형 | origin, 중간값, target |
| `STAIRCASE` | bounded level 순차 적용 | 모든 level |
| `PULSE_BURST` | origin/target duty cycle 반복 | ON과 OFF |
| `TRIANGLE_WAVE` | 상승·하강 반복 | 상승과 하강 |
| `SOAK_RECOVERY` | attack, hold, recovery | attack 2 tick, hold 1 tick, recovery 2 tick |

control cadence는 100 ms다. duration cap 반영 뒤 이 의미를 보존할 수 없으면 phase를
짧게 축소해 실행하지 않고 거부한다. `floor`는 pulse/triangle valley에만 허용한다.

Whole-phase `LINEAR_RAMP`는 nominal phase deadline에서 exact target을 새
`producerControlRevision`으로 한 번 게시하고, committed physical producer 전부가 그
revision의 fresh frame을 낼 때까지 bounded proof hold를 사용한다. Recovery가 끼면
stale endpoint evidence를 폐기하고 fresh first buffer 뒤 더 큰 revision으로 재arm한다.
Mismatch/timeout은 `INCONCLUSIVE`다. Endpoint apply 직전 한 번 샘플한 observed
publication boundary에서 actual/expected fidelity window를 함께 seal하고 이후 proof
hold frame은 제외한다.

`LoadShape`은 CPU/memory/NPU worker의 phase 내부 legacy modulation ABI다.
`TransitionSpec`과 역할이 다르며 둘을 같은 필드로 합치지 않는다.

## HWC composition expectation

### `DEVICE_ONLY`

- opaque RGB independent Surface와 보수적 topology로 DEVICE 유지 가능성을 관찰한다.
- 강제/보장 계약이 아니다.
- 최소 12초, first-buffer readiness, pre-target mutex drain, fresh matching evidence와
  post-target tick이 필요하다.
- fresh pair가 없거나 CLIENT가 존재하면 `INCONCLUSIVE`다.

### `CLIENT_REQUIRED`

- mixed/Texture/alpha/GL/plane pressure로 CLIENT 경로 전환을 관찰한다.
- GPU 합성을 강제로 지정하는 API가 아니다.
- 최소 16초와 서로 다른 fresh matching evidence 두 번이 필요하다.
- target에서 CLIENT>0을 확인하지 못하면 `INCONCLUSIVE`다.

runtime clamp가 typed phase의 layer topology, producer FPS, display pacing, GL producer나
GPU pressure를 바꾸면 다른 실험으로 낮춰 실행하지 않고 reject한다.
Fresh evidence가 같은 target geometry를 나타내야 하므로
`GRADUAL_SMALL_TO_FULL`/`ABRUPT_SMALL_FULL` dynamic profile을 결합한 typed phase도
reject한다.

실행 HUD의 `HWC APP RAW · D/C/T · AGE · SRC`는 위 controller 계약의 입력이 될 수
있는 현재 atomic tuple을 보여줄 뿐이다. `T`는 같은 tuple의 `D+C`이고, 현재
portable/vendor 계약은 Activity root/control과 committed workload producer를
per-layer identity로 분리하지 않는다. 따라서 raw D/C/T가 matching target에서
관측됐다는 사실을 producer별 assignment나 plane ceiling으로 바꾸어 해석하지 않는다.
그 주장은 display/CRTC scope와 committed producer identity에 결속된 BSP evidence가
추가된 뒤에만 가능하다.

## Catalog 목적별 지도

현재 source candidate의 catalog는 36개 preset이며 Custom은 이 수에 포함하지 않는다.

### Baseline, DVFS와 DPU burst

| ID | 핵심 입력 변화 | 해석 |
|---|---|---|
| `baseline-display-modes` | 1L RGB, 60→90→120 Hz | display pacing 기준선 |
| `dvfs-single-layer-wake` | 긴 1L 저부하 뒤 같은 Surface의 FPS/Hz/transform STEP | clock ramp 지연 관찰; clock 강제 아님 |
| `dvfs-composition-shock` | settle 뒤 HWC-friendly, alpha/client, DRAM+3D shock | 단계별 복합 ramp |
| `dpu-device-envelope-burst` | 1L/30fps→opaque RGB 4L/120fps/120Hz | 보수적 DEVICE candidate |
| `dpu-client-fallback-burst` | 1L/30fps→20L mixed/alpha/GL 120fps | CLIENT fallback candidate |
| `dpu-only-repeat-shock` | generated cross-load 0, 1L/30fps↔12L/120fps 반복 | display-pipeline 급변; DPU 단일축 아님 |

4L이나 20L은 제품의 보편적 HWC 한계가 아니다. Process-session capacity calibration은
20L를 요청하지만 safety/graphics budget이 actual candidate를 줄일 수 있다. 최초
terminal 결과를 이후 scenario/repeat/START가 재사용하더라도 matching opaque RGB
DISPLAY tile topology의 advisory boundary일 뿐이며 catalog target, safety cap 또는 typed
phase evidence를 바꾸지 않는다. Raw D/C/T에는 Activity root/control 분리가 없으므로
candidate producer 수와 raw D 또는 T의 차이를 이용해 producer ceiling을 추론하지
않는다.

`dpu-only-repeat-shock`는 automation 호환성을 위해 stable ID를 유지하지만
사용자-facing 이름은 `Display-pipeline Repeated Step Shock`이다. `workloads=0`은
명시적 CPU/memory/GPU/NPU generator가 idle이라는 뜻이다. 각 Canvas producer의 draw,
buffer post와 memory write는 producer FPS에 따라 계속되므로 이 preset을 DPU-only,
GPU/CPU/DRAM-free 또는 단일 원인 실험으로 설명하면 안 된다.

### Layer size profile

| ID | Profile·topology | 목적 |
|---|---|---|
| `small-layer-density` | full 1L→small 12L→small 20L | 작은 visible area에서 producer/plane density |
| `mixed-layer-size-matrix` | 10L small→mixed→full→mixed | layer/FPS를 고정한 size A/B |
| `gradual-layer-size-expansion` | 8L `GRADUAL_SMALL_TO_FULL` | destination 면적의 느린 증가 |
| `abrupt-layer-size-toggle` | 8L `ABRUPT_SMALL_FULL` 반복 | producer 수를 고정한 면적 shock |
| `layer-size-fps-burst` | small 1L/30fps→14L/120fps abrupt, 이후 18L mixed | size+layer+FPS 동시 STEP |
| `layer-size-device-candidate` | small 1L→mixed/small 4L, `DEVICE_ONLY` | 크기 분포가 있는 DEVICE candidate |
| `layer-size-client-pressure` | small 1L DEVICE→20L mixed/alpha/GL, abrupt/mixed | 크기 분포가 있는 CLIENT pressure |

마지막 두 preset의 최종 판정은 fresh vendor DEVICE/CLIENT 원자 쌍이 없으면
`INCONCLUSIVE`다.

### HWC와 transform

| ID | 핵심 |
|---|---|
| `plane-staircase` | opaque independent Surface 1→2→4→6→8→12L bounded sweep |
| `composition-pivot` | content와 pacing을 고정하고 independent→mixed→flattened backend 전환 |
| `transform-storm` | 12L zoom/scroll/rotate/parallax와 View/client Z proxy |
| `mid-load-perturbation` | 4~8L, 60~90fps의 A/B/A 중간 부하 matrix |
| `rotated-resolution-fit-matrix` | 2K/4K/8K 고정 90° FIT; 8K static/parallax/bounded zoom |
| `8k-presentation-fit-crop-aba` | 같은 8K allocation에서 FIT→1:1 crop→FIT projection-only A/B/A |

### Video, format와 compression

| ID | 요구사항 |
|---|---|
| `dvfs-video-shock` | 검증된 4K media와 concrete hardware decoder |
| `4k-mixed` | 4K decoder Surface + RGB overlay + memory pulse |
| `8k-decoder-pressure` | 8K30 metadata와 size/rate를 지원하는 hardware decoder |
| `8k60-p010-pressure` | 8K60 10-bit P010 fingerprint와 hardware decoder |
| `sbwc-matrix` | 동일 decoder content와 vendor SBWC route acknowledgment |
| `resolution-only-sweep` | 1L/30fps/60Hz/FIT/0°/static/zero-load 고정, 1K→8K→1K resolution-only A/B |

YUV/P010/SBWC phase는 procedural RGBA로 대체하지 않는다. selected media의 URI,
descriptor, dimensions, FPS, MIME, profile, codec name과 P010 fingerprint를 preflight와
renderer에서 재검증한다.

### Pacing, resource와 transition

| ID | 핵심 |
|---|---|
| `mixed-pacing` | producer FPS와 display Hz의 비동일 조합 |
| `resource-pulse` | 고정 7L topology에서 CPU/memory/GPU를 한 축씩 pulse |
| `instant-isolated-contention` | 고정 8L topology에서 cross-load 즉시 ON/OFF |
| `instant-burst-transitions` | layer/FPS STEP 뒤 contention duty cycle |
| `gradual-load-transitions` | topology와 cross-load의 combined ramp/staircase |
| `continuous-crossload-ramp` | 고정 8L topology에서 cross-load 0→high→hold→0 |
| `resolution-load-sweep` | 1K→2K→4K→8K→4K→2K→1K와 cross-load 상승·감소 |
| `wave-soak-recovery` | triangle 반복과 attack/hold/release |
| `npu-cross-load` | vendor NPU + memory/GPU; adapter 없으면 `UNSUPPORTED` |
| `adaptive-underrun-hunt` | layer/backend/alpha/memory 다축 staircase |
| `mixed-soak` | transform/pacing/cross-load 장시간 회귀 |

다축 scenario는 경계를 찾는 데 유용하지만 단일 원인의 증거로 해석하지 않는다.

## Catalog facet

facet은 같은 행의 여러 값이 OR, 서로 다른 행이 AND다.

- Category: `LAYER_HWC`, `TRANSFORM`, `VIDEO_FORMAT`, `REFRESH`, `RESOURCE`,
  `TRANSITION`, `MIXED`, `ADAPTIVE`, `SOAK`
- Pattern: 순간 STEP, 느린 점진, 반복/펄스, 고정 유지
- Estimated load band: 낮음, 보통, 높음, 매우 높음
- Condition: display-only, multi-layer, CPU/memory/GPU/NPU, video/format,
  1K/2K/4K/8K, 1:1 crop, 고정 90°, transform/high refresh/DVFS, DPU burst,
  DEVICE/CLIENT 목표, layer size

intensity score는 catalog 비교와 UI 탐색용 추정치다. 실제 HW capacity 또는 위험 판정이
아니다. layer-size score도 visible-area heuristic이며 safety memory budget을 줄이지 않는다.

## 목적 중심 빠른 선택

UI의 빠른 목적은 다음 질문에 대응한다.

- **급격한 DPU 부하:** layer/FPS/Hz가 낮은 기준선에서 STEP으로 커지는가?
- **DEVICE 후보 유지:** 보수적 opaque independent topology에서 DEVICE-only fresh evidence가
  유지되는가?
- **CLIENT 전환 목표:** mixed/alpha/GL pressure 뒤 CLIENT>0 fresh evidence가 반복되는가?

각 card는 “입력 변화”, “합성 목표”, “확인할 metric”을 실행 전에 보여야 한다.
`RAW MATCH/WAIT/N/A`와 `HWC APP RAW D/C/T`는 2.5초 이내 동일
source/quality/timestamp pair의 보조 표시이며 controller의 phase coverage verdict를
대신하지 않는다. Pure Compose HUD는 별도 Surface를 추가하지 않지만 Activity
root/window layer의 HWC assignment를 강제하지도 않으며, 현재 raw tuple에서 이를
workload producer와 분리하지 못한다.

## Custom scenario

Custom builder도 catalog와 같은 `ScenarioSafetyPolicy`를 통과한다.

- layer 1..20
- producer FPS 최대 120
- requested display 최대 240 Hz
- load는 정확한 0 또는 `0.001` 초과
- `FLATTENED_TEXTURE`는 DISPLAY/RGB_8888 단일 producer로 명시적 정규화
- positive GPU load에는 실제 GPU-backed producer 필요
- decoder route에는 선택·검증된 media와 concrete codec binding 필요
- graphics budget이 맞지 않으면 silent clamp 대신 reject될 수 있음
- buffer projection은 FIT/1:1 crop, 고정 orientation은 0°/90° 중 선택
- 1:1 crop은 `FULL_SCREEN`과 non-scaling motion만 허용

Custom ID는 process 내에서 고유하게 생성되며 외부 Intent automation에서는 허용되지 않는다.

## 신규 scenario 작성 checklist

1. ID와 phase ID가 bounded·유일한가?
2. 하나의 실험 질문을 설명하고 비교 기준선/recovery가 있는가?
3. 순간 변화인지 점진 변화인지 transition coverage가 가능한가?
4. discrete route 변경 전에 load zero→teardown→vendor route 순서를 지킬 수 있는가?
5. typed HWC expectation의 최소 시간·evidence 수를 충족하는가?
6. media/NPU/SBWC requirement를 proxy로 대체하지 않는가?
7. graphics memory와 producer cap을 통과하는가?
8. expected physical producer 수와 `FLATTENED_TEXTURE` count 1 의미가 맞는가?
9. classifier facet과 목적 UI에 올바르게 노출되는가?
10. catalog, safety, classifier, controller coverage test와 문서를 갱신했는가?

검증 명령과 test map은 [TESTING.md](TESTING.md)를 따른다.
