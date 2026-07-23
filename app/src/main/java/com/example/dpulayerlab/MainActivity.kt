package com.example.dpulayerlab

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.example.dpulayerlab.engine.LabController
import com.example.dpulayerlab.ui.DpuLayerLabApp
import com.example.dpulayerlab.ui.theme.DpuLabTheme
import com.example.dpulayerlab.util.currentDisplayCompat
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var controller: LabController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller = LabController(this, ::requestDisplayRefresh)
        setContent {
            DpuLabTheme {
                DpuLayerLabApp(controller)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        if (controller.isRunning) controller.stopScenario("앱이 백그라운드로 전환되어 안전 중단")
        controller.pause()
        super.onStop()
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    private fun requestDisplayRefresh(targetHz: Float) {
        if (!targetHz.isFinite() || targetHz <= 0f) {
            val attributes = window.attributes
            attributes.preferredDisplayModeId = 0
            attributes.preferredRefreshRate = 0f
            window.attributes = attributes
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
        val attributes = window.attributes
        attributes.preferredDisplayModeId = chosen.modeId
        attributes.preferredRefreshRate = targetHz
        window.attributes = attributes
    }
}
