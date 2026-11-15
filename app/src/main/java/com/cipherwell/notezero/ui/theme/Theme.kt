package com.cipherwell.notezero.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.cipherwell.notezero.model.ThemeMode

/**
 * Extended (non-Material3) semantic colours. Provided by [NoteZeroTheme]; read via
 * `LocalNoteZeroColors.current`. Never build colours outside this package.
 */
val LocalNoteZeroColors = staticCompositionLocalOf { nzLightColors() }

private fun darkScheme() = darkColorScheme(
    // Filled controls: bright accent with black label = 5.8:1 (AA at normal text size).
    primary = Color(NoteZeroPalette.AccentFillDark),
    onPrimary = Color(NoteZeroPalette.OnAccentFillDark),
    primaryContainer = Color(0xFF00306B),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(NoteZeroPalette.AccentTextDark),
    onSecondary = Color(NoteZeroPalette.CanvasDark),
    background = Color(NoteZeroPalette.CanvasDark),
    onBackground = Color(NoteZeroPalette.TextPrimaryDark),
    surface = Color(NoteZeroPalette.SurfaceDark),
    onSurface = Color(NoteZeroPalette.TextPrimaryDark),
    surfaceVariant = Color(NoteZeroPalette.SurfaceRaisedDark),
    onSurfaceVariant = Color(NoteZeroPalette.TextSecondaryDark),
    surfaceContainerLowest = Color(NoteZeroPalette.CanvasDark),
    surfaceContainerLow = Color(NoteZeroPalette.SurfaceDark),
    surfaceContainer = Color(NoteZeroPalette.SurfaceDark),
    surfaceContainerHigh = Color(NoteZeroPalette.SurfaceRaisedDark),
    surfaceContainerHighest = Color(NoteZeroPalette.SurfaceRaisedDark),
    outline = Color(NoteZeroPalette.FieldBorderDark),
    outlineVariant = Color(NoteZeroPalette.SeparatorDark),
    error = Color(NoteZeroPalette.DangerTextDark),
    onError = Color(NoteZeroPalette.CanvasDark),
    errorContainer = Color(NoteZeroPalette.DangerContainerDark),
    onErrorContainer = Color(NoteZeroPalette.OnDangerContainerDark),
)

private fun lightScheme() = lightColorScheme(
    primary = Color(NoteZeroPalette.AccentFillLight),
    onPrimary = Color(NoteZeroPalette.OnAccentFillLight),
    primaryContainer = Color(NoteZeroPalette.PillSelectedLight),
    onPrimaryContainer = Color(NoteZeroPalette.AccentTextLight),
    secondary = Color(NoteZeroPalette.AccentTextLight),
    onSecondary = Color(NoteZeroPalette.CanvasLight),
    background = Color(NoteZeroPalette.CanvasLight),
    onBackground = Color(NoteZeroPalette.TextPrimaryLight),
    surface = Color(NoteZeroPalette.SurfaceLight),
    onSurface = Color(NoteZeroPalette.TextPrimaryLight),
    surfaceVariant = Color(NoteZeroPalette.SurfaceRaisedLight),
    onSurfaceVariant = Color(NoteZeroPalette.TextSecondaryLight),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(NoteZeroPalette.SurfaceLight),
    surfaceContainer = Color(NoteZeroPalette.SurfaceLight),
    surfaceContainerHigh = Color(NoteZeroPalette.SurfaceRaisedLight),
    surfaceContainerHighest = Color(NoteZeroPalette.SurfaceRaisedLight),
    outline = Color(NoteZeroPalette.FieldBorderLight),
    outlineVariant = Color(NoteZeroPalette.SeparatorLight),
    error = Color(NoteZeroPalette.DangerTextLight),
    onError = Color(NoteZeroPalette.CanvasLight),
    errorContainer = Color(NoteZeroPalette.DangerContainerLight),
    onErrorContainer = Color(NoteZeroPalette.OnDangerContainerLight),
)

/** Resolves the explicit user preference to a concrete dark/light decision. */
fun resolveDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * NoteZero theme. Light / dark / system follows [themeMode] (persisted in vault
 * preferences by the data layer); when only [darkTheme] is supplied the seeded
 * behaviour (follow the platform) is preserved.
 */
@Composable
fun NoteZeroTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkTheme(themeMode, darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Edge-to-edge: the status/navigation bar icons must contrast with the app surface.
        // Without this the light theme renders white icons on the light surface (invisible
        // status bar), and the dark theme does the opposite.
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalNoteZeroColors provides if (dark) nzDarkColors() else nzLightColors()) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme() else lightScheme(),
            typography = noteZeroTypography(),
            shapes = noteZeroShapes(),
            content = content,
        )
    }
}

/** Finds the host Activity through the Compose context chain (context may be a wrapper). */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}
