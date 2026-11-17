package com.cipherwell.notezero.ui.pdf

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.ui.theme.NoteZeroPalette
import kotlin.math.roundToInt

/**
 * Colours, alphas and stroke widths of the PDF annotation layer.
 *
 * The single source of truth shared by the on-screen overlay and the flattened exporter, so a
 * stroke looks the same before and after export. The page under the annotation is always white
 * paper (the renderer paints `Color.WHITE` before drawing the PDF page, and the flattened export
 * draws its own white page), therefore every colour is derived from the **light-ground** design
 * tokens exactly like [com.cipherwell.notezero.ui.attachments.DrawingPalette]: a colour that only
 * contrasted on a dark ground would vanish on the exported paper.
 *
 * No hex literals live here. Six colours and three widths mirror the model bounds
 * ([PdfAnnotations.PALETTE_SIZE] / [PdfAnnotations.WIDTH_LEVELS]); the two hues that have no
 * semantic token (orange, purple) use the same HSV derivation as the drawing palette.
 */
object PdfAnnotationPalette {

    /** Number of selectable colours; must equal [PdfAnnotations.PALETTE_SIZE]. */
    const val PALETTE_SIZE: Int = PdfAnnotations.PALETTE_SIZE

    /** Number of selectable stroke widths; must equal [PdfAnnotations.WIDTH_LEVELS]. */
    const val WIDTH_LEVELS: Int = PdfAnnotations.WIDTH_LEVELS

    /** Alpha of a freehand ink stroke. */
    const val INK_ALPHA: Float = 1f

    /** Alpha of a highlighter stroke: translucent so the text underneath stays readable. */
    const val HIGHLIGHTER_ALPHA: Float = 0.42f

    /** Highlighter strokes are drawn a little wider than ink at the same width index. */
    private const val HIGHLIGHTER_WIDTH_SCALE: Float = 1.8f

    /** Alpha of a rectangle-highlight fill. */
    const val HIGHLIGHT_FILL_ALPHA: Float = 0.26f

    /** Alpha of the rectangle-highlight border. */
    const val HIGHLIGHT_BORDER_ALPHA: Float = 0.85f

    /** Marker radius as a fraction of the page's short edge (keeps it page-relative). */
    private const val MARKER_RADIUS_FRACTION: Float = 0.016f

    /** Tap/drag tolerance for hitting a sticky-note marker, as a fraction of the short edge. */
    private const val MARKER_HIT_FRACTION: Float = 0.035f

    /** Stroke widths as a fraction of the page's short edge. Tuned to read well at A4 scale. */
    private val WIDTH_FRACTIONS: FloatArray = floatArrayOf(0.003f, 0.006f, 0.011f)

    /** The six annotation colours, in palette-index order. */
    val colors: List<Color> = listOf(
        // Ink black: same token the drawing canvas uses for its darkest pen.
        Color(NoteZeroPalette.TextPrimaryLight),
        Color(NoteZeroPalette.DangerLight),
        Color.hsv(28f, 0.92f, 0.96f),
        Color(NoteZeroPalette.SuccessTextLight),
        Color(NoteZeroPalette.AccentFillLight),
        Color.hsv(274f, 0.60f, 0.56f),
    )

    init {
        check(colors.size == PALETTE_SIZE) { "annotation palette must match model bounds" }
        check(WIDTH_FRACTIONS.size == WIDTH_LEVELS) { "annotation widths must match model bounds" }
    }

    /** Compose colour for [index], clamped so a corrupt payload cannot crash drawing. */
    fun color(index: Int): Color = colors[index.coerceIn(0, PALETTE_SIZE - 1)]

    /**
     * ARGB integer for [index] with [alpha] applied, for the flatten exporter's
     * `android.graphics.Paint` (the only place that needs platform colour ints).
     */
    fun argb(index: Int, alpha: Float = 1f): Int = paintArgb(color(index), alpha)

    /** ARGB integer of any Compose [color] with [alpha] applied (exporter paint helper). */
    fun paintArgb(color: Color, alpha: Float = 1f): Int {
        val rgb = color.toArgb()
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    /** Alpha used to draw a stroke of [kind]. */
    fun strokeAlpha(kind: PdfInkKind): Float = when (kind) {
        PdfInkKind.INK -> INK_ALPHA
        PdfInkKind.HIGHLIGHTER -> HIGHLIGHTER_ALPHA
    }

    /** Compose colour of a stroke of [kind]/[colorIndex]. */
    fun strokeColor(kind: PdfInkKind, colorIndex: Int): Color =
        color(colorIndex).copy(alpha = strokeAlpha(kind))

    /** Stroke width in pixels for a page whose short edge is [minEdgePx]. */
    fun strokeWidthPx(minEdgePx: Float, kind: PdfInkKind, widthIndex: Int): Float {
        val fraction = WIDTH_FRACTIONS[widthIndex.coerceIn(0, WIDTH_LEVELS - 1)]
        val scale = if (kind == PdfInkKind.HIGHLIGHTER) HIGHLIGHTER_WIDTH_SCALE else 1f
        return (minEdgePx * fraction * scale).coerceAtLeast(1f)
    }

    /** Width in pixels of the rectangle-highlight border for a page short edge. */
    fun highlightBorderWidthPx(minEdgePx: Float): Float =
        (minEdgePx * WIDTH_FRACTIONS[0]).coerceAtLeast(1f)

    /** Radius in pixels of a sticky-note marker for a page short edge. */
    fun markerRadiusPx(minEdgePx: Float): Float =
        (minEdgePx * MARKER_RADIUS_FRACTION).coerceAtLeast(4f)

    /** Hit radius in pixels for a sticky-note marker for a page short edge. */
    fun markerHitRadiusPx(minEdgePx: Float): Float =
        (minEdgePx * MARKER_HIT_FRACTION).coerceAtLeast(12f)

    /** Fill colour of a sticky-note marker (amber token; notes carry no palette index). */
    val noteMarkerFill: Color = Color(NoteZeroPalette.WarningTextLight)

    /** Border/ink colour of the sticky-note marker glyph. */
    val noteMarkerGlyph: Color = Color(NoteZeroPalette.SurfaceLight)
}
