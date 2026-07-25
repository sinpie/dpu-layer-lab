package com.example.dpulayerlab

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.dpulayerlab.engine.AutomationCommand
import com.example.dpulayerlab.engine.AutomationIntentContract
import com.example.dpulayerlab.engine.AutomationIntentInput
import com.example.dpulayerlab.engine.AutomationIntentParseResult
import com.example.dpulayerlab.engine.FocusGainIsolationAction
import com.example.dpulayerlab.engine.LabController
import com.example.dpulayerlab.engine.PendingAutomationEnqueueResult
import com.example.dpulayerlab.engine.ControllerBackendCleanupPhase
import com.example.dpulayerlab.engine.ProcessControllerBackendCleanupCoordinator
import com.example.dpulayerlab.engine.ProcessTestWindowIsolationLeaseRegistry
import com.example.dpulayerlab.engine.ReleaseStatus
import com.example.dpulayerlab.engine.RequestResult
import com.example.dpulayerlab.engine.ScenarioCatalog
import com.example.dpulayerlab.engine.TestWindowIsolationPhase
import com.example.dpulayerlab.engine.TestWindowIsolationPort
import com.example.dpulayerlab.engine.TestWindowIsolationState
import com.example.dpulayerlab.engine.automationActionNeedsStartExtras
import com.example.dpulayerlab.engine.createLabController
import com.example.dpulayerlab.engine.enqueuePendingAutomation
import com.example.dpulayerlab.engine.focusGainIsolationAction
import com.example.dpulayerlab.engine.foreignLeaseRestorationAcknowledged
import com.example.dpulayerlab.engine.isolationStateAfterRestoreCommandResult
import com.example.dpulayerlab.engine.observeSystemBars
import com.example.dpulayerlab.engine.observeWindowFocusLoss
import com.example.dpulayerlab.engine.shouldHideIdleSystemBarsForForeignLease
import com.example.dpulayerlab.engine.shouldAttemptForeignLeaseHide
import com.example.dpulayerlab.engine.shouldShowIdleSystemBars
import com.example.dpulayerlab.model.PlanSource
import com.example.dpulayerlab.model.ScenarioRunPlan
import com.example.dpulayerlab.ui.DpuLayerLabApp
import com.example.dpulayerlab.ui.theme.DpuLabTheme
import com.example.dpulayerlab.util.currentDisplayCompat
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: LabController? = null
    private lateinit var testWindowIsolation: ActivityTestWindowIsolation
    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var backendGateText: String? = null
    private var pendingControllerError: String? = null
    private val backendRetry = Runnable(::initializeControllerIfAvailable)
    private var lastPreferredModeId = Int.MIN_VALUE
    private var lastPreferredRefreshRate = Float.NaN
    private var activityStarted = false
    private var lastReceivedIntent: Intent? = null
    private var pendingAutomationOverflow = false
    private var automationStartWaitingForInsets = false
    private var automationWindowReadyDrainPosted = false
    private var lastDisplayEnvelopeIdentity: DisplayEnvelopeIdentity? = null
    private var activeRunDisplayEnvelopeIdentity: DisplayEnvelopeIdentity? = null
    private var displayEnvelopeInvalidationReported = false
    private var displayListenerRegistered = false
    private var displayRunStateJob: Job? = null
    private var batterySaverSettingsOpenPending = false
    private var batterySaverSettingsOpenAttempts = 0
    private val pendingAutomation = ArrayDeque<AutomationIntentParseResult>()
    private val catalogIds: Set<String> =
        ScenarioCatalog.presets.mapTo(LinkedHashSet()) { it.id }
    private val displayChangeCallbackHolder = DisplayChangeCallbackHolder()
    private val displayListener = RuntimeDisplayListener(displayChangeCallbackHolder)
    private val automationWindowReadyDrain = Runnable {
        automationWindowReadyDrainPosted = false
        if (!activityStarted || destroyed) {
            return@Runnable
        }
        // The Activity display can be null before its Window is attached. Refresh the display
        // identity after every delivered Insets state so a manual UI START also has an attached
        // baseline. A deferred automation START is consumed only after that refresh.
        validateCurrentDisplayEnvelope()
        if (!automationStartWaitingForInsets || controller == null) {
            return@Runnable
        }
        automationStartWaitingForInsets = false
        drainPendingAutomation()
    }
    private val batterySaverSettingsOpenDrain = object : Runnable {
        override fun run() {
            if (!batterySaverSettingsOpenPending || destroyed) return
            val planCleanupComplete =
                controller?.canOpenBatterySaverSettings == true
            if (
                planCleanupComplete &&
                testWindowIsolation.isExternalNavigationSafe()
            ) {
                batterySaverSettingsOpenPending = false
                batterySaverSettingsOpenAttempts = 0
                launchBatterySaverSettings()
                return
            }
            batterySaverSettingsOpenAttempts++
            if (
                batterySaverSettingsOpenAttempts >=
                MAX_BATTERY_SETTINGS_DEFER_ATTEMPTS
            ) {
                batterySaverSettingsOpenPending = false
                batterySaverSettingsOpenAttempts = 0
                controller?.showBatterySaverSettingsError(
                    "테스트 정리 또는 SystemUI 복구가 완료되지 않아 설정 화면을 열지 않았습니다.",
                )
                return
            }
            mainHandler.postDelayed(this, BATTERY_SETTINGS_DEFER_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        testWindowIsolation = ActivityTestWindowIsolation(
            activity = this,
            contaminationCallback = { token, reason, eventType ->
                controller?.onTestWindowIsolationLost(token, reason, eventType)
            },
            windowInsetsReadyCallback = ::onAutomationWindowInsetsReady,
        )
        // Recreating an Activity must never replay a stress request that may already have run.
        if (savedInstanceState == null) enqueueAutomation(intent)
        initializeControllerIfAvailable()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        registerDisplayListener()
        // Compare before overwriting: a fold/external-display move can happen while the Activity
        // is stopped without producing an in-process configuration callback.
        validateCurrentDisplayEnvelope()
        testWindowIsolation.reconcileWithLifecycle()
        controller?.let { activeController ->
            activeController.start()
            drainPendingAutomation()
        } ?: run {
            initializeControllerIfAvailable()
        }
        if (batterySaverSettingsOpenPending) {
            batterySaverSettingsOpenDrain.run()
        }
    }

    override fun onStop() {
        activityStarted = false
        mainHandler.removeCallbacks(batterySaverSettingsOpenDrain)
        try {
            controller?.let { activeController ->
                if (activeController.isRunning) {
                    activeController.stopScenario("앱이 백그라운드로 전환되어 안전 중단")
                }
                activeController.pause()
            }
        } finally {
            unregisterDisplayListener()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueueAutomation(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        validateCurrentDisplayEnvelope()
        testWindowIsolation.reapplyIfRequested()
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (
            controller != null &&
            isInMultiWindowMode &&
            controller?.isRunning == true
        ) {
            controller?.invalidateSafetyEnvelope(
                "실행 중 multi-window mode로 전환되어 fullscreen 격리가 무효화됨",
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (
            controller != null &&
            isInPictureInPictureMode &&
            controller?.isRunning == true
        ) {
            controller?.invalidateSafetyEnvelope(
                "실행 중 picture-in-picture mode로 전환되어 fullscreen 격리가 무효화됨",
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Activity has no public onMovedToDisplay callback. A moved window regains focus on its
        // destination display, covering equal-size display moves that configuration deltas alone
        // cannot identify; onStart provides the second lifecycle boundary check.
        if (hasFocus) validateCurrentDisplayEnvelope()
        testWindowIsolation.onWindowFocusChanged(hasFocus)
    }

    private fun validateCurrentDisplayEnvelope() {
        if (destroyed) return
        val previous = lastDisplayEnvelopeIdentity
        val current = currentDisplayEnvelopeIdentity()
        lastDisplayEnvelopeIdentity = current
        if (displaySafetyEnvelopeChanged(previous, current)) {
            controller?.refreshHwcCapacityCalibrationDisplayProjection()
        }
        val activeController = controller
        if (activeController?.isRunning != true) {
            clearActiveRunDisplayEnvelope()
            return
        }
        val runSnapshot = selectRunDisplayEnvelopeSnapshot(
            existingRunSnapshot = activeRunDisplayEnvelopeIdentity,
            lastObserved = previous,
            current = current,
        ).also {
            if (activeRunDisplayEnvelopeIdentity == null) {
                activeRunDisplayEnvelopeIdentity = it
            }
        }
        if (
            runningDisplaySafetyEnvelopeChanged(runSnapshot, current) &&
            !displayEnvelopeInvalidationReported
        ) {
            displayEnvelopeInvalidationReported = true
            activeController.invalidateSafetyEnvelope(
                "실행 중 display identity/physical size가 " +
                    "${runSnapshot.summary()} → ${current.summary()}로 변경됨",
            )
        }
    }

    private fun registerDisplayListener() {
        if (destroyed) return
        if (!ProcessDisplayListenerCleanupState.isConfirmed()) return
        displayChangeCallbackHolder.attach(::onRuntimeDisplayEvent)
        if (displayListenerRegistered) return
        val manager = getSystemService(DisplayManager::class.java)
        if (manager == null) {
            displayChangeCallbackHolder.detach()
            return
        }
        displayListenerRegistered = runCatching {
            manager.registerDisplayListener(displayListener, mainHandler)
        }.isSuccess
        if (!displayListenerRegistered) displayChangeCallbackHolder.detach()
    }

    private fun unregisterDisplayListener() {
        // Detach the Activity callback first so a failed Binder unregister cannot retain this
        // Activity or deliver a late safety mutation into a destroyed controller.
        displayChangeCallbackHolder.detach()
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        val manager = getSystemService(DisplayManager::class.java)
        val cleanupConfirmed =
            manager != null &&
                runCatching { manager.unregisterDisplayListener(displayListener) }.isSuccess
        if (!cleanupConfirmed) ProcessDisplayListenerCleanupState.markUnconfirmed()
    }

    private fun onRuntimeDisplayEvent() {
        if (!activityStarted || destroyed) return
        validateCurrentDisplayEnvelope()
    }

    private fun observeControllerRunDisplayEnvelope(activeController: LabController) {
        displayRunStateJob?.cancel()
        displayRunStateJob = lifecycleScope.launch {
            snapshotFlow { activeController.isRunning }
                .distinctUntilChanged()
                .collect { running ->
                    if (controller !== activeController || destroyed) return@collect
                    if (!running) {
                        clearActiveRunDisplayEnvelope()
                        return@collect
                    }
                    val current = currentDisplayEnvelopeIdentity()
                    val previous = lastDisplayEnvelopeIdentity
                    val runSnapshot = selectRunDisplayEnvelopeSnapshot(
                        existingRunSnapshot = activeRunDisplayEnvelopeIdentity,
                        lastObserved = previous,
                        current = current,
                    ).also {
                        if (activeRunDisplayEnvelopeIdentity == null) {
                            activeRunDisplayEnvelopeIdentity = it
                        }
                    }
                    lastDisplayEnvelopeIdentity = current
                    if (displaySafetyEnvelopeChanged(previous, current)) {
                        activeController.refreshHwcCapacityCalibrationDisplayProjection()
                    }
                    when {
                        !displayListenerRegistered && !displayEnvelopeInvalidationReported -> {
                            displayEnvelopeInvalidationReported = true
                            activeController.invalidateSafetyEnvelope(
                                "실행 중 display identity/physical size 변경 감시를 " +
                                    "등록하지 못해 safety envelope를 보장할 수 없음",
                            )
                        }
                        runningDisplaySafetyEnvelopeChanged(runSnapshot, current) &&
                            !displayEnvelopeInvalidationReported -> {
                            displayEnvelopeInvalidationReported = true
                            activeController.invalidateSafetyEnvelope(
                                "실행 시작 display identity/physical size " +
                                    "${runSnapshot.summary()}와 현재 ${current.summary()}가 다름",
                            )
                        }
                    }
                }
        }
    }

    private fun clearActiveRunDisplayEnvelope() {
        activeRunDisplayEnvelopeIdentity = null
        displayEnvelopeInvalidationReported = false
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(backendRetry)
        mainHandler.removeCallbacks(batterySaverSettingsOpenDrain)
        batterySaverSettingsOpenPending = false
        batterySaverSettingsOpenAttempts = 0
        pendingAutomation.clear()
        automationStartWaitingForInsets = false
        window.decorView.removeCallbacks(automationWindowReadyDrain)
        automationWindowReadyDrainPosted = false
        displayRunStateJob?.cancel()
        displayRunStateJob = null
        unregisterDisplayListener()
        // Publish the process-wide stage-removal token before Compose disposal can detach its
        // AndroidView. The later stage callback clears the token; native worker leases remain
        // authoritative if any thread outlives the bounded join.
        val closingController = controller
        // Detach the Activity field first. Even if fatal cleanup rethrows, this Activity must not
        // retain the controller/backend graph until framework destruction eventually completes.
        controller = null
        try {
            closingController?.close()
        } finally {
            try {
                if (::testWindowIsolation.isInitialized) {
                    testWindowIsolation.close()
                }
            } finally {
                super.onDestroy()
            }
        }
    }

    /**
     * Serializes backend construction across Activity recreation. No LoadManager, monitor, or
     * VendorBridge is touched until the prior Activity's bounded cleanup has reached IDLE.
     */
    private fun initializeControllerIfAvailable() {
        if (destroyed || controller != null) return
        mainHandler.removeCallbacks(backendRetry)
        val ownerToken =
            ProcessControllerBackendCleanupCoordinator.tryAcquireOwner()
        if (ownerToken == null) {
            val snapshot = ProcessControllerBackendCleanupCoordinator.snapshot()
            val message = when (snapshot.phase) {
                ControllerBackendCleanupPhase.ACTIVE ->
                    "이전 화면의 backend가 아직 활성 상태입니다.\n안전한 종료를 기다리는 중…"
                ControllerBackendCleanupPhase.CLEANING ->
                    "CPU/메모리/codec/vendor backend를 정리하는 중입니다.\n" +
                        "새 테스트는 정리 확인 뒤 자동으로 열립니다."
                ControllerBackendCleanupPhase.FAILED ->
                    "이전 backend 종료를 확인할 수 없습니다.\n" +
                        "중복 worker나 vendor 정책 누수를 막기 위해 새 테스트를 차단했습니다.\n" +
                        "앱 process를 완전히 종료한 뒤 다시 실행하세요.\n\n" +
                        (snapshot.failureReason ?: "원인 정보 없음")
                ControllerBackendCleanupPhase.IDLE ->
                    "Backend owner 경합을 해소하는 중…"
            }
            showBackendGate(message)
            if (snapshot.phase != ControllerBackendCleanupPhase.FAILED) {
                mainHandler.postDelayed(backendRetry, BACKEND_OWNER_RETRY_MS)
            }
            return
        }
        var candidate: LabController? = null
        try {
            val initializedController = createLabController(
                activity = this,
                requestDisplayMode = ::requestDisplayRefresh,
                testWindowIsolation = testWindowIsolation,
                backendOwnerToken = ownerToken,
            )
            candidate = initializedController
            backendGateText = null
            lastDisplayEnvelopeIdentity = currentDisplayEnvelopeIdentity()
            setContent {
                DpuLabTheme {
                    DpuLayerLabApp(
                        controller = initializedController,
                        onOpenBatterySaverSettings = ::openBatterySaverSettings,
                    )
                }
            }
            pendingControllerError?.let(initializedController::showError)
            pendingControllerError = null
            if (activityStarted) {
                initializedController.start()
            }
            controller = initializedController
            observeControllerRunDisplayEnvelope(initializedController)
            if (activityStarted) drainPendingAutomation()
        } catch (error: Throwable) {
            if (candidate == null) {
                ProcessControllerBackendCleanupCoordinator.failOwner(
                    ownerToken,
                    "backend constructor failed: ${error.javaClass.simpleName}",
                )
            } else {
                // Construction succeeded, so the candidate owns every partially started backend.
                // Its Activity-free close transaction must run before this Activity drops it.
                candidate.let { initialized ->
                    if (runCatching(initialized::close).isFailure) {
                        ProcessControllerBackendCleanupCoordinator.failOwner(
                            ownerToken,
                            "backend rollback failed after UI initialization error",
                        )
                    }
                }
            }
            displayRunStateJob?.cancel()
            displayRunStateJob = null
            clearActiveRunDisplayEnvelope()
            controller = null
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            showBackendGate(
                "Backend/UI 초기화에 실패했습니다.\n부분 초기화 자원의 중복 생성을 막기 위해 " +
                    "현재 process에서 새 테스트를 차단했습니다.\n앱 process를 완전히 종료한 뒤 " +
                    "다시 실행하세요.\n\n${error.javaClass.simpleName}",
            )
        }
    }

    private fun openBatterySaverSettings() {
        if (batterySaverSettingsOpenPending || destroyed) return
        batterySaverSettingsOpenPending = true
        batterySaverSettingsOpenAttempts = 0
        batterySaverSettingsOpenDrain.run()
    }

    private fun launchBatterySaverSettings() {
        val opened = batterySaverSettingsActionOrder().any { action ->
            try {
                startActivity(Intent(action))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        if (!opened) {
            controller?.showBatterySaverSettingsError(
                "배터리 절약 설정 화면을 열 수 없습니다.",
            )
        }
    }

    private fun showBackendGate(message: String) {
        if (backendGateText == message) return
        backendGateText = message
        setContentView(
            TextView(this).apply {
                text = getString(
                    R.string.backend_gate_message,
                    getString(R.string.app_name),
                    message,
                )
                setTextColor(Color.rgb(225, 247, 239))
                setBackgroundColor(Color.rgb(7, 19, 16))
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            },
        )
    }

    private fun showControllerError(message: String) {
        controller?.let {
            it.showError(message)
        } ?: run {
            pendingControllerError = message
        }
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
        if (activityStarted && controller != null) drainPendingAutomation()
    }

    private fun drainPendingAutomation() {
        if (!activityStarted || controller == null) return
        while (pendingAutomation.isNotEmpty()) {
            val next = pendingAutomation.first()
            if (
                shouldDeferAutomationUntilWindowReady(
                    parsed = next,
                    decorAttached =
                        window.decorView.isAttachedToWindow,
                    rootInsetsAvailable =
                        testWindowIsolation.hasRootWindowInsets(),
                )
            ) {
                automationStartWaitingForInsets = true
                testWindowIsolation.requestRootWindowInsets()
                return
            }
            automationStartWaitingForInsets = false
            when (val result = pendingAutomation.removeFirst()) {
                AutomationIntentParseResult.Ignored -> Unit
                is AutomationIntentParseResult.Rejected ->
                    showControllerError("Automation 요청 오류: ${result.message}")
                is AutomationIntentParseResult.Accepted ->
                    executeAutomation(result.command)
            }
        }
        if (pendingAutomationOverflow) {
            pendingAutomationOverflow = false
            showControllerError(
                "대기 중인 automation 요청이 너무 많아 새 요청을 거부했습니다.",
            )
        }
    }

    private fun onAutomationWindowInsetsReady() {
        if (
            destroyed ||
            automationWindowReadyDrainPosted
        ) {
            return
        }
        // Exit the Insets dispatch before display validation or startPlan() can request immersive
        // Insets again. This avoids recursively entering the isolation state machine from its
        // readiness callback.
        automationWindowReadyDrainPosted =
            window.decorView.post(automationWindowReadyDrain)
    }

    /**
     * MainActivity remains exported for the launcher, so action matching alone is not caller
     * authorization. Only the permission-protected manifest alias may deliver stress commands.
     * An explicit START sent directly to MainActivity is therefore ignored.
     */
    private fun Intent.targetsProtectedAutomationAlias(): Boolean =
        component?.className == AUTOMATION_ALIAS_CLASS

    private fun executeAutomation(command: AutomationCommand) {
        val activeController = controller ?: return
        when (command) {
            AutomationCommand.Show -> Unit
            AutomationCommand.Stop ->
                activeController.stopScenario("외부 Intent가 전체 test plan 중단을 요청함")
            is AutomationCommand.Start -> {
                val scenarios = command.scenarioIds.mapNotNull(ScenarioCatalog::byId)
                if (scenarios.size != command.scenarioIds.size) {
                    activeController.showError(
                        "Automation 요청 처리 중 catalog가 변경되어 plan을 시작하지 못했습니다.",
                    )
                    return
                }
                activeController.startPlan(
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

    private fun requestDisplayRefresh(targetHz: Float): Boolean = runCatching {
        if (!targetHz.isFinite() || targetHz <= 0f) {
            if (lastPreferredModeId == 0 && lastPreferredRefreshRate == 0f) {
                return@runCatching true
            }
            val attributes = window.attributes
            attributes.preferredDisplayModeId = 0
            attributes.preferredRefreshRate = 0f
            window.attributes = attributes
            lastPreferredModeId = 0
            lastPreferredRefreshRate = 0f
            return@runCatching true
        }
        val currentDisplay = currentDisplayCompat() ?: return@runCatching false
        val currentMode = currentDisplay.mode
        val sameResolution = currentDisplay.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        val candidates = sameResolution.ifEmpty { currentDisplay.supportedModes.toList() }
        val chosen = candidates.minByOrNull { abs(it.refreshRate - targetHz) }
            ?: return@runCatching false
        if (
            chosen.modeId == lastPreferredModeId &&
            abs(chosen.refreshRate - lastPreferredRefreshRate) < 0.01f
        ) {
            return@runCatching true
        }
        val attributes = window.attributes
        attributes.preferredDisplayModeId = chosen.modeId
        attributes.preferredRefreshRate = chosen.refreshRate
        window.attributes = attributes
        lastPreferredModeId = chosen.modeId
        lastPreferredRefreshRate = chosen.refreshRate
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun currentDisplayEnvelopeIdentity(): DisplayEnvelopeIdentity {
        val display = runCatching { currentDisplayCompat() }.getOrNull()
        val mode = runCatching { display?.mode }.getOrNull()
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
        const val BACKEND_OWNER_RETRY_MS = 100L
        const val BATTERY_SETTINGS_DEFER_POLL_MS = 100L
        const val MAX_BATTERY_SETTINGS_DEFER_ATTEMPTS = 300
        const val AUTOMATION_ALIAS_CLASS = "com.example.dpulayerlab.AutomationActivity"
    }
}

internal fun batterySaverSettingsActionOrder(): List<String> = listOf(
    Settings.ACTION_BATTERY_SAVER_SETTINGS,
    Settings.ACTION_SETTINGS,
)

internal fun batterySaverSettingsNavigationReady(
    planCleanupComplete: Boolean,
    isolationPhase: TestWindowIsolationPhase,
    processLeaseActive: Boolean,
    foreignLeaseMasking: Boolean,
): Boolean =
    planCleanupComplete &&
        isolationPhase == TestWindowIsolationPhase.IDLE &&
        !processLeaseActive &&
        !foreignLeaseMasking

private object ProcessDisplayListenerCleanupState {
    private val cleanupConfirmed = AtomicBoolean(true)

    fun isConfirmed(): Boolean = cleanupConfirmed.get()

    fun markUnconfirmed() {
        cleanupConfirmed.set(false)
    }
}

private class DisplayChangeCallbackHolder {
    @Volatile
    private var callback: (() -> Unit)? = null

    fun attach(newCallback: () -> Unit) {
        callback = newCallback
    }

    fun detach() {
        callback = null
    }

    fun dispatch() {
        callback?.invoke()
    }
}

private class RuntimeDisplayListener(
    private val callbackHolder: DisplayChangeCallbackHolder,
) : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) = callbackHolder.dispatch()

    override fun onDisplayRemoved(displayId: Int) = callbackHolder.dispatch()

    override fun onDisplayChanged(displayId: Int) = callbackHolder.dispatch()
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

internal fun displayEnvelopeSnapshotAtRunStart(
    lastObserved: DisplayEnvelopeIdentity?,
    current: DisplayEnvelopeIdentity,
): DisplayEnvelopeIdentity = lastObserved ?: current

internal fun selectRunDisplayEnvelopeSnapshot(
    existingRunSnapshot: DisplayEnvelopeIdentity?,
    lastObserved: DisplayEnvelopeIdentity?,
    current: DisplayEnvelopeIdentity,
): DisplayEnvelopeIdentity =
    existingRunSnapshot ?: displayEnvelopeSnapshotAtRunStart(lastObserved, current)

internal fun runningDisplaySafetyEnvelopeChanged(
    runSnapshot: DisplayEnvelopeIdentity?,
    current: DisplayEnvelopeIdentity,
): Boolean = runSnapshot != null && displaySafetyEnvelopeChanged(runSnapshot, current)

internal fun shouldDeferAutomationUntilWindowReady(
    parsed: AutomationIntentParseResult,
    decorAttached: Boolean,
    rootInsetsAvailable: Boolean,
): Boolean =
    parsed is AutomationIntentParseResult.Accepted &&
        parsed.command is AutomationCommand.Start &&
        (!decorAttached || !rootInsetsAvailable)

/**
 * MainActivity owns SystemUI because only the Window can acknowledge actual Insets visibility.
 * Standard immersive mode cannot permanently block a user's edge swipe, so a post-confirmation
 * reveal is reported to the controller and invalidates the measured run.
 */
private class ActivityTestWindowIsolation(
    private val activity: ComponentActivity,
    private val contaminationCallback: (Long, String, String) -> Unit,
    private val windowInsetsReadyCallback: () -> Unit,
) : TestWindowIsolationPort, AutoCloseable {
    private val decorView: View
        get() = activity.window.decorView
    private var state = TestWindowIsolationState()
    private var originalBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    private var lastReleasedToken: Long? = null
    private var closing = false
    private var listenerAttached = true
    private var rootInsetsRequestPosted = false
    private var foreignLeaseMasking = false
    private var foreignLeaseHideAttemptCount = 0
    private var foreignLeaseHideVerificationPending = false
    private var foreignLeaseMaskFailureReported = false
    private var foreignLeaseMaskGeneration = 0L
    private var foreignMaskedLeaseToken: Long? = null
    private var foreignRestoreCommandIssued = false
    private var foreignLeaseOriginalBehavior: Int? = null
    private var foreignRestoreStatusBarVisible: Boolean? = null
    private var foreignRestoreNavigationBarVisible: Boolean? = null
    private val processLeaseOwnerFailureListener: (Long, String) -> Unit =
        { ownerToken, reason ->
            contaminationCallback(
                ownerToken,
                reason,
                "SYSTEM_UI_REVEALED",
            )
        }
    private val processLeaseReleaseListener: (Long) -> Unit = { releasedToken ->
        decorView.post {
            if (
                !closing &&
                shouldShowIdleSystemBars(
                    state = state,
                    processLeaseActive =
                        ProcessTestWindowIsolationLeaseRegistry.hasActiveLease(),
                    releasedToken = releasedToken,
                    locallyReleasedToken = lastReleasedToken,
                )
            ) {
                showSystemBarsForIdleWindow()
            }
        }
    }
    private val rootInsetsRequest = Runnable {
        rootInsetsRequestPosted = false
        if (!closing) ViewCompat.requestApplyInsets(decorView)
    }

    init {
        ProcessTestWindowIsolationLeaseRegistry.addReleaseListener(processLeaseReleaseListener)
        ViewCompat.setOnApplyWindowInsetsListener(decorView) { _, insets ->
            observeInsets(insets)
            if (rootInsetsRequestPosted) {
                decorView.removeCallbacks(rootInsetsRequest)
                rootInsetsRequestPosted = false
            }
            windowInsetsReadyCallback()
            insets
        }
        if (ProcessTestWindowIsolationLeaseRegistry.hasActiveLease()) {
            hideSystemBarsForForeignLease()
        }
    }

    fun hasRootWindowInsets(): Boolean =
        ViewCompat.getRootWindowInsets(decorView) != null

    fun isExternalNavigationSafe(): Boolean =
        batterySaverSettingsNavigationReady(
            planCleanupComplete = true,
            isolationPhase = state.phase,
            processLeaseActive =
                ProcessTestWindowIsolationLeaseRegistry.hasActiveLease(),
            foreignLeaseMasking = foreignLeaseMasking,
        )

    fun requestRootWindowInsets() {
        if (closing) return
        if (decorView.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(decorView)
        } else if (!rootInsetsRequestPosted) {
            rootInsetsRequestPosted = decorView.post(rootInsetsRequest)
        }
    }

    override fun request(): RequestResult {
        if (
            state.phase != TestWindowIsolationPhase.IDLE ||
            foreignLeaseMasking ||
            activity.isInMultiWindowMode ||
            activity.isInPictureInPictureMode
        ) {
            return RequestResult.Rejected
        }
        val controller =
            runCatching { insetsController() }.getOrNull() ?: return RequestResult.Rejected
        // Restoring an assumed visibility mask can reveal bars that were intentionally hidden
        // before the test. Wait until Android has supplied an authoritative Insets snapshot.
        val originalInsets =
            ViewCompat.getRootWindowInsets(decorView) ?: return RequestResult.Rejected
        val token =
            ProcessTestWindowIsolationLeaseRegistry.acquire(
                failureListener = processLeaseOwnerFailureListener,
            )
                ?: return RequestResult.Rejected
        originalBehavior = runCatching {
            controller.systemBarsBehavior
        }.getOrDefault(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT)
        state = TestWindowIsolationState(
            token = token,
            phase = TestWindowIsolationPhase.REQUESTED,
            restoreStatusBarVisible =
                originalInsets.isVisible(WindowInsetsCompat.Type.statusBars()),
            restoreNavigationBarVisible =
                originalInsets.isVisible(WindowInsetsCompat.Type.navigationBars()),
        )
        if (hideForToken(token)) return RequestResult.Acquired(token)

        // Ownership has already crossed the process-wide boundary. Return the cleanup token even
        // if the hide and its immediate rollback both fail; the controller must retain it.
        state = state.copy(phase = TestWindowIsolationPhase.RESTORE_FAILED)
        restoreMatchingToken(token)
        return RequestResult.CleanupRequired(token)
    }

    override fun isConfirmed(token: Long): Boolean =
        state.token == token &&
            state.phase == TestWindowIsolationPhase.CONFIRMED

    override fun release(token: Long): ReleaseStatus = restoreMatchingToken(token)

    override fun releaseStatus(token: Long): ReleaseStatus = when {
        state.phase == TestWindowIsolationPhase.IDLE && lastReleasedToken == token ->
            ReleaseStatus.RESTORED
        state.token != token -> ReleaseStatus.STALE
        state.phase == TestWindowIsolationPhase.RESTORE_FAILED -> ReleaseStatus.FAILED
        else -> ReleaseStatus.PENDING
    }

    fun reconcileWithLifecycle() {
        when (state.phase) {
            TestWindowIsolationPhase.IDLE -> {
                val processLeaseActive =
                    ProcessTestWindowIsolationLeaseRegistry.hasActiveLease()
                if (
                    shouldHideIdleSystemBarsForForeignLease(
                        state = state,
                        processLeaseActive = processLeaseActive,
                    )
                ) {
                    hideSystemBarsForForeignLease()
                } else if (
                    shouldShowIdleSystemBars(
                        state = state,
                        processLeaseActive = processLeaseActive,
                    )
                ) {
                    showSystemBarsForIdleWindow()
                }
            }
            TestWindowIsolationPhase.RESTORE_FAILED ->
                state.token?.let(::restoreMatchingToken)
            TestWindowIsolationPhase.RESTORING ->
                ViewCompat.requestApplyInsets(decorView)
            else -> reapplyIfRequested()
        }
    }

    fun reapplyIfRequested() {
        if (state.token == null) {
            val processLeaseActive =
                ProcessTestWindowIsolationLeaseRegistry.hasActiveLease()
            when {
                shouldHideIdleSystemBarsForForeignLease(state, processLeaseActive) ->
                    hideSystemBarsForForeignLease()
                foreignLeaseMasking && !processLeaseActive ->
                    showSystemBarsForIdleWindow()
            }
            return
        }
        if (
            state.phase == TestWindowIsolationPhase.REQUESTED ||
            state.phase == TestWindowIsolationPhase.CONFIRMED ||
            state.phase == TestWindowIsolationPhase.CONTAMINATED
        ) {
            state.token?.let(::hideForToken)
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        val token = state.token
        if (token == null) {
            if (hasFocus) reapplyIfRequested()
            return
        }
        if (hasFocus) {
            when (focusGainIsolationAction(state.phase)) {
                FocusGainIsolationAction.HIDE -> hideForToken(token)
                FocusGainIsolationAction.RETRY_RESTORE -> restoreMatchingToken(token)
                FocusGainIsolationAction.NONE -> Unit
            }
            return
        }
        val observation = observeWindowFocusLoss(state, token)
        state = observation.state
        if (observation.contaminationDetected) {
            contaminationCallback(
                token,
                "측정 중 window focus를 잃어 SystemUI/overlay 개입 가능성이 생김",
                "WINDOW_FOCUS_LOST",
            )
        }
    }

    private fun restoreMatchingToken(token: Long): ReleaseStatus {
        if (state.token != token) {
            return releaseStatus(token)
        }
        val previous = state
        // A successful show/hide command is only a request. RESTORING remains sticky until an
        // Insets callback (or a synchronous current-Insets observation) matches the original
        // status/navigation visibility mask.
        state = previous.copy(phase = TestWindowIsolationPhase.RESTORING)
        val commandAccepted = runCatching {
            insetsController().apply {
                systemBarsBehavior = originalBehavior
                val showTypes =
                    (if (previous.restoreStatusBarVisible) {
                        WindowInsetsCompat.Type.statusBars()
                    } else {
                        0
                    }) or
                        (if (previous.restoreNavigationBarVisible) {
                            WindowInsetsCompat.Type.navigationBars()
                        } else {
                            0
                        })
                val hideTypes =
                    (if (!previous.restoreStatusBarVisible) {
                        WindowInsetsCompat.Type.statusBars()
                    } else {
                        0
                    }) or
                        (if (!previous.restoreNavigationBarVisible) {
                            WindowInsetsCompat.Type.navigationBars()
                        } else {
                            0
                        })
                if (showTypes != 0) show(showTypes)
                if (hideTypes != 0) hide(hideTypes)
            }
            ViewCompat.requestApplyInsets(decorView)
        }.isSuccess
        // show()/requestApplyInsets() may synchronously re-enter the listener and publish IDLE.
        // Never resurrect the old token after that acknowledged transition.
        state = isolationStateAfterRestoreCommandResult(
            current = state,
            token = token,
            succeeded = commandAccepted,
        )
        ViewCompat.getRootWindowInsets(decorView)?.let(::observeInsets)
        return releaseStatus(token)
    }

    override fun close() {
        closing = true
        decorView.removeCallbacks(rootInsetsRequest)
        rootInsetsRequestPosted = false
        state.token?.let { token ->
            ProcessTestWindowIsolationLeaseRegistry.removeOwnerFailureListener(
                token,
                processLeaseOwnerFailureListener,
            )
        }
        ProcessTestWindowIsolationLeaseRegistry.removeReleaseListener(
            processLeaseReleaseListener,
        )
        // The controller's NonCancellable finalizer exclusively starts restoration after the
        // physical-producer/terminal-sample barrier. Keep the listener attached until that
        // restoration is acknowledged; otherwise close() could either reveal SystemUI early or
        // discard the only proof that it became visible.
        if (state.phase == TestWindowIsolationPhase.IDLE) detachInsetsListener()
    }

    private fun observeInsets(insets: WindowInsetsCompat) {
        val token = state.token
        if (token == null) {
            observeForeignLeaseInsets(insets)
            return
        }
        val previous = state
        val observation = observeSystemBars(
            state = previous,
            token = token,
            statusBarVisible =
                insets.isVisible(WindowInsetsCompat.Type.statusBars()),
            navigationBarVisible =
                insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
        )
        if (observation.restorationConfirmed) {
            if (ProcessTestWindowIsolationLeaseRegistry.confirmReleased(token)) {
                state = observation.state
                lastReleasedToken = token
                if (closing) detachInsetsListener()
            } else {
                // Never publish local success if the process-wide owner disagrees.
                state = previous.copy(phase = TestWindowIsolationPhase.RESTORE_FAILED)
            }
            return
        }
        state = observation.state
        if (observation.contaminationDetected) {
            hideForToken(token)
            contaminationCallback(
                token,
                "측정 중 status/navigation bar가 다시 표시됨",
                "SYSTEM_UI_REVEALED",
            )
        }
    }

    private fun detachInsetsListener() {
        if (!listenerAttached) return
        listenerAttached = false
        ViewCompat.setOnApplyWindowInsetsListener(decorView, null)
    }

    private fun showSystemBarsForIdleWindow() {
        val restoringForeignLease =
            foreignLeaseMasking &&
                !ProcessTestWindowIsolationLeaseRegistry.hasActiveLease()
        if (
            restoringForeignLease &&
            foreignRestoreStatusBarVisible == null &&
            foreignRestoreNavigationBarVisible == null &&
            foreignLeaseHideAttemptCount == 0
        ) {
            // This Window never received Insets and therefore never issued a hide command. There
            // is no local mutation to undo; do not manufacture a visible-bars default.
            clearForeignLeaseMask()
            return
        }
        if (restoringForeignLease) {
            foreignLeaseMaskGeneration = nextIsolationToken(foreignLeaseMaskGeneration)
            foreignLeaseHideVerificationPending = false
            foreignRestoreCommandIssued = true
        }
        val commandAccepted = runCatching {
            insetsController().apply {
                foreignLeaseOriginalBehavior?.let { systemBarsBehavior = it }
                if (restoringForeignLease) {
                    val showTypes =
                        (if (foreignRestoreStatusBarVisible == true) {
                            WindowInsetsCompat.Type.statusBars()
                        } else {
                            0
                        }) or
                            (if (foreignRestoreNavigationBarVisible == true) {
                                WindowInsetsCompat.Type.navigationBars()
                            } else {
                                0
                            })
                    val hideTypes =
                        (if (foreignRestoreStatusBarVisible == false) {
                            WindowInsetsCompat.Type.statusBars()
                        } else {
                            0
                        }) or
                            (if (foreignRestoreNavigationBarVisible == false) {
                                WindowInsetsCompat.Type.navigationBars()
                            } else {
                                0
                            })
                    if (showTypes != 0) show(showTypes)
                    if (hideTypes != 0) hide(hideTypes)
                } else {
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
            ViewCompat.requestApplyInsets(decorView)
        }.isSuccess
        // A synchronous Insets callback is stronger evidence than a command exception. Only
        // withdraw the pending acknowledgment when the foreign mask still belongs to this path.
        if (!commandAccepted && foreignLeaseMasking && restoringForeignLease) {
            foreignRestoreCommandIssued = false
        }
    }

    private fun hideSystemBarsForForeignLease() {
        val activeLeaseToken =
            ProcessTestWindowIsolationLeaseRegistry.activeLeaseToken() ?: return
        if (
            !shouldHideIdleSystemBarsForForeignLease(
                state = state,
                processLeaseActive = true,
            )
        ) {
            return
        }
        if (!foreignLeaseMasking || foreignMaskedLeaseToken != activeLeaseToken) {
            foreignLeaseHideAttemptCount = 0
            foreignLeaseHideVerificationPending = false
            foreignLeaseMaskFailureReported = false
            foreignLeaseMaskGeneration = nextIsolationToken(foreignLeaseMaskGeneration)
            foreignMaskedLeaseToken = activeLeaseToken
        }
        foreignLeaseMasking = true
        foreignRestoreCommandIssued = false
        val currentInsets = ViewCompat.getRootWindowInsets(decorView)
        if (currentInsets == null) {
            // Do not hide until the original visibility mask is known. The Insets listener will
            // capture that first snapshot and continue the bounded hide handshake.
            ViewCompat.requestApplyInsets(decorView)
            return
        }
        captureForeignRestoreMask(currentInsets)
        val allHidden = currentForeignLeaseSystemBarsHidden()
        if (allHidden) {
            foreignLeaseHideAttemptCount = 0
            foreignLeaseMaskFailureReported = false
            return
        }
        if (
            shouldAttemptForeignLeaseHide(
                processLeaseActive = true,
                allSystemBarsHidden = false,
                attemptCount = foreignLeaseHideAttemptCount,
                maxAttempts = MAX_FOREIGN_LEASE_HIDE_ATTEMPTS,
                verificationPending = foreignLeaseHideVerificationPending,
                failureReported = foreignLeaseMaskFailureReported,
            )
        ) {
            issueForeignLeaseHideAttempt(
                generation = foreignLeaseMaskGeneration,
                leaseToken = activeLeaseToken,
            )
        }
    }

    private fun observeForeignLeaseInsets(insets: WindowInsetsCompat) {
        if (!foreignLeaseMasking) return
        captureForeignRestoreMask(insets)
        val processLeaseActive =
            ProcessTestWindowIsolationLeaseRegistry.hasActiveLease()
        if (processLeaseActive) {
            foreignRestoreCommandIssued = false
            val activeLeaseToken =
                ProcessTestWindowIsolationLeaseRegistry.activeLeaseToken()
            if (activeLeaseToken == null || activeLeaseToken != foreignMaskedLeaseToken) {
                hideSystemBarsForForeignLease()
                return
            }
            val allHidden =
                !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            if (allHidden) {
                foreignLeaseHideAttemptCount = 0
                foreignLeaseMaskFailureReported = false
                return
            }
            if (
                shouldAttemptForeignLeaseHide(
                    processLeaseActive = true,
                    allSystemBarsHidden = false,
                    attemptCount = foreignLeaseHideAttemptCount,
                    maxAttempts = MAX_FOREIGN_LEASE_HIDE_ATTEMPTS,
                    verificationPending = foreignLeaseHideVerificationPending,
                    failureReported = foreignLeaseMaskFailureReported,
                )
            ) {
                issueForeignLeaseHideAttempt(
                    generation = foreignLeaseMaskGeneration,
                    leaseToken = activeLeaseToken,
                )
            } else if (
                foreignLeaseHideAttemptCount >= MAX_FOREIGN_LEASE_HIDE_ATTEMPTS &&
                !foreignLeaseHideVerificationPending
            ) {
                reportForeignLeaseMaskFailure()
            }
            return
        }
        if (
            !foreignLeaseRestorationAcknowledged(
                processLeaseActive = false,
                restoreCommandIssued = foreignRestoreCommandIssued,
                statusBarVisible =
                    insets.isVisible(WindowInsetsCompat.Type.statusBars()),
                navigationBarVisible =
                    insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
                restoreStatusBarVisible = foreignRestoreStatusBarVisible,
                restoreNavigationBarVisible = foreignRestoreNavigationBarVisible,
            )
        ) {
            return
        }
        val behaviorRestored = runCatching {
            foreignLeaseOriginalBehavior?.let {
                insetsController().systemBarsBehavior = it
            }
        }.isSuccess
        if (!behaviorRestored) return
        clearForeignLeaseMask()
    }

    private fun captureForeignRestoreMask(insets: WindowInsetsCompat) {
        if (foreignRestoreStatusBarVisible == null) {
            foreignRestoreStatusBarVisible =
                insets.isVisible(WindowInsetsCompat.Type.statusBars())
        }
        if (foreignRestoreNavigationBarVisible == null) {
            foreignRestoreNavigationBarVisible =
                insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun clearForeignLeaseMask() {
        foreignLeaseMasking = false
        foreignRestoreCommandIssued = false
        foreignLeaseHideAttemptCount = 0
        foreignLeaseHideVerificationPending = false
        foreignLeaseMaskFailureReported = false
        foreignLeaseMaskGeneration = nextIsolationToken(foreignLeaseMaskGeneration)
        foreignMaskedLeaseToken = null
        foreignLeaseOriginalBehavior = null
        foreignRestoreStatusBarVisible = null
        foreignRestoreNavigationBarVisible = null
    }

    private fun issueForeignLeaseHideAttempt(
        generation: Long,
        leaseToken: Long,
    ) {
        if (
            closing ||
            generation != foreignLeaseMaskGeneration ||
            foreignMaskedLeaseToken != leaseToken ||
            !foreignLeaseMasking ||
            !ProcessTestWindowIsolationLeaseRegistry.owns(leaseToken) ||
            foreignLeaseHideVerificationPending ||
            foreignLeaseMaskFailureReported ||
            foreignLeaseHideAttemptCount >= MAX_FOREIGN_LEASE_HIDE_ATTEMPTS
        ) {
            return
        }
        foreignLeaseHideAttemptCount += 1
        foreignLeaseHideVerificationPending = true
        val verificationAccepted = decorView.postDelayed(
            {
                if (generation != foreignLeaseMaskGeneration) return@postDelayed
                foreignLeaseHideVerificationPending = false
                verifyForeignLeaseHide(generation, leaseToken)
            },
            FOREIGN_LEASE_HIDE_VERIFY_DELAY_MS,
        )
        runCatching {
            insetsController().apply {
                if (foreignLeaseOriginalBehavior == null) {
                    foreignLeaseOriginalBehavior = runCatching {
                        systemBarsBehavior
                    }.getOrDefault(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT)
                }
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            ViewCompat.requestApplyInsets(decorView)
        }
        if (!verificationAccepted && generation == foreignLeaseMaskGeneration) {
            foreignLeaseHideVerificationPending = false
            reportForeignLeaseMaskFailure()
        }
    }

    private fun verifyForeignLeaseHide(
        generation: Long,
        leaseToken: Long,
    ) {
        if (
            closing ||
            generation != foreignLeaseMaskGeneration ||
            !foreignLeaseMasking
        ) {
            return
        }
        if (
            foreignMaskedLeaseToken != leaseToken ||
            !ProcessTestWindowIsolationLeaseRegistry.owns(leaseToken)
        ) {
            if (ProcessTestWindowIsolationLeaseRegistry.hasActiveLease()) {
                hideSystemBarsForForeignLease()
            }
            return
        }
        val allHidden = currentForeignLeaseSystemBarsHidden()
        if (allHidden) {
            foreignLeaseHideAttemptCount = 0
            foreignLeaseMaskFailureReported = false
            return
        }
        if (
            shouldAttemptForeignLeaseHide(
                processLeaseActive = true,
                allSystemBarsHidden = false,
                attemptCount = foreignLeaseHideAttemptCount,
                maxAttempts = MAX_FOREIGN_LEASE_HIDE_ATTEMPTS,
                verificationPending = false,
                failureReported = foreignLeaseMaskFailureReported,
            )
        ) {
            issueForeignLeaseHideAttempt(generation, leaseToken)
        } else {
            reportForeignLeaseMaskFailure()
        }
    }

    private fun currentForeignLeaseSystemBarsHidden(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(decorView) ?: return false
        return !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
            !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    }

    private fun reportForeignLeaseMaskFailure() {
        if (foreignLeaseMaskFailureReported) return
        val leaseToken = foreignMaskedLeaseToken ?: return
        foreignLeaseMaskFailureReported = true
        ProcessTestWindowIsolationLeaseRegistry.reportOwnerFailure(
            leaseToken,
            "재생성된 Window에서 status/navigation bar hide를 " +
                "${foreignLeaseHideAttemptCount.coerceAtLeast(1)}회 시도했지만 확인하지 못함",
        )
    }

    private fun hideForToken(token: Long): Boolean {
        if (state.token != token) return false
        val requested = runCatching {
            insetsController().apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            ViewCompat.requestApplyInsets(decorView)
        }.isSuccess
        if (!requested) return false
        decorView.post {
            if (
                state.token == token &&
                state.phase != TestWindowIsolationPhase.RESTORING &&
                state.phase != TestWindowIsolationPhase.RESTORE_FAILED
            ) {
                runCatching {
                    insetsController().hide(WindowInsetsCompat.Type.systemBars())
                    ViewCompat.requestApplyInsets(decorView)
                }.onFailure {
                    val confirmed =
                        state.phase == TestWindowIsolationPhase.CONFIRMED
                    if (confirmed) {
                        state = state.copy(
                            phase = TestWindowIsolationPhase.CONTAMINATED,
                        )
                        contaminationCallback(
                            token,
                            "측정 중 SystemUI hide 재적용에 실패함",
                            "SYSTEM_UI_REVEALED",
                        )
                    }
                }
            }
        }
        return true
    }

    private fun insetsController(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(activity.window, decorView)

    private companion object {
        const val MAX_FOREIGN_LEASE_HIDE_ATTEMPTS = 4
        const val FOREIGN_LEASE_HIDE_VERIFY_DELAY_MS = 100L
    }
}

internal fun nextIsolationToken(previous: Long): Long =
    if (previous == Long.MAX_VALUE) 1L else (previous + 1L).coerceAtLeast(1L)
