package com.cipherwell.notezero.ui.attachments

/**
 * Pure (no `android.*`) stroke model behind the drawing canvas.
 *
 * The model is deliberately immutable: every pointer sample produces a new [DrawingSketch],
 * which is what lets Compose recompose the preview without an extra observable graph, and
 * what makes the undo/clear/bounds behaviour a plain JVM unit test. Rendering to a bitmap
 * ([DrawingRenderer]) and the Compose preview only read this model.
 *
 * Coordinates are **normalised to the 0..1 canvas box**, never pixels: the preview and the
 * saved PNG have different pixel sizes (and the page is re-measured on rotation), so a
 * pixel-based stroke would drift. [DrawingWidth] is likewise a fraction of the canvas short
 * edge, which keeps the line weight identical on screen and in the exported image.
 *
 * Bounds (memory is the reason, not the UI): [MAX_STROKES] strokes, [MAX_POINTS_PER_STROKE]
 * samples per stroke and [MAX_TOTAL_POINTS] samples for the whole sketch. Once a budget is
 * exhausted further input is ignored instead of growing without limit; a fixed-size canvas
 * must never be able to exhaust the heap.
 */
data class DrawingPoint(val x: Float, val y: Float) {
    /** Clamps into the canvas box so a pointer leaving the page cannot escape the bitmap. */
    fun clamped(): DrawingPoint = DrawingPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/**
 * Ink choices. Colour is resolved by `DrawingPalette` (derived from `ui/theme` tokens), so
 * the pure model stays free of Compose/Android types.
 */
enum class DrawingInk {
    BLACK,
    GREY,
    RED,
    ORANGE,
    GREEN,
    BLUE,
    PURPLE,
}

/**
 * The three pen widths, as a fraction of the canvas short edge. [fraction] is applied by
 * both the preview and the PNG renderer so the saved image matches what was drawn.
 */
enum class DrawingWidth(val fraction: Float) {
    THIN(0.008f),
    MEDIUM(0.018f),
    BOLD(0.035f),
}

/** One stroke: the samples, the ink and the pen width it was drawn with. */
data class DrawingStroke(
    val points: List<DrawingPoint>,
    val ink: DrawingInk,
    val width: DrawingWidth,
)

/**
 * Immutable state of one drawing: finished [strokes], the [active] in-progress stroke (null
 * between gestures) and the tool selection for the next stroke.
 *
 * A single tap is a valid stroke with one point (rendered as a dot). [undo] first discards
 * an in-progress stroke, then the most recent finished one — an interrupted line is undone
 * one step at a time.
 */
data class DrawingSketch(
    val strokes: List<DrawingStroke> = emptyList(),
    val active: DrawingStroke? = null,
    val ink: DrawingInk = DrawingInk.BLACK,
    val width: DrawingWidth = DrawingWidth.MEDIUM,
) {
    /** True once there is anything to save; also drives undo/clear/save enabled state. */
    val hasContent: Boolean
        get() = strokes.isNotEmpty() || !active?.points.isNullOrEmpty()

    /** Finished strokes plus the in-progress one, in paint order. */
    val visibleStrokes: List<DrawingStroke>
        get() = if (active == null) strokes else strokes + active

    private val pointCount: Int
        get() = strokes.sumOf { it.points.size } + (active?.points?.size ?: 0)

    /** Begins a stroke. No-op while one is active or when a bound is reached. */
    fun start(point: DrawingPoint): DrawingSketch {
        if (active != null) return this
        if (strokes.size >= MAX_STROKES) return this
        if (pointCount >= MAX_TOTAL_POINTS) return this
        return copy(active = DrawingStroke(listOf(point.clamped()), ink, width))
    }

    /**
     * Adds a sample to the active stroke. Coordinates are clamped, samples that barely moved
     * (sub-pixel at export resolution) are dropped, and the per-stroke/total bounds hold.
     */
    fun extend(point: DrawingPoint): DrawingSketch {
        val stroke = active ?: return this
        if (stroke.points.size >= MAX_POINTS_PER_STROKE) return this
        if (pointCount >= MAX_TOTAL_POINTS) return this
        val next = point.clamped()
        val last = stroke.points.last()
        if (
            kotlin.math.abs(next.x - last.x) < MIN_POINT_DISTANCE &&
            kotlin.math.abs(next.y - last.y) < MIN_POINT_DISTANCE
        ) {
            return this
        }
        return copy(active = stroke.copy(points = stroke.points + next))
    }

    /** Ends the active stroke and keeps it (a single-point tap becomes a dot). */
    fun finish(): DrawingSketch {
        val stroke = active ?: return this
        if (stroke.points.isEmpty()) return copy(active = null)
        return copy(strokes = strokes + stroke, active = null)
    }

    /** Removes the in-progress stroke, else the most recent finished stroke. */
    fun undo(): DrawingSketch = when {
        active != null -> copy(active = null)
        strokes.isNotEmpty() -> copy(strokes = strokes.dropLast(1))
        else -> this
    }

    /** Clears the page but keeps the pen selection. */
    fun clear(): DrawingSketch = copy(strokes = emptyList(), active = null)

    fun withInk(value: DrawingInk): DrawingSketch = copy(ink = value)

    fun withWidth(value: DrawingWidth): DrawingSketch = copy(width = value)

    companion object {
        /** Undo depth and per-stroke memory bound. */
        const val MAX_STROKES = 128

        /** Samples kept per stroke. */
        const val MAX_POINTS_PER_STROKE = 2_048

        /** Samples kept for the whole sketch (bounds a full-page scribble). */
        const val MAX_TOTAL_POINTS = 32_768

        /** Samples closer than this (normalised) add nothing visible at export resolution. */
        private const val MIN_POINT_DISTANCE = 0.0015f
    }
}
