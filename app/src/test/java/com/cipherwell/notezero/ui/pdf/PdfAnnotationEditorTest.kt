package com.cipherwell.notezero.ui.pdf

import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfHighlightRect
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the annotation editor state machine: bounds clamping, hit testing, undo/redo
 * and the note-text budget. No Android or Compose UI is involved (the state properties are
 * Compose snapshot state, which runs on the JVM).
 */
class PdfAnnotationEditorTest {

    private fun stroke(
        page: Int = 0,
        kind: PdfInkKind = PdfInkKind.INK,
        points: List<PdfPoint> = listOf(PdfPoint(0.1f, 0.5f), PdfPoint(0.9f, 0.5f)),
    ) = PdfInkStroke(page, kind, 0, 0, points)

    @Test
    fun `addStroke rejects fewer than two points`() {
        val editor = PdfAnnotationEditor()
        assertFalse(editor.addStroke(0, PdfInkKind.INK, 0, 0, listOf(PdfPoint(0.5f, 0.5f))))
        assertFalse(editor.addStroke(0, PdfInkKind.INK, 0, 0, emptyList()))
        assertTrue(editor.value.isEmpty)
        assertFalse(editor.canUndo)
    }

    @Test
    fun `addStroke clamps points and indices into the model bounds`() {
        val editor = PdfAnnotationEditor()
        assertTrue(
            editor.addStroke(
                page = 0,
                kind = PdfInkKind.INK,
                colorIndex = 99,
                widthIndex = -3,
                points = listOf(PdfPoint(-1f, 2f), PdfPoint(0.5f, 0.5f)),
            ),
        )
        val stroke = editor.value.strokes.single()
        assertEquals(PdfAnnotations.PALETTE_SIZE - 1, stroke.colorIndex)
        assertEquals(0, stroke.widthIndex)
        assertEquals(PdfPoint(0f, 1f), stroke.points.first())
        assertTrue(editor.canUndo)
    }

    @Test
    fun `addStroke ignores non-finite points and caps the per-stroke point count`() {
        val editor = PdfAnnotationEditor()
        val many = (0 until 3_000).map { index ->
            PdfPoint(index / 3_000f, 0.5f)
        } + PdfPoint(Float.NaN, 0.5f)
        assertTrue(editor.addStroke(0, PdfInkKind.HIGHLIGHTER, 1, 2, many))
        assertEquals(PdfAnnotations.MAX_POINTS_PER_STROKE, editor.value.strokes.single().points.size)
        editor.value.strokes.single().points.forEach { point ->
            assertTrue(point.x.isFinite() && point.y.isFinite())
        }
    }

    @Test
    fun `addHighlight normalises an inverted drag and rejects tiny drags`() {
        val editor = PdfAnnotationEditor()
        assertFalse(editor.addHighlight(0, 0.5f, 0.5f, 0.5001f, 0.5001f, 0))
        assertTrue(editor.addHighlight(0, 0.8f, 0.7f, 0.2f, 0.1f, 3))
        val highlight = editor.value.highlights.single()
        assertEquals(0.2f, highlight.left, 0.0001f)
        assertEquals(0.8f, highlight.right, 0.0001f)
        assertEquals(0.1f, highlight.top, 0.0001f)
        assertEquals(0.7f, highlight.bottom, 0.0001f)
        assertEquals(3, highlight.colorIndex)
    }

    @Test
    fun `sticky notes can be added edited and deleted and clear the layer when last`() {
        val editor = PdfAnnotationEditor()
        val index = editor.addNote(0, 0.5f, 0.5f, "  hello  ")
        assertNotNull(index)
        assertEquals("hello", editor.value.notes.single().text)

        assertTrue(editor.updateNote(index!!, "edited"))
        assertEquals("edited", editor.value.notes.single().text)
        assertFalse(editor.updateNote(index, "   "))

        assertTrue(editor.deleteNote(index))
        assertTrue(editor.value.isEmpty)
    }

    @Test
    fun `note text is capped to the UTF-8 budget without splitting characters`() {
        val long = "é".repeat(PdfAnnotations.MAX_NOTE_BYTES)
        val bounded = PdfAnnotationEditor.boundedNoteText(long)
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= PdfAnnotations.MAX_NOTE_BYTES)

        val emoji = "\uD83D\uDCDD".repeat(PdfAnnotations.MAX_NOTE_BYTES)
        val boundedEmoji = PdfAnnotationEditor.boundedNoteText(emoji)
        assertTrue(boundedEmoji.toByteArray(Charsets.UTF_8).size <= PdfAnnotations.MAX_NOTE_BYTES)
        // No dangling high surrogate at the end.
        assertFalse(boundedEmoji.last().isHighSurrogate())

        val editor = PdfAnnotationEditor()
        assertNull(editor.addNote(0, 0.5f, 0.5f, " \n\t "))
    }

    @Test
    fun `eraser removes strokes highlights and notes near the point only`() {
        val editor = PdfAnnotationEditor()
        editor.addStroke(
            0,
            PdfInkKind.INK,
            0,
            0,
            listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.9f, 0.1f)),
        )
        editor.addStroke(
            0,
            PdfInkKind.INK,
            0,
            0,
            listOf(PdfPoint(0.1f, 0.9f), PdfPoint(0.9f, 0.9f)),
        )
        editor.addHighlight(0, 0.6f, 0.6f, 0.7f, 0.7f, 1)
        editor.addNote(0, 0.3f, 0.3f, "note")
        assertEquals(2, editor.value.strokes.size)

        // Nothing near this point: no mutation, no history entry.
        assertFalse(editor.eraseAt(0, 0.5f, 0.5f, 0.02f))

        assertTrue(editor.eraseAt(0, 0.3f, 0.1f, 0.03f))
        assertEquals(1, editor.value.strokes.size)
        assertEquals(
            "the far stroke survives",
            0.9f,
            editor.value.strokes.single().points.first().y,
            0.001f,
        )

        assertTrue(editor.eraseAt(0, 0.65f, 0.65f, 0.03f))
        assertTrue(editor.value.highlights.isEmpty())

        assertTrue(editor.eraseAt(0, 0.3f, 0.3f, 0.03f))
        assertTrue(editor.value.notes.isEmpty())
        assertTrue(editor.value.highlights.isEmpty())
        assertEquals("the far stroke survives", 1, editor.value.strokes.size)
    }

    @Test
    fun `annotations on other pages are not erased`() {
        val editor = PdfAnnotationEditor()
        editor.addStroke(1, PdfInkKind.INK, 0, 0, listOf(PdfPoint(0.2f, 0.5f), PdfPoint(0.8f, 0.5f)))
        editor.addNote(1, 0.5f, 0.5f, "page two")
        assertFalse(editor.eraseAt(0, 0.5f, 0.5f, 0.03f))
        assertEquals(1, editor.value.strokes.size)
        assertEquals(1, editor.value.notes.size)
        assertTrue(editor.eraseAt(1, 0.5f, 0.5f, 0.03f))
        assertTrue(editor.value.isEmpty)
    }

    @Test
    fun `undo and redo walk the history and a new mutation clears redo`() {
        val editor = PdfAnnotationEditor()
        assertFalse(editor.undo())
        assertFalse(editor.redo())

        editor.addStroke(0, PdfInkKind.INK, 0, 0, listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.2f, 0.2f)))
        editor.addHighlight(0, 0.5f, 0.5f, 0.6f, 0.6f, 0)
        assertEquals(2, editor.value.strokes.size + editor.value.highlights.size)

        assertTrue(editor.undo())
        assertEquals(1, editor.value.strokes.size)
        assertTrue(editor.value.highlights.isEmpty())
        assertTrue(editor.canRedo)

        assertTrue(editor.undo())
        assertTrue(editor.value.isEmpty)
        assertFalse(editor.canUndo)

        assertTrue(editor.redo())
        assertTrue(editor.redo())
        assertFalse(editor.canRedo)
        assertFalse(editor.redo())

        // A new mutation after an undo drops the redo branch.
        editor.undo()
        editor.addNote(0, 0.5f, 0.5f, "new")
        assertFalse(editor.canRedo)
    }

    @Test
    fun `eraser and clear are undoable`() {
        val editor = PdfAnnotationEditor()
        editor.addStroke(0, PdfInkKind.INK, 0, 0, listOf(PdfPoint(0.1f, 0.5f), PdfPoint(0.9f, 0.5f)))
        assertTrue(editor.eraseAt(0, 0.5f, 0.5f, 0.03f))
        assertTrue(editor.value.isEmpty)
        assertTrue(editor.undo())
        assertEquals(1, editor.value.strokes.size)

        assertTrue(editor.clear())
        assertTrue(editor.value.isEmpty)
        assertTrue(editor.undo())
        assertEquals(1, editor.value.strokes.size)
    }

    @Test
    fun `history keeps at least fifty steps and is bounded`() {
        val editor = PdfAnnotationEditor()
        val steps = 60
        repeat(steps) { index ->
            assertTrue(
                editor.addNote(0, 0.01f, 0.01f + index * 0.001f, "note-$index") != null,
            )
        }
        var undone = 0
        while (editor.undo()) undone++
        assertEquals(steps, undone)
        assertTrue(undone >= 50)
    }

    @Test
    fun `load resets the layer and the history`() {
        val editor = PdfAnnotationEditor()
        editor.addStroke(0, PdfInkKind.INK, 0, 0, listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.2f, 0.2f)))
        assertTrue(editor.canUndo)

        val loaded = PdfAnnotations(notes = listOf(PdfNote(0, 0.5f, 0.5f, "stored")))
        editor.load(loaded)
        assertEquals(loaded, editor.value)
        assertFalse(editor.canUndo)
        assertFalse(editor.canRedo)

        editor.load(null)
        assertTrue(editor.value.isEmpty)
    }

    @Test
    fun `model bounds are enforced instead of throwing`() {
        val editor = PdfAnnotationEditor(
            PdfAnnotations(
                highlights = List(1) { PdfHighlightRect(0, 0.1f, 0.1f, 0.2f, 0.2f, 0) },
            ),
        )
        // Page index outside the model domain is rejected without throwing.
        assertFalse(
            editor.addStroke(
                page = PdfAnnotations.MAX_PAGE + 1,
                kind = PdfInkKind.INK,
                colorIndex = 0,
                widthIndex = 0,
                points = listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.2f, 0.2f)),
            ),
        )
        assertFalse(
            editor.addStroke(
                0,
                PdfInkKind.INK,
                0,
                0,
                listOf(PdfPoint(Float.NaN, 0f), PdfPoint(0.2f, 0.2f)),
            ),
        )
    }

    @Test
    fun `normalisedRect orders and clamps and rejects degenerate drags`() {
        val rect = normalisedRect(0.9f, 0.8f, 0.1f, 0.2f)
        assertNotNull(rect)
        assertEquals(0.1f, rect!!.left, 0.0001f)
        assertEquals(0.2f, rect.top, 0.0001f)
        assertEquals(0.9f, rect.right, 0.0001f)
        assertEquals(0.8f, rect.bottom, 0.0001f)
        assertNull(normalisedRect(0.5f, 0.5f, 0.5f, 0.5f))
        assertNull(normalisedRect(Float.NaN, 0.5f, 0.5f, 0.5f))
    }

    @Test
    fun `distanceToSegment reports the perpendicular distance`() {
        assertEquals(0f, distanceToSegment(0.5f, 0.5f, 0f, 0.5f, 1f, 0.5f), 0.0001f)
        assertEquals(0.5f, distanceToSegment(0.5f, 1f, 0f, 0.5f, 1f, 0.5f), 0.0001f)
        assertEquals(0.5f, distanceToSegment(-0.5f, 0.5f, 0f, 0.5f, 1f, 0.5f), 0.0001f)
    }

    @Test
    fun `clear on an empty layer is a no-op`() {
        val editor = PdfAnnotationEditor()
        assertFalse(editor.clear())
        assertFalse(editor.canUndo)
    }
}
