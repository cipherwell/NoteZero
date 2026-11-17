package com.cipherwell.notezero.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroPalette

/**
 * Concrete colours for one reader theme.
 *
 * All values are derived from the design-token palette in `ui/theme` (the only place a
 * colour literal may live): the sepia ground is a small warm tint over the light canvas,
 * the night ground is the true-black canvas. Keeping the derivation here means the reader
 * never introduces its own hex values outside the token system.
 */
@Immutable
data class ReaderPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val codeBackground: Color,
    val divider: Color,
)

/** Fraction of the warm token mixed into the light canvas to obtain the sepia paper. */
private const val SEPIA_TINT = 0.10f

/** Light ground for the sepia theme, tinted from token colours only. */
private val SepiaGround: Color =
    lerp(Color(NoteZeroPalette.CanvasLight), Color(NoteZeroPalette.WarningTextLight), SEPIA_TINT)

/** Slightly raised sepia surface for chrome and code blocks. */
private val SepiaSurface: Color =
    lerp(Color(NoteZeroPalette.SurfaceRaisedLight), Color(NoteZeroPalette.WarningTextLight), SEPIA_TINT * 1.6f)

/**
 * Resolves the palette for [theme]. Paper follows the app Material theme (so it honours
 * the user's light/dark choice), sepia and night are fixed reading grounds.
 */
@Composable
fun rememberReaderPalette(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.PAPER -> ReaderPalette(
        background = MaterialTheme.colorScheme.background,
        surface = MaterialTheme.colorScheme.surfaceContainer,
        text = MaterialTheme.colorScheme.onSurface,
        secondaryText = MaterialTheme.colorScheme.onSurfaceVariant,
        accent = LocalNoteZeroColors.current.accentText,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        divider = MaterialTheme.colorScheme.outlineVariant,
    )

    ReaderTheme.SEPIA -> ReaderPalette(
        background = SepiaGround,
        surface = SepiaSurface,
        text = Color(NoteZeroPalette.TextPrimaryLight),
        secondaryText = Color(NoteZeroPalette.TextSecondaryLight),
        accent = Color(NoteZeroPalette.AccentTextLight),
        codeBackground = SepiaSurface,
        divider = Color(NoteZeroPalette.SeparatorLight),
    )

    ReaderTheme.NIGHT -> ReaderPalette(
        background = Color(NoteZeroPalette.CanvasDark),
        surface = Color(NoteZeroPalette.SurfaceDark),
        text = Color(NoteZeroPalette.TextPrimaryDark),
        secondaryText = Color(NoteZeroPalette.TextSecondaryDark),
        accent = Color(NoteZeroPalette.AccentTextDark),
        codeBackground = Color(NoteZeroPalette.SurfaceRaisedDark),
        divider = Color(NoteZeroPalette.SeparatorDark),
    )
}
