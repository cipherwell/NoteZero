package com.cipherwell.notezero.ui.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Pure JVM tests for the drawing page geometry and the saved file name. */
class DrawingFormatTest {

    @Test
    fun `default page is a 3 by 4 sheet bounded to 2048 px`() {
        val size = DrawingFormat.pixelSize()
        assertEquals(1_536, size.widthPx)
        assertEquals(2_048, size.heightPx)
        assertEquals(0.75f, DrawingFormat.aspectRatio, 0f)
    }

    @Test
    fun `custom page keeps the aspect ratio`() {
        assertEquals(DrawingSize(widthPx = 48, heightPx = 64), DrawingFormat.pixelSize(64))
        assertEquals(DrawingSize(widthPx = 3, heightPx = 4), DrawingFormat.pixelSize(4))
    }

    @Test
    fun `page size is clamped to the hard bound and never degenerate`() {
        val huge = DrawingFormat.pixelSize(10_000)
        assertEquals(DrawingFormat.MAX_DIMENSION_PX, huge.heightPx)
        assertEquals(1_536, huge.widthPx)

        val tiny = DrawingFormat.pixelSize(0)
        assertTrue(tiny.widthPx >= 1)
        assertTrue(tiny.heightPx >= 1)

        val negative = DrawingFormat.pixelSize(-5)
        assertTrue(negative.widthPx >= 1)
        assertTrue(negative.heightPx >= 1)
    }

    @Test
    fun `file name is deterministic and locale independent`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(
            "drawing-19700101-000000.png",
            DrawingFormat.fileName(0L, utc),
        )
        assertEquals(
            "drawing-20260115-181500.png",
            DrawingFormat.fileName(1_768_500_900_000L, utc),
        )
    }

    @Test
    fun `file name is a display name only`() {
        val name = DrawingFormat.fileName(1_768_500_900_000L, TimeZone.getTimeZone("UTC"))
        assertTrue(name.startsWith(DrawingFormat.FILE_PREFIX))
        assertTrue(name.endsWith(".png"))
        assertTrue(name.matches(Regex("drawing-\\d{8}-\\d{6}\\.png")))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
    }

    @Test
    fun `mime type is image png`() {
        assertEquals("image/png", DrawingFormat.MIME_TYPE)
    }
}
