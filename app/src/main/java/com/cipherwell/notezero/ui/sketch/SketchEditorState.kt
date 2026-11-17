package com.cipherwell.notezero.ui.sketch

import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchInkKind
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShape
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import com.cipherwell.notezero.model.SketchTemplate
import com.cipherwell.notezero.model.SketchWidth
import kotlin.math.abs
import kotlin.math.hypot

/** Active drawing tool in the studio toolbar. */
enum class SketchTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPE,
}

/**
 * Immutable editing state of one standalone sketch: the [document] itself, the tool
 * selections for the *next* stroke and a bounded undo/redo history.
 *
 * Every committed mutation goes through a private `push`: the previous document is appended to
 * [undoStack] (bounded to [MAX_HISTORY] steps) and [redoStack] is cleared, so undo/redo works
 * on whole document snapshots while the immutable [SketchDocument] shares the untouched lists.
 * Nothing in this class uses Android, Compose or logging — it is a plain state machine that the
 * Robolectric studio tests and the pure JVM editor tests exercise through the screen.
 *
 * Bounds: input that would make [SketchDocument]'s constructor throw (stroke/shape/point limits,
 * non-finite or out-of-page samples) is *dropped* instead of being written; the studio can
 * therefore never crash the save path by drawing beyond the frozen model bounds.
 */
data class SketchEditorState(
    val document: SketchDocument = SketchDocument(),
    val tool: SketchTool = SketchTool.PEN,
    val shapeKind: SketchShapeKind = SketchShapeKind.LINE,
    val ink: SketchInk = SketchInk.BLACK,
    val width: SketchWidth = SketchWidth.MEDIUM,
    val undoStack: List<SketchDocument> = emptyList(),
    val redoStack: List<SketchDocument> = emptyList(),
) {

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val isEmpty: Boolean get() = document.isEmpty

    /** Ink kind used by the next freehand stroke. */
    val inkKind: SketchInkKind
        get() = if (tool == SketchTool.HIGHLIGHTER) SketchInkKind.HIGHLIGHTER else SketchInkKind.PEN

    fun withTool(value: SketchTool): SketchEditorState = copy(tool = value)

    /** Selecting a shape from the menu also activates the shape tool. */
    fun withShapeKind(value: SketchShapeKind): SketchEditorState =
        copy(tool = SketchTool.SHAPE, shapeKind = value)

    fun withInk(value: SketchInk): SketchEditorState = copy(ink = value)

    fun withWidth(value: SketchWidth): SketchEditorState = copy(width = value)

    /** Replaces the loaded document (studio open) and resets the history. */
    fun loaded(document: SketchDocument): SketchEditorState =
        copy(document = document, undoStack = emptyList(), redoStack = emptyList())

    /** Paper template switch; undoable like any other page mutation. */
    fun setTemplate(template: SketchTemplate): SketchEditorState {
        if (document.template == template) return this
        return push(document.copy(template = template))
    }

    /**
     * Commits one freehand stroke. Samples are clamped, near-duplicates dropped, a single tap
     * becomes a two-point dot, and a stroke that would exceed the model bounds is ignored.
     */
    fun addStroke(points: List<SketchPoint>): SketchEditorState {
        if (document.strokes.size >= SketchDocument.MAX_STROKES) return this
        val cleaned = normalisePoints(points)
        if (cleaned.size < SketchDocument.MIN_POINTS_PER_STROKE) return this
        if (totalPoints() + cleaned.size > SketchDocument.MAX_TOTAL_POINTS) return this
        val stroke = SketchStroke(
            points = cleaned,
            ink = ink,
            width = width,
            kind = inkKind,
        )
        return push(document.copy(strokes = document.strokes + stroke))
    }

    /** Commits one two-point shape with the currently selected shape kind/ink/width. */
    fun addShape(start: SketchPoint, end: SketchPoint): SketchEditorState {
        if (document.shapes.size >= SketchDocument.MAX_SHAPES) return this
        val shape = SketchShape(
            kind = shapeKind,
            start = SketchGeometry.clamp(start),
            end = SketchGeometry.clamp(end),
            ink = ink,
            width = width,
        )
        return push(document.copy(shapes = document.shapes + shape))
    }

    /** Eraser gesture: removes every stroke/shape near the drag path (undoable). */
    fun erase(eraserPath: List<SketchPoint>): SketchEditorState {
        if (eraserPath.isEmpty()) return this
        val next = SketchGeometry.erase(document, eraserPath)
        return if (next === document) this else push(next)
    }

    /** Lasso gesture: removes every stroke/shape enclosed by the free-path polygon (undoable). */
    fun lasso(polygon: List<SketchPoint>): SketchEditorState {
        if (polygon.size < SketchGeometry.LASSO_MIN_POINTS) return this
        val next = SketchGeometry.lasso(document, polygon)
        return if (next === document) this else push(next)
    }

    /** Clears the page content but keeps the paper template; undoable. */
    fun clearContent(): SketchEditorState {
        if (document.isEmpty) return this
        return push(document.copy(strokes = emptyList(), shapes = emptyList()))
    }

    fun undo(): SketchEditorState {
        if (undoStack.isEmpty()) return this
        val previous = undoStack.last()
        return copy(
            document = previous,
            undoStack = undoStack.dropLast(1),
            redoStack = (listOf(document) + redoStack).take(MAX_HISTORY),
        )
    }

    fun redo(): SketchEditorState {
        if (redoStack.isEmpty()) return this
        val next = redoStack.first()
        return copy(
            document = next,
            undoStack = (undoStack + document).takeLast(MAX_HISTORY),
            redoStack = redoStack.drop(1),
        )
    }

    /**
     * Sanitises raw pointer samples into a valid stroke point list: clamp into the page, drop
     * non-finite coordinates, collapse samples that barely moved (invisible at any export
     * resolution), cap the per-stroke budget and turn a single tap into a two-point dot.
     */
    private fun normalisePoints(raw: List<SketchPoint>): List<SketchPoint> {
        val cleaned = ArrayList<SketchPoint>(minOf(raw.size, SketchDocument.MAX_POINTS_PER_STROKE))
        for (point in raw) {
            if (cleaned.size >= SketchDocument.MAX_POINTS_PER_STROKE) break
            if (!point.x.isFinite() || !point.y.isFinite()) continue
            val clamped = SketchGeometry.clamp(point)
            val last = cleaned.lastOrNull()
            if (last != null &&
                abs(clamped.x - last.x) < MIN_POINT_DISTANCE &&
                abs(clamped.y - last.y) < MIN_POINT_DISTANCE
            ) {
                continue
            }
            cleaned += clamped
        }
        if (cleaned.size == 1) {
            // A tap is a dot: duplicate the sample so the model's 2-point minimum holds and
            // the renderer can draw the point as a round cap.
            cleaned += cleaned.first()
        }
        return cleaned
    }

    private fun totalPoints(): Int = document.strokes.sumOf { it.points.size }

    private fun push(next: SketchDocument): SketchEditorState = copy(
        document = next,
        undoStack = (undoStack + document).takeLast(MAX_HISTORY),
        redoStack = emptyList(),
    )

    companion object {
        /** Undo depth; the contract requires at least 50 steps. */
        const val MAX_HISTORY: Int = 100

        /** Samples closer than this (normalised) add nothing visible at export resolution. */
        const val MIN_POINT_DISTANCE: Float = 0.0015f

        /** Distance under which a two-sample stroke is treated as a tap/dot. */
        const val DOT_RADIUS: Float = 0.002f

        /** True when a committed stroke is a tap dot (renderer draws a filled circle for it). */
        fun isDot(stroke: SketchStroke): Boolean {
            if (stroke.points.isEmpty()) return false
            val first = stroke.points.first()
            return stroke.points.all {
                hypot(it.x - first.x, it.y - first.y) <= DOT_RADIUS
            }
        }
    }
}
