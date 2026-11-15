package com.cipherwell.notezero.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private fun darkScheme() = darkColorScheme(
    primary = Color(NoteZeroPalette.Accent),
    onPrimary = Color.White,
    secondary = Color(NoteZeroPalette.Accent),
    onSecondary = Color.White,
    background = Color(NoteZeroPalette.CanvasDark),
    onBackground = Color(NoteZeroPalette.TextPrimaryDark),
    surface = Color(NoteZeroPalette.SurfaceDark),
    onSurface = Color(NoteZeroPalette.TextPrimaryDark),
    surfaceVariant = Color(NoteZeroPalette.SurfaceRaisedDark),
    onSurfaceVariant = Color(NoteZeroPalette.TextSecondaryDark),
    surfaceContainer = Color(NoteZeroPalette.SurfaceDark),
    surfaceContainerHigh = Color(NoteZeroPalette.SurfaceRaisedDark),
    outline = Color(NoteZeroPalette.SeparatorDark),
    outlineVariant = Color(NoteZeroPalette.SeparatorDark),
    error = Color(NoteZeroPalette.DangerDark),
)

private fun lightScheme() = lightColorScheme(
    primary = Color(NoteZeroPalette.Accent),
    onPrimary = Color.White,
    secondary = Color(NoteZeroPalette.Accent),
    onSecondary = Color.White,
    background = Color(NoteZeroPalette.CanvasLight),
    onBackground = Color(NoteZeroPalette.TextPrimaryLight),
    surface = Color(NoteZeroPalette.SurfaceLight),
    onSurface = Color(NoteZeroPalette.TextPrimaryLight),
    surfaceVariant = Color(NoteZeroPalette.SurfaceRaisedLight),
    onSurfaceVariant = Color(NoteZeroPalette.TextSecondaryLight),
    surfaceContainer = Color(NoteZeroPalette.SurfaceLight),
    surfaceContainerHigh = Color(NoteZeroPalette.SurfaceRaisedLight),
    outline = Color(NoteZeroPalette.SeparatorLight),
    outlineVariant = Color(NoteZeroPalette.SeparatorLight),
    error = Color(NoteZeroPalette.DangerLight),
)

/**
 * NoteZero theme. Light / dark / system follows the platform unless the user
 * picked an explicit mode (persisted in vault preferences).
 */
@Composable
fun NoteZeroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme() else lightScheme(),
        typography = noteZeroTypography(),
        shapes = noteZeroShapes(),
        content = content,
    )
}
