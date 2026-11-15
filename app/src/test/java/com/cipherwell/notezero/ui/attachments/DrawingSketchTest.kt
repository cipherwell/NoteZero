package com.cipherwell.notezero.ui.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the drawing stroke model: add/undo/clear, coordinate clamping and the
 * memory bounds. No Android, no Robolectric, no sleeps.
 */
class DrawingSketchTest {

    private fun sketch(): DrawingSketch = DrawingSketch()

    /** Two samples that pass the minimum-distance filter. */
    private fun stroke(sketch: DrawingSketch, points: List<DrawingPoint>): DrawingSketch {
        var next = sketch.start(points.first())
        points.drop(1).forEach { next = next.extend(it) }
        return next.finish()
    }

    @Test
    fun `a new sketch is empty and cannot undo`() {
        val empty = sketch()
        assertFalse(empty.hasContent)
        assertTrue(empty.strokes.isEmpty())
        assertNull(empty.active)
        assertTrue(empty.visibleStrokes.isEmpty())
    }

    @Test
    fun `start extend finish records one visible stroke`() {
        var drawing = sketch()
        drawing = drawing.start(DrawingPoint(0.1f, 0.1f))
        drawing = drawing.extend(DrawingPoint(0.2f, 0.2f))
        drawing = drawing.extend(DrawingPoint(0.3f, 0.25f))

        assertTrue(drawing.hasContent)
        assertEquals(1, drawing.visibleStrokes.size)
        assertEquals(3, drawing.visibleStrokes.single().points.size)
        assertEquals(DrawingInk.BLACK, drawing.visibleStrokes.single().ink)
        assertEquals(DrawingWidth.MEDIUM, drawing.visibleStrokes.single().width)

        drawing = drawing.finish()
        assertNull(drawing.active)
        assertEquals(1, drawing.strokes.size)
        assertEquals(3, drawing.strokes.single().points.size)
    }

    @Test
    fun `coordinates are clamped into the page`() {
        var drawing = sketch()
        drawing = drawing.start(DrawingPoint(-0.5f, -20f))
        drawing = drawing.extend(DrawingPoint(1.7f, 3f)).finish()

        val points = drawing.strokes.single().points
        assertEquals(0f, points[0].x, 0f)
        assertEquals(0f, points[0].y, 0f)
        assertEquals(1f, points[1].x, 0f)
        assertEquals(1f, points[1].y, 0f)
    }

    @Test
    fun `samples that barely moved are dropped`() {
        var drawing = sketch()
        drawing = drawing.start(DrawingPoint(0.5f, 0.5f))
        drawing = drawing.extend(DrawingPoint(0.5001f, 0.5001f))
        drawing = drawing.extend(DrawingPoint(0.5f, 0.5f))

        assertEquals(1, drawing.active!!.points.size)
    }

    @Test
    fun `a single tap survives finish as a dot`() {
        val drawing = sketch().start(DrawingPoint(0.5f, 0.5f)).finish()
        assertEquals(1, drawing.strokes.size)
        assertEquals(1, drawing.strokes.single().points.size)
    }

    @Test
    fun `extend without a start and finish without an active stroke are no-ops`() {
        val drawing = sketch()
        assertTrue(drawing.extend(DrawingPoint(0.4f, 0.4f)) === drawing)
        assertTrue(drawing.finish() === drawing)
    }

    @Test
    fun `a second start while one stroke is active is ignored`() {
        var drawing = sketch().start(DrawingPoint(0.1f, 0.1f))
        drawing = drawing.start(DrawingPoint(0.9f, 0.9f))
        assertEquals(1, drawing.active!!.points.size)
        assertEquals(0.1f, drawing.active!!.points.single().x, 0f)
    }

    @Test
    fun `undo removes the most recent finished stroke`() {
        var drawing = sketch()
        drawing = stroke(drawing, listOf(DrawingPoint(0.1f, 0.1f), DrawingPoint(0.2f, 0.2f)))
        drawing = stroke(drawing, listOf(DrawingPoint(0.3f, 0.3f), DrawingPoint(0.4f, 0.4f)))
        assertEquals(2, drawing.strokes.size)

        drawing = drawing.undo()
        assertEquals(1, drawing.strokes.size)
        assertEquals(0.1f, drawing.strokes.single().points.first().x, 0f)

        drawing = drawing.undo()
        assertFalse(drawing.hasContent)
        assertTrue(drawing.undo() === drawing)
    }

    @Test
    fun `undo during an active stroke discards only that stroke`() {
        var drawing = stroke(
            sketch(),
            listOf(DrawingPoint(0.1f, 0.1f), DrawingPoint(0.2f, 0.2f)),
        )
        drawing = drawing.start(DrawingPoint(0.8f, 0.8f)).extend(DrawingPoint(0.9f, 0.9f))

        drawing = drawing.undo()
        assertNull(drawing.active)
        assertEquals(1, drawing.strokes.size)
        assertEquals(0.1f, drawing.strokes.single().points.first().x, 0f)
    }

    @Test
    fun `clear empties the page but keeps the pen selection`() {
        var drawing = DrawingSketch(ink = DrawingInk.RED, width = DrawingWidth.BOLD)
        drawing = stroke(drawing, listOf(DrawingPoint(0.2f, 0.2f), DrawingPoint(0.3f, 0.3f)))

        drawing = drawing.clear()
        assertFalse(drawing.hasContent)
        assertTrue(drawing.strokes.isEmpty())
        assertEquals(DrawingInk.RED, drawing.ink)
        assertEquals(DrawingWidth.BOLD, drawing.width)
    }

    @Test
    fun `ink and width selection apply to the next stroke`() {
        var drawing = sketch().withInk(DrawingInk.BLUE).withWidth(DrawingWidth.THIN)
        drawing = stroke(drawing, listOf(DrawingPoint(0.2f, 0.2f), DrawingPoint(0.3f, 0.3f)))

        val stroke = drawing.strokes.single()
        assertEquals(DrawingInk.BLUE, stroke.ink)
        assertEquals(DrawingWidth.THIN, stroke.width)
    }

    @Test
    fun `ink palette exposes seven distinct entries`() {
        assertEquals(7, DrawingInk.entries.size)
        assertEquals(7, DrawingInk.entries.distinct().size)
        assertEquals(3, DrawingWidth.entries.size)
    }

    @Test
    fun `stroke count is bounded`() {
        var drawing = sketch()
        repeat(DrawingSketch.MAX_STROKES) { index ->
            drawing = drawing.start(DrawingPoint(index / 1_000f, 0.5f)).finish()
        }
        assertEquals(DrawingSketch.MAX_STROKES, drawing.strokes.size)

        val atLimit = drawing.start(DrawingPoint(0.5f, 0.5f))
        assertNull(atLimit.active)
        assertEquals(DrawingSketch.MAX_STROKES, atLimit.strokes.size)
    }

    @Test
    fun `samples per stroke are bounded`() {
        var drawing = sketch().start(DrawingPoint(0.1f, 0.1f))
        repeat(DrawingSketch.MAX_POINTS_PER_STROKE + 100) { index ->
            drawing = drawing.extend(
                DrawingPoint(
                    x = (index % 512) / 1_024f,
                    y = if (index % 2 == 0) 0.25f else 0.75f,
                ),
            )
        }
        assertEquals(DrawingSketch.MAX_POINTS_PER_STROKE, drawing.active!!.points.size)
    }

    @Test
    fun `total sample budget is bounded across strokes`() {
        var drawing = sketch()
        var strokes = 0
        while (true) {
            val started = drawing.start(DrawingPoint(0.1f, 0.1f))
            if (started.active == null) break
            var next = started
            repeat(DrawingSketch.MAX_POINTS_PER_STROKE - 1) { index ->
                next = next.extend(
                    DrawingPoint(
                        x = (index % 512) / 1_024f,
                        y = if (index % 2 == 0) 0.25f else 0.75f,
                    ),
                )
            }
            drawing = next.finish()
            strokes++
            assertTrue("unbounded loop", strokes <= DrawingSketch.MAX_TOTAL_POINTS)
        }
        assertEquals(DrawingSketch.MAX_TOTAL_POINTS / DrawingSketch.MAX_POINTS_PER_STROKE, strokes)
        val total = drawing.strokes.sumOf { it.points.size }
        assertEquals(DrawingSketch.MAX_TOTAL_POINTS, total)
    }

    @Test
    fun `visible strokes keep the active stroke last`() {
        var drawing = stroke(
            sketch(),
            listOf(DrawingPoint(0.1f, 0.1f), DrawingPoint(0.2f, 0.2f)),
        )
        drawing = drawing.start(DrawingPoint(0.7f, 0.7f))

        assertEquals(2, drawing.visibleStrokes.size)
        assertNotNull(drawing.active)
        assertEquals(0.7f, drawing.visibleStrokes.last().points.single().x, 0f)
    }
}
