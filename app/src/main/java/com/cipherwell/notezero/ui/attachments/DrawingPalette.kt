package com.cipherwell.notezero.ui.attachments

import androidx.compose.ui.graphics.Color
import com.cipherwell.notezero.ui.theme.NoteZeroPalette

/**
 * Ink and paper colours for the drawing canvas.
 *
 * The canvas is a sheet of **white paper in both app themes**, because the saved PNG has a
 * white background and must look the same in the strip, the viewer and any export. Ink
 * colours are therefore derived from the light-ground design tokens in `ui/theme` — a colour
 * that only contrasted on a dark ground would vanish on the saved image.
 *
 * No hex literals live here: every value is a `NoteZeroPalette` token or an HSV derivation
 * chosen to stay legible on white (the two derived hues fill the gap between the semantic
 * red/green/blue tokens).
 */
object DrawingPalette {

    /** White paper — the same colour the encoded PNG starts from. */
    val paper: Color = Color(NoteZeroPalette.SurfaceLight)

    /** Ink colour for one palette entry. */
    fun ink(ink: DrawingInk): Color = when (ink) {
        DrawingInk.BLACK -> Color(NoteZeroPalette.TextPrimaryLight)
        DrawingInk.GREY -> Color(NoteZeroPalette.TextSecondaryLight)
        DrawingInk.RED -> Color(NoteZeroPalette.DangerLight)
        DrawingInk.ORANGE -> Color.hsv(28f, 0.92f, 0.96f)
        DrawingInk.GREEN -> Color(NoteZeroPalette.SuccessTextLight)
        DrawingInk.BLUE -> Color(NoteZeroPalette.AccentFillLight)
        DrawingInk.PURPLE -> Color.hsv(274f, 0.60f, 0.56f)
    }

    /**
     * Pen width in pixels for a page whose short edge is [canvasMinEdgePx]. Shared by the
     * Compose preview and the PNG renderer so both produce the same line weight.
     */
    fun strokeWidthPx(width: DrawingWidth, canvasMinEdgePx: Float): Float =
        (width.fraction * canvasMinEdgePx).coerceAtLeast(1f)
}
