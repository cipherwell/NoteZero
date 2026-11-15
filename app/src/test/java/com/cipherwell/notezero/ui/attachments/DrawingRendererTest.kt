package com.cipherwell.notezero.ui.attachments

import android.graphics.BitmapFactory
import com.cipherwell.notezero.data.AttachmentLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory

/**
 * Robolectric tests for the PNG render path. The renderer is bounded and in-memory only;
 * the fixtures are tiny sketches rendered at 64 px so the test never allocates a full page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawingRendererTest {

    private fun lineSketch(): DrawingSketch = DrawingSketch()
        .start(DrawingPoint(0.1f, 0.1f))
        .extend(DrawingPoint(0.4f, 0.3f))
        .extend(DrawingPoint(0.8f, 0.9f))
        .finish()

    @Test
    fun `renders a bounded png page with the expected dimensions`() {
        val png = DrawingRenderer.renderPng(lineSketch(), maxDimensionPx = 64)

        assertNotNull(png)
        assertTrue(png!!.size > 8)
        // Real PNG signature: the encoder is the platform's, not a test stub.
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])

        // A shadow that fabricates 100x100 for unparseable data would hide an invalid page.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        try {
            val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertNotNull(decoded)
            assertEquals(48, decoded!!.width)
            assertEquals(64, decoded.height)
        } finally {
            ShadowBitmapFactory.setAllowInvalidImageData(true)
        }
    }

    @Test
    fun `a single tap renders a non-empty page`() {
        val png = DrawingRenderer.renderPng(
            DrawingSketch().start(DrawingPoint(0.5f, 0.5f)).finish(),
            maxDimensionPx = 32,
        )
        assertNotNull(png)
        assertTrue(png!!.isNotEmpty())
    }

    @Test
    fun `an empty sketch renders nothing`() {
        assertNull(DrawingRenderer.renderPng(DrawingSketch(), maxDimensionPx = 64))
        assertNull(DrawingRenderer.renderPng(DrawingSketch().clear(), maxDimensionPx = 64))
    }

    @Test
    fun `every ink and width renders`() {
        DrawingInk.entries.forEach { ink ->
            DrawingWidth.entries.forEach { width ->
                val sketch = DrawingSketch(ink = ink, width = width)
                    .start(DrawingPoint(0.2f, 0.2f))
                    .extend(DrawingPoint(0.6f, 0.7f))
                    .finish()
                val png = DrawingRenderer.renderPng(sketch, maxDimensionPx = 32)
                assertNotNull("ink=$ink width=$width", png)
                assertTrue("ink=$ink width=$width", png!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `a full page is kept under the 10 MiB attachment cap`() {
        // An unbounded request still renders: DrawingFormat clamps the page, and if the PNG
        // would exceed the attachment limit the renderer halves the resolution instead of
        // handing the pipeline an oversized image.
        val png = DrawingRenderer.renderPng(lineSketch(), maxDimensionPx = 1_000_000)
        assertNotNull(png)
        assertTrue(png!!.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES)

        ShadowBitmapFactory.setAllowInvalidImageData(false)
        try {
            val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertNotNull(decoded)
            assertTrue(decoded!!.width <= DrawingFormat.MAX_DIMENSION_PX)
            assertTrue(decoded.height <= DrawingFormat.MAX_DIMENSION_PX)
            assertTrue(decoded.width * DrawingFormat.ASPECT_HEIGHT == decoded.height * DrawingFormat.ASPECT_WIDTH)
            assertTrue(decoded.width >= 1)
        } finally {
            ShadowBitmapFactory.setAllowInvalidImageData(true)
        }
    }
}
