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

object ScenarioCatalog {
    val presets: List<ScenarioSpec> = listOf(
        baseline(),
        planeStaircase(),
        compositionPivot(),
        transformStorm(),
        mixed4k(),
        video8k(),
        refreshPacing(),
        resourcePulse(),
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
    ) = ScenarioSpec(
        id = "custom-${System.currentTimeMillis()}",
        name = "Custom Lab",
        description = "사용자가 지정한 단일 phase 테스트",
        category = ScenarioCategory.MIXED,
        risk = if (loads.memory > 0.75f || layers > 12) RiskLevel.HIGH else RiskLevel.MEDIUM,
        tags = setOf("custom", "${layers}L", "${producerFps.toInt()}fps"),
        phases = listOf(
            phase(
                id = "custom",
                label = "Custom workload",
                seconds = durationSeconds,
                layers = layers,
                fps = producerFps,
                hz = requestedHz,
                backend = backend,
                route = pixelRoute,
                size = bufferSize,
                motion = motion,
                loads = loads.normalized(),
                alpha = backend != LayerBackend.INDEPENDENT_SURFACES,
                gl = loads.gpu > 0.05f,
            ),
        ),
        isCustom = true,
    )

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
                gl = true,
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

    private fun video8k() = ScenarioSpec(
        id = "8k-decoder-pressure",
        name = "8K Decoder Pressure",
        description = "8K YUV 영상 Surface와 6개 overlay를 결합합니다. 장치 codec/asset capability를 반드시 확인합니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.HIGH,
        tags = setOf("8K", "HEVC", "AV1", "codec"),
        requirements = setOf("8K decoder", "8K local media"),
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
            phase(
                "8k60",
                "8K60 + overlays",
                12,
                7,
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
                loads = LoadSetpoints(memory = (index * 0.13f).coerceAtMost(0.9f), shape = LoadShape.RAMP),
                alpha = layers >= 8,
            )
        } + phase("hunt-recover", "Recovery check", 8, 2, 60f, 60f),
    )

    private fun sbwcMatrix() = ScenarioSpec(
        id = "sbwc-matrix",
        name = "Linear ↔ SBWC A/B",
        description = "동일 콘텐츠를 linear/YUV/SBWC로 비교합니다. SBWC 검증은 vendor gralloc adapter가 필수입니다.",
        category = ScenarioCategory.VIDEO_FORMAT,
        risk = RiskLevel.MEDIUM,
        tags = setOf("SBWC", "compression", "A/B"),
        requirements = setOf("SBWC vendor adapter"),
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
    )
}
