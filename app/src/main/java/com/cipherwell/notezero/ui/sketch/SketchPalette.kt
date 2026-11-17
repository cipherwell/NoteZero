package com.cipherwell.notezero.ui.sketch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchInkKind
import com.cipherwell.notezero.model.SketchWidth
import com.cipherwell.notezero.ui.theme.NoteZeroPalette

/**
 * Colours for the standalone drawing studio.
 *
 * The page is a sheet of **white paper in both app themes** (like the note-owned drawing
 * canvas), because the flattened preview PNG is rendered on white and must look the same on
 * the shelf, in a backup and in any future export. Ink colours are therefore derived from the
 * light-ground design tokens in `ui/theme`; nothing here invents a colour value: every entry
 * is a [NoteZeroPalette] token or an HSV derivation chosen to stay legible on white (the two
 * derived hues fill the semantic gap between the token red/green/blue values).
 *
 * [SketchRenderer] is the only Android-aware consumer: it reads the ARGB accessors and never
 * touches Compose at draw time. The studio preview uses the Compose [Color] accessors.
 */
object SketchPalette {

    /** White paper — the same colour the encoded PNG starts from. */
    val paper: Color = Color(NoteZeroPalette.SurfaceLight)

    /** White paper as an ARGB int, for the in-memory PNG renderer. */
    val paperArgb: Int = NoteZeroPalette.SurfaceLight.toInt()

    /** Paper template lines (ruled/grid): the low-contrast separator token on white. */
    val templateLine: Color = Color(NoteZeroPalette.SeparatorLight)
    val templateLineArgb: Int = NoteZeroPalette.SeparatorLight.toInt()

    /** Paper template dots; the secondary text token is darker than the rule so dots read. */
    val templateDot: Color = Color(NoteZeroPalette.TextSecondaryLight)
    val templateDotArgb: Int = NoteZeroPalette.TextSecondaryLight.toInt()

    /** Alpha applied to highlighter ink (both preview and PNG). */
    const val HIGHLIGHTER_ALPHA: Float = 0.34f

    /** Highlighters are visibly wider than the pen at the same width setting. */
    const val HIGHLIGHTER_WIDTH_MULTIPLIER: Float = 2.6f

    /** Ink colour for one palette entry. */
    fun ink(ink: SketchInk): Color = when (ink) {
        SketchInk.BLACK -> Color(NoteZeroPalette.TextPrimaryLight)
        SketchInk.GREY -> Color(NoteZeroPalette.TextSecondaryLight)
        SketchInk.RED -> Color(NoteZeroPalette.DangerLight)
        SketchInk.ORANGE -> Color.hsv(28f, 0.92f, 0.96f)
        SketchInk.GREEN -> Color(NoteZeroPalette.SuccessTextLight)
        SketchInk.BLUE -> Color(NoteZeroPalette.AccentFillLight)
        SketchInk.PURPLE -> Color.hsv(274f, 0.60f, 0.56f)
    }

    /** Ink colour as an ARGB int for the in-memory PNG renderer. */
    fun inkArgb(ink: SketchInk): Int = ink(ink).toArgb()

    /** Ink colour with the transparency of a highlighter, as an ARGB int. */
    fun inkArgb(ink: SketchInk, kind: SketchInkKind): Int = when (kind) {
        SketchInkKind.PEN -> inkArgb(ink)
        SketchInkKind.HIGHLIGHTER -> ink(ink).copy(alpha = HIGHLIGHTER_ALPHA).toArgb()
    }

    /** Line width in pixels for a page whose short edge is [canvasMinEdgePx]. */
    fun strokeWidthPx(width: SketchWidth, canvasMinEdgePx: Float): Float =
        (width.fraction * canvasMinEdgePx).coerceAtLeast(1f)

    /** Line width in pixels for a freehand stroke, widened for the highlighter. */
    fun strokeWidthPx(
        width: SketchWidth,
        kind: SketchInkKind,
        canvasMinEdgePx: Float,
    ): Float {
        val base = strokeWidthPx(width, canvasMinEdgePx)
        return if (kind == SketchInkKind.HIGHLIGHTER) {
            base * HIGHLIGHTER_WIDTH_MULTIPLIER
        } else {
            base
        }
    }
}
