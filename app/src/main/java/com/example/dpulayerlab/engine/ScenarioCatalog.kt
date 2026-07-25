package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.BufferPresentation
import com.example.dpulayerlab.model.HwcCompositionExpectation
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LayerSizeProfile
import com.example.dpulayerlab.model.LayerOrientation
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.RiskLevel
import com.example.dpulayerlab.model.ScenarioCategory
import com.example.dpulayerlab.model.ScenarioSpec
import com.example.dpulayerlab.model.TransitionMode
import com.example.dpulayerlab.model.TransitionSpec
import com.example.dpulayerlab.model.usesSelectedMediaDecoder
import java.util.concurrent.atomic.AtomicLong

object ScenarioCatalog {
    private val customIdSequence = AtomicLong()

    val presets: List<ScenarioSpec> = listOf(
        baseline(),
        dvfsSingleLayerWake(),
        dvfsCompositionShock(),
        dpuDeviceEnvelopeBurst(),
        dpuClientFallbackBurst(),
        dpuOnlyRepeatShock(),
        smallLayerDensity(),
        mixedLayerSizeMatrix(),
        gradualLayerSizeExpansion(),
        abruptLayerSizeToggle(),
        layerSizeFpsBurst(),
        layerSizeDeviceCandidate(),
        layerSizeClientPressure(),
        midLoadPerturbation(),
        dvfsVideoShock(),
        planeStaircase(),
        compositionPivot(),
        transformStorm(),
        mixed4k(),
        video8k30(),
        video8k60P010(),
        refreshPacing(),
        resourcePulse(),
        instantIsolatedContention(),
        instantBurstTransitions(),
        gradualTransitions(),
        continuousCrossLoadRamp(),
        resolutionLoadSweep(),
        rotatedResolutionFitMatrix(),
        resolutionOnlySweep(),
        eightKPresentationAba(),
        waveRecoveryTransitions(),
        npuCrossLoad(),
        adaptiveHunt(),
        sbwcMatrix(),
        soak(),
    )

    fun byId(id: String): ScenarioSpec? = presets.firstOrNull { it.id == id }

    fun custom(
        layers: Int,
        durationSeconds: Int,
        producerFps: Float,
        requestedHz: Float,
        backend: LayerBackend,
        pixelRoute: PixelRoute,
        bufferSize: BufferSize,
        bufferPresentation: BufferPresentation = BufferPresentation.FIT,
        layerOrientation: LayerOrientation = LayerOrientation.ROTATION_0,
        motion: MotionProfile,
        loads: LoadSetpoints,
        layerSizeProfile: LayerSizeProfile = LayerSizeProfile.FULL_SCREEN,
        transition: TransitionSpec = TransitionSpec(),
    ): ScenarioSpec {
        val normalizedLoads = loads.normalized()
        val flattenedInputNormalized =
            backend == LayerBackend.FLATTENED_TEXTURE &&
                (
                    pixelRoute != PixelRoute.RGB_8888 ||
                        bufferSize != BufferSize.DISPLAY
                    )
        val effectivePixelRoute = if (backend == LayerBackend.FLATTENED_TEXTURE) {
            PixelRoute.RGB_8888
        } else {
            pixelRoute
        }
        val effectiveBufferSize = if (backend == LayerBackend.FLATTENED_TEXTURE) {
            BufferSize.DISPLAY
        } else {
            bufferSize
        }
        val flattenedSizeProfileNormalized =
            backend == LayerBackend.FLATTENED_TEXTURE &&
                layerSizeProfile == LayerSizeProfile.MIXED_SIZES
        val effectiveLayerSizeProfile = if (flattenedSizeProfileNormalized) {
            LayerSizeProfile.FULL_SCREEN
        } else {
            layerSizeProfile
        }
        // Any explicitly requested GPU load needs a GPU-backed producer. Treating a small,
        // non-zero value as zero made the custom control look accepted while independent
        // Surface backends had nowhere to execute that workload.
        val includeGlTail =
            normalizedLoads.gpu > 0f && backend != LayerBackend.FLATTENED_TEXTURE
        val needsDedicatedPrimary =
            backend != LayerBackend.FLATTENED_TEXTURE &&
                (
                    effectivePixelRoute.usesSelectedMediaDecoder() ||
                        effectiveBufferSize != BufferSize.DISPLAY
                    )
        val promotedToPrimaryAndGlTail =
            layers == 1 && includeGlTail && needsDedicatedPrimary
        val effectiveLayers = if (promotedToPrimaryAndGlTail) 2 else layers
        val topologyNote = if (promotedToPrimaryAndGlTail) {
            " 요청 1L은 선택한 primary와 GPU 출력을 모두 보존하도록 " +
                "2L(primary + GL tail)로 명시적으로 구성합니다."
        } else {
            ""
        }
        val topologyTags = if (promotedToPrimaryAndGlTail) {
            setOf("requested 1L", "primary + GL tail")
        } else {
            emptySet()
        }
        val flattenedNote = if (flattenedInputNormalized) {
            " Flattened Texture는 display-sized RGBA 단일 producer이므로 요청한 " +
                "${bufferSize.label}/${pixelRoute.label} 입력은 DISPLAY/RGB_8888로 " +
                "명시적으로 정규화했습니다. decoder 또는 4K/8K BufferQueue 부하가 아닙니다."
        } else {
            ""
        }
        val flattenedTags = if (flattenedInputNormalized) {
            setOf("flattened DISPLAY/RGB", "input normalized")
        } else {
            emptySet()
        }
        val sizeProfileNote = if (flattenedSizeProfileNormalized) {
            " Flattened Texture는 physical producer가 1개라 Mixed sizes 분포를 만들 수 " +
                "없으므로 Full screen으로 명시적으로 정규화했습니다."
        } else {
            ""
        }
        val sizeProfileTags = if (flattenedSizeProfileNormalized) {
            setOf("size Full screen", "size profile normalized")
        } else {
            setOf("size ${effectiveLayerSizeProfile.label}")
        }

        return ScenarioSpec(
            id = "custom-${System.currentTimeMillis()}-${customIdSequence.incrementAndGet()}",
            name = "Custom Lab",
            description =
                "사용자가 지정한 단일 phase 테스트.$topologyNote$flattenedNote$sizeProfileNote",
            category = ScenarioCategory.MIXED,
            risk = if (
                maxOf(
                    normalizedLoads.cpu,
                    normalizedLoads.memory,
                    normalizedLoads.gpu,
                    normalizedLoads.npu,
                ) > 0.75f ||
                effectiveLayers > 12 ||
                effectiveBufferSize == BufferSize.UHD_4K ||
                effectiveBufferSize == BufferSize.UHD_8K ||
                effectivePixelRoute == PixelRoute.P010 ||
                effectivePixelRoute == PixelRoute.SBWC_AUTO ||
                effectivePixelRoute == PixelRoute.SBWC_REQUIRED
            ) {
                RiskLevel.HIGH
            } else {
                RiskLevel.MEDIUM
            },
            tags = setOf(
                "custom",
                "${effectiveLayers}L",
                "${producerFps.toInt()}fps",
            ) + topologyTags + flattenedTags + sizeProfileTags,
            phases = listOf(
                phase(
                    id = "custom",
                    label = when {
                        promotedToPrimaryAndGlTail ->
                            "Custom workload · requested 1L → 2L primary + GL tail"
                        flattenedInputNormalized || flattenedSizeProfileNormalized ->
                            "Custom workload · flattened input normalization"
                        else -> "Custom workload"
                    },
                    seconds = durationSeconds,
                    layers = effectiveLayers,
                    fps = producerFps,
                    hz = requestedHz,
                    backend = backend,
                    route = effectivePixelRoute,
                    size = effectiveBufferSize,
                    bufferPresentation = bufferPresentation,
                    layerOrientation = layerOrientation,
                    motion = motion,
                    layerSizeProfile = effectiveLayerSizeProfile,
                    loads = normalizedLoads,
                    alpha = backend != LayerBackend.INDEPENDENT_SURFACES,
                    gl = includeGlTail,
                    transition = transition,
                ),
            ),
            isCustom = true,
        )
    }

    private fun baseline() = ScenarioSpec(
        id = "baseline-display-modes",
        name = "Baseline 60 → Max",
        description = "RGB 독립 Surface 1장으로 60/90/120Hz 요청과 실제 display mode를 비교합니다.",
        category = ScenarioCategory.REFRESH,
        risk = RiskLevel.LOW,
        tags = setOf("baseline", "RGB", "refresh"),
        phases = listOf(
            phase("b60", "60 Hz baseline", 5, 1, 60f, 60f),
            phase("b90", "90 Hz request", 5, 1, 90f, 90f),
            phase("b120", "120 Hz request", 5, 1, 120f, 120f),
            phase("recover", "60 Hz recovery", 4, 1, 60f, 60f),
        ),
    )

    private fun dvfsSingleLayerWake() = ScenarioSpec(
        id = "dvfs-single-layer-wake",
        name = "Low-clock Single-layer Wake",
        description = "긴 1-layer 저부하 구간으로 governor 하강을 유도한 뒤 같은 Surface의 FPS·주사율·확대/회전을 즉시 올려 DPU clock ramp 지연을 관찰합니다. 앱이 DPU clock을 강제로 고정하지는 않습니다.",
        category = ScenarioCategory.ADAPTIVE,
        risk = RiskLevel.MEDIUM,
        tags = setOf("DVFS", "1 layer", "idle→burst", "clock ramp"),
        requirements = setOf("DPU frequency counter 권장"),
        phases = listOf(
            phase("sl-settle-a", "Governor settle · 1L 30fps", 10, 1, 30f, 60f),
            phase(
                "sl-wake-a",
                "Instant 120fps zoom/rotate",
                4,
                1,
                120f,
                120f,
                motion = MotionProfile.ZOOM_PAN,
            ),
            phase("sl-settle-b", "Second low-power settle", 8, 1, 30f, 60f),
            phase(
                "sl-wake-b",
                "Instant 120fps transform",
                4,
                1,
                120f,
                120f,
                motion = MotionProfile.ROTATE,
            ),
            phase("sl-recover", "Clock/load recovery", 6, 1, 30f, 60f),
        ),
    )

    private fun dvfsCompositionShock() = ScenarioSpec(
        id = "dvfs-composition-shock",
        name = "Idle → Composition Shock",
        description = "저부하 governor settle 직후 HWC-friendly plane 증가, alpha/client 합성, DRAM+3D 충돌을 각각 짧게 인가하고 완전히 해제합니다.",
        category = ScenarioCategory.ADAPTIVE,
        risk = RiskLevel.HIGH,
        tags = setOf("DVFS", "HWC", "client", "DRAM", "instant"),
        requirements = setOf("DPU/DDR frequency counter 권장"),
        phases = listOf(
            phase("cs-settle-a", "Governor settle", 10, 1, 30f, 60f),
            phase(
                "cs-plane",
                "12-plane instant shock",
                4,
                12,
                120f,
                120f,
                motion = MotionProfile.PARALLAX,
            ),
            phase("cs-release-a", "Plane release", 7, 1, 30f, 60f),
            phase(
                "cs-client",
                "Alpha/client composition shock",
                4,
                12,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.TRANSFORM_STORM,
                alpha = true,
            ),
            phase("cs-release-b", "Client release", 7, 1, 30f, 60f),
            phase(
                "cs-dram",
                "DPU + DRAM + 3D collision",
                5,
                8,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.ZOOM_PAN,
                loads = LoadSetpoints(cpu = 0.35f, memory = 0.9f, gpu = 0.7f),
                alpha = true,
                gl = true,
            ),
            phase("cs-recover", "Full recovery", 8, 1, 30f, 60f),
        ),
    )

    private fun dpuDeviceEnvelopeBurst() = ScenarioSpec(
        id = "dpu-device-envelope-burst",
        name = "DPU 4L DEVICE Candidate Burst",
        description =
            "1L/30fps governor settle에서 불투명 RGB 독립 Surface 4L/120fps/120Hz로 " +
                "즉시 전환합니다. 4L은 DEVICE 합성 가능성을 확인하는 보수적 candidate일 뿐 " +
                "제품별 plane capacity를 보장하지 않습니다. 각 burst의 fresh HWC " +
                "DEVICE/CLIENT 계측이 DEVICE-only 조건을 충족하는지 결과에서 확인하며, " +
                "계측이 없거나 조건이 다르면 INCONCLUSIVE입니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf(
            "DPU low→high",
            "idle→burst",
            "DEVICE intent",
            "opaque RGB",
            "physical Surface",
            "120fps",
        ),
        requirements = setOf(
            "expectation 검증용 fresh HWC DEVICE/CLIENT telemetry 필수(미가용 시 INCONCLUSIVE)",
        ),
        phases = listOf(
            phase(
                "de-settle-a",
                "Governor settle · 1L 30fps DEVICE baseline",
                12,
                1,
                30f,
                60f,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "de-static-burst",
                "4L/120fps opaque DEVICE-intent step",
                12,
                4,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "de-release",
                "Return to 1L DEVICE baseline",
                12,
                1,
                30f,
                60f,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "de-transform-burst",
                "4L/120fps transform DEVICE-intent step",
                12,
                4,
                120f,
                120f,
                motion = MotionProfile.ZOOM_PAN,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase("de-recover", "DEVICE probe recovery", 7, 1, 30f, 60f),
        ),
    )

    private fun dpuClientFallbackBurst() = ScenarioSpec(
        id = "dpu-client-fallback-burst",
        name = "DPU 20L CLIENT Fallback Candidate",
        description =
            "1L/30fps governor settle에서 app hard cap인 mixed Surface/Texture + alpha/GL " +
                "20L/120fps/120Hz로 즉시 전환해 plane-count pressure로 CLIENT fallback을 " +
                "유도합니다. 실제 plane capacity와 safety clamp는 제품마다 달라 CLIENT를 " +
                "보장하지 않습니다. 각 burst target window의 distinct fresh HWC 계측 " +
                "2회에서 CLIENT>0을 확인해야 하며 계측 부재 또는 fallback 미관측은 " +
                "INCONCLUSIVE입니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.HIGH,
        tags = setOf(
            "DPU low→high",
            "idle→burst",
            "CLIENT fallback intent",
            "plane-pressure candidate",
            "20L",
            "120fps",
        ),
        requirements = setOf(
            "expectation 검증용 fresh HWC DEVICE/CLIENT telemetry 필수(미가용 시 INCONCLUSIVE)",
            "runtime safety policy 필수(20L/120fps/GL target이 clamp되면 expectation 보존을 위해 실행 거부)",
        ),
        phases = listOf(
            phase(
                "cf-settle-a",
                "Governor settle · 1L 30fps DEVICE baseline",
                12,
                1,
                30f,
                60f,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "cf-static-burst",
                "20L/120fps mixed/alpha/GL CLIENT-intent step",
                16,
                20,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                alpha = true,
                gl = true,
                hwcExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
            ),
            phase(
                "cf-release",
                "Return to 1L DEVICE baseline",
                12,
                1,
                30f,
                60f,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "cf-transform-burst",
                "20L/120fps transformed overflow step",
                16,
                20,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.ZOOM_PAN,
                alpha = true,
                gl = true,
                hwcExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
            ),
            phase("cf-recover", "CLIENT probe recovery", 7, 1, 30f, 60f),
        ),
    )

    private fun dpuOnlyRepeatShock() = ScenarioSpec(
        id = "dpu-only-repeat-shock",
        name = "DPU-only Repeated Step Shock",
        description =
            "CPU·memory·GPU·NPU cross-load와 alpha/GL을 모두 0으로 유지한 채 " +
                "1L/30fps/60Hz와 12L/120fps/120Hz를 세 번 STEP 왕복합니다. " +
                "요청한 layer/FPS/Hz edge의 반복성과 DPU recovery를 비교하며, 실제 " +
                "DEVICE/CLIENT 경로·주사율·DPU clock 변화는 계측 결과로만 판단합니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf(
            "DPU-only",
            "DPU low→high",
            "idle→burst",
            "repeat shock",
            "no cross-load",
            "120fps",
        ),
        requirements = setOf(
            "DPU 응답 비교용 frequency/busy 또는 HWC telemetry(미가용 시 요청 부하만 기록)",
        ),
        phases = listOf(
            phase("dr-settle", "DPU low-load settle · 1L/30fps", 10, 1, 30f, 60f),
            phase(
                "dr-burst-1",
                "DPU-only burst 1 · 12L/120fps",
                5,
                12,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase("dr-release-1", "DPU-only release 1", 6, 1, 30f, 60f),
            phase(
                "dr-burst-2",
                "DPU-only burst 2 · 12L/120fps",
                5,
                12,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase("dr-release-2", "DPU-only release 2", 6, 1, 30f, 60f),
            phase(
                "dr-burst-3",
                "DPU-only burst 3 · 12L/120fps",
                5,
                12,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase("dr-recover", "DPU-only final recovery", 8, 1, 30f, 60f),
        ),
    )

    private fun smallLayerDensity() = ScenarioSpec(
        id = "small-layer-density",
        name = "Small-layer Density Sweep",
        description =
            "동일한 display에서 작은 destination rectangle의 독립 Surface를 1→12→20장으로 늘려 " +
                "full-screen overlap과 구분되는 plane-count·visible-area 조합을 관찰합니다. " +
                "물리 producer 수와 buffer 할당 안전 예산은 작은 표시 면적과 별도로 유지됩니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf("small layers", "density", "plane count", "display-only"),
        phases = listOf(
            phase("sd-base", "Full-screen 1L reference", 6, 1, 30f, 60f),
            phase(
                "sd-small-12",
                "12 small independent layers",
                10,
                12,
                60f,
                60f,
                motion = MotionProfile.PARALLAX,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "sd-small-20",
                "20 small layers at high pacing",
                8,
                20,
                90f,
                120f,
                motion = MotionProfile.SCROLL,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase("sd-recover", "Density recovery", 6, 1, 30f, 60f),
        ),
    )

    private fun mixedLayerSizeMatrix() = ScenarioSpec(
        id = "mixed-layer-size-matrix",
        name = "Small / Mixed / Full Size Matrix",
        description =
            "10L·90fps·120Hz와 opaque 독립 Surface를 고정하고 layer geometry만 " +
                "small uniform→small/medium/large mixed→full-screen→mixed로 바꿔 " +
                "크기 분포의 영향을 A/B 방식으로 비교합니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf("size matrix", "small", "medium", "large", "A/B"),
        phases = listOf(
            phase(
                "sm-small-ref",
                "10L small-size reference",
                6,
                10,
                90f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "sm-mixed-a",
                "10L mixed small/medium/large",
                10,
                10,
                90f,
                120f,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            phase("sm-full", "10L full-screen reference", 8, 10, 90f, 120f),
            phase(
                "sm-mixed-b",
                "10L mixed-size repeat",
                10,
                10,
                90f,
                120f,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            phase("sm-recover", "Size matrix recovery", 6, 2, 60f, 60f),
        ),
    )

    private fun gradualLayerSizeExpansion() = ScenarioSpec(
        id = "gradual-layer-size-expansion",
        name = "Gradual Small → Full Expansion",
        description =
            "8개 layer의 수와 backend를 유지한 채 small geometry에서 full-screen까지 " +
                "천천히 확대합니다. Layer/FPS burst와 분리해 크기 변화 자체의 점진적 " +
                "composition footprint와 plane scaling 응답을 관찰합니다. Source full-buffer " +
                "traffic 추정값은 destination 면적으로 줄이지 않습니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.MEDIUM,
        tags = setOf("size transition", "gradual", "small→full", "display-only"),
        phases = listOf(
            phase(
                "gs-small",
                "Small-size steady origin",
                6,
                8,
                90f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "gs-expand",
                "Gradual small-to-full expansion",
                16,
                8,
                90f,
                120f,
                layerSizeProfile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
            ),
            phase("gs-full", "Full-screen steady target", 6, 8, 90f, 120f),
            phase("gs-recover", "Expansion recovery", 6, 2, 60f, 60f),
        ),
    )

    private fun abruptLayerSizeToggle() = ScenarioSpec(
        id = "abrupt-layer-size-toggle",
        name = "Abrupt Small ↔ Full Toggle",
        description =
            "8L topology와 cross-load를 고정하고 small/full geometry를 급격하게 반복 " +
                "전환합니다. Producer 수가 아닌 visible composition 면적의 순간 변화와 " +
                "회복 응답을 분리해 관찰합니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("size transition", "abrupt", "small↔full", "repeat"),
        phases = listOf(
            phase(
                "as-small-a",
                "Small-size steady origin",
                6,
                8,
                60f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "as-toggle-a",
                "Abrupt small/full toggles",
                12,
                8,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
            ),
            phase(
                "as-small-b",
                "Small-size release reference",
                6,
                8,
                60f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "as-toggle-b",
                "Abrupt toggle repeat",
                12,
                8,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
            ),
            phase("as-recover", "Toggle recovery", 6, 2, 60f, 60f),
        ),
    )

    private fun layerSizeFpsBurst() = ScenarioSpec(
        id = "layer-size-fps-burst",
        name = "Size + Layer + FPS Step Burst",
        description =
            "1L·30fps small geometry로 governor를 안정화한 뒤 14L·120fps·120Hz와 " +
                "full-screen geometry를 같은 phase 경계에서 동시에 STEP 인가합니다. 두 번째 burst는 " +
                "18L mixed size를 사용해 크기·layer·pacing 축의 조합을 비교합니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("DPU low→high", "idle→burst", "size + layer + FPS", "step"),
        requirements = setOf("DPU busy/frequency 또는 HWC telemetry 권장"),
        phases = listOf(
            phase(
                "sb-settle-a",
                "Small 1L/30fps governor settle",
                10,
                1,
                30f,
                60f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "sb-full-burst",
                "14L/120fps full-size step burst",
                8,
                14,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
            ),
            phase(
                "sb-release",
                "Return to small 1L/30fps",
                8,
                1,
                30f,
                60f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "sb-mixed-burst",
                "18L/120fps mixed-size burst",
                8,
                18,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            phase("sb-recover", "Combined burst recovery", 8, 1, 30f, 60f),
        ),
    )

    private fun layerSizeDeviceCandidate() = ScenarioSpec(
        id = "layer-size-device-candidate",
        name = "Sized 4L DEVICE Candidate",
        description =
            "불투명 RGB 독립 Surface를 보수적인 4L candidate 안에서 small·mixed size로 " +
                "변화시킵니다. DEVICE 합성을 강제하거나 보장하지 않으며 각 target의 fresh " +
                "vendor DEVICE/CLIENT 원자 쌍이 없거나 DEVICE-only가 아니면 INCONCLUSIVE입니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf("size profile", "DEVICE intent", "opaque RGB", "4L"),
        requirements = setOf(
            "expectation 검증용 fresh vendor HWC DEVICE/CLIENT telemetry 필수(미가용 시 INCONCLUSIVE)",
        ),
        phases = listOf(
            phase(
                "sc-device-base",
                "Small 1L DEVICE baseline",
                12,
                1,
                30f,
                60f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "sc-device-mixed",
                "4L mixed-size DEVICE candidate",
                12,
                4,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "sc-device-small",
                "4L small-size DEVICE candidate",
                12,
                4,
                120f,
                120f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "sc-device-return",
                "Return to small 1L DEVICE baseline",
                12,
                1,
                30f,
                60f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase("sc-device-recover", "Sized DEVICE recovery", 7, 1, 30f, 60f),
        ),
    )

    private fun layerSizeClientPressure() = ScenarioSpec(
        id = "layer-size-client-pressure",
        name = "Sized 20L CLIENT Pressure",
        description =
            "small 1L DEVICE baseline에서 full-screen 또는 mixed-size alpha/GL 20L로 " +
                "phase 경계에서 즉시 전환합니다. 판정 phase 내부 geometry는 고정해 " +
                "CLIENT 합성 관측 원인을 격리하며, target마다 distinct fresh vendor 원자 " +
                "쌍에서 CLIENT>0이 확인되지 않으면 INCONCLUSIVE입니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.HIGH,
        tags = setOf("size profile", "CLIENT fallback intent", "20L", "alpha", "GL"),
        requirements = setOf(
            "expectation 검증용 fresh vendor HWC DEVICE/CLIENT telemetry 필수(미가용 시 INCONCLUSIVE)",
            "runtime safety policy 필수(20L/120fps/GL target clamp 시 실행 거부)",
        ),
        phases = listOf(
            phase(
                "sp-client-base-a",
                "Small 1L DEVICE baseline",
                12,
                1,
                30f,
                60f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "sp-client-full",
                "20L full-screen CLIENT pressure",
                16,
                20,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                alpha = true,
                gl = true,
                hwcExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
            ),
            phase(
                "sp-client-base-b",
                "Return to small 1L DEVICE baseline",
                12,
                1,
                30f,
                60f,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                hwcExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
            phase(
                "sp-client-mixed",
                "20L mixed-size CLIENT pressure",
                16,
                20,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
                alpha = true,
                gl = true,
                hwcExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
            ),
            phase("sp-client-recover", "Sized CLIENT recovery", 7, 1, 30f, 60f),
        ),
    )

    private fun midLoadPerturbation() = ScenarioSpec(
        id = "mid-load-perturbation",
        name = "Paired Mid-load Perturbation Matrix",
        description = "최대 부하가 아닌 4~8 layer·60~90fps 구간에서 각 변화 전후에 동일한 " +
            "reference로 복귀합니다. scroll, rotate, layer, alpha, pacing, CPU, DRAM 축을 " +
            "A/B/A로 분리해 중간 부하 취약점의 원인을 비교합니다.",
        category = ScenarioCategory.ADAPTIVE,
        risk = RiskLevel.MEDIUM,
        tags = setOf("mid load", "isolation", "60–90fps", "A/B/A", "paired reference"),
        phases = listOf(
            phase("mp-base", "4L static reference", 6, 4, 60f, 60f),
            phase("mp-scroll", "Scroll only", 5, 4, 60f, 60f, motion = MotionProfile.SCROLL),
            phase("mp-scroll-ref", "Scroll reference recovery", 5, 4, 60f, 60f),
            phase("mp-rotate", "Rotate only", 5, 4, 60f, 60f, motion = MotionProfile.ROTATE),
            phase("mp-rotate-ref", "Rotate reference recovery", 5, 4, 60f, 60f),
            phase("mp-layers", "8L plane count only", 5, 8, 60f, 60f),
            phase("mp-layers-ref", "Layer reference recovery", 5, 4, 60f, 60f),
            phase(
                "mp-alpha-ref-a",
                "8L mixed alpha reference",
                5,
                8,
                60f,
                60f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
            ),
            phase(
                "mp-alpha",
                "8L alpha only",
                5,
                8,
                60f,
                60f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                alpha = true,
            ),
            phase(
                "mp-alpha-ref-b",
                "Alpha reference recovery",
                5,
                8,
                60f,
                60f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
            ),
            phase("mp-90-ref-a", "8L 60fps pacing reference", 5, 8, 60f, 60f),
            phase(
                "mp-90",
                "8L 90fps/90Hz pacing only",
                5,
                8,
                90f,
                90f,
            ),
            phase("mp-90-ref-b", "Pacing reference recovery", 5, 8, 60f, 60f),
            phase("mp-bus-ref-a", "6L bus reference", 5, 6, 60f, 60f),
            phase(
                "mp-cpu",
                "Moderate CPU contention only",
                6,
                6,
                60f,
                60f,
                loads = LoadSetpoints(cpu = 0.45f),
            ),
            phase("mp-bus-ref-b", "CPU reference recovery", 5, 6, 60f, 60f),
            phase(
                "mp-memory",
                "Moderate DRAM contention only",
                6,
                6,
                60f,
                60f,
                loads = LoadSetpoints(memory = 0.6f),
            ),
            phase("mp-bus-ref-c", "Memory reference recovery", 5, 6, 60f, 60f),
            phase("mp-recover", "Reference recovery", 6, 4, 60f, 60f),
        ).map { phase ->
            // This A/B matrix assumes every logical layer remains visible while one control axis
            // changes. Explicit geometry prevents opaque static full-screen siblings from being
            // trivially occluded or culled.
            phase.copy(layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM)
        },
    )

    private fun dvfsVideoShock() = ScenarioSpec(
        id = "dvfs-video-shock",
        name = "Idle → 4K Video Shock",
        description =
            "RGB 1-layer settle에서 4K decoder-to-Surface와 RGB overlay로 즉시 전환해 " +
                "codec/DPU/DRAM 동시 ramp를 확인합니다. 검증된 로컬 영상이 없으면 " +
                "proxy로 대체하지 않고 실행 전에 거부합니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.HIGH,
        tags = setOf("DVFS", "4K", "YUV", "decoder", "overlay"),
        requirements = setOf("4K local media 권장", "DPU/DDR frequency counter 권장"),
        phases = listOf(
            phase("vs-settle-a", "Governor settle", 10, 1, 30f, 60f),
            phase(
                "vs-yuv",
                "4K YUV + 4 overlays shock",
                5,
                5,
                60f,
                120f,
                route = PixelRoute.YUV_420,
                size = BufferSize.UHD_4K,
                motion = MotionProfile.ZOOM_PAN,
            ),
            phase("vs-release", "Decoder/display release", 8, 1, 30f, 60f),
            phase(
                "vs-bus",
                "4K YUV + DRAM collision",
                5,
                6,
                60f,
                120f,
                route = PixelRoute.YUV_420,
                size = BufferSize.UHD_4K,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(cpu = 0.25f, memory = 0.85f),
            ),
            phase("vs-recover", "Post-video recovery", 8, 1, 30f, 60f),
        ),
    )

    private fun planeStaircase() = ScenarioSpec(
        id = "plane-staircase",
        name = "HWC Plane Staircase",
        description =
            "불투명 독립 Surface demand를 1→2→4→6→8→12L로 올렸다가 줄입니다. " +
                "앱 process-session 최초 1회 capacity 관측과 별개인 bounded sweep이며 " +
                "이 sweep의 " +
                "최대값도 제품 HWC 최대 plane 수로 간주하지 않습니다. Active load를 " +
                "교란하지 않도록 SurfaceFlinger dump는 중지하고, fresh vendor composition " +
                "snapshot이 없으면 단계별 DEVICE/CLIENT 결과는 N/A일 수 있습니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf("HWC", "plane", "bounded sweep", "session calibration separate"),
        requirements = setOf(
            "저교란 단계별 HWC 관측에는 vendor composition snapshot 권장",
            "Active 단계는 SurfaceFlinger fallback 없이 vendor snapshot만 사용",
        ),
        phases = listOf(1, 2, 4, 6, 8, 12, 8, 4, 1).mapIndexed { index, layers ->
            phase(
                "p$index",
                "$layers independent layers",
                4,
                layers,
                60f,
                120f,
                motion = MotionProfile.PARALLAX,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            )
        },
    )

    private fun compositionPivot() = ScenarioSpec(
        id = "composition-pivot",
        name = "HWC ↔ GPU Composition Pivot",
        description = "10개 콘텐츠의 FPS, 주사율, motion, alpha와 외부 workload를 고정하고 " +
            "독립 Surface, 혼합, Texture backend만 전환해 composition 경로의 영향을 분리합니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf("HWC", "GPU", "A/B", "backend only", "fixed content"),
        phases = listOf(
            phase(
                "device",
                "Independent Surface backend",
                6,
                10,
                90f,
                120f,
                motion = MotionProfile.ROTATE,
            ),
            phase(
                "mixed",
                "Mixed Surface/Texture backend",
                6,
                10,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.ROTATE,
            ),
            phase(
                "client",
                "Flattened Texture composition",
                6,
                10,
                90f,
                120f,
                backend = LayerBackend.FLATTENED_TEXTURE,
                motion = MotionProfile.ROTATE,
            ),
            phase(
                "restore",
                "Independent Surface restore",
                6,
                10,
                90f,
                120f,
                motion = MotionProfile.ROTATE,
            ),
        ),
    )

    private fun transformStorm() = ScenarioSpec(
        id = "transform-storm",
        name = "Transform Storm",
        description = "12개 layer가 서로 다른 위상으로 확대/축소/스크롤/회전하고 " +
            "View/client Z-order proxy를 실행합니다. Z proxy는 translationZ 기반이며 " +
            "physical Surface/HWC Z-order 전환 또는 HWC 능력의 exact 증거가 아닙니다.",
        category = ScenarioCategory.TRANSFORM,
        risk = RiskLevel.HIGH,
        tags = setOf("zoom", "scroll", "rotate", "120Hz"),
        phases = listOf(
            phase("warm", "Scroll warm-up", 5, 8, 60f, 120f, motion = MotionProfile.SCROLL),
            phase(
                "storm",
                "Asynchronous transform storm",
                12,
                12,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.TRANSFORM_STORM,
                alpha = true,
            ),
            phase(
                "z",
                "View/client Z proxy (not physical HWC)",
                8,
                12,
                90f,
                90f,
                motion = MotionProfile.Z_ORDER_SWAP,
            ),
            phase("cool", "Static recovery", 5, 2, 60f, 60f),
        ),
    )

    private fun mixed4k() = ScenarioSpec(
        id = "4k-mixed",
        name = "4K YUV + RGB Overlay",
        description =
            "4K decoder-to-Surface 경로와 RGB overlay, memory pulse를 함께 실행합니다. " +
                "검증된 로컬 영상이 없으면 YUV proxy로 대체하지 않고 실행 전에 거부합니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.HIGH,
        tags = setOf("4K", "YUV", "overlay", "memory"),
        requirements = setOf("4K decoder asset 권장"),
        phases = listOf(
            phase(
                "decode",
                "4K60 + 4 overlays",
                8,
                5,
                60f,
                120f,
                route = PixelRoute.YUV_420,
                size = BufferSize.UHD_4K,
                motion = MotionProfile.ZOOM_PAN,
            ),
            phase(
                "pulse",
                "Memory bus pulse",
                16,
                7,
                60f,
                120f,
                route = PixelRoute.YUV_420,
                size = BufferSize.UHD_4K,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(cpu = 0.25f, memory = 0.9f, shape = LoadShape.PULSE),
            ),
            phase("release", "Bus release", 6, 5, 60f, 60f, route = PixelRoute.YUV_420),
        ),
    )

    private fun video8k30() = ScenarioSpec(
        id = "8k-decoder-pressure",
        name = "8K30 YUV Decoder Pressure",
        description = "8K30 YUV 영상 Surface와 6개 overlay를 결합합니다. " +
            "장치의 8K30 codec/asset capability를 반드시 확인합니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.HIGH,
        tags = setOf("8K30", "YUV", "HEVC", "AV1", "codec"),
        requirements = setOf("8K30 decoder", "8K 30fps local media"),
        phases = listOf(
            phase(
                "8k30",
                "8K30 decode path",
                10,
                7,
                30f,
                120f,
                route = PixelRoute.YUV_420,
                size = BufferSize.UHD_8K,
                motion = MotionProfile.ZOOM_PAN,
            ),
            phase("release", "Decoder recovery", 6, 2, 60f, 60f),
        ),
    )

    private fun video8k60P010() = ScenarioSpec(
        id = "8k60-p010-pressure",
        name = "8K60 P010 Decoder Pressure",
        description = "8K60 10-bit P010 영상 Surface와 6개 overlay 및 GPU tail을 결합합니다. " +
            "장치의 8K60 10-bit codec/asset capability를 반드시 확인합니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.HIGH,
        tags = setOf("8K60", "P010", "10-bit", "HEVC", "AV1", "codec"),
        requirements = setOf("8K60 10-bit decoder", "8K 60fps 10-bit local media"),
        phases = listOf(
            phase(
                "8k60",
                "8K60 + overlays",
                12,
                // Decoder primary + six RGB overlays + one GL tail.
                8,
                60f,
                120f,
                route = PixelRoute.P010,
                size = BufferSize.UHD_8K,
                motion = MotionProfile.TRANSFORM_STORM,
                loads = LoadSetpoints(memory = 0.45f, gpu = 0.35f),
                gl = true,
            ),
            phase("release", "Decoder recovery", 6, 2, 60f, 60f),
        ),
    )

    private fun refreshPacing() = ScenarioSpec(
        id = "mixed-pacing",
        name = "Mixed Frame Pacing",
        description = "producer FPS와 display Hz를 서로 다르게 조합해 pacing/beat 패턴을 찾습니다.",
        category = ScenarioCategory.REFRESH,
        risk = RiskLevel.MEDIUM,
        tags = setOf("60", "72", "90", "120"),
        phases = listOf(
            phase("60-120", "60 fps on 120 Hz", 6, 6, 60f, 120f, motion = MotionProfile.SCROLL),
            phase("72-120", "72 fps on 120 Hz", 6, 6, 72f, 120f, motion = MotionProfile.SCROLL),
            phase("90-120", "90 fps on 120 Hz", 6, 6, 90f, 120f, motion = MotionProfile.SCROLL),
            phase("120-120", "120 fps on 120 Hz", 6, 6, 120f, 120f, motion = MotionProfile.SCROLL),
            phase("60-60", "60 fps recovery", 5, 2, 60f, 60f),
        ),
    )

    private fun resourcePulse() = ScenarioSpec(
        id = "resource-pulse",
        name = "Fixed-topology Resource Pulse",
        description = "동일한 7-layer MIXED/GL topology, 90fps/120Hz, motion, alpha를 유지한 채 " +
            "CPU 연산, 메모리 copy, 3D 부하를 한 축씩 pulse로 켰다 끄며 bus contention과 " +
            "복구를 비교합니다.",
        category = ScenarioCategory.RESOURCE,
        risk = RiskLevel.HIGH,
        tags = setOf("CPU", "memory", "3D", "pulse", "fixed topology", "isolation"),
        phases = listOf(
            phase(
                "idle",
                "Fixed-topology idle baseline",
                5,
                7,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
            phase(
                "cpu",
                "CPU-only math pulse",
                12,
                7,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(cpu = 0.85f, shape = LoadShape.PULSE),
                alpha = true,
                gl = true,
            ),
            phase(
                "cpu-release",
                "CPU pulse release reference",
                4,
                7,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
            phase(
                "memory",
                "Memory-only bandwidth pulse",
                12,
                7,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(memory = 0.95f, shape = LoadShape.PULSE),
                alpha = true,
                gl = true,
            ),
            phase(
                "memory-release",
                "Memory pulse release reference",
                4,
                7,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
            phase(
                "gpu",
                "GPU-only 3D fragment pulse",
                12,
                7,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(gpu = 0.9f, shape = LoadShape.PULSE),
                alpha = true,
                gl = true,
            ),
            phase(
                "recover",
                "Fixed-topology all-load release",
                7,
                7,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
        ),
    )

    private fun instantIsolatedContention() = ScenarioSpec(
        id = "instant-isolated-contention",
        name = "Instant Isolated Contention",
        description = "동일한 8-layer MIXED/GL topology와 90fps/120Hz를 유지한 채 CPU, " +
            "메모리, GPU contention을 각각 한 pulse phase 안에서 즉시 켰다 끕니다. " +
            "phase 경계의 안전 quiesce와 측정 ON/OFF edge를 분리해 순간 부하의 인과를 비교합니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("pulse burst", "instant", "fixed topology", "isolation", "CPU", "memory", "GPU"),
        phases = listOf(
            phase(
                "ii-base",
                "Fixed-topology quiet baseline",
                5,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
            phase(
                "ii-cpu-pulse",
                "CPU-only instant ON/OFF pulses",
                12,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(cpu = 0.8f),
                alpha = true,
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.PULSE_BURST,
                    cycleMs = 2_000L,
                    dutyCycle = 0.35f,
                ),
            ),
            phase(
                "ii-cpu-release",
                "CPU pulse fully released",
                3,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
            phase(
                "ii-memory-pulse",
                "Memory-only instant ON/OFF pulses",
                12,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(memory = 0.9f),
                alpha = true,
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.PULSE_BURST,
                    cycleMs = 2_000L,
                    dutyCycle = 0.35f,
                ),
            ),
            phase(
                "ii-memory-release",
                "Memory pulse fully released",
                3,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
            phase(
                "ii-gpu-pulse",
                "GPU-only instant ON/OFF pulses",
                12,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(gpu = 0.75f),
                alpha = true,
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.PULSE_BURST,
                    cycleMs = 2_000L,
                    dutyCycle = 0.35f,
                ),
            ),
            phase(
                "ii-recover",
                "Fixed-topology all-load recovery",
                6,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
        ),
    )

    private fun instantBurstTransitions() = ScenarioSpec(
        id = "instant-burst-transitions",
        name = "Instant Step & Burst",
        description =
            "layer·FPS를 즉시 올리고 교차 부하를 step으로 켠 뒤, burst topology를 유지한 " +
                "zero-load reference에서 짧은 contention duty cycle을 반복하고 최종 recovery로 내립니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("step", "burst", "latency", "release"),
        phases = listOf(
            phase("ib-base", "Quiet baseline", 4, 4, 60f, 60f),
            phase(
                "ib-step-on",
                "Instant contention on",
                5,
                10,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.TRANSFORM_STORM,
                loads = LoadSetpoints(cpu = 0.55f, memory = 0.8f, gpu = 0.65f),
                alpha = true,
                gl = true,
                transition = TransitionSpec(TransitionMode.STEP),
            ),
            phase(
                "ib-step-off",
                "Instant release · burst topology armed",
                4,
                10,
                120f,
                120f,
                motion = MotionProfile.PARALLAX,
                gl = true,
            ),
            phase(
                "ib-burst",
                "25% duty contention bursts",
                12,
                10,
                120f,
                120f,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(cpu = 0.65f, memory = 0.9f, gpu = 0.55f),
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.PULSE_BURST,
                    cycleMs = 2_000L,
                    dutyCycle = 0.25f,
                ),
            ),
            phase("ib-recover", "Burst recovery", 5, 4, 60f, 60f),
        ),
    )

    private fun gradualTransitions() = ScenarioSpec(
        id = "gradual-load-transitions",
        name = "Topology + Load Combined Ramp",
        description = "layer, FPS, motion과 교차 부하를 함께 바꾸는 선형 ramp/staircase " +
            "시스템 스트레스입니다. 여러 축이 동시에 변하므로 단일 원인 격리용이 아닙니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("linear", "staircase", "up", "down", "combined axes", "topology change"),
        phases = listOf(
            phase("gt-base", "Quiet baseline", 4, 4, 60f, 60f, gl = true),
            phase(
                "gt-ramp-up",
                "Linear ramp up",
                10,
                12,
                120f,
                120f,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(cpu = 0.55f, memory = 0.85f, gpu = 0.5f),
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.LINEAR_RAMP,
                    transitionDurationMs = 8_000L,
                ),
            ),
            phase(
                "gt-ramp-down",
                "Linear ramp down",
                8,
                4,
                60f,
                60f,
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.LINEAR_RAMP,
                    transitionDurationMs = 6_000L,
                ),
            ),
            phase(
                "gt-stairs-up",
                "Six-level load increase",
                10,
                16,
                120f,
                120f,
                motion = MotionProfile.SCROLL,
                loads = LoadSetpoints(cpu = 0.45f, memory = 0.9f, gpu = 0.55f),
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.STAIRCASE,
                    transitionDurationMs = 7_200L,
                    stepCount = 6,
                ),
            ),
            phase(
                "gt-stairs-down",
                "Six-level load release",
                8,
                4,
                60f,
                60f,
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.STAIRCASE,
                    transitionDurationMs = 6_000L,
                    stepCount = 6,
                ),
            ),
            phase("gt-recover", "Stable recovery", 4, 4, 60f, 60f),
        ),
    )

    private fun continuousCrossLoadRamp() = ScenarioSpec(
        id = "continuous-crossload-ramp",
        name = "Continuous Fixed-topology Cross-load Ramp",
        description = "동일한 8-layer MIXED/GL topology, 90fps/120Hz, motion, alpha를 유지하고 " +
            "하나의 측정 phase 안에서 CPU·메모리·GPU를 0→high→hold→0으로 천천히 변화시킵니다. " +
            "phase 경계의 안전 quiesce가 ramp 중간에 끼지 않습니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("soak recovery", "slow ramp", "fixed topology", "continuous", "up", "down"),
        phases = listOf(
            phase(
                "cr-base",
                "Fixed-topology quiet baseline",
                5,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
            phase(
                "cr-soak",
                "Continuous 0 → high → hold → 0 cross-load",
                50,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                loads = LoadSetpoints(cpu = 0.65f, memory = 0.9f, gpu = 0.7f),
                alpha = true,
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.SOAK_RECOVERY,
                    transitionDurationMs = 15_000L,
                ),
            ),
            phase(
                "cr-recover",
                "Post-envelope zero hold",
                6,
                8,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.PARALLAX,
                alpha = true,
                gl = true,
            ),
        ),
    )

    private fun resolutionLoadSweep() = ScenarioSpec(
        id = "resolution-load-sweep",
        name = "1K → 8K → 1K Load Sweep",
        description =
            "실제 primary producer 버퍼를 1K, 2K/1080p, 4K, 8K 순으로 키우며 " +
                "교차 부하를 단계적으로 높인 뒤 같은 순서를 반대로 내려 복구를 확인합니다. " +
                "각 해상도는 graphics-memory budget을 통과해야 하며 축소 대체하지 않습니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("1K", "2K", "4K", "8K", "resolution", "load rise", "load fall"),
        phases = listOf(
            phase(
                "rs-1k-up",
                "1K low-load origin",
                5,
                2,
                30f,
                60f,
                size = BufferSize.HD_1K,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase(
                "rs-2k-up",
                "2K moderate-load rise",
                5,
                3,
                60f,
                90f,
                size = BufferSize.FHD,
                motion = MotionProfile.ZOOM_PAN,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
                loads = LoadSetpoints(cpu = 0.25f, memory = 0.3f),
            ),
            phase(
                "rs-4k-up",
                "4K high-load rise",
                6,
                2,
                60f,
                120f,
                size = BufferSize.UHD_4K,
                motion = MotionProfile.ROTATE,
                layerSizeProfile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                loads = LoadSetpoints(cpu = 0.4f, memory = 0.5f),
            ),
            phase(
                "rs-8k-peak",
                "8K bounded peak",
                6,
                1,
                30f,
                120f,
                size = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
                motion = MotionProfile.PARALLAX,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                loads = LoadSetpoints(cpu = 0.5f, memory = 0.65f),
            ),
            phase(
                "rs-4k-down",
                "4K load release",
                6,
                2,
                60f,
                90f,
                size = BufferSize.UHD_4K,
                bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
                motion = MotionProfile.SCROLL,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                loads = LoadSetpoints(cpu = 0.35f, memory = 0.4f),
            ),
            phase(
                "rs-2k-down",
                "2K recovery",
                5,
                2,
                60f,
                60f,
                size = BufferSize.FHD,
                motion = MotionProfile.ROTATE,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
                loads = LoadSetpoints(cpu = 0.15f, memory = 0.2f),
            ),
            phase(
                "rs-1k-down",
                "1K recovery baseline",
                5,
                1,
                30f,
                60f,
                size = BufferSize.HD_1K,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
        ),
    )

    private fun rotatedResolutionFitMatrix() = ScenarioSpec(
        id = "rotated-resolution-fit-matrix",
        name = "90° Fit 2K / 4K / 8K Matrix",
        description =
            "2K, 4K, 8K primary producer를 고정 90° 회전하고 종횡비를 보존해 화면 안에 " +
                "맞춘 상태에서 정적 pacing, 이동, 확대/축소와 CPU/메모리 교차 부하를 비교합니다.",
        category = ScenarioCategory.TRANSFORM,
        risk = RiskLevel.HIGH,
        tags = setOf("90°", "fit", "2K", "4K", "8K", "zoom", "rotation"),
        phases = listOf(
            phase(
                "rf-2k-static",
                "2K · 90° fit · static",
                5,
                2,
                60f,
                60f,
                size = BufferSize.FHD,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_90,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            phase(
                "rf-4k-pan",
                "4K · 90° fit · parallax",
                6,
                2,
                60f,
                90f,
                size = BufferSize.UHD_4K,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_90,
                motion = MotionProfile.PARALLAX,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
                loads = LoadSetpoints(cpu = 0.25f, memory = 0.3f),
            ),
            phase(
                "rf-8k-static",
                "8K · 90° fit · pacing",
                6,
                1,
                30f,
                120f,
                size = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_90,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                loads = LoadSetpoints(memory = 0.35f),
            ),
            phase(
                "rf-8k-pan",
                "8K · 90° fit · moving load",
                6,
                1,
                30f,
                120f,
                size = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_90,
                motion = MotionProfile.PARALLAX,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                loads = LoadSetpoints(cpu = 0.35f, memory = 0.5f),
            ),
            phase(
                "rf-8k-zoom",
                "8K · 90° fit · bounded zoom",
                7,
                1,
                30f,
                120f,
                size = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_90,
                motion = MotionProfile.ZOOM_PAN,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                loads = LoadSetpoints(cpu = 0.45f, memory = 0.6f),
            ),
            phase(
                "rf-4k-recover",
                "4K · 90° fit · recovery",
                5,
                1,
                60f,
                60f,
                size = BufferSize.UHD_4K,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_90,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
            ),
            phase(
                "rf-2k-recover",
                "2K · 90° fit · recovery",
                5,
                1,
                60f,
                60f,
                size = BufferSize.FHD,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_90,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
            ),
        ),
    )

    private fun resolutionOnlySweep() = ScenarioSpec(
        id = "resolution-only-sweep",
        name = "Resolution-only 1K / 2K / 4K / 8K A/B",
        description =
            "한 primary producer, 30fps/60Hz, FIT/0°, 정적 geometry와 zero cross-load를 " +
                "고정하고 source buffer 해상도만 1K→2K→4K→8K→4K→2K→1K로 바꿉니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.HIGH,
        tags = setOf("resolution-only", "A/B/A", "1K", "2K", "4K", "8K"),
        phases = listOf(
            BufferSize.HD_1K,
            BufferSize.FHD,
            BufferSize.UHD_4K,
            BufferSize.UHD_8K,
            BufferSize.UHD_4K,
            BufferSize.FHD,
            BufferSize.HD_1K,
        ).mapIndexed { index, size ->
            phase(
                id = "ro-$index",
                label = "${size.label} primary · fixed reference",
                seconds = 5,
                layers = 1,
                fps = 30f,
                hz = 60f,
                size = size,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_0,
                motion = MotionProfile.STATIC,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
            )
        },
    )

    private fun eightKPresentationAba() = ScenarioSpec(
        id = "8k-presentation-fit-crop-aba",
        name = "8K FIT ↔ 1:1 Crop A/B/A",
        description =
            "동일한 8K primary allocation, 1L/30fps/60Hz, 0°와 zero cross-load를 유지하고 " +
                "전체 화면 FIT과 centered 1:1 crop 투영만 A/B/A로 비교합니다.",
        category = ScenarioCategory.TRANSFORM,
        risk = RiskLevel.HIGH,
        tags = setOf("8K", "FIT", "1:1 crop", "A/B/A", "presentation-only"),
        phases = listOf(
            phase(
                "pa-fit-a",
                "8K FIT reference A",
                6,
                1,
                30f,
                60f,
                size = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.FIT,
            ),
            phase(
                "pa-crop",
                "8K centered 1:1 crop B",
                6,
                1,
                30f,
                60f,
                size = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
            ),
            phase(
                "pa-fit-b",
                "8K FIT reference A",
                6,
                1,
                30f,
                60f,
                size = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.FIT,
            ),
        ),
    )

    private fun waveRecoveryTransitions() = ScenarioSpec(
        id = "wave-soak-recovery",
        name = "Triangle Wave & Soak Recovery",
        description = "각 envelope 안에서는 topology를 고정하고, 삼각파 반복과 " +
            "attack/hold/release soak로 bus 획득·해제 및 복구 지연을 관찰합니다. " +
            "두 envelope 사이는 명시적 zero-load reset phase에서 전환합니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("triangle", "wave", "soak", "recovery"),
        phases = listOf(
            phase(
                "wr-base",
                "Triangle topology quiet baseline",
                4,
                10,
                120f,
                120f,
                motion = MotionProfile.ZOOM_PAN,
                gl = true,
            ),
            phase(
                "wr-triangle",
                "Two triangle load cycles",
                12,
                10,
                120f,
                120f,
                motion = MotionProfile.ZOOM_PAN,
                loads = LoadSetpoints(cpu = 0.5f, memory = 0.85f, gpu = 0.65f),
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.TRIANGLE_WAVE,
                    cycleMs = 6_000L,
                ),
            ),
            phase(
                "wr-reset",
                "Zero-load reset · soak topology armed",
                3,
                12,
                120f,
                120f,
                motion = MotionProfile.TRANSFORM_STORM,
                gl = true,
            ),
            phase(
                "wr-soak",
                "3s attack · 8s hold · 3s release",
                14,
                12,
                120f,
                120f,
                motion = MotionProfile.TRANSFORM_STORM,
                loads = LoadSetpoints(cpu = 0.55f, memory = 0.9f, gpu = 0.7f),
                gl = true,
                transition = TransitionSpec(
                    mode = TransitionMode.SOAK_RECOVERY,
                    transitionDurationMs = 3_000L,
                ),
            ),
            phase("wr-recover", "Post-soak recovery", 5, 4, 60f, 60f),
        ),
    )

    private fun npuCrossLoad() = ScenarioSpec(
        id = "npu-cross-load",
        name = "NPU Cross-load",
        description = "NNAPI vendor adapter burst와 3D/memory 부하를 교차 실행합니다. adapter가 없으면 UNSUPPORTED로 기록합니다.",
        category = ScenarioCategory.RESOURCE,
        risk = RiskLevel.HIGH,
        tags = setOf("NPU", "NNAPI", "3D"),
        requirements = setOf("NNAPI/NPU vendor adapter"),
        phases = listOf(
            phase(
                "npu",
                "NPU inference burst",
                12,
                6,
                60f,
                120f,
                loads = LoadSetpoints(npu = 0.9f, shape = LoadShape.PULSE),
            ),
            phase(
                "cross",
                "NPU + GPU + memory",
                16,
                8,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                loads = LoadSetpoints(memory = 0.55f, gpu = 0.7f, npu = 0.9f, shape = LoadShape.PULSE),
                gl = true,
            ),
            phase("recover", "Cross-load release", 7, 4, 60f, 60f),
        ),
    )

    private fun adaptiveHunt() = ScenarioSpec(
        id = "adaptive-underrun-hunt",
        name = "Multidimensional Adaptive Underrun Hunt",
        description = "layer 수, backend/alpha composition 경로와 memory 부하를 함께 올리는 " +
            "다축 staircase로 첫 underrun 경계와 recovery를 찾습니다. 축이 동시에 변하므로 " +
            "단일 원인 격리 결과로 해석하지 않습니다.",
        category = ScenarioCategory.ADAPTIVE,
        risk = RiskLevel.HIGH,
        tags = setOf("search", "boundary", "recovery", "multidimensional", "combined axes"),
        phases = listOf(2, 4, 6, 8, 10, 12, 16).mapIndexed { index, layers ->
            phase(
                "hunt-$index",
                "Boundary step $layers L",
                7,
                layers,
                120f,
                120f,
                backend = if (layers < 8) LayerBackend.INDEPENDENT_SURFACES else LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.TRANSFORM_STORM,
                // Hold each boundary setpoint through the end-of-phase counter sample. A legacy
                // 6 s sawtooth ramp inside this 7 s phase reset before that boundary.
                loads = LoadSetpoints(
                    memory = (index * 0.13f).coerceAtMost(0.9f),
                    shape = LoadShape.STEADY,
                ),
                alpha = layers >= 8,
            )
        } + phase("hunt-recover", "Recovery check", 8, 2, 60f, 60f),
    )

    private fun sbwcMatrix() = ScenarioSpec(
        id = "sbwc-matrix",
        name = "Linear ↔ SBWC A/B",
        description = "YUV와 SBWC는 선택한 동일 decoder 콘텐츠로 비교하고 RGB linear는 " +
            "procedural reference로 사용합니다. SBWC 검증은 vendor gralloc adapter가 필수입니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.MEDIUM,
        tags = setOf("SBWC", "compression", "A/B"),
        requirements = setOf("SBWC vendor adapter", "4K local media 권장"),
        phases = listOf(
            phase(
                "linear",
                "RGB linear reference",
                8,
                6,
                60f,
                120f,
                route = PixelRoute.RGB_8888,
                size = BufferSize.UHD_4K,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            phase(
                "yuv",
                "YUV codec reference",
                8,
                6,
                60f,
                120f,
                route = PixelRoute.YUV_420,
                size = BufferSize.UHD_4K,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            phase(
                "sbwc",
                "SBWC required",
                8,
                6,
                60f,
                120f,
                route = PixelRoute.SBWC_REQUIRED,
                size = BufferSize.UHD_4K,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            phase("restore", "Linear recovery", 6, 4, 60f, 60f),
        ),
    )

    private fun soak() = ScenarioSpec(
        id = "mixed-soak",
        name = "Mixed Stress Soak",
        description = "transform, frame pacing, CPU/memory/3D pulse를 장시간 반복하는 열/회귀 테스트입니다.",
        category = ScenarioCategory.SOAK,
        risk = RiskLevel.HIGH,
        tags = setOf("soak", "thermal", "regression"),
        requirements = setOf("외부 전원 권장"),
        phases = (0 until 12).map { index ->
            phase(
                id = "soak-$index",
                label = "Soak cycle ${index + 1}",
                seconds = 16,
                layers = 8 + (index % 3) * 2,
                fps = listOf(60f, 90f, 120f)[index % 3],
                hz = 120f,
                backend = if (index % 2 == 0) LayerBackend.MIXED_SURFACE_TEXTURE else LayerBackend.INDEPENDENT_SURFACES,
                motion = MotionProfile.TRANSFORM_STORM,
                loads = LoadSetpoints(
                    cpu = 0.35f + (index % 3) * 0.15f,
                    memory = 0.45f + (index % 2) * 0.3f,
                    gpu = 0.55f,
                    shape = LoadShape.PULSE,
                ),
                alpha = index % 2 == 0,
                gl = true,
            )
        } + phase("soak-cool", "Thermal cooldown", 20, 2, 60f, 60f),
    )

    private fun phase(
        id: String,
        label: String,
        seconds: Int,
        layers: Int,
        fps: Float,
        hz: Float,
        backend: LayerBackend = LayerBackend.INDEPENDENT_SURFACES,
        route: PixelRoute = PixelRoute.RGB_8888,
        size: BufferSize = BufferSize.DISPLAY,
        bufferPresentation: BufferPresentation = BufferPresentation.FIT,
        layerOrientation: LayerOrientation = LayerOrientation.ROTATION_0,
        motion: MotionProfile = MotionProfile.STATIC,
        layerSizeProfile: LayerSizeProfile = LayerSizeProfile.FULL_SCREEN,
        loads: LoadSetpoints = LoadSetpoints(),
        alpha: Boolean = false,
        gl: Boolean = false,
        transition: TransitionSpec = TransitionSpec(),
        hwcExpectation: HwcCompositionExpectation = HwcCompositionExpectation.NONE,
    ) = PhaseSpec(
        id = id,
        label = label,
        durationMs = seconds * 1_000L,
        activeLayers = layers,
        producerFps = fps,
        requestedDisplayHz = hz,
        backend = backend,
        pixelRoute = route,
        bufferSize = size,
        bufferPresentation = bufferPresentation,
        layerOrientation = layerOrientation,
        motion = motion,
        layerSizeProfile = layerSizeProfile,
        workloads = loads,
        alphaOverlap = alpha,
        includeGlLayer = gl,
        transition = transition,
        hwcCompositionExpectation = hwcExpectation,
    )
}
