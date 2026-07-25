# Build와 검증

> **Authority:** host build/test 명령, invariant-to-test 지도, fault/boundary 검증과 승인된 실기기 절차
> **Audience:** 개발자, reviewer, CI maintainer, BSP validation 담당자
> **Update when:** toolchain, test suite, acceptance gate, device 검증 protocol 또는 산출물 경로가 바뀔 때
> **Does not own:** 안전 불변식의 원문, 릴리스 게시, scenario·metric 의미, BSP provider 구현
> **Related:** [Documentation index](INDEX.md), [AGENTS.md](../AGENTS.md), [PLAN.md](../PLAN.md),
> [ARCHITECTURE.md](../ARCHITECTURE.md), [SCENARIOS.md](SCENARIOS.md),
> [METRICS.md](METRICS.md), [REPORT_SCHEMA.md](REPORT_SCHEMA.md),
> [HWC_CAPACITY_CALIBRATION.md](HWC_CAPACITY_CALIBRATION.md),
> [AUTOMATION.md](AUTOMATION.md), [UI_SPEC.md](UI_SPEC.md), [RELEASE.md](RELEASE.md),
> [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)

테스트 개수는 변동 값이므로 이 문서에 고정하지 않는다. 현재 suite는 source와 Gradle
report에서 확인한다.

```powershell
Get-ChildItem app/src/test -Recurse -Filter *Test.kt |
    Sort-Object FullName |
    Select-Object -ExpandProperty FullName

Get-ChildItem app/build/test-results/testDebugUnitTest -Filter *.xml -ErrorAction SilentlyContinue
```

릴리스 시점의 고정 검증 수치는 [RELEASE.md](RELEASE.md)의 해당 release evidence에만
기록한다.

## 기준 환경

| 항목 | 기준 |
|---|---|
| JDK | 17 |
| Android compile/target SDK | 36 |
| min SDK | 29 |
| Android Gradle Plugin | 8.12.2 |
| Gradle wrapper | 8.13 |
| Kotlin plugin | 1.9.0 |
| Android Studio | Narwhal Feature Drop 2025.1.2 이상 또는 AGP 8.12 지원 버전 |

환경별 JDK/SDK 절대 경로를 tracked Gradle 파일에 넣지 않는다. `local.properties`는
gitignored다.

## 표준 host gate

PowerShell 예:

```powershell
$env:JAVA_HOME='<JDK_17_HOME>'
$env:ANDROID_HOME='<ANDROID_SDK_ROOT>'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

일반 기능 수정의 최소 gate:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

release 요청이 없으면 `assembleRelease`를 무조건 실행할 필요는 없지만, manifest,
permission, build type, shrink/proguard 또는 release artifact 의미를 바꿨다면 실행한다.

### 산출물

| Gate | 확인 위치 |
|---|---|
| unit XML | `app/build/test-results/testDebugUnitTest/` |
| unit HTML | `app/build/reports/tests/testDebugUnitTest/index.html` |
| lint report | `app/build/reports/lint-results-debug.html` |
| debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| unsigned release APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |

Gradle task exit code가 0이어도 요청한 산출물이 실제 존재하는지 확인한다.

## Android Studio

VCS-shared configuration:

- `DPULayerTest - Debug APK` → `:app:assembleDebug`
- `DPULayerTest - Release APK (unsigned)` → `:app:assembleRelease`

파일:

- `.idea/runConfigurations/DPULayerTest_Debug.xml`
- `.idea/runConfigurations/DPULayerTest_Release.xml`

release configuration에 local keystore, platform key 경로나 credential을 추가하지 않는다.

## Suite 지도

### 모델과 policy

| Test | 계약 |
|---|---|
| `model/LabModelsTest.kt` | model normalization, duration, terminal reason, dynamic size coverage mask |
| `model/ScenarioPlanPolicyTest.kt` | UI 400/external 40 source cap, whole-queue repeat, 지원 시간 배율, overflow, immutable single materialization과 duration safety cap |
| `model/ScenarioQueueEditorTest.kt` | duplicate, order, move, unknown restore |
| `model/ScenarioSafetyPolicyTest.kt` | hard cap, negative/sub-effective load reject, duration, graphics budget, typed phase, FIT/1:1/90°와 capacity-tile 조합 |
| `model/ScenarioClassifierTest.kt` | facet OR/AND, 1K/2K/1:1/90° 조건과 intensity |
| `model/LoadShapeEvaluatorTest.kt` | worker modulation |
| `model/LoadTransitionEvaluatorTest.kt` | STEP/ramp/stair/pulse/triangle/soak |
| `model/LayerTrafficEstimatorTest.kt` | format/dimensions/linear traffic와 N/A |

### Catalog와 controller

| Test | 계약 |
|---|---|
| `engine/ScenarioCatalogTest.kt` | 36개 preset uniqueness/validity, 1K↔8K, 2K/4K/8K 90° FIT, resolution-only와 FIT/crop A/B/A |
| `engine/LabControllerMathTest.kt` | verdict, telemetry/HWC coverage, session-calibration deadline, projection/orientation origin·topology·HWC contract, atomic error notice, size-profile arm·coverage, terminal linear endpoint revision/timeout/recovery/fidelity seal, cleanup fatal precedence와 media-worker fatal relay |
| `engine/HwcCapacityCalibrationSessionTest.kt` | process one-shot claim/reuse, terminal N/A, Activity recreation scope와 display projection invalidation |
| `engine/DeviceRenderSafetyTest.kt` | RAM/power-save/low-RAM envelope |
| `engine/AutomationIntentContractTest.kt` | explicit action, malformed extras, STOP ordering |
| `engine/TestWindowIsolationTest.kt` | token/state/focus/restore |
| `engine/PerformanceEnvironmentTest.kt` | Battery Saver ticket/restore/session |

### Renderer와 producer

| Test | 계약 |
|---|---|
| `render/LayerStageViewMathTest.kt` | FIT/1:1 scale, 90° source-axis swap, fit translation clamp, geometry function, controller elapsed re-anchor, dynamic cadence/final sample, narrow-stage visibility |
| `render/ProducerFrameRelayTest.kt` | generation·producer ID·control revision 2-phase relay와 decoder epoch+PTS queue/barrier |
| `render/ProducerFrameCommitTest.kt` | callback/native draw failure의 worker revoke, cleanup와 VM fatal 원본 전파 |
| `render/RendererSafetyStateTest.kt` | process-wide teardown latch |
| `render/RendererRecoveryPolicyTest.kt` | hand-off/recovery deadline |
| `render/CanvasComplexityTest.kt` | flattened GPU/canvas work mapping |
| `render/CodecLoopBackoffTest.kt` | bounded codec dequeue/backoff |
| `render/VideoDecoderSelectionTest.kt` | immutable media/codec fingerprint, bounded descriptor close와 cleanup VM-fatal precedence |
| `render/MediaSampleFlagsTest.kt` | codec input/sample flag |

### Local load와 lifecycle

| Test | 계약 |
|---|---|
| `engine/LoadThreadStartTest.kt` | partial thread start rollback과 cleanup fatal 우선순위 |
| `engine/LoadManagerPrewarmTest.kt` | allocation/page-touch/ack/cancel |
| `engine/LoadManagerNpuControlTest.kt` | latest-wins ticket와 ordered zero |
| `engine/LoadSafetyStateTest.kt` | sticky local/NPU failure와 partial-start OOME cleanup 후 원본 재전파 |
| `engine/ActivityFreeCompletionGroupTest.kt` | Activity-free job completion |
| `engine/ControllerBackendCleanupTest.kt` | backend shutdown order |
| `engine/ControllerBackendCleanupCoordinatorTest.kt` | process cleanup gate와 OOME/VM-fatal/ThreadDeath 원본 재전파 |

### Telemetry와 vendor

| Test | 계약 |
|---|---|
| `monitor/SystemMonitorMathTest.kt` | source merge, interval, HWC evidence와 one-shot vendor-prefetch/SF-fallback 선택 |
| `monitor/SystemMonitorConstructionTest.kt` | transactional construction rollback |
| `monitor/KernelSensorProviderTest.kt` | typed ABI parse, Xclipse/KGSL/GED, units, key별 sysfs allowlist와 exact underrun default |
| `monitor/LongTimestampMapTest.kt` | 20-producer timestamp map과 expansion OOME 원자성 |
| `monitor/SurfaceFlingerProbeTest.kt` | bounded dump parse/lifecycle, worker·child all-action cleanup과 fatal 원본 재전파 |
| `monitor/ProducerGenerationGateTest.kt` | fresh generation count, 같은 ID의 physical producer 재생성 뒤 fresh-buffer 강제와 generation-scoped geometry revision/profile acknowledgment |
| `monitor/CapabilityScannerTest.kt` | display/codec capability projection |
| `vendor/VendorBridgeStateTest.kt` | API version, session, lane, NPU/SBWC/power restore, broker permission/signer trust와 fatal rollback |

### UI, version과 report

| Test | 계약 |
|---|---|
| `ui/DpuLayerLabAppMathTest.kt`, `engine/LabControllerMathTest.kt` | 목적/facet/preview, bounded queue, whole-loop repeat/time 배율 복원, 400-run/장시간 format, decoder-media 노출, topology-pending 즉시 `—P`와 atomic notice |
| `ui/RendererContainerRememberOwnerTest.kt` | Compose renderer owner identity |
| `MainActivityMathTest.kt` | display/window/automation, Battery Saver 전용→일반 settings 순서와 cleanup defer |
| `engine/ReportWriterMathTest.kt` | projection/orientation/effective duration schema, provenance, 400 retention, atomic naming helper |
| `AppVersionTest.kt` | versionName/versionCode 계약 |

## Process-session HWC capacity 검증

Capacity 변경은 다음 경계를 별도로 검증해야 한다.

- 최초 START만 measurement claim을 얻고 같은 process의 이후 scenario/repeat/START는
  terminal result를 재사용
- Activity 재생성 뒤 새 controller도 같은 in-memory result를 재사용
- 성공뿐 아니라 timeout, 취소, topology/sample 실패도 terminal `UNAVAILABLE`이며 두
  번째 20-layer burst를 만들지 않음
- SharedPreferences, file/report 또는 다른 disk state에서 calibration을 복원하지 않음
- requested topology가 20L/30fps/60Hz independent opaque RGB DISPLAY
  `CAPACITY_TILES`이고 actual candidate가 safety/graphics budget에 따라 줄 수 있음
- UI/event/report가 requested 20과 actual candidate를 혼동하지 않음
- safety clamp 뒤 renderer target handoff 이후 취소는 실제 candidate를 유지하고 그 전
  실패는 candidate N/A
- topology readiness, 100ms stabilization과 single sample 전체가 하나의 absolute
  6000ms producer-active deadline을 공유
- deadline 직전 readiness poll은 stabilization+snapshot reserve를 침범하지 않고,
  stabilization 전체 budget이 없으면 target을 즉시 null
- sample 전후 topology/geometry revision과 discontinuity serial이 같고 모든 producer
  heartbeat가 fresh일 때만 observation 수락
- 모든 terminal path에서 load zero, renderer teardown과 counter drain을 확인하고
  cleanup-confirmed non-cancelled 경로에서만 cancellable 3000ms settle 뒤 scenario로
  진행하며 STOP/cancel은 settle을 기다리지 않고 terminal `UNAVAILABLE`로 one-shot을
  닫아 두 번째 20L burst를 만들지 않음
- safety-clamped 6/12/16L의 partial final tile row도 non-overlap 상태로 전체 stage
  crop union을 덮고 HUD 평균이 `100 / actual producer count`와 일치
- final producer teardown 뒤 calibration frame/generated-traffic counter를 drain해 첫
  scenario baseline/peak와 분리
- priority 획득 뒤 기존 periodic local sample, SurfaceFlinger worker/child, vendor v1/v2
  lane을 drain한 후 producer 시작
- timeout/STOP 뒤 post-sample worker quiescence가 확인되기 전 priority/첫 scenario를
  해제하지 않고, 미확인은 process-sticky lifecycle failure
- watchdog pause가 last-success timestamp를 변경하지 않으며 resume grace 안 실제
  telemetry가 없으면 stale abort
- producer readiness 대기에서 direct thermal/power/low-memory check가 100ms cadence로
  유지되고 pre-drain/composition sample에는 별도 병렬 poll을 만들지 않음
- calibration sample은 vendor snapshot을 최대 한 번만 prefetch하고 current-session
  nonnegative atomic D/C pair이면 SurfaceFlinger를 실행하지 않음
- calibration은 optional vendor v2 transaction을 생략하고, v1 실패/partial 뒤 actual
  vendor-worker quiescence가 확인되지 않으면 SF fallback을 시작하지 않음
- capability getter는 v1 exact telemetry와 분리된 no-backlog lane의 single total
  deadline을 사용하고 late/stale-session 결과를 폐기하며 첫 getter timeout 뒤 두 번째
  getter를 시작하지 않음
- capability deadline의 signed `nanoTime` wrap과 worker-return 직전 transient
  `SynchronousQueue` rejection에서 하나의 deferred refresh가 보존됨
- 첫 capability getter 도중 service-session이 바뀌면 stale Binder의 두 번째 getter를
  시작하지 않고, Handler/executor admission fatal은 active/timeout rollback 뒤 재전파됨
- calibration capability admission token이 delayed retry/service callback을 post-drain
  release까지 하나로 defer하고 stuck capability call은 pre/post barrier를 실패시킴
- calibration final barrier/capability release가 `Error`를 던져도 watchdog/priority/
  claim cleanup을 끝낸 뒤 fatal을 재전파하며, thermal NPU apply의 cancellation/fatal도
  일반 adapter 실패로 삼키지 않음
- vendor pair null/partial/negative/session mismatch에서만 SurfaceFlinger fallback 한 번,
  fallback 뒤 vendor snapshot 재호출 없음
- display ID 또는 normalized physical short/long edge 변경은 projection을 N/A로 만들고
  같은 process에서 새 claim을 발급하지 않음
- width/height만 바뀐 orientation swap은 같은 normalized scope로 재사용
- result가 advisory-only이며 ScenarioSafetyPolicy cap, catalog target, typed phase
  evidence로 사용되지 않음
- 각 scenario report event가 `SESSION_HWC_CAPACITY_CALIBRATION`과
  `SESSION_HWC_CAPACITY_REUSE_GUIDANCE` 이름을 사용

## LayerSizeProfile 검증

크기 profile 변경은 다음 boundary를 별도로 검증해야 한다.

- 기존 phase와 custom default가 `FULL_SCREEN`
- `SMALL_UNIFORM`의 index-independent bounded scale
- `MIXED_SIZES`의 결정적 index cycle
- `GRADUAL_SMALL_TO_FULL`의 0, midpoint, 1과 clamped progress
- `ABRUPT_SMALL_FULL`의 bounded small/full step
- duration cap 뒤 gradual 2×100 ms, abrupt 8×100 ms 미만 reject
- invalid index/count와 `NaN`, positive/negative infinity가 full-screen
- `LayerStageView`가 destination footprint와 기존 motion을 함께 적용
- size-only phase가 불필요하게 physical topology를 교체하지 않음
- topology preparation/recovery가 prior explicit full/small/mixed measured origin을
  보존하고, 없을 때만 dynamic fraction-zero와 동등한 `SMALL_UNIFORM`을 사용
- 같은 generation의 matching `SMALL_UNIFORM` applied acknowledgment만 target dynamic
  profile의 origin coverage bit 하나를 equivalent evidence로 seed하며, active profile
  자체의 apply 증거나 mid/end/다른 abrupt step을 대신하지 않음
- controller-owned pause-aware `phaseElapsedMs` re-anchor가 preparation/recovery 및
  generation rebuild 뒤에도 progress를 이어감
- allocation route preparation이 target route와 measured size origin edge를 함께 보존
- fresh baseline과 origin readiness 뒤 첫 active cyclic fraction 0에서 target profile을
  arm하고 이후 pulse/triangle valley에서 origin으로 되돌아가지 않음
- dynamic transform이 producer FPS와 무관하게 최대 100 ms cadence이고 final fraction
  1 sample을 강제
- 실제 base geometry apply만 revision을 request하고 두 번의 후속 Choreographer
  callback/traversal opportunity 뒤 matching revision/profile을 acknowledge
- pending revision의 2-frame ACK 동안 last-applied base-size fraction은 고정되지만
  controller desired clock은 진행하며, ACK 뒤 stale intermediate가 아닌 latest
  fraction 하나만 적용
- gradual revision key는 origin/mid/exact endpoint 3개, abrupt는 8 step이고 최소
  200 ms gradual window가 30/60/120 fps 모두에서 origin/mid/endpoint coverage를 보존
- producer activation과 typed HWC arm이 matching geometry revision/profile 없이는
  진행하지 않으며, 이 evidence를 physical HWC proof로 해석하지 않음
- `topologyMissed`, `teardownFailed`, `teardownCompleted`가 readiness와
  geometry/typed-HWC evidence를 무효화하고 이전 acknowledgment를 재사용하지 않음
- miss/failure는 새 generation 없이는 복구하지 않으며 clean reattach도 topology
  pending, 새 geometry, topology publish, activation, fresh buffers와 fresh HWC
  observation 전에는 ready가 되지 않음
- gradual origin/mid/end와 abrupt 8 step applied coverage 누락이
  `LAYER_SIZE_COVERAGE_MISSING`/`INCONCLUSIVE`, 충족이 `LAYER_SIZE_COVERAGE`
- narrow-stage centered scale-aware stagger가 각 layer의 수평 최소 1 px visibility 보존
- typed `DEVICE_ONLY`/`CLIENT_REQUIRED`와 dynamic size profile 조합은 reject
- UI card, queue, HUD와 result/report가 같은 enum 의미를 표시
- 일반 destination screen-equivalent와 producer당 평균 `%`가 base profile/fraction을
  따르고 `MotionProfile` scale, overlap, crop/clipping을 포함하지 않음
- `CAPACITY_TILES`는 base profile 대신 crop-union 1 screen-equivalent와
  평균 `100 / producer count`%, HUD는 estimator scope label을 그대로 표시
- footprint가 full-buffer DPU read/producer write estimate를 줄이지 않음
- physical producer count와 graphics budget이 visible footprint로 축소되지 않음

관련 source를 추가했는데 위 test가 없다면 `LayerSizeProfile`은 완료로 판정하지 않는다.

## Fault와 corner-case matrix

### 입력

- empty/oversized/non-finite scenario field
- `0 < load <= 0.001`
- phase와 total duration overflow
- unsupported duration multiplier, 중복 materialization과 100× 뒤 duration cap
- UI 40×10=400 whole-queue loop와 외부 40-run 분리
- 1:1 + scaling motion/dynamic size reject, capacity tiles + crop/90° reject
- typed HWC minimum duration 부족
- malformed START extra와 oversized queue
- restored unknown preset ID

### Resource

- RAM total/available invalid, lowMemory, allocation OOM
- CPU/memory worker partial start, unexpected exception, memory drop 뒤 NPU zero 예외
- NPU apply timeout, rejected ticket, session change, stale positive waveform, zero/close 실패
- pulse/triangle NPU positive→zero→positive semantic edge마다 matching ticket/ACK와
  health를 요구하고 ACK 전 coverage를 거부
- adapter release가 manager의 zero request를 supersede한 뒤 동일 zero semantic apply가
  CPU/memory restart 없이 fresh ticket을 발행하는지 확인
- triangle zero-origin full-cycle `999→1001 ms`와 zero-target half-cycle
  `249→251 ms` jitter crossing, 각 terminal zero, nonzero floor/no-zero와 no-crossing을
  구분하고 여러 누락 zero boundary를 replay하지 않는지 확인
- codec capability mismatch, descriptor open/parse timeout, provider/parser worker fatal의 owning
  coroutine identity relay
- GL/Canvas/codec teardown timeout과 commit failure
- Canvas/Texture/Video/GL Surface destroy/recreate의 pending → geometry ACK → forced
  expected-set republish와 stale first-buffer/HWC evidence 거부
- renderer thread-start callback의 notification/cleanup 부분 실패에서도 detach,
  interrupt/quit/join/owner clear 전부 실행, ordinary rollback failure 전파
- decoder requestStop/release/start-failure의 action별 예외와 두 thread shared-deadline
  join, callback fatal identity 보존
- child transaction owner storage 선할당과 registration 경계 failure
- frame/deferred post 거부의 topology rollback, expected callback의 configure/release
  재진입과 stale publication bookkeeping 거부
- lifecycle stage owner 복수 identity, bounded cap과 cleanup watcher failure sticky 처리
- timestamp map 두 번째 backing-array OOME에서 기존 map 보존
- VM fatal cleanup 뒤 원본 fatal identity 보존
- 20-relay control-token prepare 중 OOME에서 기존 binding 전부 보존
- prepared token 뒤 rebind의 stale commit 거부와 commit fatal의 전 relay rollback

### Transition endpoint

- whole-phase linear endpoint를 nominal deadline에 한 번 게시
- committed physical producer 전부의 exact control revision ACK
- old generation/revision, pre-activation frame과 partial ACK 거부
- topology recovery에서 stale 증거 폐기, fresh buffer 뒤 revision 증가 재arm
- revision mismatch/timeout을 `INCONCLUSIVE`로 분류
- endpoint apply 전 같은 observed boundary에서 actual/expected seal, delayed tick 대칭성
- endpoint proof hold frame을 producer-fidelity에서 제외
- decoder duplicate PTS, submit 실패 exact epoch/PTS/identity rollback
- EOS listener disable과 flush 전후 reusable callback barrier, clear+epoch 증가 뒤
  old callback/new same-PTS offer 분리

### Telemetry

- source/quality change
- cumulative counter reset/regress/wrap/read gap
- outer sample failure가 kernel probe 전/후 어느 쪽이든 GPU/bus/DPU cumulative baseline
  전체를 reset해 다음 interval을 N/A로 만듦
- stale/partial DEVICE/CLIENT pair
- session calibration vendor prefetch complete/partial/session-change와 SF fallback
- vendor v2 timeout while v1 remains valid
- SurfaceFlinger lane timeout·late completion
- final post-teardown sample failure

### Lifecycle

- Activity recreation and close during active producer
- HWC capacity claim owner 취소/실패/재생성과 같은 process 후속 START의 terminal N/A 재사용
- orientation-only display swap과 display ID/normalized-size 변경 projection
- STOP during warm-up, phase, report publication과 cleanup
- topology miss/teardown failure/completed teardown 뒤 stale producer·geometry·typed HWC
  evidence 재사용과 incomplete reattach
- system bar partial hide, focus loss, restore timeout
- cold-start automation START의 decor/root-Insets defer와 readiness 전 STOP supersede
- 실행 시작 display snapshot 대비 same-size display-ID 변경, normalized physical-size
  변경 중단 및 단순 width/height 축 교환 허용
- Battery Saver renewal/END/restore failure
- telemetry monitor/watchdog one-sided completion
- old job finalizer racing with new START

각 fault는 성공 또는 `CLEAN`으로 낮추지 말고 typed rejection, `UNSUPPORTED`,
`INCONCLUSIVE` 또는 `ABORTED`와 sticky gate 중 올바른 결과를 검증한다.

## Markdown와 repository 검사

문서 변경 후:

```powershell
git diff --check
```

tracked와 새 Markdown의 상대 링크, 그리고 repository root 기준으로 쓴 제한된 inline
path의 존재 여부를 검사한다. Inline 검사는 `app/`, `docs/`, `system_integration/`,
`.idea/`, `gradle/` 아래의 source/config path와 알려진 root 파일만 대상으로 하며,
제품 절대 경로·생성되는 `build/`/APK·glob·placeholder·짧은 package-relative path는
의도적으로 제외한다. URL과 heading anchor는 별도 처리한다.

```powershell
$root = (Get-Location).Path
$missing = New-Object System.Collections.Generic.List[string]
$rootFiles = [Collections.Generic.HashSet[string]]::new(
    [string[]]@(
        '.gitignore',
        'AGENT.md',
        'AGENTS.md',
        'ARCHITECTURE.md',
        'PLAN.md',
        'PROJECT_MEMORY.md',
        'README.md',
        'build.gradle.kts',
        'gradle.properties',
        'gradlew',
        'gradlew.bat',
        'settings.gradle.kts'
    ),
    [StringComparer]::Ordinal
)
$repoPrefixes = @('app/', 'docs/', 'system_integration/', '.idea/', 'gradle/')
$sourcePathPattern = '\.(aidl|bp|conf|example|jar|kt|kts|md|mk|properties|toml|xml)$'
$markdown = @(
    git ls-files --cached -- '*.md'
    git ls-files --others --exclude-standard -- '*.md'
) | Sort-Object -Unique

foreach ($relativeFile in $markdown) {
    $file = Get-Item -LiteralPath (Join-Path $root $relativeFile)
    $text = Get-Content -LiteralPath $file.FullName -Raw
    # Code examples can themselves contain bracket/parenthesis or path-like syntax.
    $text = [regex]::Replace($text, '(?ms)^```.*?^```\s*', '')

    [regex]::Matches($text, '\[[^\]]+\]\(([^)#]+)(?:#[^)]+)?\)') |
        ForEach-Object {
            $target = $_.Groups[1].Value
            if ($target -match '^[a-z]+:' -or $target.StartsWith('/')) { return }
            $resolved = Join-Path $file.DirectoryName $target
            if (-not (Test-Path -LiteralPath $resolved)) {
                $missing.Add("$($file.FullName): link $target")
            }
        }

    [regex]::Matches($text, '`([^`\r\n]+)`') |
        ForEach-Object {
            $target = $_.Groups[1].Value.Trim().Replace('\', '/')
            $knownRootFile = $rootFiles.Contains($target)
            $knownPrefix = $repoPrefixes |
                Where-Object { $target.StartsWith($_, [StringComparison]::Ordinal) } |
                Select-Object -First 1
            if (-not $knownRootFile -and $null -eq $knownPrefix) { return }
            if (
                $target -match '[/\\]build[/\\]' -or
                $target -match '[*?${}<>…]' -or
                $target.EndsWith('.apk') -or
                (
                    -not $knownRootFile -and
                    -not $target.EndsWith('/') -and
                    $target -notmatch $sourcePathPattern
                )
            ) {
                return
            }
            $resolved = Join-Path $root $target
            if (-not (Test-Path -LiteralPath $resolved)) {
                $missing.Add("$($file.FullName): inline $target")
            }
        }
}
if ($missing.Count -gt 0) {
    $missing | Sort-Object -Unique
    throw 'Missing Markdown targets'
}
```

## 정적 review checklist

- hot path에 새 buffer/lambda/boxed timestamp 할당이 없는가?
- loop, queue, thread, buffer, Binder와 codec wait가 bounded인가?
- Activity보다 긴 job/callback이 Activity를 강하게 보유하지 않는가?
- partial start가 모든 생성 resource를 rollback하는가?
- timeout이나 enqueue를 terminal cleanup evidence로 오인하지 않는가?
- `N/A`를 0으로 바꾸지 않는가?
- typed contract를 safety clamp 후 다른 실험으로 실행하지 않는가?
- report schema와 문서가 새 field를 포함하는가?
- Battery settings 이동이 performance restore와 Window isolation 복구 전에는 defer되고,
  stale Snackbar consume이 후속 오류를 지우지 않는가?

## 실기기 검증 정책

연결된 실기기에 stress scenario를 자동 실행하지 않는다. 사용자가 다음을 명시해야 한다.

- 대상 실험기와 build fingerprint
- 허용할 catalog/custom scenario
- repeat와 최대 실행 시간
- duration multiplier
- thermal/power 환경
- report 저장·공유 범위

### 승인 후 preflight

1. 테스트 전용 장치인지 확인한다.
2. display physical size/mode, multi-window/PiP OFF를 확인한다.
3. Battery Saver broker 또는 app-only OFF 상태를 확인한다.
4. vendor API/session과 exact underrun source를 확인한다.
5. GPU가 Xclipse/KGSL/GED/Mali 중 어떤 typed ABI인지 확인한다.
6. media scenario면 pinned asset fingerprint와 codec capability를 확인한다.
7. 1L baseline 한 번으로 Window isolation, telemetry와 cleanup을 확인한다.
8. 승인된 low-risk→high-risk 순서로 진행한다.

### 실기기 acceptance

- status/navigation bar hide acknowledgment 뒤 producer 시작
- HUD 값에 source/quality 표시
- expected/observed producer와 frame fidelity 충족
- typed phase의 fresh HWC pair coverage
- 첫 process-session HWC capacity attempt 뒤 같은 process의 queue/repeat/새 START에서
  동일 terminal result가 재사용되고 두 번째 calibration producer burst가 없는지
- requested 20L와 safety-approved actual candidate, calibration display scope가 HUD와
  `SESSION_HWC_CAPACITY_*` report event에 구분되어 남는지
- 2K/4K/8K 90° FIT phase에서 전체 source가 보이고, 8K 1:1은 centered crop인지
- whole-queue repeat 경계가 마지막→첫 scenario 순서이고 선택 배율별 effective phase
  시간이 report와 일치하는지
- exact baseline과 post-teardown terminal sample continuity
- STOP/완료 뒤 thread, Surface, codec, NPU/SBWC, wake/display와 system bar 복구
- 새 plan 시작 전에 sticky cleanup latch 없음
- report JSON parsing과 schema/provenance 확인

BSP 제품 통합 검증은 [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)의 별도 matrix도
통과해야 한다.

## 완료 판정

변경은 관련 gate가 모두 통과하고 실패한 명령·미실행 gate·환경 제약을 숨기지 않았을 때
완료다. 문서 수정만으로 code/runtime gate를 통과했다고 기록하지 않는다.
