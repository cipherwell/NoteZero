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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for the studio's editing state machine and geometry.
 *
 * These cover the parts that are awkward to drive reliably through touch injection (undo depth,
 * bounds clamping, lasso point-in-polygon, eraser tolerance) with the same [SketchEditorState]
 * the Robolectric screen uses.
 */
class SketchEditorStateTest {

    private fun state() = SketchEditorState()

    private fun lineStroke(
        from: SketchPoint = SketchPoint(0.1f, 0.1f),
        to: SketchPoint = SketchPoint(0.4f, 0.4f),
    ): SketchStroke = SketchStroke(
        points = listOf(from, to),
        ink = SketchInk.BLACK,
        width = SketchWidth.MEDIUM,
    )

    @Test
    fun `committing a stroke records it and enables undo`() {
        val next = state().addStroke(listOf(SketchPoint(0.1f, 0.1f), SketchPoint(0.5f, 0.5f)))

        assertEquals(1, next.document.strokes.size)
        assertEquals(2, next.document.strokes.single().points.size)
        assertTrue(next.canUndo)
        assertFalse(next.canRedo)
        assertEquals(SketchInkKind.PEN, next.document.strokes.single().kind)
    }

    @Test
    fun `a single tap becomes a valid two-point dot`() {
        val next = state().addStroke(listOf(SketchPoint(0.5f, 0.5f)))

        val stroke = next.document.strokes.single()
        assertEquals(2, stroke.points.size)
        assertTrue(SketchEditorState.isDot(stroke))
    }

    @Test
    fun `samples are clamped into the page and non-finite input is dropped`() {
        val next = state().addStroke(
            listOf(
                SketchPoint(-1f, 2f),
                SketchPoint(Float.NaN, 0.5f),
                SketchPoint(1.5f, -0.2f),
            ),
        )

        next.document.strokes.single().points.forEach { point ->
            assertTrue(point.x in 0f..1f)
            assertTrue(point.y in 0f..1f)
        }
    }

    @Test
    fun `per-stroke point budget is capped`() {
        val raw = (0 until SketchDocument.MAX_POINTS_PER_STROKE * 2).map { index ->
            // Alternating 2%-steps avoid the near-duplicate sample filter.
            SketchPoint((index % 50) / 50f, (index % 37) / 37f)
        }
        val next = state().addStroke(raw)

        assertEquals(SketchDocument.MAX_POINTS_PER_STROKE, next.document.strokes.single().points.size)
    }

    @Test
    fun `undo and redo walk the document history`() {
        val first = state().addStroke(listOf(SketchPoint(0.1f, 0.1f), SketchPoint(0.2f, 0.2f)))
        val second = first.addStroke(listOf(SketchPoint(0.5f, 0.5f), SketchPoint(0.6f, 0.6f)))
        assertEquals(2, second.document.strokes.size)

        val undone = second.undo()
        assertEquals(1, undone.document.strokes.size)
        assertTrue(undone.canRedo)

        val redone = undone.redo()
        assertEquals(2, redone.document.strokes.size)
        assertFalse(redone.canRedo)

        val undoneTwice = redone.undo().undo()
        assertEquals(0, undoneTwice.document.strokes.size)
        assertFalse(undoneTwice.canUndo)
    }

    @Test
    fun `undo depth is bounded but at least the contract minimum`() {
        var editor = state()
        repeat(150) { index ->
            editor = editor.addStroke(
                listOf(
                    SketchPoint(0.01f + (index % 50) * 0.01f, 0.01f),
                    SketchPoint(0.02f + (index % 50) * 0.01f, 0.02f),
                ),
            )
        }
        assertEquals(150, editor.document.strokes.size)
        assertEquals(SketchEditorState.MAX_HISTORY, editor.undoStack.size)
        assertTrue(SketchEditorState.MAX_HISTORY >= 50)

        repeat(SketchEditorState.MAX_HISTORY) { editor = editor.undo() }
        assertEquals(50, editor.document.strokes.size)
        assertFalse(editor.canUndo)
    }

    @Test
    fun `a new mutation clears the redo stack`() {
        val drawn = state().addStroke(listOf(SketchPoint(0.1f, 0.1f), SketchPoint(0.2f, 0.2f)))
        val undone = drawn.undo()
        assertTrue(undone.canRedo)

        val redrawn = undone.addStroke(listOf(SketchPoint(0.4f, 0.4f), SketchPoint(0.5f, 0.5f)))
        assertFalse(redrawn.canRedo)
        assertEquals(1, redrawn.document.strokes.size)
    }

    @Test
    fun `the template switch is a document mutation and is undoable`() {
        val next = state().setTemplate(SketchTemplate.GRID)
        assertEquals(SketchTemplate.GRID, next.document.template)
        assertTrue(next.canUndo)

        val undone = next.undo()
        assertEquals(SketchTemplate.BLANK, undone.document.template)

        assertSame(next, next.setTemplate(SketchTemplate.GRID))
    }

    @Test
    fun `clear keeps the template and is undoable`() {
        val drawn = state()
            .setTemplate(SketchTemplate.RULED)
            .addStroke(listOf(SketchPoint(0.1f, 0.1f), SketchPoint(0.2f, 0.2f)))
        val cleared = drawn.clearContent()

        assertTrue(cleared.document.isEmpty)
        assertEquals(SketchTemplate.RULED, cleared.document.template)

        val restored = cleared.undo()
        assertEquals(1, restored.document.strokes.size)
    }

    @Test
    fun `clear on an empty page is a no-op`() {
        val empty = state()
        assertSame(empty, empty.clearContent())
    }

    @Test
    fun `shape commits use the selected shape kind ink and width`() {
        val next = state()
            .withShapeKind(SketchShapeKind.ELLIPSE)
            .withInk(SketchInk.RED)
            .withWidth(SketchWidth.BOLD)
            .addShape(SketchPoint(0.2f, 0.2f), SketchPoint(0.8f, 0.7f))

        val shape = next.document.shapes.single()
        assertEquals(SketchShapeKind.ELLIPSE, shape.kind)
        assertEquals(SketchInk.RED, shape.ink)
        assertEquals(SketchWidth.BOLD, shape.width)
        assertEquals(SketchTool.SHAPE, next.tool)
    }

    @Test
    fun `eraser removes touched strokes and shapes only`() {
        val near = SketchStroke(
            points = listOf(SketchPoint(0.2f, 0.2f), SketchPoint(0.8f, 0.8f)),
            ink = SketchInk.BLACK,
            width = SketchWidth.MEDIUM,
        )
        val far = SketchStroke(
            points = listOf(SketchPoint(0.05f, 0.9f), SketchPoint(0.1f, 0.95f)),
            ink = SketchInk.BLUE,
            width = SketchWidth.THIN,
        )
        val document = SketchDocument(
            strokes = listOf(near, far),
            shapes = listOf(
                SketchShape(
                    kind = SketchShapeKind.RECT,
                    start = SketchPoint(0.1f, 0.1f),
                    end = SketchPoint(0.5f, 0.5f),
                    ink = SketchInk.GREEN,
                    width = SketchWidth.MEDIUM,
                ),
            ),
        )
        val editor = SketchEditorState(document = document)
        val erased = editor.erase(listOf(SketchPoint(0.5f, 0.5f)))

        assertEquals(1, erased.document.strokes.size)
        assertEquals(SketchInk.BLUE, erased.document.strokes.single().ink)
        assertTrue(erased.document.shapes.isEmpty())
        assertTrue(erased.canUndo)
    }

    @Test
    fun `eraser drag far from everything leaves the document untouched`() {
        val document = SketchDocument(strokes = listOf(lineStroke()))
        val editor = SketchEditorState(document = document)
        val erased = editor.erase(listOf(SketchPoint(0.95f, 0.05f), SketchPoint(0.98f, 0.02f)))

        assertSame(editor, erased)
    }

    @Test
    fun `lasso removes enclosed strokes and leaves the rest`() {
        val inside = SketchStroke(
            points = listOf(SketchPoint(0.25f, 0.25f), SketchPoint(0.35f, 0.35f)),
            ink = SketchInk.BLACK,
            width = SketchWidth.MEDIUM,
        )
        val outside = SketchStroke(
            points = listOf(SketchPoint(0.75f, 0.75f), SketchPoint(0.85f, 0.85f)),
            ink = SketchInk.RED,
            width = SketchWidth.MEDIUM,
        )
        val document = SketchDocument(strokes = listOf(inside, outside))
        val polygon = listOf(
            SketchPoint(0.1f, 0.1f),
            SketchPoint(0.6f, 0.1f),
            SketchPoint(0.6f, 0.6f),
            SketchPoint(0.1f, 0.6f),
        )

        val lashed = SketchEditorState(document = document).lasso(polygon)

        assertEquals(1, lashed.document.strokes.size)
        assertEquals(SketchInk.RED, lashed.document.strokes.single().ink)
        assertTrue(lashed.canUndo)
    }

    @Test
    fun `lasso needs at least three samples`() {
        val document = SketchDocument(strokes = listOf(lineStroke()))
        val editor = SketchEditorState(document = document)

        assertSame(editor, editor.lasso(listOf(SketchPoint(0.1f, 0.1f), SketchPoint(0.5f, 0.5f))))
    }

    @Test
    fun `stroke and shape model bounds are enforced without throwing`() {
        val fullStrokes = (0 until SketchDocument.MAX_STROKES).map {
            SketchStroke(
                points = listOf(SketchPoint(0f, 0f), SketchPoint(1f, 1f)),
                ink = SketchInk.BLACK,
                width = SketchWidth.THIN,
            )
        }
        val fullShapes = (0 until SketchDocument.MAX_SHAPES).map {
            SketchShape(
                kind = SketchShapeKind.LINE,
                start = SketchPoint(0f, 0f),
                end = SketchPoint(1f, 1f),
                ink = SketchInk.BLACK,
                width = SketchWidth.THIN,
            )
        }
        val document = SketchDocument(strokes = fullStrokes, shapes = fullShapes)
        val editor = SketchEditorState(document = document)

        assertSame(
            editor,
            editor.addStroke(listOf(SketchPoint(0.2f, 0.2f), SketchPoint(0.3f, 0.3f))),
        )
        assertSame(
            editor,
            editor.addShape(SketchPoint(0.2f, 0.2f), SketchPoint(0.3f, 0.3f)),
        )
        assertEquals(SketchDocument.MAX_STROKES, editor.document.strokes.size)
        assertEquals(SketchDocument.MAX_SHAPES, editor.document.shapes.size)
    }

    @Test
    fun `tool selection never touches the document or the history`() {
        val editor = state()
            .withTool(SketchTool.HIGHLIGHTER)
            .withShapeKind(SketchShapeKind.ARROW)
            .withInk(SketchInk.PURPLE)
            .withWidth(SketchWidth.THIN)

        assertTrue(editor.document.isEmpty)
        assertFalse(editor.canUndo)
        assertEquals(SketchTool.SHAPE, editor.tool)
        assertEquals(SketchShapeKind.ARROW, editor.shapeKind)
        assertEquals(SketchInk.PURPLE, editor.ink)
        assertEquals(SketchWidth.THIN, editor.width)
    }

    @Test
    fun `lasso polygon hit testing is ray casting`() {
        val polygon = listOf(
            SketchPoint(0.2f, 0.2f),
            SketchPoint(0.8f, 0.2f),
            SketchPoint(0.8f, 0.8f),
            SketchPoint(0.2f, 0.8f),
        )
        assertTrue(SketchGeometry.pointInPolygon(SketchPoint(0.5f, 0.5f), polygon))
        assertFalse(SketchGeometry.pointInPolygon(SketchPoint(0.05f, 0.5f), polygon))
        assertFalse(SketchGeometry.pointInPolygon(SketchPoint(0.9f, 0.9f), polygon))
    }

    @Test
    fun `arrow heads sit behind the tip on both sides`() {
        val head = SketchGeometry.arrowHead(SketchPoint(0.2f, 0.2f), SketchPoint(0.8f, 0.5f))
        assertEquals(2, head.size)
        head.forEach { point ->
            assertTrue(point.x < 0.8f)
            assertTrue(point.y in 0f..1f)
        }
    }
}
