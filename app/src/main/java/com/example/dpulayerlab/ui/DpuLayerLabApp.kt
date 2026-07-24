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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.dpulayerlab.BuildConfig
import com.example.dpulayerlab.engine.LabController
import com.example.dpulayerlab.engine.ScenarioCatalog
import com.example.dpulayerlab.engine.GaugePeak
import com.example.dpulayerlab.engine.consistentGaugePeak
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.DecoderLinearReference
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LayerTrafficEstimate
import com.example.dpulayerlab.model.LayerTrafficEstimator
import com.example.dpulayerlab.model.LoadTransitionEvaluator
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.PlanProgress
import com.example.dpulayerlab.model.PlanRunResult
import com.example.dpulayerlab.model.PlanSource
import com.example.dpulayerlab.model.PlanState
import com.example.dpulayerlab.model.RiskLevel
import com.example.dpulayerlab.model.RunProgress
import com.example.dpulayerlab.model.RunSummary
import com.example.dpulayerlab.model.RunVerdict
import com.example.dpulayerlab.model.RunnerStage
import com.example.dpulayerlab.model.ScenarioCategory
import com.example.dpulayerlab.model.ScenarioChangePattern
import com.example.dpulayerlab.model.ScenarioClassifier
import com.example.dpulayerlab.model.ScenarioCondition
import com.example.dpulayerlab.model.ScenarioLoadBand
import com.example.dpulayerlab.model.ScenarioPlanPolicy
import com.example.dpulayerlab.model.ScenarioQueueEditor
import com.example.dpulayerlab.model.ScenarioRunPlan
import com.example.dpulayerlab.model.ScenarioSelectionFilter
import com.example.dpulayerlab.model.ScenarioSpec
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.model.terminalReason
import com.example.dpulayerlab.model.TransitionMode
import com.example.dpulayerlab.model.TransitionSpec
import com.example.dpulayerlab.monitor.CapabilityScanner
import com.example.dpulayerlab.monitor.CapabilitySnapshot
import com.example.dpulayerlab.render.LayerStageView
import com.example.dpulayerlab.render.ProducerFrameCallback
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
    val dpuBusy: Gauge,
    val cpuBusy: Gauge,
    val gpuBusy: Gauge,
)

private data class LiveHudMetricSpec(
    val label: String,
    val provenance: String,
    val value: Float?,
    val valueText: String,
    val history: List<Float?>,
    val maxValue: Float,
    val color: Color,
)

private data class DashboardMetricSpec(
    val label: String,
    val gauge: Gauge,
    val detail: String,
    val valueText: String? = null,
)

private data class ScenarioOverview(
    val patternLabel: String,
    val intensityScore: Int,
    val intensityLabel: String,
    val phaseSequence: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpuLayerLabApp(controller: LabController) {
    var section by remember { mutableStateOf(AppSection.DASHBOARD) }
    var selectedScenarioIds by rememberSaveable {
        mutableStateOf<List<String>>(arrayListOf())
    }
    var planRepeatCount by rememberSaveable { mutableIntStateOf(1) }
    val knownScenarioIds = remember {
        ScenarioCatalog.presets.mapTo(LinkedHashSet()) { it.id }
    }
    val validatedScenarioIds = remember(selectedScenarioIds, knownScenarioIds) {
        ScenarioQueueEditor.retainKnown(selectedScenarioIds, knownScenarioIds)
    }
    val validatedRepeatCount = remember(validatedScenarioIds.size, planRepeatCount) {
        ScenarioPlanPolicy.normalizeRepeatCount(
            queueSize = validatedScenarioIds.size,
            requested = planRepeatCount,
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val progress = controller.progress
    val planProgress = controller.planProgress
    val error = controller.errorMessage
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::setMediaUri)
    }

    LaunchedEffect(progress.stage, planProgress.state) {
        section = when {
            planProgress.active -> AppSection.RUN
            // A rejected start attempt is not a new run result. Preserve the screen that owns the
            // previous summary instead of combining old artifacts with the rejected plan metadata.
            planProgress.state == PlanState.REJECTED -> section
            else -> when (progress.stage) {
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
    }
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(error)
            controller.clearError()
        }
    }
    LaunchedEffect(
        validatedScenarioIds,
        selectedScenarioIds,
        validatedRepeatCount,
        planRepeatCount,
    ) {
        if (validatedScenarioIds != selectedScenarioIds) {
            selectedScenarioIds = validatedScenarioIds
        }
        if (planRepeatCount != validatedRepeatCount) {
            planRepeatCount = validatedRepeatCount
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
                                Text("DPULayerTest", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${visibleAppVersion(BuildConfig.VERSION_NAME)} · " +
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
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                    buildList {
                        add(AppSection.DASHBOARD)
                        add(AppSection.CATALOG)
                        add(AppSection.BUILDER)
                        add(AppSection.SYSTEM)
                        if (controller.lastSummary != null) add(AppSection.RESULT)
                    }.forEach { item ->
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
                selectedScenarioIds = validatedScenarioIds,
                repeatCount = validatedRepeatCount,
                addScenario = { scenarioId ->
                    val updated = ScenarioQueueEditor.append(
                        queue = validatedScenarioIds,
                        scenarioId = scenarioId,
                    )
                    selectedScenarioIds = updated
                    planRepeatCount = validatedRepeatCount.coerceAtMost(
                        maximumPlanRepeats(updated.size),
                    )
                },
                removeScenario = { scenarioId ->
                    selectedScenarioIds = ScenarioQueueEditor.removeLast(
                        queue = validatedScenarioIds,
                        scenarioId = scenarioId,
                    )
                },
                removeQueueAt = { index ->
                    selectedScenarioIds = ScenarioQueueEditor.removeAt(
                        queue = validatedScenarioIds,
                        index = index,
                    )
                },
                moveQueueItem = { fromIndex, toIndex ->
                    selectedScenarioIds = ScenarioQueueEditor.move(
                        queue = validatedScenarioIds,
                        fromIndex = fromIndex,
                        toIndex = toIndex,
                    )
                },
                appendFiltered = { scenarioIds ->
                    val updated = ScenarioQueueEditor.appendAll(
                        queue = validatedScenarioIds,
                        scenarioIds = scenarioIds,
                    )
                    selectedScenarioIds = updated
                    planRepeatCount = validatedRepeatCount.coerceAtMost(
                        maximumPlanRepeats(updated.size),
                    )
                },
                replaceWithFiltered = { scenarioIds ->
                    val updated = ScenarioQueueEditor.appendAll(
                        queue = emptyList(),
                        scenarioIds = scenarioIds,
                    )
                    selectedScenarioIds = updated
                    planRepeatCount = validatedRepeatCount.coerceAtMost(
                        maximumPlanRepeats(updated.size),
                    )
                },
                selectAll = {
                    val updated = ScenarioQueueEditor.appendAll(
                        queue = emptyList(),
                        scenarioIds = ScenarioCatalog.presets.asSequence().map { it.id }.asIterable(),
                    )
                    selectedScenarioIds = updated
                    planRepeatCount = validatedRepeatCount.coerceAtMost(
                        maximumPlanRepeats(updated.size),
                    )
                },
                clearSelection = {
                    selectedScenarioIds = arrayListOf()
                    planRepeatCount = 1
                },
                resetOrder = {
                    selectedScenarioIds = ScenarioQueueEditor.resetToCatalogOrder(
                        queue = validatedScenarioIds,
                        catalogOrder = ScenarioCatalog.presets.map { it.id },
                    )
                },
                changeRepeatCount = {
                    planRepeatCount = it.coerceIn(
                        1,
                        maximumPlanRepeats(validatedScenarioIds.size),
                    )
                },
                runSelection = { scenarios, repeats ->
                    controller.startPlan(
                        ScenarioRunPlan(
                            scenarios = scenarios,
                            repeatCount = repeats,
                            source = PlanSource.USER_SELECTION,
                        ),
                    )
                },
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
                LastResultCard(
                    summary = summary,
                    reportAvailable = controller.lastReportFile?.isFile == true,
                    share = controller::shareLastReport,
                )
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
    val compositionGauge = Gauge(
        value = telemetry.hwcDeviceLayers?.toFloat()
            ?: telemetry.hwcClientLayers?.toFloat(),
        quality = when {
            telemetry.hwcDeviceLayers != null -> telemetry.hwcDeviceLayersQuality
            telemetry.hwcClientLayers != null -> telemetry.hwcClientLayersQuality
            else -> MetricQuality.UNAVAILABLE
        },
        source = compositionProvenance(telemetry),
    )
    val metrics = listOf(
        DashboardMetricSpec("AP CPU", telemetry.cpu, "전체 CPU"),
        DashboardMetricSpec("APP CPU", telemetry.appCpu, "프로세스"),
        DashboardMetricSpec("MEMORY", telemetry.memoryUsed, telemetry.memoryAvailable.display()),
        DashboardMetricSpec("PRODUCER", telemetry.producedFps, "primary layer"),
        DashboardMetricSpec("GPU BUSY", telemetry.gpuBusy, telemetry.gpuFrequency.display()),
        DashboardMetricSpec(
            "MEM BUS",
            telemetry.busBusy,
            "gen ${telemetry.generatedBandwidth.display(2)}",
        ),
        DashboardMetricSpec(
            label = "DPU BUSY",
            gauge = telemetry.dpuBusy,
            detail = telemetry.dpuBusy.provenanceLabel(),
        ),
        DashboardMetricSpec(
            label = "HWC D / C",
            gauge = compositionGauge,
            detail = compositionProvenance(telemetry),
            valueText = "${telemetry.hwcDeviceLayers ?: "–"} / " +
                "${telemetry.hwcClientLayers ?: "–"}",
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
        items(metrics) { metric ->
            MetricCard(
                label = metric.label,
                gauge = metric.gauge,
                detail = metric.detail,
                valueText = metric.valueText,
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    gauge: Gauge,
    detail: String,
    valueText: String? = null,
) {
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
                valueText
                    ?: gauge.display(
                        if (
                            gauge.unit.contains("fps") ||
                            gauge.unit.contains("Hz")
                        ) {
                            1
                        } else {
                            0
                        },
                    ),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrendCard(controller: LabController) {
    val cpu = controller.telemetryHistory.map {
        it.cpu.value?.takeIf(Float::isFinite)
    }
    val memory = controller.telemetryHistory.map {
        it.memoryUsed.value?.takeIf(Float::isFinite)
    }
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
                fun line(values: List<Float?>, color: Color) {
                    if (values.isEmpty()) return
                    val path = Path()
                    var previousWasValue = false
                    var hasValue = false
                    values.forEachIndexed { index, value ->
                        if (value == null) {
                            previousWasValue = false
                            return@forEachIndexed
                        }
                        val x = if (values.size == 1) {
                            size.width
                        } else {
                            index.toFloat() / (values.size - 1) * size.width
                        }
                        val y = size.height - value.coerceIn(0f, 100f) / 100f * size.height
                        if (previousWasValue) path.lineTo(x, y) else path.moveTo(x, y)
                        previousWasValue = true
                        hasValue = true
                    }
                    if (hasValue) {
                        drawPath(
                            path,
                            color,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
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
    selectedScenarioIds: List<String>,
    repeatCount: Int,
    addScenario: (String) -> Unit,
    removeScenario: (String) -> Unit,
    removeQueueAt: (Int) -> Unit,
    moveQueueItem: (Int, Int) -> Unit,
    appendFiltered: (List<String>) -> Unit,
    replaceWithFiltered: (List<String>) -> Unit,
    selectAll: () -> Unit,
    clearSelection: () -> Unit,
    resetOrder: () -> Unit,
    changeRepeatCount: (Int) -> Unit,
    runSelection: (List<ScenarioSpec>, Int) -> Unit,
) {
    var categoryKeys by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var patternKeys by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var loadBandKeys by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var conditionKeys by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    val validCategoryKeys = remember(categoryKeys) {
        categoryKeys.retainKnownEnumKeys<ScenarioCategory>()
    }
    val validPatternKeys = remember(patternKeys) {
        patternKeys.retainKnownEnumKeys<ScenarioChangePattern>()
    }
    val validLoadBandKeys = remember(loadBandKeys) {
        loadBandKeys.retainKnownEnumKeys<ScenarioLoadBand>()
    }
    val validConditionKeys = remember(conditionKeys) {
        conditionKeys.retainKnownEnumKeys<ScenarioCondition>()
    }
    LaunchedEffect(categoryKeys, patternKeys, loadBandKeys, conditionKeys) {
        if (categoryKeys != validCategoryKeys) categoryKeys = validCategoryKeys
        if (patternKeys != validPatternKeys) patternKeys = validPatternKeys
        if (loadBandKeys != validLoadBandKeys) loadBandKeys = validLoadBandKeys
        if (conditionKeys != validConditionKeys) conditionKeys = validConditionKeys
    }
    val selectedCategories = remember(validCategoryKeys) {
        validCategoryKeys.toKnownEnumSet<ScenarioCategory>()
    }
    val selectedPatterns = remember(validPatternKeys) {
        validPatternKeys.toKnownEnumSet<ScenarioChangePattern>()
    }
    val selectedLoadBands = remember(validLoadBandKeys) {
        validLoadBandKeys.toKnownEnumSet<ScenarioLoadBand>()
    }
    val selectedConditions = remember(validConditionKeys) {
        validConditionKeys.toKnownEnumSet<ScenarioCondition>()
    }
    val selectionFilter = remember(
        selectedCategories,
        selectedPatterns,
        selectedLoadBands,
        selectedConditions,
    ) {
        ScenarioSelectionFilter(
            categories = selectedCategories,
            patterns = selectedPatterns,
            loadBands = selectedLoadBands,
            conditions = selectedConditions,
        )
    }
    val scenarios = remember(selectionFilter) {
        ScenarioClassifier.filter(ScenarioCatalog.presets, selectionFilter)
    }
    val selectedScenarios = remember(selectedScenarioIds) {
        selectedScenarioIds.mapNotNull(ScenarioCatalog::byId)
    }
    val selectedPositions = remember(selectedScenarioIds) {
        selectedScenarioIds.withIndex().groupBy(
            keySelector = { it.value },
            valueTransform = { it.index + 1 },
        )
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
                Text("DPULayerTest 시나리오", style = MaterialTheme.typography.displaySmall)
                Text(
                    "DPU composition 한계와 underrun 징후를 확인할 테스트를 순서대로 " +
                        "선택하고, 같은 queue를 반복 실행합니다.",
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
            QueuePlanCard(
                selectedScenarios = selectedScenarios,
                repeatCount = repeatCount,
                maximumRepeatCount = maximumPlanRepeats(selectedScenarios.size),
                running = controller.isRunning,
                changeRepeatCount = changeRepeatCount,
                selectAll = selectAll,
                clearSelection = clearSelection,
                resetOrder = resetOrder,
                removeQueueAt = removeQueueAt,
                moveQueueItem = moveQueueItem,
                runSelection = runSelection,
            )
        }
        item {
            CatalogFilterCard(
                selectedCategoryKeys = validCategoryKeys.toSet(),
                selectedPatternKeys = validPatternKeys.toSet(),
                selectedLoadBandKeys = validLoadBandKeys.toSet(),
                selectedConditionKeys = validConditionKeys.toSet(),
                resultCount = scenarios.size,
                queueSize = selectedScenarioIds.size,
                running = controller.isRunning,
                toggleCategory = {
                    categoryKeys = toggleFilterKey(validCategoryKeys, it)
                },
                togglePattern = {
                    patternKeys = toggleFilterKey(validPatternKeys, it)
                },
                toggleLoadBand = {
                    loadBandKeys = toggleFilterKey(validLoadBandKeys, it)
                },
                toggleCondition = {
                    conditionKeys = toggleFilterKey(validConditionKeys, it)
                },
                clearCategories = { categoryKeys = arrayListOf() },
                clearPatterns = { patternKeys = arrayListOf() },
                clearLoadBands = { loadBandKeys = arrayListOf() },
                clearConditions = { conditionKeys = arrayListOf() },
                clearAll = {
                    categoryKeys = arrayListOf()
                    patternKeys = arrayListOf()
                    loadBandKeys = arrayListOf()
                    conditionKeys = arrayListOf()
                },
                appendResults = { appendFiltered(scenarios.map { it.id }) },
                replaceWithResults = { replaceWithFiltered(scenarios.map { it.id }) },
            )
        }
        if (scenarios.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("조건에 맞는 테스트가 없습니다.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "같은 행의 조건을 줄이거나 다른 조합으로 변경해 주세요.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                categoryKeys = arrayListOf()
                                patternKeys = arrayListOf()
                                loadBandKeys = arrayListOf()
                                conditionKeys = arrayListOf()
                            },
                        ) {
                            Text("필터 초기화")
                        }
                    }
                }
            }
        }
        items(scenarios, key = { it.id }) { scenario ->
            ScenarioCard(
                scenario = scenario,
                selectedPositions = selectedPositions[scenario.id].orEmpty(),
                queueFull = selectedScenarioIds.size >= ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS,
                queueEditable = !controller.isRunning,
                addSelection = { addScenario(scenario.id) },
                removeSelection = { removeScenario(scenario.id) },
            )
        }
    }
}

@Composable
private fun CatalogFilterCard(
    selectedCategoryKeys: Set<String>,
    selectedPatternKeys: Set<String>,
    selectedLoadBandKeys: Set<String>,
    selectedConditionKeys: Set<String>,
    resultCount: Int,
    queueSize: Int,
    running: Boolean,
    toggleCategory: (String) -> Unit,
    togglePattern: (String) -> Unit,
    toggleLoadBand: (String) -> Unit,
    toggleCondition: (String) -> Unit,
    clearCategories: () -> Unit,
    clearPatterns: () -> Unit,
    clearLoadBands: () -> Unit,
    clearConditions: () -> Unit,
    clearAll: () -> Unit,
    appendResults: () -> Unit,
    replaceWithResults: () -> Unit,
) {
    val availableQueueSlots =
        (ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS - queueSize).coerceAtLeast(0)
    val appendCount = minOf(resultCount.coerceAtLeast(0), availableQueueSlots)
    val replaceCount = minOf(
        resultCount.coerceAtLeast(0),
        ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS,
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("카테고리 · 부하 · 조건 조합", style = MaterialTheme.typography.titleLarge)
                Text(
                    "같은 행은 OR, 서로 다른 행은 AND로 결합합니다. 결과 순서는 catalog와 같습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            FacetFilterRow(
                title = "카테고리",
                allLabel = "모든 카테고리",
                items = ScenarioCategory.entries.map { it.name to it.label },
                selectedKeys = selectedCategoryKeys,
                clear = clearCategories,
                toggle = toggleCategory,
            )
            FacetFilterRow(
                title = "부하 변화",
                allLabel = "모든 변화",
                items = ScenarioChangePattern.entries.map { it.name to it.label },
                selectedKeys = selectedPatternKeys,
                clear = clearPatterns,
                toggle = togglePattern,
            )
            FacetFilterRow(
                title = "예상 강도",
                allLabel = "모든 강도",
                items = ScenarioLoadBand.entries.map { it.name to it.label },
                selectedKeys = selectedLoadBandKeys,
                clear = clearLoadBands,
                toggle = toggleLoadBand,
            )
            FacetFilterRow(
                title = "부하/조건",
                allLabel = "모든 자원",
                items = ScenarioCondition.entries.map { it.name to it.label },
                selectedKeys = selectedConditionKeys,
                clear = clearConditions,
                toggle = toggleCondition,
            )
            HorizontalDivider()
            Text(
                "${resultCount}개 테스트 일치 · 예상 강도는 preset 비교용이며 HW 수용 한계가 아닙니다.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(
                onClick = clearAll,
                enabled = (
                    selectedCategoryKeys.isNotEmpty() ||
                        selectedPatternKeys.isNotEmpty() ||
                        selectedLoadBandKeys.isNotEmpty() ||
                        selectedConditionKeys.isNotEmpty()
                    ) && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("조합 필터 초기화")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = replaceWithResults,
                    enabled = replaceCount > 0 && !running,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text("결과로 교체 · $replaceCount")
                }
                Button(
                    onClick = appendResults,
                    enabled = appendCount > 0 && !running,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text("결과 추가 · $appendCount")
                }
            }
        }
    }
}

@Composable
private fun FacetFilterRow(
    title: String,
    allLabel: String,
    items: List<Pair<String, String>>,
    selectedKeys: Set<String>,
    clear: () -> Unit,
    toggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterChip(
                selected = selectedKeys.isEmpty(),
                onClick = clear,
                label = { Text(allLabel) },
            )
            items.forEach { (key, label) ->
                FilterChip(
                    selected = key in selectedKeys,
                    onClick = { toggle(key) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun QueuePlanCard(
    selectedScenarios: List<ScenarioSpec>,
    repeatCount: Int,
    maximumRepeatCount: Int,
    running: Boolean,
    changeRepeatCount: (Int) -> Unit,
    selectAll: () -> Unit,
    clearSelection: () -> Unit,
    resetOrder: () -> Unit,
    removeQueueAt: (Int) -> Unit,
    moveQueueItem: (Int, Int) -> Unit,
    runSelection: (List<ScenarioSpec>, Int) -> Unit,
) {
    val oneLoopDurationMs = remember(selectedScenarios) {
        ScenarioRunPlan(
            scenarios = selectedScenarios,
            repeatCount = 1,
            source = PlanSource.USER_SELECTION,
        ).estimatedDurationMs
    }
    val previewPlan = remember(selectedScenarios, repeatCount) {
        ScenarioRunPlan(
            scenarios = selectedScenarios,
            repeatCount = repeatCount,
            source = PlanSource.USER_SELECTION,
        )
    }
    val totalDurationMs = previewPlan.estimatedDurationMs
    val totalRuns = previewPlan.totalRuns
    val catalogIds = remember { ScenarioCatalog.presets.map { it.id } }
    val selectionMatchesCatalog = remember(selectedScenarios, catalogIds) {
        selectedScenarios.map { it.id } == catalogIds
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.64f),
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("선택 실행 큐", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "표시된 #번호 순서로 실행합니다. ←/→로 이동하고 ×로 한 항목만 제거합니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Surface(
                    color = if (selectedScenarios.isEmpty()) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    shape = RoundedCornerShape(100.dp),
                ) {
                    Text(
                        "${selectedScenarios.size} selected",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        color = if (selectedScenarios.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScenarioAttribute(
                    label = "항목",
                    value = "${selectedScenarios.size} tests",
                    modifier = Modifier.weight(1f),
                )
                ScenarioAttribute(
                    label = "1 LOOP",
                    value = formatDuration(oneLoopDurationMs),
                    modifier = Modifier.weight(1f),
                )
                ScenarioAttribute(
                    label = "총 예상",
                    value = formatDuration(totalDurationMs),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "실행 순서",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (selectedScenarios.isEmpty()) {
                Text(
                    "아래 테스트에서 ‘큐에 추가’를 눌러 순서를 만드세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    selectedScenarios.forEachIndexed { index, scenario ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(100.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "#${index + 1} ${scenario.name}",
                                    modifier = Modifier.widthIn(max = 210.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = { moveQueueItem(index, index - 1) },
                                    enabled = index > 0 && !running,
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                ) {
                                    Text("←")
                                }
                                TextButton(
                                    onClick = { moveQueueItem(index, index + 1) },
                                    enabled = index < selectedScenarios.lastIndex && !running,
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                ) {
                                    Text("→")
                                }
                                TextButton(
                                    onClick = { removeQueueAt(index) },
                                    enabled = !running,
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                ) {
                                    Text("×")
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("LOOP 반복", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedButton(
                        onClick = { changeRepeatCount(repeatCount - 1) },
                        enabled = repeatCount > 1 && !running,
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                    ) {
                        Text("−")
                    }
                    Text(
                        "$repeatCount ×",
                        modifier = Modifier.width(42.dp),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(
                        onClick = { changeRepeatCount(repeatCount + 1) },
                        enabled = repeatCount < maximumRepeatCount && !running,
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                    ) {
                        Text("+")
                    }
                }
            }
            Text(
                "최대 ${ScenarioPlanPolicy.MAX_REPEAT_COUNT} loops · " +
                    "${ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS} scenario runs · " +
                    "현재 $totalRuns runs",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "예상 시간은 scenario phase 합계이며 precheck·warm-up·cooldown·report I/O는 제외합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = resetOrder,
                    enabled = selectedScenarios.size > 1 && !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("순서 초기화")
                }
                TextButton(
                    onClick = selectAll,
                    enabled = !selectionMatchesCatalog && !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("전체 선택")
                }
                TextButton(
                    onClick = clearSelection,
                    enabled = selectedScenarios.isNotEmpty() && !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("모두 해제")
                }
            }
            Button(
                onClick = { runSelection(selectedScenarios, repeatCount) },
                enabled = selectedScenarios.isNotEmpty() && !running &&
                    totalRuns <= ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("선택한 DPU PLAN 실행 · $totalRuns runs")
            }
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
private fun ScenarioCard(
    scenario: ScenarioSpec,
    selectedPositions: List<Int>,
    queueFull: Boolean,
    queueEditable: Boolean,
    addSelection: () -> Unit,
    removeSelection: () -> Unit,
) {
    val overview = remember(scenario) { scenario.overview() }
    val selected = selectedPositions.isNotEmpty()
    val cardShape = RoundedCornerShape(22.dp)
    Card(
        modifier = Modifier.then(
            if (selected) {
                Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = cardShape,
                )
            } else {
                Modifier
            },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = cardShape,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    selectedPositions.takeIf { it.isNotEmpty() }?.let { positions ->
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(100.dp),
                        ) {
                            Text(
                                positions.positionSummary(),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    RiskBadge(scenario.risk)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "테스트 목적",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(scenario.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScenarioAttribute(
                    label = "부하 패턴",
                    value = overview.patternLabel,
                    modifier = Modifier.weight(1f),
                )
                ScenarioAttribute(
                    label = "최대 구성",
                    value = "${scenario.maxLayers}L · ${scenario.maxHz.toInt()}Hz",
                    modifier = Modifier.weight(1f),
                )
                ScenarioIntensity(
                    overview = overview,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Phase 흐름 · ${overview.phaseSequence}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "실행 전 확인 · ${scenario.requirements.joinToString()}",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatDuration(scenario.durationMs)} · ${scenario.phases.size} phases · 최대 ${scenario.maxLayers}L / ${scenario.maxHz.toInt()}Hz",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected) {
                    OutlinedButton(
                        onClick = removeSelection,
                        enabled = queueEditable,
                    ) {
                        Text("1개 제거")
                    }
                    Spacer(Modifier.width(7.dp))
                    Button(
                        onClick = addSelection,
                        enabled = queueEditable && !queueFull,
                    ) {
                        Text("다시 추가")
                    }
                } else {
                    Button(
                        onClick = addSelection,
                        enabled = queueEditable && !queueFull,
                    ) {
                        Text("큐에 추가")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioAttribute(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(13.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScenarioIntensity(
    overview: ScenarioOverview,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(13.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "예상 강도",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${overview.intensityLabel} · ${overview.intensityScore}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val activeSegments = ((overview.intensityScore + 19) / 20).coerceIn(1, 5)
                repeat(5) { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .background(
                                if (index < activeSegments) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                },
                            ),
                    )
                }
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
    var layers by rememberSaveable { mutableIntStateOf(8) }
    var duration by rememberSaveable { mutableIntStateOf(30) }
    var fps by rememberSaveable { mutableFloatStateOf(120f) }
    var hz by rememberSaveable { mutableFloatStateOf(120f) }
    var cpu by rememberSaveable { mutableFloatStateOf(0.25f) }
    var memory by rememberSaveable { mutableFloatStateOf(0.55f) }
    var gpu by rememberSaveable { mutableFloatStateOf(0.35f) }
    var npu by rememberSaveable { mutableFloatStateOf(0f) }
    var backend by rememberSaveable { mutableStateOf(LayerBackend.MIXED_SURFACE_TEXTURE) }
    var route by rememberSaveable { mutableStateOf(PixelRoute.RGB_8888) }
    var size by rememberSaveable { mutableStateOf(BufferSize.DISPLAY) }
    var motion by rememberSaveable { mutableStateOf(MotionProfile.TRANSFORM_STORM) }
    var shape by rememberSaveable { mutableStateOf(LoadShape.STEADY) }
    var transitionMode by rememberSaveable { mutableStateOf(TransitionMode.STEP) }
    var transitionSeconds by rememberSaveable { mutableFloatStateOf(6f) }
    var transitionCycleSeconds by rememberSaveable { mutableFloatStateOf(4f) }
    var transitionSteps by rememberSaveable { mutableIntStateOf(6) }
    var transitionDuty by rememberSaveable { mutableFloatStateOf(0.5f) }

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
                if (motion == MotionProfile.Z_ORDER_SWAP) {
                    Text(
                        "View translationZ 기반 client proxy입니다. physical Surface/HWC " +
                            "Z-order 변경이나 HWC capability의 exact 판정으로 사용하지 않습니다.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        item {
            BuilderCard("CROSS-LOAD") {
                LabeledSlider("CPU math", "${(cpu * 100).roundToInt()}%", cpu, 0f..1f, 9) { cpu = it }
                LabeledSlider("Memory copy", "${(memory * 100).roundToInt()}%", memory, 0f..1f, 9) { memory = it }
                LabeledSlider("GPU 3D", "${(gpu * 100).roundToInt()}%", gpu, 0f..1f, 9) { gpu = it }
                LabeledSlider("NPU adapter", "${(npu * 100).roundToInt()}%", npu, 0f..1f, 9) { npu = it }
                EnumSelector("Load shape", shape, LoadShape.entries) { shape = it }
                Text(
                    "Load shape은 CPU/memory/NPU worker 내부 미세 파형입니다. Phase 전체의 " +
                        "layer·FPS·교차 부하 변화는 아래 전이 조건으로 제어합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
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
            BuilderCard("PHASE TRANSITION") {
                EnumSelector(
                    "전이 방식",
                    transitionMode,
                    TransitionMode.entries,
                ) { transitionMode = it }
                Text(
                    transitionMode.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                when (transitionMode) {
                    TransitionMode.LINEAR_RAMP,
                    TransitionMode.STAIRCASE,
                    TransitionMode.SOAK_RECOVERY,
                    -> {
                        val requestedMaximum = if (
                            transitionMode == TransitionMode.SOAK_RECOVERY
                        ) {
                            duration / 2
                        } else {
                            duration
                        }
                        val maximumSeconds =
                            requestedMaximum.coerceAtMost(60).coerceAtLeast(1).toFloat()
                        LabeledSlider(
                            label = if (transitionMode == TransitionMode.SOAK_RECOVERY) {
                                "Attack / release"
                            } else {
                                "전이 시간"
                            },
                            valueLabel = "${transitionSeconds.coerceAtMost(maximumSeconds).roundToInt()}s",
                            value = transitionSeconds.coerceAtMost(maximumSeconds),
                            range = 1f..maximumSeconds,
                            steps = (maximumSeconds.toInt() - 2).coerceAtLeast(0),
                        ) { transitionSeconds = it }
                    }

                    TransitionMode.PULSE_BURST,
                    TransitionMode.TRIANGLE_WAVE,
                    -> {
                        val maximumCycleSeconds =
                            duration.coerceAtMost(12).coerceAtLeast(1).toFloat()
                        LabeledSlider(
                            "반복 주기",
                            "${transitionCycleSeconds.coerceAtMost(maximumCycleSeconds).roundToInt()}s",
                            transitionCycleSeconds.coerceAtMost(maximumCycleSeconds),
                            1f..maximumCycleSeconds,
                            (maximumCycleSeconds.toInt() - 2).coerceAtLeast(0),
                        ) { transitionCycleSeconds = it }
                    }

                    TransitionMode.STEP -> Unit
                }
                if (transitionMode == TransitionMode.STAIRCASE) {
                    LabeledSlider(
                        "단계 수",
                        "${transitionSteps}단",
                        transitionSteps.toFloat(),
                        2f..12f,
                        9,
                    ) { transitionSteps = it.roundToInt() }
                }
                if (transitionMode == TransitionMode.PULSE_BURST) {
                    LabeledSlider(
                        "ON duty",
                        "${(transitionDuty * 100).roundToInt()}%",
                        transitionDuty,
                        0.1f..0.9f,
                        7,
                    ) { transitionDuty = it }
                }
                if (shape != LoadShape.STEADY && transitionMode != TransitionMode.STEP) {
                    Text(
                        "미세 파형과 phase 전이가 함께 적용됩니다. 단일 전이 응답을 비교하려면 " +
                            "Load shape을 Steady로 두세요.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        item {
            val transitionDurationLimit = if (
                transitionMode == TransitionMode.SOAK_RECOVERY
            ) {
                duration / 2f
            } else {
                duration.toFloat()
            }
            val safeTransitionSeconds = transitionSeconds
                .coerceAtMost(transitionDurationLimit)
                .coerceAtLeast(1f)
            val safeCycleSeconds = transitionCycleSeconds
                .coerceAtMost(duration.toFloat())
                .coerceAtLeast(1f)
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
                transition = TransitionSpec(
                    mode = transitionMode,
                    transitionDurationMs = (safeTransitionSeconds * 1_000f).toLong(),
                    cycleMs = (safeCycleSeconds * 1_000f).toLong(),
                    stepCount = transitionSteps,
                    dutyCycle = transitionDuty,
                ),
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
    is TransitionMode -> value.label
    else -> value.toString()
}

@Composable
private fun RunningScreen(controller: LabController) {
    val progress = controller.progress
    val planProgress = controller.planProgress
    val phase = progress.phase
    val renderStage = phase != null && when (progress.stage) {
        RunnerStage.WARMUP,
        RunnerStage.RUNNING,
        RunnerStage.COOLDOWN,
        -> true
        else -> false
    }
    var stageView by remember { mutableStateOf<LayerStageView?>(null) }
    var stageWidthPx by remember { mutableIntStateOf(0) }
    var stageHeightPx by remember { mutableIntStateOf(0) }
    val producerFrameCallback = remember(controller) {
        ProducerFrameCallback { generation, producerId, primary ->
            controller.frameTracker.onProducerBufferProduced(
                generation = generation,
                producerId = producerId,
                primary = primary,
            )
        }
    }
    val expectedProducersCallback = remember(controller) {
        { generation: Long, producerIds: Set<Long> ->
            controller.frameTracker.expectProducers(generation, producerIds)
        }
    }
    val producerTopologyPendingCallback = remember(controller) {
        { generation: Long ->
            controller.onProducerTopologyPending(generation)
        }
    }
    var hudSamples by remember(
        progress.scenario?.id,
        planProgress.repeatIndex,
        planProgress.queueIndex,
    ) {
        mutableStateOf(emptyList<RunningHudSample>())
    }
    var lastHudSampleMs by remember(
        progress.scenario?.id,
        planProgress.repeatIndex,
        planProgress.queueIndex,
    ) {
        mutableLongStateOf(0L)
    }
    val telemetry = controller.telemetry

    LaunchedEffect(renderStage, progress.producerGeneration) {
        if (!renderStage && progress.producerGeneration > 0L) {
            val stopped = stageView?.release() ?: true
            stageView = null
            // Stage removal and producer-thread termination are independent facts. Always
            // acknowledge removal; the process-wide lease remains authoritative while a bounded
            // controller barrier polls a producer that is still draining.
            controller.onRendererStageRemoved(
                generation = progress.producerGeneration,
                stopped = stopped,
            )
        }
    }

    LaunchedEffect(telemetry.monotonicMs) {
        val activePhase = phase ?: return@LaunchedEffect
        if (
            telemetry.monotonicMs <= 0L ||
            telemetry.monotonicMs <= lastHudSampleMs
        ) {
            return@LaunchedEffect
        }
        lastHudSampleMs = telemetry.monotonicMs
        hudSamples = (
            hudSamples + RunningHudSample(
                layerCount = activePhase.activeLayers.toFloat(),
                dpuBusy = telemetry.dpuBusy,
                cpuBusy = telemetry.cpu,
                gpuBusy = telemetry.gpuBusy,
            )
            ).takeLast(60)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (renderStage && phase != null) {
            AndroidView(
                factory = { context ->
                    LayerStageView(context).also { stageView = it }
                },
                update = { stage ->
                    stage.configure(
                        newPhase = phase,
                        selectedMedia = controller.selectedMediaUri,
                        selectedDecoder = controller.selectedVideoDecoder,
                        newProducerGeneration = progress.producerGeneration,
                        onProducerFrame = producerFrameCallback,
                        onExpectedProducers = expectedProducersCallback,
                        onProducerTopologyPending = producerTopologyPendingCallback,
                        onProducerTeardownFailure =
                            controller.frameTracker::markProducerTeardownFailure,
                        onProducerRuntimeFailure = controller::onProducerRuntimeFailure,
                        onStageRemoved = controller::onRendererStageRemoved,
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
            planProgress = planProgress,
            telemetry = telemetry,
            history = hudSamples,
            stageWidthPx = stageWidthPx,
            stageHeightPx = stageHeightPx,
            mediaSelected = controller.selectedMediaUri != null,
            mediaWidthPx = controller.selectedMediaWidthPx,
            mediaHeightPx = controller.selectedMediaHeightPx,
            decoderLinearReference = controller.selectedMediaLinearReference,
            safetyAdjustments = controller.lastSafetyAdjustments,
            stop = controller::stopScenario,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                stageView?.release()
            } finally {
                stageView = null
                // This also clears the close-time lifecycle token when disposal happened before
                // AndroidView.factory created a stage. Live native workers keep their own leases.
                controller.onRendererContainerDisposed()
            }
        }
    }
}

@Composable
private fun RunningHud(
    progress: RunProgress,
    planProgress: PlanProgress,
    telemetry: TelemetrySnapshot,
    history: List<RunningHudSample>,
    stageWidthPx: Int,
    stageHeightPx: Int,
    mediaSelected: Boolean,
    mediaWidthPx: Int?,
    mediaHeightPx: Int?,
    decoderLinearReference: DecoderLinearReference?,
    safetyAdjustments: List<String>,
    stop: () -> Unit,
) {
    val phase = progress.phase
    val configuration = LocalConfiguration.current
    val compactHud =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenHeightDp < 640
    val detailPanelMaxHeight = when {
        compactHud && configuration.screenHeightDp < 400 -> 92.dp
        compactHud -> 116.dp
        configuration.screenHeightDp < 720 -> 132.dp
        else -> 168.dp
    }
    val traffic = phase?.let {
        LayerTrafficEstimator.estimate(
            phase = it,
            displayWidthPx = stageWidthPx,
            displayHeightPx = stageHeightPx,
            measuredDisplayHz = telemetry.displayHz.value,
            mediaSelected = mediaSelected,
            mediaWidthPx = mediaWidthPx,
            mediaHeightPx = mediaHeightPx,
            decoderLinearReference = decoderLinearReference,
        )
    }
    val layerScale = maxOf(
        1,
        progress.scenario?.maxLayers ?: 1,
        phase?.activeLayers ?: 1,
    ).toFloat()
    val phaseFraction = phase?.let {
        if (it.durationMs > 0L) {
            (progress.phaseElapsedMs.toDouble() / it.durationMs.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        } else {
            0f
        }
    } ?: 0f
    val layerHistory = remember(history) { history.map { it.layerCount } }
    val dpuHistory = remember(history) {
        segmentedGaugeHistory(history.map { it.dpuBusy })
    }
    val cpuHistory = remember(history) {
        segmentedGaugeHistory(history.map { it.cpuBusy })
    }
    val gpuHistory = remember(history) {
        segmentedGaugeHistory(history.map { it.gpuBusy })
    }
    val liveMetrics = listOf(
        LiveHudMetricSpec(
            label = "LAYERS",
            provenance = "PHYSICAL",
            value = phase?.activeLayers?.toFloat(),
            valueText = phase?.let {
                "${it.activeLayers}L · " +
                    producerCountDisplay(
                        observed = progress.observedProducerCount,
                        expected = progress.expectedProducerCount,
                    )
            } ?: "N/A",
            history = layerHistory,
            maxValue = layerScale,
            color = Color(0xFF65E6C4),
        ),
        LiveHudMetricSpec(
            label = "DPU",
            provenance = gaugeProvenanceLabel(telemetry.dpuBusy),
            value = telemetry.dpuBusy.value,
            valueText = telemetry.dpuBusy.display(),
            history = dpuHistory,
            maxValue = 100f,
            color = Color(0xFFFFC857),
        ),
        LiveHudMetricSpec(
            label = "CPU",
            provenance = gaugeProvenanceLabel(telemetry.cpu),
            value = telemetry.cpu.value,
            valueText = telemetry.cpu.display(),
            history = cpuHistory,
            maxValue = 100f,
            color = Color(0xFF4CC9F0),
        ),
        LiveHudMetricSpec(
            label = "GPU",
            provenance = gaugeProvenanceLabel(telemetry.gpuBusy),
            value = telemetry.gpuBusy.value,
            valueText = telemetry.gpuBusy.display(),
            history = gpuHistory,
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
                .widthIn(max = if (compactHud) 620.dp else 370.dp)
                .fillMaxWidth(),
            color = Color(0xD90A1512),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                Modifier.padding(if (compactHud) 9.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactHud) 4.dp else 7.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            planProgress.currentScenario?.name
                                ?: progress.scenario?.name
                                ?: "DPU test 준비",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "QUEUE ${planProgress.currentQueuePosition}/" +
                                "${planProgress.queueSize} · LOOP ${planProgress.currentRepeat}/" +
                                "${planProgress.repeatCount} · ${progress.stage.displayLabel()}",
                            color = Color(0xFFB8CBC5),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            visibleAppVersion(BuildConfig.VERSION_NAME),
                            color = Color(0xFF8FA9A1),
                            fontSize = 8.sp,
                            maxLines = 1,
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
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = stop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text("STOP", fontWeight = FontWeight.Bold)
                    }
                }
                HudProgressLine(
                    label = if (compactHud) {
                        "PLAN ${(planProgress.overallFraction * 100f).roundToInt()}% · " +
                            "PHASE ${(progress.phaseIndex + 1).coerceAtLeast(1)}/" +
                            "${progress.scenario?.phases?.size ?: 0} " +
                            "${(phaseFraction * 100f).roundToInt()}%"
                    } else {
                        "DPU PLAN · ${planProgress.completedRuns}/${planProgress.totalRuns} runs"
                    },
                    detail = if (compactHud) {
                        "${planProgress.completedRuns}/${planProgress.totalRuns} runs"
                    } else {
                        "현재 scenario ${(planProgress.boundedCurrentRunFraction * 100f).roundToInt()}%"
                    },
                    fraction = planProgress.overallFraction,
                    color = Color(0xFF65E6C4),
                )
                if (!compactHud) phase?.let { activePhase ->
                    HudProgressLine(
                        label = "PHASE ${(progress.phaseIndex + 1).coerceAtLeast(1)}/" +
                            "${progress.scenario?.phases?.size ?: 0}",
                        detail = "${activePhase.label} · " +
                            "${formatDuration(progress.phaseElapsedMs)} / " +
                            formatDuration(activePhase.durationMs),
                        fraction = phaseFraction,
                        color = Color(0xFFFFC857),
                    )
                }
                if (compactHud) {
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
                                        provenance = metric.provenance,
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
                            provenance = metric.provenance,
                            value = metric.value,
                            valueText = metric.valueText,
                            history = metric.history,
                            maxValue = metric.maxValue,
                            color = metric.color,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.13f))
                TrafficHud(traffic, compact = compactHud)
            }
        }
        Surface(
            modifier = Modifier
                .widthIn(max = if (compactHud) 760.dp else 520.dp)
                .fillMaxWidth(),
            color = Color(0xD90A1512),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (compactHud) {
                Row(
                    Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        RunTransitionStatus(
                            progress = progress,
                            planProgress = planProgress,
                            telemetry = telemetry,
                            safetyAdjustments = safetyAdjustments,
                            compact = true,
                            modifier = Modifier
                                .heightIn(max = detailPanelMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            } else {
                Column(
                    Modifier.padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            val coolingDown = progress.stage == RunnerStage.COOLDOWN
                            val targetPhase =
                                if (coolingDown) null else progress.displayedTargetPhase
                            Text(
                                when {
                                    coolingDown -> "Cooldown · 부하 해제 및 counter 안정화"
                                    progress.phase != null ->
                                        "현재 ${progress.phase.activeLayers}L / " +
                                            producerCountDisplay(
                                                observed = progress.observedProducerCount,
                                                expected = progress.expectedProducerCount,
                                            ) +
                                            " / " +
                                            "${progress.phase.producerFps.toInt()}fps · " +
                                            "목표 ${targetPhase?.activeLayers ?: progress.phase.activeLayers}L / " +
                                            "${(targetPhase?.producerFps ?: progress.phase.producerFps).toInt()}fps"
                                    else -> "surface 준비"
                                },
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                progress.phase?.let {
                                    "${it.backend.label} · ${it.pixelRoute.label} · " +
                                        "${it.motion.label} · ${it.requestedDisplayHz.toInt()}Hz"
                                } ?: "부하 없음",
                                color = Color(0xFFB8CBC5),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                    RunTransitionStatus(
                        progress = progress,
                        planProgress = planProgress,
                        telemetry = telemetry,
                        safetyAdjustments = safetyAdjustments,
                        compact = false,
                        modifier = Modifier
                            .heightIn(max = detailPanelMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

internal fun visibleAppVersion(versionName: String): String {
    val normalized = versionName.trim().take(MAX_VISIBLE_VERSION_CHARS)
    return "BUILD ${normalized.ifEmpty { "N/A" }}"
}

internal fun producerCountDisplay(observed: Int, expected: Int): String {
    val safeObserved = observed.coerceAtLeast(0)
    return if (expected > 0) {
        "$safeObserved/${expected}P"
    } else {
        "$safeObserved/\u2014P"
    }
}

private const val MAX_VISIBLE_VERSION_CHARS = 64

@Composable
private fun HudProgressLine(
    label: String,
    detail: String,
    fraction: Float,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                detail,
                color = Color(0xFFB8CBC5),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(100.dp)),
            color = color,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
    }
}

@Composable
private fun RunTransitionStatus(
    progress: RunProgress,
    planProgress: PlanProgress,
    telemetry: TelemetrySnapshot,
    safetyAdjustments: List<String>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = progress.phase
    val coolingDown = progress.stage == RunnerStage.COOLDOWN
    val targetPhase = if (coolingDown) null else progress.displayedTargetPhase
    val nextPhase = progress.scenario?.phases?.getOrNull(progress.phaseIndex + 1)
    val nextPhaseText = nextPhase?.let {
        "다음 phase · ${it.label} · ${it.activeLayers}L / " +
            "${it.producerFps.toInt()}fps · ${it.workloads.peakSummary()}"
    } ?: "다음 phase · 현재 scenario cooldown"
    val nextScenarioText = planProgress.nextScenario?.let {
        "다음 scenario · ${it.name}"
    } ?: "다음 scenario · plan 종료"
    val dpuClockText = telemetry.dpuFrequency.value
        ?.takeIf(Float::isFinite)
        ?.let { "DPU CLK ${telemetry.dpuFrequency.display()}" }
    val transitionSample = targetPhase?.let {
        LoadTransitionEvaluator.sampleAt(
            spec = it.transition,
            elapsedMs = progress.phaseElapsedMs,
            phaseDurationMs = it.durationMs,
        )
    }
    val transitionLabel = when {
        coolingDown -> "COOLDOWN · 부하 해제"
        targetPhase != null ->
            "${targetPhase.transition.mode.label} · " +
                "${transitionSample?.segment?.label ?: "Target"} · " +
                "${(progress.boundedTransitionFraction * 100f).roundToInt()}%"
        else -> "준비 중"
    }
    val transitionTitle = if (coolingDown) {
        "회복 상태 · $transitionLabel"
    } else {
        "부하 전이 · $transitionLabel"
    }
    val safetyDerated = progress.thermalDerated
    val safetyLabel = when {
        telemetry.memoryLow -> "MEMORY LOW · 안전 중단"
        safetyDerated -> "THERMAL DERATE · 제한 유지"
        safetyAdjustments.isNotEmpty() -> "SAFETY CLAMP · ${safetyAdjustments.size}건"
        else -> "SAFETY ENVELOPE · 정상"
    }
    val safetyColor = when {
        telemetry.memoryLow -> Color(0xFFFF7A90)
        safetyDerated || safetyAdjustments.isNotEmpty() -> Color(0xFFFFC857)
        else -> Color(0xFF65E6C4)
    }

    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.07f),
        shape = RoundedCornerShape(13.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
        ) {
            if (compact) {
                Text(
                    transitionTitle,
                    color = Color(0xFFFFC857),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    safetyLabel,
                    color = safetyColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        transitionTitle,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFFFC857),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        safetyLabel,
                        color = safetyColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                when {
                    coolingDown -> phase?.let { current ->
                        "현재 cooldown · ${current.activeLayers}L / " +
                            "${current.producerFps.toInt()}fps · 교차 부하 해제"
                    } ?: "현재 cooldown · 부하 해제"
                    phase != null ->
                        "현재 → 목표 · ${phase.activeLayers}→" +
                            "${targetPhase?.activeLayers ?: phase.activeLayers}L · " +
                            "${phase.producerFps.toInt()}→" +
                            "${(targetPhase?.producerFps ?: phase.producerFps).toInt()}fps · " +
                            workloadTransitionSummary(
                                current = phase.workloads,
                                target = targetPhase?.workloads ?: phase.workloads,
                            )
                    else -> "현재 부하를 준비하는 중"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                nextPhaseText,
                color = Color(0xFFB8CBC5),
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                nextScenarioText,
                color = Color(0xFF86A39A),
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (dpuClockText != null) {
                Text(
                    "$dpuClockText · product probe read-only · 앱은 clock을 강제하지 않음",
                    color = Color(0xFF86A39A),
                    fontSize = 9.sp,
                    maxLines = if (compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "Vendor S${telemetry.vendorServiceSession ?: "N/A"} · " +
                    "compression ${telemetry.compressionState} · NPU ${telemetry.npuState}",
                color = Color(0xFF86A39A),
                fontSize = 9.sp,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (safetyAdjustments.isNotEmpty()) {
                Text(
                    "제한 사유 · ${safetyAdjustments.first()}" +
                        if (safetyAdjustments.size > 1) {
                            " 외 ${safetyAdjustments.size - 1}건"
                        } else {
                            ""
                        },
                    color = safetyColor,
                    fontSize = 9.sp,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (!compact) {
                Text(
                    "Thermal ${telemetry.thermalLabel} · runtime memory/thermal watchdog 활성",
                    color = Color(0xFF86A39A),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveHudMetric(
    label: String,
    provenance: String,
    value: Float?,
    valueText: String,
    history: List<Float?>,
    maxValue: Float,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(35.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(78.dp)) {
            Text(
                label,
                color = Color(0xFF86A39A),
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
                maxLines = 1,
            )
            Text(
                provenance,
                color = Color(0xFF5F8177),
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    val planResults = controller.planResultHistory.toList()
    val reportFile = controller.lastReportFile
    val reportAvailable = reportFile?.isFile == true
    if (summary == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(controller.progress.statusText)
        }
        return
    }
    val peakDpuBusy = summary.peakGauge(0f..100f) { it.dpuBusy }
    val peakGpuBusy = summary.peakGauge(0f..100f) { it.gpuBusy }
    val peakBusBusy = summary.peakGauge(0f..100f) { it.busBusy }
    val peakProducedFps = summary.peakGauge(0f..Float.MAX_VALUE) { it.producedFps }
    val peakHwcDeviceLayers = summary.peakLayerCount(
        value = TelemetrySnapshot::hwcDeviceLayers,
        quality = TelemetrySnapshot::hwcDeviceLayersQuality,
        source = TelemetrySnapshot::hwcDeviceLayersSource,
    )
    val peakHwcClientLayers = summary.peakLayerCount(
        value = TelemetrySnapshot::hwcClientLayers,
        quality = TelemetrySnapshot::hwcClientLayersQuality,
        source = TelemetrySnapshot::hwcClientLayersSource,
    )
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (
            controller.planProgress.state != PlanState.REJECTED &&
            (
                controller.planProgress.state == PlanState.ABORTED ||
                    controller.planProgress.source != PlanSource.SINGLE_SCENARIO ||
                    controller.planProgress.totalRuns > 1
                )
        ) {
            item {
                PlanResultOverview(
                    progress = controller.planProgress,
                    results = planResults,
                )
            }
            item {
                Text(
                    "Scenario 결과",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(planResults, key = { "${it.runIndex}-${it.scenario.id}" }) { result ->
                PlanResultCard(
                    result = result,
                    shareReport = { controller.shareReport(result.reportPath) },
                )
            }
            item {
                Text(
                    "최신 실행 상세",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
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
                        "Exact counter provenance",
                        if (summary.exactUnderrunSource.isNullOrBlank()) {
                            "N/A"
                        } else {
                            "${summary.exactUnderrunQuality.label} · " +
                                summary.exactUnderrunSource
                        },
                    )
                    ResultRow("Suspected proxy Δ", summary.suspectedUnderrunDelta.toString())
                    ResultRow("Peak CPU", summary.peakCpu?.let { "%.1f%%".format(it) } ?: "N/A")
                    ResultRow("Peak DPU busy", peakDpuBusy.formatPercent())
                    ResultRow("Peak GPU busy", peakGpuBusy.formatPercent())
                    ResultRow("Peak bus busy", peakBusBusy.formatPercent())
                    ResultRow(
                        "Peak produced FPS",
                        when {
                            peakProducedFps.provenanceChanged -> "N/A · source changed"
                            peakProducedFps.value != null ->
                                "%.1f fps".format(peakProducedFps.value)
                            else -> "N/A"
                        },
                    )
                    ResultRow(
                        "Peak HWC composition",
                        if (peakHwcDeviceLayers == null && peakHwcClientLayers == null) {
                            "N/A"
                        } else {
                            "device ${peakHwcDeviceLayers ?: "N/A"} · client ${peakHwcClientLayers ?: "N/A"}"
                        },
                    )
                    ResultRow("Peak memory", summary.peakMemoryUsed?.let { "%.1f%%".format(it) } ?: "N/A")
                    ResultRow(
                        "Generated bus traffic",
                        summary.peakGeneratedBandwidth?.let { "%.2f Gbps".format(it) } ?: "N/A",
                    )
                    ResultRow("Samples", summary.samples.size.toString())
                    summary.terminalReason()?.let { reason ->
                        ResultRow("종료 사유", reason)
                    }
                }
            }
        }
        item {
            Text(
                when (summary.verdict) {
                    RunVerdict.SUSPECTED_PROXY ->
                        "프레임 deadline miss가 관찰됐지만 DPU underrun으로 확정할 직접 counter는 없습니다."
                    RunVerdict.INCONCLUSIVE ->
                        "필수 counter·producer·capability 조건 중 하나가 충분하지 않아 판정을 보류했습니다. 종료 사유와 event를 확인하세요."
                    RunVerdict.CLEAN ->
                        "직접 underrun counter 기준으로 증가가 없었습니다."
                    else -> "결과 보고서에는 phase, 요청/실제 Hz, telemetry source와 event가 함께 저장됩니다."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = controller::shareLastReport,
                    enabled = reportAvailable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (reportAvailable) "최신 JSON 공유" else "공유할 보고서 없음")
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("완료")
                }
            }
        }
        reportFile?.takeIf { it.isFile }?.let { file ->
            item {
                Text(
                    file.absolutePath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } ?: item {
            Text(
                "최신 실행의 JSON 보고서가 저장되지 않아 공유할 수 없습니다.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun RunSummary.peakGauge(
    validRange: ClosedFloatingPointRange<Float>,
    selector: (TelemetrySnapshot) -> Gauge,
): GaugePeak = consistentGaugePeak(samples, selector, validRange)

private fun RunSummary.peakLayerCount(
    value: (TelemetrySnapshot) -> Int?,
    quality: (TelemetrySnapshot) -> MetricQuality,
    source: (TelemetrySnapshot) -> String,
): Int? {
    var peak: Int? = null
    var firstQuality: MetricQuality? = null
    var firstSource: String? = null
    for (sample in samples) {
        val count = value(sample)?.takeIf { it >= 0 } ?: continue
        val sampleQuality = quality(sample)
        val sampleSource = source(sample)
        if (sampleQuality == MetricQuality.UNAVAILABLE || sampleSource.isBlank()) continue
        if (firstQuality == null) {
            firstQuality = sampleQuality
            firstSource = sampleSource
        } else if (sampleQuality != firstQuality || sampleSource != firstSource) {
            return null
        }
        peak = peak?.let { maxOf(it, count) } ?: count
    }
    return peak
}

private fun GaugePeak.formatPercent(): String = when {
    provenanceChanged -> "N/A · source changed"
    value != null -> "%.1f%%".format(value)
    else -> "N/A"
}

@Composable
private fun PlanResultOverview(
    progress: PlanProgress,
    results: List<PlanRunResult>,
) {
    val clean = results.count { it.verdict == RunVerdict.CLEAN }
    val underrun = results.count { it.verdict == RunVerdict.UNDERRUN_DETECTED }
    val unsupported = results.count { it.verdict == RunVerdict.UNSUPPORTED }
    val suspected = results.count { it.verdict == RunVerdict.SUSPECTED_PROXY }
    val remaining = results.size - clean - underrun - unsupported - suspected
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier.padding(19.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DPU PLAN RESULTS", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${progress.state.name} · ${results.size}/${progress.totalRuns} runs · " +
                            "${progress.repeatCount} loops",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    progress.source.label,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlanCountMetric(
                    label = "CLEAN",
                    count = clean,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                PlanCountMetric(
                    label = "UNDERRUN",
                    count = underrun,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                PlanCountMetric(
                    label = "UNSUPPORTED",
                    count = unsupported,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Proxy suspected $suspected · aborted/inconclusive $remaining · " +
                    "UNDERRUN은 direct counter 판정만 집계",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            progress.terminalReason?.let { reason ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "Plan 종료 사유 · $reason",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanCountMetric(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 9.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                count.toString(),
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                label,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PlanResultCard(
    result: PlanRunResult,
    shareReport: () -> Unit,
) {
    val verdictColor = when (result.verdict) {
        RunVerdict.CLEAN -> MaterialTheme.colorScheme.primary
        RunVerdict.UNDERRUN_DETECTED -> MaterialTheme.colorScheme.error
        RunVerdict.SUSPECTED_PROXY -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(17.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(verdictColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "#${result.runNumber}",
                    color = verdictColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    result.scenario.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Loop ${result.repeatIndex + 1} · Queue ${result.queueIndex + 1} · " +
                        formatDuration(result.finishedEpochMs - result.startedEpochMs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "Exact Δ ${result.exactUnderrunDelta?.toString() ?: "N/A"} · " +
                        "Proxy Δ ${result.suspectedUnderrunDelta}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                result.terminalReason?.let { reason ->
                    Text(
                        "종료 사유 · $reason",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (result.reportPath.isNullOrBlank()) {
                        "JSON 보고서 없음"
                    } else {
                        "JSON 보고서 저장됨"
                    },
                    color = if (result.reportPath.isNullOrBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = verdictColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(100.dp),
                ) {
                    Text(
                        result.verdict.label,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = verdictColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
                TextButton(
                    onClick = shareReport,
                    enabled = !result.reportPath.isNullOrBlank(),
                ) {
                    Text("JSON 공유", fontSize = 10.sp)
                }
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
    LaunchedEffect(
        controller.hasDumpPermission,
        controller.hasNpuAdapter,
        controller.hasSbwcAdapter,
    ) {
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
                "SBWC adapter",
                controller.hasSbwcAdapter,
                "S${controller.telemetry.vendorServiceSession ?: "N/A"} · " +
                    controller.telemetry.compressionState,
            )
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
private fun LastResultCard(
    summary: RunSummary,
    reportAvailable: Boolean,
    share: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("최근 결과 · ${summary.verdict.label}", style = MaterialTheme.typography.titleMedium)
                Text(summary.scenario.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                summary.terminalReason()?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = share, enabled = reportAvailable) {
                Text(if (reportAvailable) "공유" else "보고서 없음")
            }
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

/**
 * Keeps the HUD time axis intact while preventing a line from joining samples that came from
 * different measurement domains. The first sample after an explicit N/A can be drawn because the
 * N/A itself already creates a gap; a direct provenance switch consumes one sample as the gap.
 */
internal fun segmentedGaugeHistory(samples: List<Gauge>): List<Float?> {
    var previousProvenance: Pair<MetricQuality, String>? = null
    return samples.map { gauge ->
        val value = gauge.value?.takeIf(Float::isFinite)
        val source = gauge.source.trim()
        if (
            value == null ||
            gauge.quality == MetricQuality.UNAVAILABLE ||
            source.isEmpty()
        ) {
            previousProvenance = null
            null
        } else {
            val provenance = gauge.quality to source
            if (previousProvenance != null && previousProvenance != provenance) {
                previousProvenance = provenance
                null
            } else {
                previousProvenance = provenance
                value
            }
        }
    }
}

internal fun gaugeProvenanceLabel(gauge: Gauge): String {
    val quality = when (gauge.quality) {
        MetricQuality.HARDWARE_COUNTER -> "HW"
        MetricQuality.KERNEL -> "KRN"
        MetricQuality.SYSTEM_SERVICE -> "SYS"
        MetricQuality.MEASURED -> "MEAS"
        MetricQuality.ESTIMATED -> "EST"
        MetricQuality.PROXY -> "PROXY"
        MetricQuality.UNAVAILABLE -> "N/A"
    }
    if (gauge.quality == MetricQuality.UNAVAILABLE) return quality
    val source = gauge.source
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(18)
    return if (source.isEmpty()) quality else "$quality · $source"
}

private fun Gauge.provenanceLabel(): String =
    if (source.isBlank()) {
        "${quality.label} · source N/A"
    } else {
        "${quality.label} · $source"
    }

private fun compositionProvenance(telemetry: TelemetrySnapshot): String {
    val sources = buildList {
        if (telemetry.hwcDeviceLayers != null) {
            add(
                Triple(
                    "D",
                    telemetry.hwcDeviceLayersQuality,
                    telemetry.hwcDeviceLayersSource,
                ),
            )
        }
        if (telemetry.hwcClientLayers != null) {
            add(
                Triple(
                    "C",
                    telemetry.hwcClientLayersQuality,
                    telemetry.hwcClientLayersSource,
                ),
            )
        }
    }
    if (sources.isEmpty()) return "Unavailable · source N/A"

    val first = sources.first()
    if (sources.all { it.second == first.second && it.third == first.third }) {
        return Gauge(
            quality = first.second,
            source = first.third,
        ).provenanceLabel()
    }
    return sources.joinToString(" · ") { (label, quality, source) ->
        "$label ${Gauge(quality = quality, source = source).provenanceLabel()}"
    }
}

private fun ScenarioSpec.overview(): ScenarioOverview {
    val patternParts = buildList {
        val transitionModes = phases
            .map { it.transition.mode }
            .filter { it != TransitionMode.STEP }
            .distinct()
        transitionModes.forEach { mode ->
            add(mode.catalogLabel())
        }
        if (isEmpty()) {
            if (phases.any { it.workloads.shape == LoadShape.PULSE }) add("Worker 펄스")
            if (phases.any { it.workloads.shape == LoadShape.RAMP }) add("Worker 램프")
            if (phases.any { it.workloads.shape == LoadShape.SAW }) add("Worker 왕복")
        }
        if (isEmpty() && phases.size > 1) add("즉시 STEP")
    }
    val score = ScenarioClassifier.intensityScore(this)
    val label = when {
        score < 30 -> "낮음"
        score < 50 -> "보통"
        score < 70 -> "높음"
        else -> "매우 높음"
    }
    val sequencePhases = if (phases.size <= 5) {
        phases
    } else {
        phases.take(4) + phases.last()
    }
    val sequence = sequencePhases.mapIndexed { index, phase ->
        val token = buildString {
            append("${phase.activeLayers}L·${phase.producerFps.toInt()}f")
            phase.workloads.peakPercent()
                .takeIf { it > 0 }
                ?.let { append("·$it%") }
            if (phase.transition.mode != TransitionMode.STEP) {
                append("·${phase.transition.mode.shortLabel()}")
            }
        }
        if (phases.size > 5 && index == sequencePhases.lastIndex) "… → $token" else token
    }.joinToString(" → ")
    return ScenarioOverview(
        patternLabel = patternParts.take(2).joinToString(" + ").ifBlank { "고정 유지" },
        intensityScore = score,
        intensityLabel = label,
        phaseSequence = sequence.ifBlank { "phase 없음" },
    )
}

private fun LoadSetpoints.peakPercent(): Int = normalized().let {
    (maxOf(it.cpu, it.memory, it.gpu, it.npu) * 100f).roundToInt()
}

private fun LoadSetpoints.peakSummary(): String {
    val safe = normalized()
    val active = listOf(
        "CPU" to safe.cpu,
        "MEM" to safe.memory,
        "GPU" to safe.gpu,
        "NPU" to safe.npu,
    ).filter { (_, value) -> value > 0.001f }
    return if (active.isEmpty()) {
        "교차 부하 없음"
    } else {
        active.joinToString(" · ") { (label, value) ->
            "$label ${(value * 100f).roundToInt()}%"
        }
    }
}

private fun workloadTransitionSummary(
    current: LoadSetpoints?,
    target: LoadSetpoints,
): String {
    val safeCurrent = current?.normalized() ?: LoadSetpoints()
    val safeTarget = target.normalized()
    val resources = listOf(
        Triple("CPU", safeCurrent.cpu, safeTarget.cpu),
        Triple("MEM", safeCurrent.memory, safeTarget.memory),
        Triple("GPU", safeCurrent.gpu, safeTarget.gpu),
        Triple("NPU", safeCurrent.npu, safeTarget.npu),
    ).filter { (_, from, to) -> from > 0.001f || to > 0.001f }
    return if (resources.isEmpty()) {
        "교차 부하 0%"
    } else {
        resources.joinToString(" · ") { (label, from, to) ->
            "$label ${(from * 100f).roundToInt()}→${(to * 100f).roundToInt()}%"
        }
    }
}

private fun TransitionMode.catalogLabel(): String = when (this) {
    TransitionMode.STEP -> "즉시 STEP"
    TransitionMode.LINEAR_RAMP -> "선형 RAMP"
    TransitionMode.STAIRCASE -> "계단 전이"
    TransitionMode.PULSE_BURST -> "ON/OFF BURST"
    TransitionMode.TRIANGLE_WAVE -> "삼각파"
    TransitionMode.SOAK_RECOVERY -> "SOAK 복구"
}

private fun TransitionMode.shortLabel(): String = when (this) {
    TransitionMode.STEP -> "STEP"
    TransitionMode.LINEAR_RAMP -> "RAMP"
    TransitionMode.STAIRCASE -> "STAIR"
    TransitionMode.PULSE_BURST -> "BURST"
    TransitionMode.TRIANGLE_WAVE -> "WAVE"
    TransitionMode.SOAK_RECOVERY -> "SOAK"
}

private fun RunnerStage.displayLabel(): String = when (this) {
    RunnerStage.IDLE -> "대기"
    RunnerStage.PRECHECK -> "사전 검사"
    RunnerStage.WARMUP -> "워밍업"
    RunnerStage.RUNNING -> "실행 중"
    RunnerStage.COOLDOWN -> "회복 확인"
    RunnerStage.COMPLETE -> "완료"
    RunnerStage.ABORTED -> "중단"
    RunnerStage.UNSUPPORTED -> "미지원"
}

private fun toggleFilterKey(current: List<String>, key: String): List<String> {
    if (key.isBlank()) return current
    return ArrayList(current).apply {
        if (!remove(key)) add(key)
    }
}

private inline fun <reified T : Enum<T>> List<String>.toKnownEnumSet(): Set<T> {
    if (isEmpty()) return emptySet()
    val valuesByName = enumValues<T>().associateBy { it.name }
    return mapNotNullTo(LinkedHashSet(size)) { valuesByName[it] }
}

private inline fun <reified T : Enum<T>> List<String>.retainKnownEnumKeys(): List<String> =
    toKnownEnumSet<T>().mapTo(ArrayList()) { it.name }

private fun maximumPlanRepeats(queueSize: Int): Int {
    return ScenarioPlanPolicy.maximumRepeatCount(queueSize)
}

private fun List<Int>.positionSummary(): String {
    val visible = take(2).joinToString(separator = " · ") { "#$it" }
    return if (size > 2) "$visible +${size - 2}" else visible
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}

private fun yesNo(value: Boolean) = if (value) "✓" else "–"
