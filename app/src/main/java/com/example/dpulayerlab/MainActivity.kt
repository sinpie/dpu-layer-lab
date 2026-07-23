package com.example.dpulayerlab

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.example.dpulayerlab.engine.AutomationCommand
import com.example.dpulayerlab.engine.AutomationIntentContract
import com.example.dpulayerlab.engine.AutomationIntentInput
import com.example.dpulayerlab.engine.AutomationIntentParseResult
import com.example.dpulayerlab.engine.LabController
import com.example.dpulayerlab.engine.PendingAutomationEnqueueResult
import com.example.dpulayerlab.engine.ScenarioCatalog
import com.example.dpulayerlab.engine.automationActionNeedsStartExtras
import com.example.dpulayerlab.engine.enqueuePendingAutomation
import com.example.dpulayerlab.model.PlanSource
import com.example.dpulayerlab.model.ScenarioRunPlan
import com.example.dpulayerlab.ui.DpuLayerLabApp
import com.example.dpulayerlab.ui.theme.DpuLabTheme
import com.example.dpulayerlab.util.currentDisplayCompat
import java.util.ArrayDeque
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var controller: LabController
    private var lastPreferredModeId = Int.MIN_VALUE
    private var lastPreferredRefreshRate = Float.NaN
    private var activityStarted = false
    private var lastReceivedIntent: Intent? = null
    private var pendingAutomationOverflow = false
    private var lastDisplayEnvelopeIdentity: DisplayEnvelopeIdentity? = null
    private val pendingAutomation = ArrayDeque<AutomationIntentParseResult>()
    private val catalogIds: Set<String> =
        ScenarioCatalog.presets.mapTo(LinkedHashSet()) { it.id }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller = LabController(this, ::requestDisplayRefresh)
        lastDisplayEnvelopeIdentity = currentDisplayEnvelopeIdentity()
        setContent {
            DpuLabTheme {
                DpuLayerLabApp(controller)
            }
        }
        // Recreating an Activity must never replay a stress request that may already have run.
        if (savedInstanceState == null) enqueueAutomation(intent)
    }

    override fun onStart() {
        super.onStart()
        controller.start()
        activityStarted = true
        drainPendingAutomation()
    }

    override fun onStop() {
        activityStarted = false
        if (controller.isRunning) controller.stopScenario("앱이 백그라운드로 전환되어 안전 중단")
        controller.pause()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueueAutomation(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val previous = lastDisplayEnvelopeIdentity
        super.onConfigurationChanged(newConfig)
        val current = currentDisplayEnvelopeIdentity()
        lastDisplayEnvelopeIdentity = current
        if (controller.isRunning && displaySafetyEnvelopeChanged(previous, current)) {
            controller.invalidateSafetyEnvelope(
                "실행 중 display identity/physical size가 " +
                    "${previous?.summary() ?: "unknown"} → ${current.summary()}로 변경됨",
            )
        }
    }

    override fun onDestroy() {
        pendingAutomation.clear()
        controller.close()
        super.onDestroy()
    }

    private fun enqueueAutomation(incoming: Intent?) {
        if (
            incoming == null ||
            incoming === lastReceivedIntent ||
            !incoming.targetsProtectedAutomationAlias()
        ) {
            return
        }
        lastReceivedIntent = incoming
        val parsed = try {
            AutomationIntentContract.parse(
                input = incoming.toAutomationInput(),
                knownScenarioIds = catalogIds,
            )
        } catch (_: RuntimeException) {
            AutomationIntentParseResult.Rejected(
                "Intent extras를 안전하게 읽을 수 없습니다.",
            )
        }
        if (parsed == AutomationIntentParseResult.Ignored) return
        when (
            enqueuePendingAutomation(
                queue = pendingAutomation,
                parsed = parsed,
                maxPendingCommands = MAX_PENDING_AUTOMATION_COMMANDS,
            )
        ) {
            PendingAutomationEnqueueResult.STOP_SUPERSEDED -> {
                // The newest STOP invalidates the burst which caused this warning too.
                pendingAutomationOverflow = false
            }
            PendingAutomationEnqueueResult.OVERFLOW -> pendingAutomationOverflow = true
            PendingAutomationEnqueueResult.DUPLICATE,
            PendingAutomationEnqueueResult.ENQUEUED,
            -> Unit
        }
        if (activityStarted) drainPendingAutomation()
    }

    private fun drainPendingAutomation() {
        if (!activityStarted) return
        while (pendingAutomation.isNotEmpty()) {
            when (val result = pendingAutomation.removeFirst()) {
                AutomationIntentParseResult.Ignored -> Unit
                is AutomationIntentParseResult.Rejected ->
                    controller.showError("Automation 요청 오류: ${result.message}")
                is AutomationIntentParseResult.Accepted ->
                    executeAutomation(result.command)
            }
        }
        if (pendingAutomationOverflow) {
            pendingAutomationOverflow = false
            controller.showError("대기 중인 automation 요청이 너무 많아 새 요청을 거부했습니다.")
        }
    }

    /**
     * MainActivity remains exported for the launcher, so action matching alone is not caller
     * authorization. Only the permission-protected manifest alias may deliver stress commands.
     * An explicit START sent directly to MainActivity is therefore ignored.
     */
    private fun Intent.targetsProtectedAutomationAlias(): Boolean =
        component?.className == AUTOMATION_ALIAS_CLASS

    private fun executeAutomation(command: AutomationCommand) {
        when (command) {
            AutomationCommand.Show -> Unit
            AutomationCommand.Stop ->
                controller.stopScenario("외부 Intent가 전체 test plan 중단을 요청함")
            is AutomationCommand.Start -> {
                val scenarios = command.scenarioIds.mapNotNull(ScenarioCatalog::byId)
                if (scenarios.size != command.scenarioIds.size) {
                    controller.showError(
                        "Automation 요청 처리 중 catalog가 변경되어 plan을 시작하지 못했습니다.",
                    )
                    return
                }
                controller.startPlan(
                    ScenarioRunPlan(
                        scenarios = scenarios,
                        repeatCount = command.repeatCount,
                        source = PlanSource.EXTERNAL_INTENT,
                    ),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.toAutomationInput(): AutomationIntentInput {
        val intentAction = action
        // STOP is the safety escape hatch. Do not unmarshal unrelated/malformed extras before
        // honoring it (SHOW and ignored actions likewise need no payload).
        if (!automationActionNeedsStartExtras(intentAction)) {
            return AutomationIntentInput(action = intentAction)
        }
        val extras = extras
        return AutomationIntentInput(
            action = intentAction,
            scenarioIdPresent = hasExtra(AutomationIntentContract.EXTRA_SCENARIO_ID),
            scenarioId = extras?.get(AutomationIntentContract.EXTRA_SCENARIO_ID),
            scenarioIdsPresent = hasExtra(AutomationIntentContract.EXTRA_SCENARIO_IDS),
            scenarioIds = extras?.get(AutomationIntentContract.EXTRA_SCENARIO_IDS),
            repeatCountPresent = hasExtra(AutomationIntentContract.EXTRA_REPEAT_COUNT),
            repeatCount = extras?.get(AutomationIntentContract.EXTRA_REPEAT_COUNT),
        )
    }

    private fun requestDisplayRefresh(targetHz: Float) {
        if (!targetHz.isFinite() || targetHz <= 0f) {
            if (lastPreferredModeId == 0 && lastPreferredRefreshRate == 0f) return
            val attributes = window.attributes
            attributes.preferredDisplayModeId = 0
            attributes.preferredRefreshRate = 0f
            window.attributes = attributes
            lastPreferredModeId = 0
            lastPreferredRefreshRate = 0f
            return
        }
        val currentDisplay = currentDisplayCompat() ?: return
        val currentMode = currentDisplay.mode
        val sameResolution = currentDisplay.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        val candidates = sameResolution.ifEmpty { currentDisplay.supportedModes.toList() }
        val chosen = candidates.minByOrNull { abs(it.refreshRate - targetHz) } ?: return
        if (
            chosen.modeId == lastPreferredModeId &&
            abs(chosen.refreshRate - lastPreferredRefreshRate) < 0.01f
        ) {
            return
        }
        val attributes = window.attributes
        attributes.preferredDisplayModeId = chosen.modeId
        attributes.preferredRefreshRate = chosen.refreshRate
        window.attributes = attributes
        lastPreferredModeId = chosen.modeId
        lastPreferredRefreshRate = chosen.refreshRate
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayEnvelopeIdentity(): DisplayEnvelopeIdentity {
        val display = currentDisplayCompat()
        val mode = display?.mode
        val widthPx = mode?.physicalWidth
            ?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val heightPx = mode?.physicalHeight
            ?.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels.coerceAtLeast(1)
        return DisplayEnvelopeIdentity(
            displayId = display?.displayId,
            shortEdgePx = minOf(widthPx, heightPx),
            longEdgePx = maxOf(widthPx, heightPx),
        )
    }

    private companion object {
        const val MAX_PENDING_AUTOMATION_COMMANDS = 8
        const val AUTOMATION_ALIAS_CLASS = "com.example.dpulayerlab.AutomationActivity"
    }
}

internal data class DisplayEnvelopeIdentity(
    val displayId: Int?,
    val shortEdgePx: Int,
    val longEdgePx: Int,
) {
    fun summary(): String =
        "display=${displayId ?: "unknown"} ${shortEdgePx.coerceAtLeast(1)}x" +
            longEdgePx.coerceAtLeast(1)
}

internal fun displaySafetyEnvelopeChanged(
    previous: DisplayEnvelopeIdentity?,
    current: DisplayEnvelopeIdentity?,
): Boolean = previous != current
