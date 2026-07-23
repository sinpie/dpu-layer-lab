package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.LayerBackend
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
        instantBurstTransitions(),
        gradualTransitions(),
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
        motion: MotionProfile,
        loads: LoadSetpoints,
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

        return ScenarioSpec(
            id = "custom-${System.currentTimeMillis()}-${customIdSequence.incrementAndGet()}",
            name = "Custom Lab",
            description =
                "사용자가 지정한 단일 phase 테스트.$topologyNote$flattenedNote",
            category = ScenarioCategory.MIXED,
            risk = if (
                normalizedLoads.memory > 0.75f || effectiveLayers > 12
            ) {
                RiskLevel.HIGH
            } else {
                RiskLevel.MEDIUM
            },
            tags = setOf(
                "custom",
                "${effectiveLayers}L",
                "${producerFps.toInt()}fps",
            ) + topologyTags + flattenedTags,
            phases = listOf(
                phase(
                    id = "custom",
                    label = when {
                        promotedToPrimaryAndGlTail ->
                            "Custom workload · requested 1L → 2L primary + GL tail"
                        flattenedInputNormalized ->
                            "Custom workload · flattened DISPLAY/RGB normalization"
                        else -> "Custom workload"
                    },
                    seconds = durationSeconds,
                    layers = effectiveLayers,
                    fps = producerFps,
                    hz = requestedHz,
                    backend = backend,
                    route = effectivePixelRoute,
                    size = effectiveBufferSize,
                    motion = motion,
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

    private fun midLoadPerturbation() = ScenarioSpec(
        id = "mid-load-perturbation",
        name = "Mid-load Perturbation Matrix",
        description = "최대 부하가 아닌 4~8 layer·60~90fps 구간에서 scroll, rotate, alpha, layer 증가와 CPU/DRAM 간섭을 한 항목씩 바꿔 취약점을 분리합니다.",
        category = ScenarioCategory.ADAPTIVE,
        risk = RiskLevel.MEDIUM,
        tags = setOf("mid load", "isolation", "60–90fps", "A/B"),
        phases = listOf(
            phase("mp-base", "4L static reference", 6, 4, 60f, 60f),
            phase("mp-scroll", "Scroll only", 5, 4, 60f, 60f, motion = MotionProfile.SCROLL),
            phase("mp-rotate", "Rotate only", 5, 4, 60f, 60f, motion = MotionProfile.ROTATE),
            phase("mp-layers", "8L plane step", 5, 8, 60f, 60f, motion = MotionProfile.PARALLAX),
            phase(
                "mp-alpha",
                "8L alpha/client step",
                5,
                8,
                60f,
                60f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.ZOOM_PAN,
                alpha = true,
            ),
            phase(
                "mp-90",
                "8L 90fps pacing",
                5,
                8,
                90f,
                90f,
                motion = MotionProfile.SCROLL,
            ),
            phase(
                "mp-bus",
                "Moderate CPU + DRAM contention",
                6,
                6,
                60f,
                60f,
                loads = LoadSetpoints(cpu = 0.45f, memory = 0.6f),
            ),
            phase("mp-recover", "Reference recovery", 6, 4, 60f, 60f),
        ),
    )

    private fun dvfsVideoShock() = ScenarioSpec(
        id = "dvfs-video-shock",
        name = "Idle → 4K Video Shock",
        description = "RGB 1-layer settle에서 4K decoder-to-Surface와 RGB overlay로 즉시 전환해 codec/DPU/DRAM 동시 ramp를 확인합니다. 영상이 없으면 visual proxy로 실행됩니다.",
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
        description = "불투명 독립 Surface를 단계적으로 추가하고 다시 줄여 HWC plane 한계와 recovery를 찾습니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf("HWC", "plane", "sweep"),
        phases = listOf(1, 2, 4, 6, 8, 12, 8, 4, 1).mapIndexed { index, layers ->
            phase(
                "p$index",
                "$layers independent layers",
                4,
                layers,
                60f,
                120f,
                motion = MotionProfile.PARALLAX,
            )
        },
    )

    private fun compositionPivot() = ScenarioSpec(
        id = "composition-pivot",
        name = "HWC ↔ GPU Composition Pivot",
        description = "동일한 10개 콘텐츠를 독립 Surface, 혼합, Texture 합성으로 A/B 전환합니다.",
        category = ScenarioCategory.LAYER_HWC,
        risk = RiskLevel.MEDIUM,
        tags = setOf("HWC", "GPU", "A/B"),
        phases = listOf(
            phase("device", "HWC-friendly surfaces", 6, 10, 60f, 120f),
            phase(
                "mixed",
                "Alpha + arbitrary transform",
                6,
                10,
                90f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                motion = MotionProfile.ROTATE,
                alpha = true,
            ),
            phase(
                "client",
                "Flattened Texture composition",
                6,
                10,
                120f,
                120f,
                backend = LayerBackend.FLATTENED_TEXTURE,
                motion = MotionProfile.TRANSFORM_STORM,
                alpha = true,
                loads = LoadSetpoints(gpu = 0.65f),
            ),
            phase("restore", "HWC recovery", 5, 4, 60f, 60f),
        ),
    )

    private fun transformStorm() = ScenarioSpec(
        id = "transform-storm",
        name = "Transform Storm",
        description = "12개 layer가 서로 다른 위상으로 확대/축소/스크롤/회전/Z-order 전환합니다.",
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
            phase("z", "Z-order swap", 8, 12, 90f, 90f, motion = MotionProfile.Z_ORDER_SWAP),
            phase("cool", "Static recovery", 5, 2, 60f, 60f),
        ),
    )

    private fun mixed4k() = ScenarioSpec(
        id = "4k-mixed",
        name = "4K YUV + RGB Overlay",
        description = "4K decoder-to-Surface 경로와 RGB overlay, memory pulse를 함께 실행합니다. 영상 미선택 시 YUV proxy입니다.",
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
                14,
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
        name = "CPU / Memory / GPU Pulse",
        description = "고정 layer 위에서 CPU 연산, 메모리 copy, 3D 부하를 켰다 끄며 bus contention과 복구를 관찰합니다.",
        category = ScenarioCategory.RESOURCE,
        risk = RiskLevel.HIGH,
        tags = setOf("CPU", "memory", "3D", "pulse"),
        phases = listOf(
            phase("idle", "Idle baseline", 5, 6, 60f, 120f),
            phase(
                "cpu",
                "CPU math pulse",
                10,
                6,
                90f,
                120f,
                loads = LoadSetpoints(cpu = 0.85f, shape = LoadShape.PULSE),
            ),
            phase(
                "memory",
                "Memory bandwidth pulse",
                10,
                6,
                90f,
                120f,
                loads = LoadSetpoints(memory = 0.95f, shape = LoadShape.PULSE),
            ),
            phase(
                "gpu",
                "3D fragment pressure",
                10,
                7,
                120f,
                120f,
                backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                loads = LoadSetpoints(gpu = 0.9f, shape = LoadShape.PULSE),
                gl = true,
            ),
            phase("recover", "All load released", 7, 4, 60f, 60f),
        ),
    )

    private fun instantBurstTransitions() = ScenarioSpec(
        id = "instant-burst-transitions",
        name = "Instant Step & Burst",
        description = "layer·FPS·교차 부하를 즉시 켜고 끈 뒤 동일 topology에서 짧은 burst duty cycle을 반복합니다.",
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
            phase("ib-step-off", "Instant release", 4, 4, 60f, 60f, gl = true),
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
        name = "Linear Ramp & Staircase",
        description = "부하를 천천히 올리고 내리는 선형 ramp와 제한된 단계의 staircase를 비교합니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("linear", "staircase", "up", "down"),
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
                14,
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

    private fun waveRecoveryTransitions() = ScenarioSpec(
        id = "wave-soak-recovery",
        name = "Triangle Wave & Soak Recovery",
        description = "삼각파 반복과 attack/hold/release soak로 점진적인 bus 획득·해제 및 복구 지연을 관찰합니다.",
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.HIGH,
        tags = setOf("triangle", "wave", "soak", "recovery"),
        phases = listOf(
            phase("wr-base", "Quiet baseline", 4, 4, 60f, 60f, gl = true),
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
            phase("wr-reset", "Wave reset", 3, 4, 60f, 60f, gl = true),
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
                14,
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
        name = "Adaptive Underrun Hunt",
        description = "layer와 memory 부하 staircase를 자동 실행해 첫 underrun 경계와 recovery를 찾습니다.",
        category = ScenarioCategory.ADAPTIVE,
        risk = RiskLevel.HIGH,
        tags = setOf("search", "boundary", "recovery"),
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
            phase("linear", "RGB linear reference", 8, 6, 60f, 120f, route = PixelRoute.RGB_8888, size = BufferSize.UHD_4K),
            phase("yuv", "YUV codec reference", 8, 6, 60f, 120f, route = PixelRoute.YUV_420, size = BufferSize.UHD_4K),
            phase("sbwc", "SBWC required", 8, 6, 60f, 120f, route = PixelRoute.SBWC_REQUIRED, size = BufferSize.UHD_4K),
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
                seconds = 15,
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
        motion: MotionProfile = MotionProfile.STATIC,
        loads: LoadSetpoints = LoadSetpoints(),
        alpha: Boolean = false,
        gl: Boolean = false,
        transition: TransitionSpec = TransitionSpec(),
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
        motion = motion,
        workloads = loads,
        alphaOverlap = alpha,
        includeGlLayer = gl,
        transition = transition,
    )
}
