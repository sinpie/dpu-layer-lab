# Repository reconstruction guide

> **Authority:** source 유실·손상 시 repository를 dependency 순서로 재구성하는 절차와 단계별 acceptance checkpoint
> **Audience:** recovery maintainer, 미래 coding agent, repository owner
> **Update when:** repository skeleton, module dependency, bootstrap 순서, irreducible contract 또는 checkpoint가 바뀔 때
> **Does not own:** 정상 기능 개발 계획, 안전 규칙 원문, architecture rationale, secret/asset 보관
> **Related:** [Documentation index](INDEX.md), [AGENTS.md](../AGENTS.md), [ARCHITECTURE.md](../ARCHITECTURE.md),
> [PROJECT_MEMORY.md](../PROJECT_MEMORY.md), [PLAN.md](../PLAN.md),
> [SCENARIOS.md](SCENARIOS.md), [METRICS.md](METRICS.md),
> [TESTING.md](TESTING.md), [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md),
> [RELEASE.md](RELEASE.md)

이 문서는 source를 추측해 한 번에 생성하는 지시가 아니다. 각 dependency layer를 복구하고
그 단계의 test를 통과한 뒤 다음 layer로 이동한다. [AGENTS.md](../AGENTS.md)의 안전
불변식은 reconstruction 중에도 그대로 적용된다.

## Authority 읽기 순서

1. `README.md`: 제품 목적과 사용자-visible 동작
2. `PROJECT_MEMORY.md`: 장기간 유지할 결정과 이유
3. `AGENTS.md`: 수정 금지선, cap, cleanup과 exact/proxy 불변식
4. `docs/INDEX.md`: 문서별 authority와 역할별 route
5. `docs/REQUIREMENTS.md`: 잃으면 안 되는 요구사항 traceability
6. `docs/REPOSITORY_MAP.md`: tracked file과 dependency 위치
7. `ARCHITECTURE.md`와 `docs/STATE_MACHINES.md`: component/flow와 허용 전이
8. `docs/EXTERNAL_CONTRACTS.md`: 외부 identifier와 wire compatibility
9. `docs/SCENARIOS.md`, `docs/METRICS.md`, `docs/REPORT_SCHEMA.md`: 도메인 계약
10. `docs/SYSTEM_INTEGRATION.md`, `docs/TESTING.md`, `docs/RELEASE.md`
11. `PLAN.md`: 아직 완료되지 않은 작업

`PLAN.md`에만 있는 미래 의도를 현재 구현으로 복구하지 않는다. 문서와 남은 source/test가
충돌하면 safety는 `AGENTS.md`, product interface는 `SYSTEM_INTEGRATION.md`, 실행 가능한
현재 사실은 test와 source를 우선하고 충돌을 기록한다.

## 복구 전에 보존할 것

손상된 tree에서도 삭제·정리 전에 다음을 별도 read-only 위치에 보존한다.

- `.git/`과 remote/tag 정보
- 남아 있는 source, test, Gradle과 manifest
- released APK, `SHA256SUMS.txt`와 tag commit
- product `Android.bp`, privapp allowlist와 AIDL
- vendor provider가 가진 API/version 계약
- 공개 문서와 release note

device report, signing key와 test media는 source tree로 복사하지 않는다.

## 변경하면 안 되는 compatibility identifier

| 계약 | 값 |
|---|---|
| project/launcher | `DPULayerTest` |
| release package | `com.example.dpulayerlab` |
| automation alias | `com.example.dpulayerlab.AutomationActivity` |
| actions | `com.example.dpulayerlab.action.START`, `.STOP`, `.SHOW` |
| control permission | `${applicationId}.permission.CONTROL_TESTS` |
| vendor action | `com.example.dpulayerlab.VENDOR_TELEMETRY` |
| vendor permission | `com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY` |
| report prefix | `dpu-layer-lab-` |
| Soong module/APK | `DpuLayerLab` |

debug build는 `.debug` suffix를 사용하고 automation alias permission을 제거한다.
release manifest의 permission과 alias 보안을 debug 동작에 맞춰 약화하지 않는다.

## 복구할 수 없는 항목

이 repository만으로 다음을 재생성할 수 없다.

- platform/product private key, certificate와 keystore
- 실제 vendor provider/service implementation
- 제품 SELinux/DAC 정책의 최종 승인
- 실기기의 HWC plane limit와 GPU/DPU/DDR node ABI
- 4K/8K/P010 선택 media와 그 licensing/provenance
- 과거 device report와 exact underrun evidence
- 공개 release APK의 원래 서명 key

이 항목이 없으면 `N/A`, `UNSUPPORTED` 또는 product handoff로 남긴다. CPU proxy,
procedural texture나 임의 sysfs path로 대체하지 않는다.

## 최소 repository skeleton

```text
.
├─ AGENT.md
├─ AGENTS.md
├─ ARCHITECTURE.md
├─ PLAN.md
├─ PROJECT_MEMORY.md
├─ README.md
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ gradlew / gradlew.bat
├─ gradle/wrapper/
├─ .idea/runConfigurations/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ aidl/com/example/dpulayerlab/vendor/
│     │  ├─ java/com/example/dpulayerlab/
│     │  └─ res/
│     ├─ debug/AndroidManifest.xml
│     └─ test/java/com/example/dpulayerlab/
├─ docs/
└─ system_integration/
   ├─ product/
   └─ vendor/
```

Gradle wrapper binary와 checksum은 새 임의 버전으로 바꾸지 말고
`gradle/wrapper/gradle-wrapper.properties`의 Gradle 8.13 계약을 복구한다.

## Dependency graph

```text
Gradle / Manifest / Resources
    ↓
Pure model + evaluator + safety policy
    ↓
Frame/probe/vendor typed adapters
    ↓
Load workers + renderer producers
    ↓
LabController orchestration
    ↓
MainActivity lifecycle + Compose UI
    ↓
Report/product integration + release
```

아래 단계는 이 순서를 따른다.

## 1단계: build scaffold

Tracked build authority는 다음 순서로 복구한다.

1. `settings.gradle.kts`: plugin/dependency repository, root project와 module 목록
2. root `build.gradle.kts`: plugin ID와 plugin version
3. `app/build.gradle.kts`: Android DSL, Compose compiler, dependency coordinate와 version
4. `gradle.properties`: Gradle/AndroidX/Kotlin build flag
5. `gradle/wrapper/gradle-wrapper.properties`와 tracked
   `gradle/wrapper/gradle-wrapper.jar`

현재 저장소에는 gradle/libs.versions.toml version catalog가 없다. Dependency는
`app/build.gradle.kts`에 직접 선언되어 있으므로 reconstruction 중 임의 catalog를
도입하거나 더 최신 버전으로 치환하지 않는다. 이후 version catalog를 도입한다면 이
authority 목록과 아래 baseline을 같은 변경에서 갱신한다.

현재 exact build baseline:

| 영역 | 값 |
|---|---|
| plugin repositories | `google()`, `mavenCentral()`, `gradlePluginPortal()` |
| dependency repositories | `google()`, `mavenCentral()`; project repository 금지 |
| Android Gradle Plugin | `com.android.application:8.12.2` |
| Kotlin Android plugin | `org.jetbrains.kotlin.android:1.9.0` |
| Compose compiler extension | `1.5.1` |
| Compose BOM | `androidx.compose:compose-bom:2024.04.01` |
| BOM-managed Compose | `androidx.compose.ui:ui`, `ui-tooling-preview`, `ui-tooling`, `ui-test-manifest`, `ui-test-junit4`; `androidx.compose.foundation:foundation`; `androidx.compose.material3:material3` |
| AndroidX core | `androidx.core:core-ktx:1.13.1` |
| Activity Compose | `androidx.activity:activity-compose:1.9.2` |
| Coroutines Android | `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1` |
| Unit test | `junit:junit:4.13.2` |
| Android test | `androidx.test.ext:junit:1.2.1`, `androidx.test.espresso:espresso-core:3.6.1` |
| Gradle distribution | `https://services.gradle.org/distributions/gradle-8.13-bin.zip` |
| Distribution SHA-256 | `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` |
| Tracked wrapper JAR SHA-256 | `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` |

Compose UI/foundation/material3/tooling/test artifacts without 개별 version은 위 BOM에
결속한다. Wrapper는 URL만 맞추지 말고 `distributionSha256Sum` 검증을 유지하고, tracked
`gradle/wrapper/gradle-wrapper.jar`를 다른 출처의 binary로 조용히 교체하지 않는다.

복구:

- root project `DPULayerTest`, module `:app`
- AGP 8.12.2, Kotlin 1.9.0, Gradle wrapper 8.13
- namespace/application ID `com.example.dpulayerlab`
- min SDK 29, compile/target SDK 36, JDK/JVM 17
- Compose, AIDL와 BuildConfig
- debug `.debug`, release unsigned
- launcher, protected alias, FileProvider, icon/theme/string/XML
- VCS-shared debug/release Gradle run configurations

Checkpoint:

```powershell
.\gradlew.bat tasks
.\gradlew.bat :app:processDebugManifest :app:processReleaseManifest
```

manifest output에서 다음을 확인한다.

- release automation alias는 `signature|privileged` permission
- debug alias permission 제거
- alias에는 `CATEGORY_DEFAULT` 없음
- direct `MainActivity`는 automation START를 처리하지 않음
- `allowBackup=false`

## 2단계: pure model

다음 순서로 복구한다.

1. enum/value model in `model/LabModels.kt`
2. `LoadShapeEvaluator.kt`
3. `LoadTransitionEvaluator.kt`
4. `ScenarioQueueEditor.kt`
5. `ScenarioClassifier.kt`
6. `LayerTrafficEstimator.kt`
7. `ScenarioSafetyPolicy.kt`
8. `engine/DeviceRenderSafety.kt`

### 필수 cap

- hard layer 20
- producer FPS 120
- requested display 240 Hz
- repeat 10, expanded plan 40
- exact 0 또는 `0.001` 초과 load
- graphics buffer 최소 triple buffering
- GL color와 보수적 4 B/px depth를 각각 triple buffering

### LayerSizeProfile 복구

`PhaseSpec`은 다음 typed enum을 가져야 한다.

- `FULL_SCREEN` — default
- `SMALL_UNIFORM`
- `MIXED_SIZES`
- `GRADUAL_SMALL_TO_FULL`
- `ABRUPT_SMALL_FULL`

정규화된 width/height scale은 allocation-free 표현으로 계산한다.

- small scale: 각 축 0.30
- mixed index cycle:
  - 1.00×1.00
  - 0.72×0.56
  - 0.56×0.72
  - 0.46×0.46
  - 0.30×0.38
- gradual: 0.30에서 1.00까지 clamped phase fraction의 선형 보간
- abrupt: phase를 bounded 8 step으로 나눠 small/full 교대
- layer count는 1..20, index는 count 안이어야 함
- invalid topology와 non-finite fraction은 1.00×1.00

크기는 destination transform/crop이다. physical producer source buffer는 full size를
유지하고 graphics memory와 linear traffic safety estimate를 축소하지 않는다.

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.model.*"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.DeviceRenderSafetyTest"
```

## 3단계: catalog와 automation pure contract

복구:

- `engine/ScenarioCatalog.kt`
- `engine/AutomationIntentContract.kt`

catalog 순서는 결정적이고 ID가 유일해야 한다. 먼저 baseline, DPU burst,
layer-size matrix, HWC/transform, video/format, resource/transition, adaptive/soak 그룹을
복구한다. 상세 preset은 [SCENARIOS.md](SCENARIOS.md)를 따른다.

automation:

- explicit alias만 허용
- START에서만 extras unmarshal
- single ID 또는 ordered ID 목록
- catalog preset만 허용
- repeat/expanded cap
- newest STOP이 모든 pending START를 무효화
- pending command queue bounded

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.ScenarioCatalogTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.AutomationIntentContractTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.model.ScenarioClassifierTest"
```

## 4단계: telemetry와 vendor contract

복구 순서:

1. `monitor/FrameTracker.kt`
2. `monitor/KernelSensorProvider.kt`
3. `monitor/SurfaceFlingerProbe.kt`
4. Stable AIDL `IDpuLabVendorService.aidl`
5. `vendor/VendorBridge.kt`
6. `monitor/SystemMonitor.kt`
7. `monitor/CapabilityScanner.kt`

중요 계약:

- sample은 bounded single-flight transaction
- completion timestamp는 모든 read 뒤
- exact counter는 vendor/kernel source와 monotonic continuity
- HWC DEVICE/CLIENT는 complete atomic pair
- Xclipse는 AMD RDNA DRM direct-percent ABI, Mali와 구분
- API v2 frequency는 v1과 분리된 bounded lane
- API v3 Battery Saver lease는 global original baseline 복구
- snapshot timeout과 Binder disconnect를 구분
- source가 없으면 N/A

AIDL은 메서드 순서를 임의로 바꾸지 않는다. optional getter는 interface 끝에 append하고
api version gate를 둔다.

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.monitor.*"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.vendor.*"
```

## 5단계: local load subsystem

복구:

- `engine/LoadGenerators.kt`
- `engine/PerformanceEnvironment.kt`
- `engine/ActivityFreeCompletionGroup.kt`
- `engine/ControllerBackendCleanup.kt`
- `engine/ControllerBackendCleanupCoordinator.kt`

worker:

- CPU 12 ms fixed period와 bounded batch
- memory 10 ms fixed period, reused working set, 256 KiB block
- measured baseline 전 memory prewarm/page touch/ack
- NPU bounded latest-wins ticket와 active health
- ordered zero와 adapter close acknowledgment
- partial thread start rollback과 bounded join
- unexpected exception의 process-sticky latch

Activity-free application context와 owner identity를 먼저 게시한 뒤 worker를 시작한다.

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.Load*"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.ActivityFreeCompletionGroupTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.ControllerBackendCleanup*"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.PerformanceEnvironmentTest"
```

## 6단계: renderer

복구 순서:

1. `render/RendererSafetyState.kt`
2. `render/MediaFormatCompat.kt`
3. `render/VideoDecoderSelection.kt`
4. `render/StressGlSurfaceView.kt`
5. `render/LayerStageView.kt`

`LayerStageView`의 핵심 transaction:

1. desired topology 계산
2. old relay detach와 stop request
3. bounded shared deadline teardown
4. 모든 child 생성/add
5. runtime control과 generation relay 설치
6. expected physical producer set 한 번 publish
7. first-buffer/heartbeat

partial failure/OOM은 만들어진 child를 모두 rollback하고 fatal error는 cleanup 뒤 rethrow한다.

### Destination footprint

`LayerSizeProfile`은 각 physical child의 source buffer가 아니라 destination transform/crop에
적용한다.

- full source buffer allocation과 producer identity 유지
- stage bounds 안의 결정적 placement
- narrow-stage centered horizontal stagger는 profile scale에 맞춰 각 child의 최소
  1 px visibility 보존
- 기존 scroll/zoom/rotate transform과 합성
- dynamic profile progress authority는 controller-owned pause-aware `phaseElapsedMs`
- topology preparation/recovery는 prior explicit full/small/mixed measured origin을
  보존하고, 없을 때만 `SMALL_UNIFORM`을 dynamic fraction-zero equivalent로 사용하며
  같은 generation의 matching applied acknowledgment가 있을 때 target dynamic
  profile의 origin coverage bit 하나만 equivalent evidence로 seed
- preparation-equivalent origin은 active profile 자체의 apply 증거가 아니며 mid/end나
  abrupt의 나머지 step coverage를 대신하지 않음
- recovery/generation rebuild는 frozen controller elapsed로 re-anchor해 waveform 연속성
  유지
- allocation route 준비는 discrete target route와 measured size origin edge를 함께 보존
- fresh baseline과 origin producer readiness 뒤 첫 active cyclic fraction 0에서 target
  profile을 arm하고 이후 pulse/triangle valley에서도 origin으로 복귀하지 않음
- dynamic transform은 producer FPS와 독립적으로 최대 100 ms cadence, fraction 1
  terminal sample은 강제
- gradual은 최소 2×100 ms, abrupt 8 step은 최소 8×100 ms; cap 뒤 부족하면 reject
- pending geometry revision의 2-frame ACK가 끝날 때까지 last-applied base-size
  fraction을 유지하되 controller clock은 latest desired를 계속 갱신하고, ACK 뒤
  intermediate backlog 없이 최신 fraction만 적용
- gradual revision key는 origin/mid/exact endpoint 3개, abrupt key는 8 step으로
  제한하며 200 ms gradual window의 30/60/120 fps coverage를 보존
- typed `DEVICE_ONLY`/`CLIENT_REQUIRED` phase와 dynamic size profile 조합은 reject
- topology pending/readiness를 크기 UI 값으로 가리지 않음
- frame hot path에서 per-child heap allocation 없음

Base geometry apply key는 generation, phase ID, profile, semantic sample, layer count와
stage dimensions를 포함한다. Key가 실제로 바뀌어 transform을 적용한 경우에만 bounded
revision을 request한다. 두 번의 후속 `Choreographer` callback/traversal opportunity 뒤
matching revision/profile을 applied로 acknowledge한다. Activation과 typed HWC arm은
이를 기다리지만 physical HWC composition proof로 사용하지 않는다.

`topologyMissed`, `teardownFailed`, `teardownCompleted`는 producer readiness와 geometry
revision/profile/coverage, typed HWC evidence의 terminal boundary다. Miss/failure는
새 generation이 필요하다. Clean teardown 뒤 reattach도 topology pending → 새 geometry
acknowledgment → expected topology publish → activation → 모든 fresh first buffer →
fresh HWC observation을 다시 거쳐야 한다.

Coverage mask는 matching preparation-equivalent origin bit와 active profile의 applied
acknowledgment를 합쳐 gradual origin/mid/end와 abrupt 8 step 전체를 모은다.
Controller는 phase terminal fraction 1 transform을 강제로 게시하고 acknowledgment를
bounded wait한다. 부족하면
`LAYER_SIZE_COVERAGE_MISSING`/`INCONCLUSIVE`, 충족하면 `LAYER_SIZE_COVERAGE` event다.

HUD의 일반 destination footprint는 `LayerSizeProfile` base scale의
`Σ(widthScale × heightScale)` screen-equivalent와 physical producer당 평균 `%`로
별도 표시한다. `MotionProfile` scale, overlap, clipping/crop, rotation과 off-screen
loss를 제외한 표시 면적 요약이며 measured bus나 conservative full-buffer traffic
estimate가 아니다. `CAPACITY_TILES`는 base scale 예외로 explicit crop union
1 screen-equivalent와 평균 `100 / producer count`%를 사용하고 HUD는 estimator의 scope
label을 그대로 표시한다.

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.render.*"
```

renderer/model/controller test는 default full-screen 호환, small/mixed placement,
gradual/abrupt fraction과 required coverage, controller elapsed re-anchor,
pulse/triangle once-arm, 100 ms/final apply, 2-frame fraction hold와 latest-wins,
30/60/120 fps semantic-key coverage, narrow-stage visibility, geometry revision/profile
gate, teardown terminal evidence invalidation·fresh reattach, source buffer·producer
count 불변과 teardown timeout을 포함한다.

## 7단계: controller

`engine/LabController.kt`를 가장 나중에 복구한다. 이 파일은 앞 단계의 모든 typed
dependency를 조율한다.

복구 순서:

1. construction transaction과 telemetry monitor/watchdog pair
2. immutable plan snapshot과 start gate
3. Window isolation owner
4. Battery Saver performance session
5. process-local HWC capacity session store와 one-shot claim/projection
6. scenario media/vendor preflight
7. warm-up, memory prewarm와 exact baseline
8. phase transaction과 transition coverage
9. runtime safety/thermal/producer recovery
10. terminal teardown/sample/verdict/report
11. plan-wide restore와 sticky cleanup gate

STOP은 phase/target null과 load/display safe setpoint를 먼저 적용하고 lazy run job의
identity-matched NonCancellable finalizer가 ownership을 해제할 때까지 새 START를 막는다.

`engine/HwcCapacityCalibrationSession.kt`를 controller보다 먼저 복구한다. Store는
process당 하나의 claim/result만 소유하고 disk persistence를 사용하지 않는다. 최초
controller만 requested 20L/30fps/60Hz independent opaque RGB DISPLAY
`CAPACITY_TILES` 측정 claim을 얻는다. Safety-approved actual candidate, terminal
`OBSERVED_AT_CANDIDATE` 또는 `UNAVAILABLE`, display ID와 normalized physical
short/long edge를 result에 보존한다. 취소·timeout·failure도 terminal N/A로 complete해
후속 START와 Activity 재생성이 두 번째 producer burst를 만들지 않게 한다.

Scope 비교는 width/height 축 순서를 무시하므로 orientation-only swap은 reuse한다.
Display ID 또는 normalized dimensions가 바뀌면 projection을 N/A로 바꾸되 새 claim을
발급하지 않는다. 새 display 측정은 process restart 뒤에만 가능하다. Controller의
one-shot owner는 topology+100ms stabilization+single sample을 absolute 6000ms
producer-active deadline으로 묶는다. 모든 terminal path에서 load zero, renderer
teardown과 calibration frame/generated-traffic counter drain을 확인하고,
cleanup-confirmed non-cancelled path만 3000ms settle 뒤 scenario로 진행한다.
Calibration telemetry는 vendor snapshot을 최대 한 번
prefetch하고 완전한 current-session D/C이면 SF를 생략하며, 아니라면 SF fallback을 한
번만 사용한다. Optional v2는 생략하고, v1 실패 뒤 actual vendor-worker completion을
bounded 확인하지 못하면 SF를 시작하지 않고 N/A로 닫는다. Producer 시작 전 periodic
priority를 획득하고 기존 local/SF/vendor
작업을 drain한다. Sample 전후 topology/geometry revision과 discontinuity serial을
비교하고 teardown 뒤 실제 worker completion을 다시 확인한다. Watchdog pause/grace는
success timestamp와 분리하며, direct safety check는 producer readiness cadence에서
유지한다.

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest `
    --tests "com.example.dpulayerlab.engine.HwcCapacityCalibrationSessionTest" `
    --tests "com.example.dpulayerlab.engine.LabControllerMathTest" `
    --tests "com.example.dpulayerlab.monitor.ProducerGenerationGateTest" `
    --tests "com.example.dpulayerlab.monitor.SurfaceFlingerProbeTest" `
    --tests "com.example.dpulayerlab.monitor.SystemMonitorMathTest" `
    --tests "com.example.dpulayerlab.vendor.VendorBridgeStateTest"
```

이 checkpoint는 cancellation, exact continuity, typed HWC coverage, producer fidelity,
session one-shot/reuse/display projection, vendor-prefetch/SF-fallback, duration cap, media
fingerprint, cleanup과 performance restore fault를 포함해야 한다.

## 8단계: Activity와 UI

복구:

- `engine/TestWindowIsolation.kt`
- `MainActivity.kt`
- `util/DisplayCompat.kt`
- `ui/theme/Theme.kt`
- `ui/DpuLayerLabApp.kt`

Activity는 lifecycle, display envelope, explicit automation과 tokenized system bar
isolation만 소유한다. controller state와 backend thread를 Activity callback에 캡처해
장기간 보유하지 않는다.

UI는 다음을 제공한다.

- 목적 중심 Dashboard와 scenario quick start
- facet와 ordered queue/repeat
- custom builder
- 실행 중 항상 보이는 STOP
- 좌측 상단 build/layer/DPU/CPU/GPU graph와 provenance
- expected/observed physical producer와 pending `—P`
- full-buffer traffic와 별도의 base size-profile 또는 capacity crop-union
  screen-equivalent footprint와 estimator scope label
- plan/result/report
- system capability와 runtime protection

UI의 raw HWC badge는 controller verdict를 대신하지 않는다.

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.MainActivityMathTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.ui.*"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.TestWindowIsolationTest"
```

## 9단계: report와 product integration

복구:

- `engine/ReportWriter.kt`
- FileProvider XML과 backup/data extraction exclusion
- `system_integration/product/Android.bp`
- `system_integration/product/dpulayerlab_product.mk`
- `system_integration/product/privapp-permissions-com.example.dpulayerlab.xml`
- `system_integration/vendor/probe_paths.conf.example`

schema v2 report의 phase에는 `layerSizeProfile` enum을 포함한다. sample/value마다
quality/source를 보존하고 non-finite는 `null`로 쓴다. 일반 base size-profile 또는
`CAPACITY_TILES` crop-union destination footprint는 별도 metric/summary로 표시할 때
`ESTIMATED`, estimator scope와 screen-equivalent unit을 명확히 하며 full-buffer
DPU/producer traffic을 대체하지 않는다.

publication:

1. credential-encrypted `filesDir/reports`
2. bounded managed filename
3. `.json.part` write, flush와 fsync
4. atomic rename
5. 방금 게시한 파일 보호
6. managed completed JSON만 최신 200개 best-effort 보존

Checkpoint:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.engine.ReportWriterMathTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.dpulayerlab.AppVersionTest"
```

## 10단계: 전체 gate

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
git diff --check
```

[TESTING.md](TESTING.md)의 Markdown link/path 검사와 artifact 존재 검사를 함께 수행한다.

## 실기기 recovery validation

host gate 뒤에도 자동 stress를 실행하지 않는다. owner가 대상과 범위를 승인하면:

1. debug 또는 secure product-signed APK identity 확인
2. 1L baseline에서 Window hide/restore와 report 확인
3. exact/vendor source가 없을 때 N/A인지 확인
4. `FULL_SCREEN`, `SMALL_UNIFORM`, `MIXED_SIZES`의 destination geometry 확인
5. gradual/abrupt profile의 bounded 변화, recovery 뒤 progress 연속성, coverage event와
   source buffer 불변 확인
6. base size-profile 또는 capacity crop-union scope HUD와 full-buffer traffic이
   분리됐는지 확인
7. low-risk DEVICE candidate 후 승인된 CLIENT pressure
8. STOP과 process recreation 뒤 모든 cleanup 확인

exact DPU, SBWC, NPU와 DEVICE/CLIENT acceptance는
[SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)의 제품 검증도 필요하다.

## 복구 완료 정의

다음을 모두 만족해야 reconstruction 완료다.

- compatibility identifier와 build type 보안 일치
- pure policy와 boundary test 통과
- renderer/load의 bounded owner·teardown 증거
- exact/proxy와 N/A provenance 보존
- scenario queue/automation cap 유지
- schema v2 및 `LayerSizeProfile` round-trip
- debug/release build 성공
- 공개 release와 다른 APK를 기존 tag로 위장하지 않음
- 실기기 미검증 범위를 명시

하나라도 증명되지 않으면 해당 subsystem을 `UNVERIFIED`로 남기고 다음 release를 만들지
않는다.
