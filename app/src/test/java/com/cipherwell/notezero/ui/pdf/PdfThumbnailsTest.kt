package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit tests for the in-memory [PdfThumbnails] wrapper: 0-based lookup, bounds, emptiness and
 * the idempotent recycle ownership contract. Bitmaps are tiny Robolectric allocations — the real
 * `android.graphics.pdf` renderer is covered by `PdfThumbnailsInstrumentedTest` on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfThumbnailsTest {

    private fun bitmap(color: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun `exposes thumbnails by zero based page and reports bounds`() {
        val first = bitmap(Color.RED)
        val second = bitmap(Color.GREEN)

        val thumbnails = PdfThumbnails(listOf(first, second))

        assertEquals(2, thumbnails.size)
        assertFalse(thumbnails.isEmpty)
        assertSame(first, thumbnails.bitmap(0))
        assertSame(second, thumbnails.bitmap(1))
        assertNull("a page beyond the rendered set has no thumbnail", thumbnails.bitmap(2))
        assertNull("negative page indices are hostile input, not a crash", thumbnails.bitmap(-1))
    }

    @Test
    fun `empty set is empty and answers null for every page`() {
        val thumbnails = PdfThumbnails(emptyList())

        assertTrue(thumbnails.isEmpty)
        assertEquals(0, thumbnails.size)
        assertNull(thumbnails.bitmap(0))
        // Recycle of an empty set is a harmless no-op.
        thumbnails.recycle()
        assertTrue(thumbnails.isEmpty)
    }

    @Test
    fun `recycle recycles every bitmap and empties the set`() {
        val first = bitmap()
        val second = bitmap()
        val thumbnails = PdfThumbnails(listOf(first, second))

        thumbnails.recycle()

        assertTrue("every owned bitmap must be freed", first.isRecycled)
        assertTrue("every owned bitmap must be freed", second.isRecycled)
        assertTrue(thumbnails.isEmpty)
        assertEquals(0, thumbnails.size)
        assertNull(thumbnails.bitmap(0))
    }

    @Test
    fun `recycle is idempotent`() {
        val bitmap = bitmap()
        val thumbnails = PdfThumbnails(listOf(bitmap))

        thumbnails.recycle()
        thumbnails.recycle()

        assertTrue(bitmap.isRecycled)
        assertTrue(thumbnails.isEmpty)
    }
}
