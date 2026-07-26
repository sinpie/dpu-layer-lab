package com.example.dpulayerlab.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF65E6C4),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF124B41),
    onPrimaryContainer = Color(0xFFA9F3DF),
    secondary = Color(0xFF94B9FF),
    onSecondary = Color(0xFF0B2D58),
    secondaryContainer = Color(0xFF193757),
    tertiary = Color(0xFFFFB4A7),
    background = Color(0xFF07110F),
    onBackground = Color(0xFFE5F1ED),
    surface = Color(0xFF0D1916),
    surfaceVariant = Color(0xFF152522),
    onSurfaceVariant = Color(0xFFB8CBC5),
    outline = Color(0xFF526760),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF84F8D5),
    secondary = Color(0xFF365F8E),
    background = Color(0xFFF4FBF8),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun DpuLabTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            applyLegacyTransparentSystemBarColors(view.context as Activity)
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(
            displaySmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
            ),
            headlineMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            ),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
        ),
        content = content,
    )
}

/**
 * Android 15+ enforces edge-to-edge and ignores these legacy color setters. Keep them only for
 * older releases where a transparent bar color is still part of the Window contract.
 */
@Suppress("DEPRECATION")
private fun applyLegacyTransparentSystemBarColors(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    activity.window.statusBarColor = Color.Transparent.toArgb()
    activity.window.navigationBarColor = Color.Transparent.toArgb()
}
