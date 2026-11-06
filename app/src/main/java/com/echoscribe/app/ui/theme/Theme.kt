package com.echoscribe.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Extended palette: Material3's ColorScheme cannot express "hairline" or "paper tier"
 * semantics, and abusing `surfaceVariant` for that makes the code unreadable.
 */
data class EchoColors(
    val paper: Color,
    val raised: Color,
    val sunken: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val record: Color,
    val recordSoft: Color,
    val recordDim: Color,
    val accent: Color,
    val saved: Color,
    val processing: Color,
    val isDark: Boolean,
)

private val LightEcho = EchoColors(
    paper = Paper1,
    raised = Paper0,
    sunken = Paper3,
    hairline = HairlineLight,
    textPrimary = Ink900,
    textSecondary = Ink600,
    textTertiary = Ink400,
    record = RecordRed,
    recordSoft = RecordRedSoft,
    recordDim = RecordRedDim,
    accent = Slate700,
    saved = Sage500,
    processing = Amber500,
    isDark = false,
)

private val DarkEcho = EchoColors(
    paper = Night1,
    raised = Night2,
    sunken = Night3,
    hairline = HairlineDark,
    textPrimary = Chalk100,
    textSecondary = Chalk400,
    textTertiary = Chalk600,
    record = RecordRedSoft,
    recordSoft = RecordRed,
    recordDim = RecordRedDim,
    accent = Slate300,
    saved = Sage300,
    processing = Amber500,
    isDark = true,
)

val LocalEchoColors = staticCompositionLocalOf { LightEcho }

/** Elevation semantics that read as physical layers rather than arbitrary numbers. */
data class EchoElevation(
    val card: Dp = 1.dp,
    val raised: Dp = 3.dp,
    val floating: Dp = 8.dp,
    val sheet: Dp = 16.dp,
)

val LocalEchoElevation = staticCompositionLocalOf { EchoElevation() }

private val LightScheme = lightColorScheme(
    primary = Slate700,
    onPrimary = Paper0,
    primaryContainer = Paper3,
    onPrimaryContainer = Ink900,
    secondary = Slate500,
    onSecondary = Paper0,
    error = RecordRed,
    onError = Paper0,
    background = Paper1,
    onBackground = Ink900,
    surface = Paper1,
    onSurface = Ink900,
    surfaceVariant = Paper2,
    onSurfaceVariant = Ink600,
    outline = HairlineLight,
    outlineVariant = HairlineLight,
    surfaceContainer = Paper2,
    surfaceContainerHigh = Paper2,
    surfaceContainerHighest = Paper3,
)

private val DarkScheme = darkColorScheme(
    primary = Chalk100,
    onPrimary = Night0,
    primaryContainer = Night3,
    onPrimaryContainer = Chalk100,
    secondary = Chalk400,
    onSecondary = Night0,
    error = RecordRedSoft,
    onError = Night0,
    background = Night1,
    onBackground = Chalk100,
    surface = Night1,
    onSurface = Chalk100,
    surfaceVariant = Night2,
    onSurfaceVariant = Chalk400,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    surfaceContainer = Night2,
    surfaceContainerHigh = Night2,
    surfaceContainerHighest = Night3,
)

/**
 * @param darkTheme null = follow system. The user's explicit choice (Settings) wins.
 */
@Composable
fun EchoScribeTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val echo = if (dark) DarkEcho else LightEcho
    val scheme = if (dark) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalEchoColors provides echo,
        LocalEchoElevation provides EchoElevation(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = EchoTypography,
            content = content,
        )
    }
}

/** Shorthand accessor used throughout the UI: `EchoTheme.colors.record`. */
object EchoTheme {
    val colors: EchoColors
        @Composable get() = LocalEchoColors.current

    val elevation: EchoElevation
        @Composable get() = LocalEchoElevation.current
}
