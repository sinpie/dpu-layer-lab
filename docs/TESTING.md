# Build와 검증

> **Authority:** host build/test 명령, invariant-to-test 지도, fault/boundary 검증과 승인된 실기기 절차
> **Audience:** 개발자, reviewer, CI maintainer, BSP validation 담당자
> **Update when:** toolchain, test suite, acceptance gate, device 검증 protocol 또는 산출물 경로가 바뀔 때
> **Does not own:** 안전 불변식의 원문, 릴리스 게시, scenario·metric 의미, BSP provider 구현
> **Related:** [AGENTS.md](../AGENTS.md), [PLAN.md](../PLAN.md),
> [ARCHITECTURE.md](../ARCHITECTURE.md), [SCENARIOS.md](SCENARIOS.md),
> [METRICS.md](METRICS.md), [RELEASE.md](RELEASE.md),
> [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)

테스트 개수는 변동 값이므로 이 문서에 고정하지 않는다. 현재 suite는 source와 Gradle
report에서 확인한다.

```powershell
Get-ChildItem app/src/test -Recurse -Filter *Test.kt |
    Sort-Object FullName |
    Select-Object -ExpandProperty FullName

Get-ChildItem app/build/test-results/testDebugUnitTest -Filter *.xml -ErrorAction SilentlyContinue
```

릴리스 시점의 검증 수치가 필요한 경우 해당 release evidence를
[README.md](../README.md), [PROJECT_MEMORY.md](../PROJECT_MEMORY.md)와
[RELEASE.md](RELEASE.md)에 기록한다.

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
| `model/ScenarioPlanPolicyTest.kt` | queue/repeat/expanded-run cap |
| `model/ScenarioQueueEditorTest.kt` | duplicate, order, move, unknown restore |
| `model/ScenarioSafetyPolicyTest.kt` | hard cap, load, duration, graphics budget, typed phase |
| `model/ScenarioClassifierTest.kt` | facet OR/AND 입력과 intensity |
| `model/LoadShapeEvaluatorTest.kt` | worker modulation |
| `model/LoadTransitionEvaluatorTest.kt` | STEP/ramp/stair/pulse/triangle/soak |
| `model/LayerTrafficEstimatorTest.kt` | format/dimensions/linear traffic와 N/A |

### Catalog와 controller

| Test | 계약 |
|---|---|
| `engine/ScenarioCatalogTest.kt` | preset uniqueness, phase validity, catalog semantics |
| `engine/LabControllerMathTest.kt` | verdict, telemetry/HWC coverage, size-profile arm·coverage, preparation/recovery timing, cleanup decisions |
| `engine/DeviceRenderSafetyTest.kt` | RAM/power-save/low-RAM envelope |
| `engine/AutomationIntentContractTest.kt` | explicit action, malformed extras, STOP ordering |
| `engine/TestWindowIsolationTest.kt` | token/state/focus/restore |
| `engine/PerformanceEnvironmentTest.kt` | Battery Saver ticket/restore/session |

### Renderer와 producer

| Test | 계약 |
|---|---|
| `render/LayerStageViewMathTest.kt` | geometry function, controller elapsed re-anchor, dynamic cadence/final sample, narrow-stage visibility |
| `render/ProducerFrameRelayTest.kt` | generation·producer ID relay |
| `render/RendererSafetyStateTest.kt` | process-wide teardown latch |
| `render/RendererRecoveryPolicyTest.kt` | hand-off/recovery deadline |
| `render/CanvasComplexityTest.kt` | flattened GPU/canvas work mapping |
| `render/CodecLoopBackoffTest.kt` | bounded codec dequeue/backoff |
| `render/VideoDecoderSelectionTest.kt` | immutable media/codec fingerprint |
| `render/MediaSampleFlagsTest.kt` | codec input/sample flag |

### Local load와 lifecycle

| Test | 계약 |
|---|---|
| `engine/LoadThreadStartTest.kt` | partial thread start rollback |
| `engine/LoadManagerPrewarmTest.kt` | allocation/page-touch/ack/cancel |
| `engine/LoadManagerNpuControlTest.kt` | latest-wins ticket와 ordered zero |
| `engine/LoadSafetyStateTest.kt` | sticky local/NPU failure |
| `engine/ActivityFreeCompletionGroupTest.kt` | Activity-free job completion |
| `engine/ControllerBackendCleanupTest.kt` | backend shutdown order |
| `engine/ControllerBackendCleanupCoordinatorTest.kt` | process cleanup gate |

### Telemetry와 vendor

| Test | 계약 |
|---|---|
| `monitor/SystemMonitorMathTest.kt` | source merge, interval, HWC evidence |
| `monitor/SystemMonitorConstructionTest.kt` | transactional construction rollback |
| `monitor/KernelSensorProviderTest.kt` | typed ABI parse, Xclipse/KGSL/GED, units |
| `monitor/SurfaceFlingerProbeTest.kt` | bounded dump parse와 lifecycle |
| `monitor/ProducerGenerationGateTest.kt` | fresh generation count와 generation-scoped geometry revision/profile acknowledgment |
| `monitor/CapabilityScannerTest.kt` | display/codec capability projection |
| `vendor/VendorBridgeStateTest.kt` | API version, session, lane, NPU/SBWC/power restore |

### UI, version과 report

| Test | 계약 |
|---|---|
| `ui/DpuLayerLabAppMathTest.kt` | 목적/facet/preview/HUD pure helper |
| `ui/RendererContainerRememberOwnerTest.kt` | Compose renderer owner identity |
| `MainActivityMathTest.kt` | display/window/automation helper |
| `engine/ReportWriterMathTest.kt` | schema/provenance/retention/atomic naming helper |
| `AppVersionTest.kt` | versionName/versionCode 계약 |

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
- typed HWC minimum duration 부족
- malformed START extra와 oversized queue
- restored unknown preset ID

### Resource

- RAM total/available invalid, lowMemory, allocation OOM
- CPU/memory worker partial start와 unexpected exception
- NPU apply timeout, rejected ticket, session change, zero/close 실패
- codec capability mismatch, descriptor open/parse timeout
- GL/Canvas/codec teardown timeout

### Telemetry

- source/quality change
- cumulative counter reset/regress/wrap/read gap
- stale/partial DEVICE/CLIENT pair
- vendor v2 timeout while v1 remains valid
- SurfaceFlinger lane timeout·late completion
- final post-teardown sample failure

### Lifecycle

- Activity recreation and close during active producer
- STOP during warm-up, phase, report publication과 cleanup
- topology miss/teardown failure/completed teardown 뒤 stale producer·geometry·typed HWC
  evidence 재사용과 incomplete reattach
- system bar partial hide, focus loss, restore timeout
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

## 실기기 검증 정책

연결된 실기기에 stress scenario를 자동 실행하지 않는다. 사용자가 다음을 명시해야 한다.

- 대상 실험기와 build fingerprint
- 허용할 catalog/custom scenario
- repeat와 최대 실행 시간
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
- exact baseline과 post-teardown terminal sample continuity
- STOP/완료 뒤 thread, Surface, codec, NPU/SBWC, wake/display와 system bar 복구
- 새 plan 시작 전에 sticky cleanup latch 없음
- report JSON parsing과 schema/provenance 확인

BSP 제품 통합 검증은 [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)의 별도 matrix도
통과해야 한다.

## 완료 판정

변경은 관련 gate가 모두 통과하고 실패한 명령·미실행 gate·환경 제약을 숨기지 않았을 때
완료다. 문서 수정만으로 code/runtime gate를 통과했다고 기록하지 않는다.
