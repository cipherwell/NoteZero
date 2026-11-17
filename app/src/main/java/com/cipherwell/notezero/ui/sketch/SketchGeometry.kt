package com.cipherwell.notezero.ui.sketch

import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShape
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure geometry behind the drawing studio: page templates, arrow heads, point-in-polygon lasso
 * selection, eraser hit-testing and the two destructive operations ([erase], [lasso]).
 *
 * Everything operates on **normalised** [SketchPoint] coordinates (0..1 of the fixed A4 page),
 * so a rule chosen here is identical on a phone preview and in the flattened PNG, and the whole
 * file is plain Kotlin: no `android.*`, no Compose, so it runs as a fast JVM unit test. Nothing
 * in here logs, allocates a file or grows without bound — the input document is already bounded
 * by [SketchDocument] and the eraser/lasso results are strict subsets of it.
 */
object SketchGeometry {

    /** Horizontal rule spacing for the ruled template: page height / [RULED_LINES]. */
    const val RULED_LINES: Int = 26

    /** Vertical grid spacing for the grid template: page width / [GRID_COLUMNS]. */
    const val GRID_COLUMNS: Int = 16

    /** Grid rows that keep the cells square on an A4 portrait page. */
    val GRID_ROWS: Int = (GRID_COLUMNS / SketchDocument.PAGE_ASPECT_RATIO).roundToInt()

    /** Dotted template dots per row; rows keep the dots on a square lattice. */
    const val DOTTED_COLUMNS: Int = 16

    /** Dotted template rows (same square lattice as [GRID_ROWS]). */
    val DOTTED_ROWS: Int = GRID_ROWS

    /** Arrow-head arm length, as a fraction of the page height (normalised y units). */
    const val ARROW_HEAD_FRACTION: Float = 0.05f

    /** Half-angle between the arrow shaft and each head arm. */
    private const val ARROW_HEAD_HALF_ANGLE_RAD: Float = 0.44f

    /** Stroke removal distance for the eraser, in normalised page units. */
    const val ERASER_TOLERANCE: Float = 0.022f

    /** A lasso needs at least three samples to enclose an area. */
    const val LASSO_MIN_POINTS: Int = 3

    /** Ellipse outline approximation used by the eraser hit test. */
    private const val ELLIPSE_SEGMENTS: Int = 32

    /** Clamps a point into the page; non-finite input collapses to the origin. */
    fun clamp(point: SketchPoint): SketchPoint {
        val x = if (point.x.isFinite()) point.x.coerceIn(0f, 1f) else 0f
        val y = if (point.y.isFinite()) point.y.coerceIn(0f, 1f) else 0f
        return SketchPoint(x, y)
    }

    /** Converts a pointer position inside a page box into a clamped normalised point. */
    fun normalise(xPx: Float, yPx: Float, widthPx: Int, heightPx: Int): SketchPoint {
        if (widthPx <= 0 || heightPx <= 0) return SketchPoint(0f, 0f)
        return clamp(SketchPoint(xPx / widthPx, yPx / heightPx))
    }

    /** The two head-arm end points of an arrow from [start] to [end]. */
    fun arrowHead(start: SketchPoint, end: SketchPoint): List<SketchPoint> {
        val dx = start.x - end.x
        val dy = start.y - end.y
        val length = hypot(dx, dy)
        if (length < MIN_SEGMENT_LENGTH) return emptyList()
        val ux = dx / length
        val uy = dy / length
        val head = ARROW_HEAD_FRACTION
        val cosA = cos(ARROW_HEAD_HALF_ANGLE_RAD)
        val sinA = sin(ARROW_HEAD_HALF_ANGLE_RAD)
        val leftX = end.x + (ux * cosA - uy * sinA) * head
        val leftY = end.y + (ux * sinA + uy * cosA) * head
        val rightX = end.x + (ux * cosA + uy * sinA) * head
        val rightY = end.y + (-ux * sinA + uy * cosA) * head
        return listOf(clamp(SketchPoint(leftX, leftY)), clamp(SketchPoint(rightX, rightY)))
    }

    /** Straight-line segments that approximate the outline of [shape]. */
    fun shapeSegments(shape: SketchShape): List<Pair<SketchPoint, SketchPoint>> {
        val start = clamp(shape.start)
        val end = clamp(shape.end)
        return when (shape.kind) {
            SketchShapeKind.LINE -> listOf(start to end)
            SketchShapeKind.RECT -> {
                val left = min(start.x, end.x)
                val right = max(start.x, end.x)
                val top = min(start.y, end.y)
                val bottom = max(start.y, end.y)
                val a = SketchPoint(left, top)
                val b = SketchPoint(right, top)
                val c = SketchPoint(right, bottom)
                val d = SketchPoint(left, bottom)
                listOf(a to b, b to c, c to d, d to a)
            }

            SketchShapeKind.ELLIPSE -> {
                val centreX = (start.x + end.x) / 2f
                val centreY = (start.y + end.y) / 2f
                val radiusX = abs(end.x - start.x) / 2f
                val radiusY = abs(end.y - start.y) / 2f
                (0 until ELLIPSE_SEGMENTS).map { index ->
                    val angle = index * TWO_PI / ELLIPSE_SEGMENTS
                    val next = (index + 1) * TWO_PI / ELLIPSE_SEGMENTS
                    val from = SketchPoint(
                        centreX + radiusX * cos(angle),
                        centreY + radiusY * sin(angle),
                    )
                    val to = SketchPoint(
                        centreX + radiusX * cos(next),
                        centreY + radiusY * sin(next),
                    )
                    clamp(from) to clamp(to)
                }
            }

            SketchShapeKind.ARROW -> {
                val head = arrowHead(start, end)
                if (head.size == 2) {
                    listOf(start to end, end to head[0], end to head[1])
                } else {
                    listOf(start to end)
                }
            }
        }
    }

    /**
     * True when the eraser path passes within [tolerance] of the stroke. Each stroke segment is
     * compared against each eraser segment so a fast flick that sampled few eraser points still
     * erases a whole stroke instead of only the sampled dots.
     */
    fun eraserRemovesStroke(
        stroke: SketchStroke,
        eraserPath: List<SketchPoint>,
        tolerance: Float = ERASER_TOLERANCE,
    ): Boolean {
        if (eraserPath.isEmpty() || stroke.points.isEmpty()) return false
        val points = stroke.points.map(::clamp)
        if (points.size == 1) return nearAnySegment(points.first(), eraserPath, tolerance)
        for (index in 1 until points.size) {
            if (nearAnySegment(points[index - 1], points[index], eraserPath, tolerance)) return true
        }
        return false
    }

    /** True when the eraser path passes within [tolerance] of the shape outline. */
    fun eraserRemovesShape(
        shape: SketchShape,
        eraserPath: List<SketchPoint>,
        tolerance: Float = ERASER_TOLERANCE,
    ): Boolean {
        if (eraserPath.isEmpty()) return false
        return shapeSegments(shape).any { (from, to) ->
            nearAnySegment(from, to, eraserPath, tolerance)
        }
    }

    /**
     * True when at least half of the stroke's samples fall inside the lasso [polygon].
     * A two-point stroke therefore needs only one end inside (the contract's ">=50% rule").
     */
    fun lassoSelectsStroke(stroke: SketchStroke, polygon: List<SketchPoint>): Boolean {
        if (polygon.size < LASSO_MIN_POINTS || stroke.points.isEmpty()) return false
        val inside = stroke.points.count { pointInPolygon(it, polygon) }
        return inside * 2 >= stroke.points.size
    }

    /** True when both shape endpoints or the shape centre fall inside the lasso polygon. */
    fun lassoSelectsShape(shape: SketchShape, polygon: List<SketchPoint>): Boolean {
        if (polygon.size < LASSO_MIN_POINTS) return false
        val start = clamp(shape.start)
        val end = clamp(shape.end)
        return (pointInPolygon(start, polygon) && pointInPolygon(end, polygon)) ||
            pointInPolygon(centre(shape), polygon)
    }

    /** The centre of a shape (used by lasso selection and the eraser test). */
    fun centre(shape: SketchShape): SketchPoint = SketchPoint(
        (clamp(shape.start).x + clamp(shape.end).x) / 2f,
        (clamp(shape.start).y + clamp(shape.end).y) / 2f,
    )

    /** Ray-casting point-in-polygon; polygon is implicitly closed. */
    fun pointInPolygon(point: SketchPoint, polygon: List<SketchPoint>): Boolean {
        if (polygon.size < LASSO_MIN_POINTS) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.y > point.y) != (previous.y > point.y)
            if (crosses) {
                val xAtY = (previous.x - current.x) * (point.y - current.y) /
                    (previous.y - current.y) + current.x
                if (point.x < xAtY) inside = !inside
            }
            previous = current
        }
        return inside
    }

    /** Removes every stroke/shape the [eraserPath] touches; returns the same doc when none matches. */
    fun erase(
        document: SketchDocument,
        eraserPath: List<SketchPoint>,
        tolerance: Float = ERASER_TOLERANCE,
    ): SketchDocument {
        if (eraserPath.isEmpty()) return document
        val strokes = document.strokes.filterNot { eraserRemovesStroke(it, eraserPath, tolerance) }
        val shapes = document.shapes.filterNot { eraserRemovesShape(it, eraserPath, tolerance) }
        if (strokes.size == document.strokes.size && shapes.size == document.shapes.size) {
            return document
        }
        return document.copy(strokes = strokes, shapes = shapes)
    }

    /** Removes every stroke/shape enclosed by the [polygon]; returns the same doc when none matches. */
    fun lasso(document: SketchDocument, polygon: List<SketchPoint>): SketchDocument {
        if (polygon.size < LASSO_MIN_POINTS) return document
        val strokes = document.strokes.filterNot { lassoSelectsStroke(it, polygon) }
        val shapes = document.shapes.filterNot { lassoSelectsShape(it, polygon) }
        if (strokes.size == document.strokes.size && shapes.size == document.shapes.size) {
            return document
        }
        return document.copy(strokes = strokes, shapes = shapes)
    }

    /** Normalised distance from [point] to segment [from]–[to]. */
    fun distanceToSegment(point: SketchPoint, from: SketchPoint, to: SketchPoint): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < MIN_SEGMENT_LENGTH * MIN_SEGMENT_LENGTH) {
            return hypot(point.x - from.x, point.y - from.y)
        }
        val t = (((point.x - from.x) * dx + (point.y - from.y) * dy) / lengthSquared)
            .coerceIn(0f, 1f)
        return hypot(point.x - (from.x + t * dx), point.y - (from.y + t * dy))
    }

    /** Smallest distance between two segments (0 when they cross). */
    fun distanceBetweenSegments(
        aStart: SketchPoint,
        aEnd: SketchPoint,
        bStart: SketchPoint,
        bEnd: SketchPoint,
    ): Float {
        if (segmentsCross(aStart, aEnd, bStart, bEnd)) return 0f
        return minOf(
            distanceToSegment(aStart, bStart, bEnd),
            distanceToSegment(aEnd, bStart, bEnd),
            distanceToSegment(bStart, aStart, aEnd),
            distanceToSegment(bEnd, aStart, aEnd),
        )
    }

    /** True when any segment of [path] is closer than [tolerance] to the point/segment. */
    private fun nearAnySegment(
        from: SketchPoint,
        to: SketchPoint,
        path: List<SketchPoint>,
        tolerance: Float,
    ): Boolean {
        return if (path.size == 1) {
            distanceToSegment(path.first(), from, to) <= tolerance
        } else {
            (1 until path.size).any { index ->
                distanceBetweenSegments(from, to, path[index - 1], path[index]) <= tolerance
            }
        }
    }

    /** True when the single [point] is closer than [tolerance] to any segment of [path]. */
    private fun nearAnySegment(
        point: SketchPoint,
        path: List<SketchPoint>,
        tolerance: Float,
    ): Boolean {
        return if (path.size == 1) {
            hypot(point.x - path.first().x, point.y - path.first().y) <= tolerance
        } else {
            (1 until path.size).any { index ->
                distanceToSegment(point, path[index - 1], path[index]) <= tolerance
            }
        }
    }

    /** Orientation used by the segment-crossing test; 0 when the three points are collinear. */
    private fun orientation(a: SketchPoint, b: SketchPoint, c: SketchPoint): Int {
        val value = (b.y - a.y) * (c.x - b.x) - (b.x - a.x) * (c.y - b.y)
        return when {
            value > EPSILON -> 1
            value < -EPSILON -> -1
            else -> 0
        }
    }

    /** True when the two open segments properly straddle each other. */
    fun segmentsCross(
        aStart: SketchPoint,
        aEnd: SketchPoint,
        bStart: SketchPoint,
        bEnd: SketchPoint,
    ): Boolean {
        val o1 = orientation(aStart, aEnd, bStart)
        val o2 = orientation(aStart, aEnd, bEnd)
        val o3 = orientation(bStart, bEnd, aStart)
        val o4 = orientation(bStart, bEnd, aEnd)
        return o1 != o2 && o3 != o4
    }

    private const val TWO_PI = 6.2831855f
    private const val MIN_SEGMENT_LENGTH = 1e-6f
    private const val EPSILON = 1e-6f
}
