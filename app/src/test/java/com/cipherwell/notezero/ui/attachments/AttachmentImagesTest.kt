package com.cipherwell.notezero.ui.attachments

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.util.Base64

/**
 * Robolectric tests for in-memory image decoding. The fixtures are tiny PNGs held as
 * constants — nothing is read from or written to the filesystem, matching the
 * "never cache decrypted thumbnails" rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentImagesTest {

    /**
     * Robolectric's default bitmap shadow fabricates a 100x100 placeholder for data it
     * cannot parse. Disable that so corrupt/truncated input reaches the same null path a
     * real device takes; the valid-PNG tests are unaffected.
     */
    @Before
    fun requireRealDecodeResult() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    @After
    fun restoreShadowDefault() {
        ShadowBitmapFactory.setAllowInvalidImageData(true)
    }

    @Test
    fun `decodes a tiny in-memory png`() {
        val bitmap = AttachmentImages.decode(TINY_PNG, AttachmentImages.THUMBNAIL_TARGET_PX)
        assertNotNull(bitmap)
        assertEquals(2, bitmap!!.width)
        assertEquals(2, bitmap.height)
        bitmap.recycle()
    }

    @Test
    fun `viewer decode also handles the tiny png`() {
        val bitmap = AttachmentImages.decode(TINY_PNG, AttachmentImages.VIEWER_TARGET_PX)
        assertNotNull(bitmap)
        assertEquals(2, bitmap!!.width)
        bitmap.recycle()
    }

    @Test
    fun `empty bytes decode to null`() {
        assertNull(AttachmentImages.decode(ByteArray(0), 240))
    }

    @Test
    fun `corrupt bytes decode to null instead of throwing`() {
        val decoded = AttachmentImages.decode(ByteArray(512) { it.toByte() }, 240)
        assertNull("decoded=${decoded?.width}x${decoded?.height}", decoded)
    }

    @Test
    fun `truncated png decodes to null instead of throwing`() {
        val decoded = AttachmentImages.decode(TINY_PNG.copyOf(20), 240)
        assertNull("decoded=${decoded?.width}x${decoded?.height}", decoded)
    }

    private companion object {
        /** 2x2 opaque red PNG, generated offline; kept in memory only. */
        val TINY_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP4z8DwH4QZYAwAR8oH" +
                "+Rq28akAAAAASUVORK5CYII=",
        )
    }
}
