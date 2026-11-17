package com.cipherwell.notezero.model

/** Paper template drawn behind a sketch (fixed A4-portrait page). */
enum class SketchTemplate {
    BLANK,
    RULED,
    GRID,
    DOTTED,
}

/** Ink choices for sketch strokes/shapes; the UI palette resolves them from design tokens. */
enum class SketchInk {
    BLACK,
    GREY,
    RED,
    ORANGE,
    GREEN,
    BLUE,
    PURPLE,
}

/** Stroke widths as a fraction of the page short edge (resolution-independent). */
enum class SketchWidth(val fraction: Float) {
    THIN(0.008f),
    MEDIUM(0.018f),
    BOLD(0.035f),
}

/** Freehand stroke flavour: opaque pen or translucent highlighter. */
enum class SketchInkKind {
    PEN,
    HIGHLIGHTER,
}

/** Geometric shapes placed with two normalised points. */
enum class SketchShapeKind {
    LINE,
    RECT,
    ELLIPSE,
    ARROW,
}

/** A normalised point on the sketch page (0.0..1.0, origin top-left). */
data class SketchPoint(val x: Float, val y: Float)

/** One freehand stroke with its ink/width/tool. */
data class SketchStroke(
    val points: List<SketchPoint>,
    val ink: SketchInk,
    val width: SketchWidth,
    val kind: SketchInkKind = SketchInkKind.PEN,
)

/** One geometric shape (line/rect/ellipse/arrow) with normalised start/end. */
data class SketchShape(
    val kind: SketchShapeKind,
    val start: SketchPoint,
    val end: SketchPoint,
    val ink: SketchInk,
    val width: SketchWidth,
)

/**
 * One encrypted standalone sketch document (`ItemKind.SKETCH_FILE`, `ItemCodec` payload v6).
 *
 * This is the editable **vector** representation: it is stored inside the item's AES-256-GCM
 * envelope, travels with backups, is repository-owned like reading state/annotations, and can be
 * re-opened/edited at any resolution. The flattened PNG preview is a normal encrypted image
 * attachment rendered in memory by the studio; it never exists as a plaintext file.
 *
 * Geometry is normalised to the fixed A4-portrait page, so the same document renders correctly at
 * any canvas size, rotation or export resolution. Bounds keep a hostile/corrupt payload from
 * allocating unbounded data (2,000 strokes, 2..2,000 points/stroke, 100k total points, 500 shapes).
 */
data class SketchDocument(
    val template: SketchTemplate = SketchTemplate.BLANK,
    val strokes: List<SketchStroke> = emptyList(),
    val shapes: List<SketchShape> = emptyList(),
) {
    init {
        require(strokes.size <= MAX_STROKES) { "too many strokes" }
        var totalPoints = 0
        for (stroke in strokes) {
            require(stroke.points.size in MIN_POINTS_PER_STROKE..MAX_POINTS_PER_STROKE) {
                "stroke point count out of range"
            }
            totalPoints += stroke.points.size
            if (totalPoints > MAX_TOTAL_POINTS) throw IllegalArgumentException("too many points")
            stroke.points.forEach { point ->
                requireNormalised(point.x, "x")
                requireNormalised(point.y, "y")
            }
        }
        require(shapes.size <= MAX_SHAPES) { "too many shapes" }
        for (shape in shapes) {
            requireNormalised(shape.start.x, "shape start x")
            requireNormalised(shape.start.y, "shape start y")
            requireNormalised(shape.end.x, "shape end x")
            requireNormalised(shape.end.y, "shape end y")
        }
    }

    val isEmpty: Boolean get() = strokes.isEmpty() && shapes.isEmpty()

    private fun requireNormalised(value: Float, name: String) {
        require(value.isFinite() && value in 0f..1f) { "$name must be normalised 0..1" }
    }

    companion object {
        const val PAGE_ASPECT_RATIO: Float = 0.7071f // A4 portrait width/height
        const val MAX_STROKES: Int = 2_000
        const val MIN_POINTS_PER_STROKE: Int = 2
        const val MAX_POINTS_PER_STROKE: Int = 2_000
        const val MAX_TOTAL_POINTS: Int = 100_000
        const val MAX_SHAPES: Int = 500
        const val MAX_ENCODED_BYTES: Int = 4 * 1024 * 1024
    }
}
