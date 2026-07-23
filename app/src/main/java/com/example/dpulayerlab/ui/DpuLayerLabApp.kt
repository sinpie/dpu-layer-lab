package com.example.dpulayerlab.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.dpulayerlab.engine.LabController
import com.example.dpulayerlab.engine.ScenarioCatalog
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LayerTrafficEstimate
import com.example.dpulayerlab.model.LayerTrafficEstimator
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.RiskLevel
import com.example.dpulayerlab.model.RunProgress
import com.example.dpulayerlab.model.RunSummary
import com.example.dpulayerlab.model.RunVerdict
import com.example.dpulayerlab.model.RunnerStage
import com.example.dpulayerlab.model.ScenarioCategory
import com.example.dpulayerlab.model.ScenarioSpec
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.monitor.CapabilityScanner
import com.example.dpulayerlab.monitor.CapabilitySnapshot
import com.example.dpulayerlab.render.LayerStageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class AppSection(val label: String, val glyph: String) {
    DASHBOARD("대시보드", "●"),
    CATALOG("시나리오", "▦"),
    BUILDER("커스텀", "◇"),
    SYSTEM("시스템", "≡"),
    RUN("실행", "▶"),
    RESULT("결과", "✓"),
}

private data class RunningHudSample(
    val layerCount: Float,
    val dpuBusy: Float?,
    val cpuBusy: Float?,
    val gpuBusy: Float?,
)

private data class LiveHudMetricSpec(
    val label: String,
    val value: Float?,
    val valueText: String,
    val history: List<Float?>,
    val maxValue: Float,
    val color: Color,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpuLayerLabApp(controller: LabController) {
    var section by remember { mutableStateOf(AppSection.DASHBOARD) }
    val snackbar = remember { SnackbarHostState() }
    val progress = controller.progress
    val error = controller.errorMessage
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        controller.setMediaUri(uri)
    }

    LaunchedEffect(progress.stage) {
        section = when (progress.stage) {
            RunnerStage.PRECHECK,
            RunnerStage.WARMUP,
            RunnerStage.RUNNING,
            RunnerStage.COOLDOWN,
            -> AppSection.RUN

            RunnerStage.COMPLETE,
            RunnerStage.ABORTED,
            RunnerStage.UNSUPPORTED,
            -> AppSection.RESULT

            else -> section
        }
    }
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(error)
            controller.clearError()
        }
    }

    val immersive = section == AppSection.RUN
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!immersive) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (controller.telemetry.exactUnderruns != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        },
                                    ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("DPU Layer Lab", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    when {
                                        controller.telemetry.exactUnderruns != null ->
                                            "direct underrun counter connected"
                                        controller.hasDumpPermission ->
                                            "privileged composition · proxy mode"
                                        else ->
                                            "portable telemetry · proxy mode"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!immersive) {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    listOf(
                        AppSection.DASHBOARD,
                        AppSection.CATALOG,
                        AppSection.BUILDER,
                        AppSection.SYSTEM,
                    ).forEach { item ->
                        NavigationBarItem(
                            selected = section == item,
                            onClick = { section = item },
                            icon = { Text(item.glyph, fontSize = 18.sp) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (section) {
            AppSection.DASHBOARD -> DashboardScreen(
                controller = controller,
                modifier = Modifier.padding(padding),
                openCatalog = { section = AppSection.CATALOG },
            )

            AppSection.CATALOG -> CatalogScreen(
                controller = controller,
                modifier = Modifier.padding(padding),
                selectMedia = { mediaPicker.launch(arrayOf("video/*")) },
            )

            AppSection.BUILDER -> BuilderScreen(
                controller = controller,
                modifier = Modifier.padding(padding),
            )

            AppSection.SYSTEM -> SystemScreen(
                controller = controller,
                modifier = Modifier.padding(padding),
            )

            AppSection.RUN -> RunningScreen(controller)
            AppSection.RESULT -> ResultScreen(
                controller = controller,
                modifier = Modifier.padding(padding),
                onDone = {
                    controller.dismissResult()
                    section = AppSection.DASHBOARD
                },
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    controller: LabController,
    modifier: Modifier = Modifier,
    openCatalog: () -> Unit,
) {
    val telemetry = controller.telemetry
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        item {
            StatusHero(telemetry, controller.hasDumpPermission)
        }
        item {
            MetricGrid(telemetry)
        }
        item {
            TrendCard(controller)
        }
        controller.lastSummary?.let { summary ->
            item {
                LastResultCard(summary, controller::shareLastReport)
            }
        }
        item {
            Text("빠른 실행", style = MaterialTheme.typography.headlineMedium)
        }
        items(
            ScenarioCatalog.presets.filter {
                it.id in setOf("baseline-display-modes", "plane-staircase", "transform-storm")
            },
            key = { it.id },
        ) { scenario ->
            CompactScenarioCard(scenario) { controller.startScenario(scenario) }
        }
        item {
            OutlinedButton(onClick = openCatalog, modifier = Modifier.fillMaxWidth()) {
                Text("전체 시나리오 카탈로그 열기")
            }
        }
    }
}

@Composable
private fun StatusHero(telemetry: TelemetrySnapshot, hasDumpPermission: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (telemetry.exactUnderruns != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (telemetry.exactUnderruns != null) "DPU counter online" else "Proxy detection mode",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (telemetry.exactUnderruns != null) {
                            "직접 underrun 누적 ${telemetry.exactUnderruns}"
                        } else {
                            "UI deadline miss는 underrun으로 확정하지 않습니다."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    Text(
                        if (hasDumpPermission) "PRIV" else "PORTABLE",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeroValue("suspected", telemetry.suspectedUnderruns.toString())
                HeroValue("display", telemetry.displayHz.display(1))
                HeroValue("thermal", telemetry.thermalLabel)
            }
        }
    }
}

@Composable
private fun HeroValue(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun MetricGrid(telemetry: TelemetrySnapshot) {
    val metrics = listOf(
        Triple("AP CPU", telemetry.cpu, "전체 CPU"),
        Triple("APP CPU", telemetry.appCpu, "프로세스"),
        Triple("MEMORY", telemetry.memoryUsed, telemetry.memoryAvailable.display()),
        Triple("PRODUCER", telemetry.producedFps, "primary layer"),
        Triple("GPU BUSY", telemetry.gpuBusy, telemetry.gpuFrequency.display()),
        Triple("MEM BUS", telemetry.busBusy, "gen ${telemetry.generatedBandwidth.display(2)}"),
        Triple(
            "DPU BUSY",
            telemetry.dpuBusy,
            telemetry.surfaceFlingerHwcMissed?.let { "SF HWC miss $it · proxy" } ?: "vendor counter",
        ),
        Triple(
            "HWC D / C",
            Gauge(
                telemetry.hwcDeviceLayers?.toFloat(),
                "",
                if (telemetry.hwcDeviceLayers != null) MetricQuality.SYSTEM_SERVICE else MetricQuality.UNAVAILABLE,
                "SurfaceFlinger",
            ),
            "${telemetry.hwcDeviceLayers ?: "–"} / ${telemetry.hwcClientLayers ?: "–"}",
        ),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .height(456.dp),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(metrics) { (label, gauge, detail) ->
            MetricCard(label, gauge, detail)
        }
    }
}

@Composable
private fun MetricCard(label: String, gauge: Gauge, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(qualityColor(gauge.quality)),
                )
            }
            Text(
                if (label == "HWC D / C") detail else gauge.display(if (gauge.unit.contains("fps") || gauge.unit.contains("Hz")) 1 else 0),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
            )
            Text(
                if (label == "HWC D / C") "DEVICE / CLIENT" else detail,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrendCard(controller: LabController) {
    val cpu = controller.telemetryHistory.mapNotNull { it.cpu.value }
    val memory = controller.telemetryHistory.mapNotNull { it.memoryUsed.value }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val memoryColor = MaterialTheme.colorScheme.secondary
    val cpuColor = MaterialTheme.colorScheme.primary
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("60초 AP load", style = MaterialTheme.typography.titleMedium)
                Text("CPU · MEMORY", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(12.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(90.dp),
            ) {
                fun line(values: List<Float>, color: Color) {
                    if (values.size < 2) return
                    val path = Path()
                    values.forEachIndexed { index, value ->
                        val x = index.toFloat() / (values.size - 1) * size.width
                        val y = size.height - value.coerceIn(0f, 100f) / 100f * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                }
                drawLine(
                    gridColor,
                    Offset(0f, size.height * 0.5f),
                    Offset(size.width, size.height * 0.5f),
                )
                line(memory, memoryColor)
                line(cpu, cpuColor)
            }
        }
    }
}

@Composable
private fun CatalogScreen(
    controller: LabController,
    modifier: Modifier,
    selectMedia: () -> Unit,
) {
    var category by remember { mutableStateOf<ScenarioCategory?>(null) }
    val scenarios = remember(category) {
        ScenarioCatalog.presets.filter { category == null || it.category == category }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Test catalog", style = MaterialTheme.typography.displaySmall)
                Text(
                    "부하를 올리는 구간과 다시 내리는 recovery 구간을 모두 기록합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            MediaSourceCard(controller.selectedMediaUri, selectMedia) {
                controller.setMediaUri(null)
            }
        }
        item {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text("전체") },
                )
                ScenarioCategory.entries.forEach { item ->
                    FilterChip(
                        selected = category == item,
                        onClick = { category = item },
                        label = { Text(item.label) },
                    )
                }
            }
        }
        items(scenarios, key = { it.id }) { scenario ->
            ScenarioCard(scenario) { controller.startScenario(scenario) }
        }
    }
}

@Composable
private fun MediaSourceCard(uri: android.net.Uri?, selectMedia: () -> Unit, clear: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Decoder media", style = MaterialTheme.typography.titleMedium)
            Text(
                uri?.lastPathSegment ?: "4K/8K YUV 시나리오에 사용할 로컬 영상을 선택하세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = selectMedia) { Text(if (uri == null) "영상 선택" else "영상 변경") }
                if (uri != null) OutlinedButton(onClick = clear) { Text("해제") }
            }
        }
    }
}

@Composable
private fun ScenarioCard(scenario: ScenarioSpec, run: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(scenario.category.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(scenario.name, style = MaterialTheme.typography.titleLarge)
                }
                RiskBadge(scenario.risk)
            }
            Text(scenario.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                scenario.tags.forEach { tag ->
                    Surface(shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(tag, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (scenario.requirements.isNotEmpty()) {
                Text(
                    "필요: ${scenario.requirements.joinToString()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatDuration(scenario.durationMs)} · ${scenario.phases.size} phases · 최대 ${scenario.maxLayers}L / ${scenario.maxHz.toInt()}Hz",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = run) { Text("실행") }
            }
        }
    }
}

@Composable
private fun CompactScenarioCard(scenario: ScenarioSpec, run: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("${scenario.maxLayers}L", fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(scenario.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatDuration(scenario.durationMs)} · ${scenario.maxHz.toInt()}Hz",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Button(onClick = run, contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)) {
                Text("RUN")
            }
        }
    }
}

@Composable
private fun BuilderScreen(controller: LabController, modifier: Modifier) {
    var layers by remember { mutableIntStateOf(8) }
    var duration by remember { mutableIntStateOf(30) }
    var fps by remember { mutableFloatStateOf(120f) }
    var hz by remember { mutableFloatStateOf(120f) }
    var cpu by remember { mutableFloatStateOf(0.25f) }
    var memory by remember { mutableFloatStateOf(0.55f) }
    var gpu by remember { mutableFloatStateOf(0.35f) }
    var npu by remember { mutableFloatStateOf(0f) }
    var backend by remember { mutableStateOf(LayerBackend.MIXED_SURFACE_TEXTURE) }
    var route by remember { mutableStateOf(PixelRoute.RGB_8888) }
    var size by remember { mutableStateOf(BufferSize.DISPLAY) }
    var motion by remember { mutableStateOf(MotionProfile.TRANSFORM_STORM) }
    var shape by remember { mutableStateOf(LoadShape.PULSE) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Scenario builder", style = MaterialTheme.typography.displaySmall)
            Text(
                "현재 장치에서 layer·pacing·외부 자원 부하를 직접 조합합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            BuilderCard("DISPLAY & LAYERS") {
                LabeledSlider("독립/논리 layer", layers.toString(), layers.toFloat(), 1f..20f, 18) {
                    layers = it.roundToInt()
                }
                LabeledSlider("실행 시간", "${duration}s", duration.toFloat(), 10f..180f, 16) {
                    duration = it.roundToInt()
                }
                LabeledSlider("Producer FPS", "${fps.roundToInt()} fps", fps, 30f..120f, 5) { fps = it }
                LabeledSlider("Display request", "${hz.roundToInt()} Hz", hz, 60f..120f, 5) { hz = it }
                EnumSelector("합성 경로", backend, LayerBackend.entries) { backend = it }
                EnumSelector("Pixel route", route, PixelRoute.entries) { route = it }
                EnumSelector("Buffer size", size, BufferSize.entries) { size = it }
                EnumSelector("Motion", motion, MotionProfile.entries) { motion = it }
            }
        }
        item {
            BuilderCard("CROSS-LOAD") {
                LabeledSlider("CPU math", "${(cpu * 100).roundToInt()}%", cpu, 0f..1f, 9) { cpu = it }
                LabeledSlider("Memory copy", "${(memory * 100).roundToInt()}%", memory, 0f..1f, 9) { memory = it }
                LabeledSlider("GPU 3D", "${(gpu * 100).roundToInt()}%", gpu, 0f..1f, 9) { gpu = it }
                LabeledSlider("NPU adapter", "${(npu * 100).roundToInt()}%", npu, 0f..1f, 9) { npu = it }
                EnumSelector("Load shape", shape, LoadShape.entries) { shape = it }
                if (npu > 0f && !controller.hasNpuAdapter) {
                    Text(
                        "NPU adapter가 연결되지 않아 이 구성은 UNSUPPORTED로 기록됩니다.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        item {
            val custom = ScenarioCatalog.custom(
                layers = layers,
                durationSeconds = duration,
                producerFps = fps,
                requestedHz = hz,
                backend = backend,
                pixelRoute = route,
                bufferSize = size,
                motion = motion,
                loads = LoadSetpoints(cpu, memory, gpu, npu, shape),
            )
            Button(
                onClick = { controller.startScenario(custom) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text("CUSTOM LAB 실행")
            }
        }
    }
}

@Composable
private fun BuilderCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun <T> EnumSelector(label: String, value: T, values: List<T>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(enumLabel(value))
                Spacer(Modifier.width(7.dp))
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(enumLabel(item)) },
                        onClick = {
                            onSelect(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun enumLabel(value: Any?): String = when (value) {
    is LayerBackend -> value.label
    is PixelRoute -> value.label
    is BufferSize -> value.label
    is MotionProfile -> value.label
    is LoadShape -> value.label
    else -> value.toString()
}

@Composable
private fun RunningScreen(controller: LabController) {
    val progress = controller.progress
    val phase = progress.phase
    var stageView by remember { mutableStateOf<LayerStageView?>(null) }
    var stageWidthPx by remember { mutableIntStateOf(0) }
    var stageHeightPx by remember { mutableIntStateOf(0) }
    var hudSamples by remember(progress.scenario?.id) {
        mutableStateOf(emptyList<RunningHudSample>())
    }
    val telemetry = controller.telemetry

    LaunchedEffect(telemetry.monotonicMs, phase?.id) {
        val activePhase = phase ?: return@LaunchedEffect
        if (telemetry.monotonicMs <= 0L) return@LaunchedEffect
        hudSamples = (
            hudSamples + RunningHudSample(
                layerCount = activePhase.activeLayers.toFloat(),
                dpuBusy = telemetry.dpuBusy.value,
                cpuBusy = telemetry.cpu.value,
                gpuBusy = telemetry.gpuBusy.value,
            )
            ).takeLast(60)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (phase != null) {
            AndroidView(
                factory = { context ->
                    LayerStageView(context).also { stageView = it }
                },
                update = { stage ->
                    stage.configure(
                        newPhase = phase,
                        selectedMedia = controller.selectedMediaUri,
                        onPrimaryFrame = controller.frameTracker::onPrimaryBufferProduced,
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        stageWidthPx = it.width
                        stageHeightPx = it.height
                    },
            )
        }
        RunningHud(
            progress = progress,
            telemetry = telemetry,
            history = hudSamples,
            stageWidthPx = stageWidthPx,
            stageHeightPx = stageHeightPx,
            mediaSelected = controller.selectedMediaUri != null,
            mediaWidthPx = controller.selectedMediaWidthPx,
            mediaHeightPx = controller.selectedMediaHeightPx,
            stop = controller::stopScenario,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            stageView?.release()
            stageView = null
        }
    }
}

@Composable
private fun RunningHud(
    progress: RunProgress,
    telemetry: TelemetrySnapshot,
    history: List<RunningHudSample>,
    stageWidthPx: Int,
    stageHeightPx: Int,
    mediaSelected: Boolean,
    mediaWidthPx: Int?,
    mediaHeightPx: Int?,
    stop: () -> Unit,
) {
    val phase = progress.phase
    val compactLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val traffic = phase?.let {
        LayerTrafficEstimator.estimate(
            phase = it,
            displayWidthPx = stageWidthPx,
            displayHeightPx = stageHeightPx,
            measuredDisplayHz = telemetry.displayHz.value,
            mediaSelected = mediaSelected,
            mediaWidthPx = mediaWidthPx,
            mediaHeightPx = mediaHeightPx,
        )
    }
    val layerScale = maxOf(
        1,
        progress.scenario?.maxLayers ?: 1,
        phase?.activeLayers ?: 1,
    ).toFloat()
    val liveMetrics = listOf(
        LiveHudMetricSpec(
            label = "LAYERS",
            value = phase?.activeLayers?.toFloat(),
            valueText = phase?.activeLayers?.toString() ?: "N/A",
            history = history.map { it.layerCount },
            maxValue = layerScale,
            color = Color(0xFF65E6C4),
        ),
        LiveHudMetricSpec(
            label = "DPU",
            value = telemetry.dpuBusy.value,
            valueText = telemetry.dpuBusy.display(),
            history = history.map { it.dpuBusy },
            maxValue = 100f,
            color = Color(0xFFFFC857),
        ),
        LiveHudMetricSpec(
            label = "CPU",
            value = telemetry.cpu.value,
            valueText = telemetry.cpu.display(),
            history = history.map { it.cpuBusy },
            maxValue = 100f,
            color = Color(0xFF4CC9F0),
        ),
        LiveHudMetricSpec(
            label = "GPU",
            value = telemetry.gpuBusy.value,
            valueText = telemetry.gpuBusy.display(),
            history = history.map { it.gpuBusy },
            maxValue = 100f,
            color = Color(0xFFC77DFF),
        ),
    )

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = if (compactLandscape) 620.dp else 370.dp)
                .fillMaxWidth(),
            color = Color(0xD90A1512),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            progress.scenario?.name ?: "Preparing",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${progress.phaseIndex + 1}/${progress.scenario?.phases?.size ?: 0} · ${progress.statusText}",
                            color = Color(0xFFB8CBC5),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(color = Color(0xFF0F332C), shape = RoundedCornerShape(100.dp)) {
                        Text(
                            progress.stage.name,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Color(0xFF65E6C4),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { progress.overallFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF65E6C4),
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
                if (compactLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        liveMetrics.chunked(2).forEach { columnMetrics ->
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                columnMetrics.forEach { metric ->
                                    LiveHudMetric(
                                        label = metric.label,
                                        value = metric.value,
                                        valueText = metric.valueText,
                                        history = metric.history,
                                        maxValue = metric.maxValue,
                                        color = metric.color,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    liveMetrics.forEach { metric ->
                        LiveHudMetric(
                            label = metric.label,
                            value = metric.value,
                            valueText = metric.valueText,
                            history = metric.history,
                            maxValue = metric.maxValue,
                            color = metric.color,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.13f))
                TrafficHud(traffic, compact = compactLandscape)
            }
        }
        Surface(
            color = Color(0xD90A1512),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                Modifier.padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        progress.phase?.let { "${it.activeLayers}L · ${it.backend.label}" } ?: "surface 준비",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        progress.phase?.let {
                            "${it.pixelRoute.label} · ${it.producerFps.toInt()}fps · ${it.workloads.summary()}"
                        } ?: "부하 없음",
                        color = Color(0xFFB8CBC5),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                Button(
                    onClick = stop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("STOP")
                }
            }
        }
    }
}

@Composable
private fun LiveHudMetric(
    label: String,
    value: Float?,
    valueText: String,
    history: List<Float?>,
    maxValue: Float,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(31.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(50.dp),
            color = Color(0xFF86A39A),
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
        )
        Text(
            valueText,
            modifier = Modifier.width(58.dp),
            color = if (value == null) Color(0xFF86A39A) else color,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        OccupancyGraph(
            current = value,
            history = history,
            maxValue = maxValue,
            color = color,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.055f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(7.dp),
                ),
        )
    }
}

@Composable
private fun OccupancyGraph(
    current: Float?,
    history: List<Float?>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val safeMax = maxValue.coerceAtLeast(1f)
        current?.let {
            val ratio = (it / safeMax).coerceIn(0f, 1f)
            drawRect(
                color = color.copy(alpha = 0.14f),
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(size.width * ratio, size.height),
            )
        }
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(0f, size.height * 0.5f),
            end = Offset(size.width, size.height * 0.5f),
            strokeWidth = 1.dp.toPx(),
        )

        val visible = history.takeLast(60)
        if (visible.isNotEmpty()) {
            val path = Path()
            var hasPoint = false
            var previousWasValue = false
            visible.forEachIndexed { index, sample ->
                if (sample == null) {
                    previousWasValue = false
                } else {
                    val x = if (visible.size == 1) {
                        size.width
                    } else {
                        index.toFloat() / (visible.size - 1).toFloat() * size.width
                    }
                    val y = size.height -
                        (sample / safeMax).coerceIn(0f, 1f) * size.height
                    if (previousWasValue) path.lineTo(x, y) else path.moveTo(x, y)
                    previousWasValue = true
                    hasPoint = true
                }
            }
            if (hasPoint) {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun TrafficHud(traffic: LayerTrafficEstimate?, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "EXPECTED LAYER TRAFFIC",
                color = Color(0xFF86A39A),
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
            )
            Text(
                traffic?.bytesPerFrame.formatFrameBytes(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
        }
        Text(
            if (traffic == null) {
                "DPU R N/A · producer W N/A"
            } else {
                "DPU R ${traffic.dpuReadBytesPerSecond.formatTrafficRate()} · " +
                    "producer W ${traffic.producerWriteBytesPerSecond.formatTrafficRate()}"
            },
            color = Color(0xFF65E6C4),
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Text(
            traffic?.let {
                val compressionNote = if (it.compressionRatioExcluded) {
                    " · SBWC ratio 제외"
                } else {
                    ""
                }
                "${it.logicalLayerCount} logical / ${it.producerLayerCount} producer · " +
                    "${it.formatLabel}$compressionNote"
            } ?: "stage size를 기다리는 중",
            color = Color(0xFFB8CBC5),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact) {
            Text(
                traffic?.resolutionLabel ?: "linear full-buffer read/write estimate",
                color = Color(0xFF789087),
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Double?.formatFrameBytes(): String =
    this?.let { "%.1f MiB/frame".format(it / (1024.0 * 1024.0)) } ?: "N/A"

private fun Double?.formatTrafficRate(): String = when {
    this == null -> "N/A"
    this >= 1_000_000_000.0 -> "%.2f GB/s".format(this / 1_000_000_000.0)
    else -> "%.0f MB/s".format(this / 1_000_000.0)
}

@Composable
private fun ResultScreen(controller: LabController, modifier: Modifier, onDone: () -> Unit) {
    val summary = controller.lastSummary
    if (summary == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(controller.progress.statusText)
        }
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ResultHero(summary)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ResultRow("Exact underrun Δ", summary.exactUnderrunDelta?.toString() ?: "N/A")
                    ResultRow(
                        "Exact counter source",
                        summary.exactUnderrunSource ?: "N/A",
                    )
                    ResultRow("Suspected proxy Δ", summary.suspectedUnderrunDelta.toString())
                    ResultRow("Peak CPU", summary.peakCpu?.let { "%.1f%%".format(it) } ?: "N/A")
                    ResultRow("Peak memory", summary.peakMemoryUsed?.let { "%.1f%%".format(it) } ?: "N/A")
                    ResultRow(
                        "Generated bus traffic",
                        summary.peakGeneratedBandwidth?.let { "%.2f Gbps".format(it) } ?: "N/A",
                    )
                    ResultRow("Samples", summary.samples.size.toString())
                }
            }
        }
        item {
            Text(
                when (summary.verdict) {
                    RunVerdict.SUSPECTED_PROXY ->
                        "프레임 deadline miss가 관찰됐지만 DPU underrun으로 확정할 직접 counter는 없습니다."
                    RunVerdict.INCONCLUSIVE ->
                        "직접 counter가 연결되지 않았고 proxy 이상도 없으므로 CLEAN으로 단정하지 않습니다."
                    RunVerdict.CLEAN ->
                        "직접 underrun counter 기준으로 증가가 없었습니다."
                    else -> "결과 보고서에는 phase, 요청/실제 Hz, telemetry source와 event가 함께 저장됩니다."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = controller::shareLastReport, modifier = Modifier.weight(1f)) {
                    Text("JSON 보고서 공유")
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("완료")
                }
            }
        }
        controller.lastReportFile?.let { file ->
            item {
                Text(
                    file.absolutePath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ResultHero(summary: RunSummary) {
    val color = when (summary.verdict) {
        RunVerdict.CLEAN -> MaterialTheme.colorScheme.primaryContainer
        RunVerdict.UNDERRUN_DETECTED -> MaterialTheme.colorScheme.error
        RunVerdict.SUSPECTED_PROXY -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(summary.verdict.label, style = MaterialTheme.typography.displaySmall)
            Text(summary.scenario.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${formatDuration(summary.finishedEpochMs - summary.startedEpochMs)} · ${summary.scenario.phases.size} phases",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            modifier = Modifier.weight(0.45f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.55f),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SystemScreen(controller: LabController, modifier: Modifier) {
    val context = LocalContext.current
    var capabilities by remember { mutableStateOf<CapabilitySnapshot?>(null) }
    LaunchedEffect(controller.hasDumpPermission, controller.hasNpuAdapter) {
        capabilities = withContext(Dispatchers.Default) {
            CapabilityScanner.scan(
                activity = context as Activity,
                hasDumpPermission = controller.hasDumpPermission,
                hasNpuAdapter = controller.hasNpuAdapter,
                hasSbwcAdapter = controller.hasSbwcAdapter,
            )
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("System & capability", style = MaterialTheme.typography.displaySmall)
            Text(
                "지원 여부와 실제 사용 여부를 분리해 표시합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            PermissionCard(controller)
        }
        capabilities?.let { snapshot ->
            item {
                CapabilityCard(snapshot)
            }
            item {
                DisplayModesCard(snapshot)
            }
            item {
                CodecCard(snapshot)
            }
        } ?: item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Text("codec / HardwareBuffer capability 스캔 중…", modifier = Modifier.padding(18.dp))
            }
        }
        item {
            DirectSensorCard(controller)
        }
        item {
            IntegrationNote()
        }
    }
}

@Composable
private fun PermissionCard(controller: LabController) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Telemetry backends", style = MaterialTheme.typography.titleLarge)
            CapabilityRow("Portable API", true, "CPU · memory · FPS · thermal")
            CapabilityRow("SurfaceFlinger DUMP", controller.hasDumpPermission, "DEVICE / CLIENT layer")
            CapabilityRow("NPU adapter", controller.hasNpuAdapter, controller.telemetry.npuState)
            CapabilityRow(
                "DPU counter",
                controller.telemetry.exactUnderruns != null,
                controller.telemetry.dpuBusy.source.ifBlank { "vendor service/sysfs 필요" },
            )
        }
    }
}

@Composable
private fun CapabilityCard(snapshot: CapabilitySnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("HardwareBuffer allocation", style = MaterialTheme.typography.titleLarge)
            CapabilityRow("RGBA 4K", snapshot.rgba4k, "composer overlay usage")
            CapabilityRow("RGBA 8K", snapshot.rgba8k, "composer overlay usage")
            CapabilityRow("YUV 4K", snapshot.yuv4k, "YCbCr 420 allocation")
            CapabilityRow("YUV 8K", snapshot.yuv8k, "YCbCr 420 allocation")
            CapabilityRow("SBWC adapter", snapshot.sbwcAdapter, "AOSP 표준 포맷 아님")
        }
    }
}

@Composable
private fun CapabilityRow(label: String, available: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(
            if (available) detail else "Unavailable · $detail",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DisplayModesCard(snapshot: CapabilitySnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Display modes", style = MaterialTheme.typography.titleLarge)
            snapshot.displayModes.take(12).forEach {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (snapshot.displayModes.size > 12) {
                Text("+${snapshot.displayModes.size - 12} modes", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CodecCard(snapshot: CapabilitySnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("Hardware video decoders", style = MaterialTheme.typography.titleLarge)
            if (snapshot.codecs.isEmpty()) {
                Text("4K/8K capability를 보고한 decoder가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            snapshot.codecs.take(10).forEach { codec ->
                Column {
                    Text(codec.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${codec.mime} · 4K60 ${yesNo(codec.supports4k60)} · 8K30 ${yesNo(codec.supports8k30)} · 8K60 ${yesNo(codec.supports8k60)} · max ${codec.maxInstances}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectSensorCard(controller: LabController) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Direct kernel sensors", style = MaterialTheme.typography.titleLarge)
            if (controller.directSensors.isEmpty()) {
                Text(
                    "읽을 수 있는 GPU/bus/DPU counter가 없습니다. 값은 N/A로 유지됩니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            controller.directSensors.forEach { sensor ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(sensor.label, style = MaterialTheme.typography.titleMedium)
                        Text(sensor.source, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    Text(sensor.value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun IntegrationNote() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("System image integration", style = MaterialTheme.typography.titleLarge)
            Text(
                "플랫폼 서명만으로 sysfs/debugfs 접근이 생기지는 않습니다. priv-app allowlist와 SELinux 정책을 적용하고, 제품 빌드에서는 DPU/DDR/SBWC counter를 stable AIDL vendor service로 제공하는 구성이 권장됩니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "테스트 앱은 직접 counter가 없을 때 추정값을 DPU 점유율이나 underrun으로 둔갑시키지 않습니다.",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RiskBadge(risk: RiskLevel) {
    val color = when (risk) {
        RiskLevel.LOW -> MaterialTheme.colorScheme.primary
        RiskLevel.MEDIUM -> MaterialTheme.colorScheme.secondary
        RiskLevel.HIGH -> MaterialTheme.colorScheme.tertiary
    }
    Surface(shape = RoundedCornerShape(100.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            risk.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = color,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LastResultCard(summary: RunSummary, share: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("최근 결과 · ${summary.verdict.label}", style = MaterialTheme.typography.titleMedium)
                Text(summary.scenario.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = share) { Text("공유") }
        }
    }
}

@Composable
private fun qualityColor(quality: MetricQuality): Color = when (quality) {
    MetricQuality.HARDWARE_COUNTER -> MaterialTheme.colorScheme.primary
    MetricQuality.KERNEL -> MaterialTheme.colorScheme.secondary
    MetricQuality.SYSTEM_SERVICE -> Color(0xFF9CB8FF)
    MetricQuality.MEASURED -> Color(0xFF7DD5F5)
    MetricQuality.ESTIMATED,
    MetricQuality.PROXY,
    -> MaterialTheme.colorScheme.tertiary
    MetricQuality.UNAVAILABLE -> MaterialTheme.colorScheme.outline
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}

private fun yesNo(value: Boolean) = if (value) "✓" else "–"
