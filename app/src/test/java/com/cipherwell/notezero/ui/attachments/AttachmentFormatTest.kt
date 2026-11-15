package com.cipherwell.notezero.ui.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the attachment display/decoding arithmetic. These cover the
 * "friendly size" copy and the sampling math that keeps image decoding in memory.
 */
class AttachmentFormatTest {

    // ---- displayName ---------------------------------------------------------

    @Test
    fun `displayName keeps a plain name`() {
        assertEquals("tax-return.pdf", AttachmentFormat.displayName("tax-return.pdf"))
    }

    @Test
    fun `displayName keeps only the last path segment`() {
        assertEquals("photo.jpg", AttachmentFormat.displayName("/storage/emulated/0/DCIM/photo.jpg"))
        assertEquals("notes.txt", AttachmentFormat.displayName("C:\\Users\\me\\notes.txt"))
    }

    @Test
    fun `displayName removes control characters and trims`() {
        assertEquals("scan.pdf", AttachmentFormat.displayName("  sc\u0000an.pdf\n "))
    }

    @Test
    fun `displayName bounds the length and falls back when blank`() {
        val long = "a".repeat(500) + ".bin"
        val name = AttachmentFormat.displayName(long)
        assertEquals(AttachmentFormat.MAX_DISPLAY_NAME_CHARS, name.length)

        assertEquals("Attachment", AttachmentFormat.displayName(null))
        assertEquals("Attachment", AttachmentFormat.displayName("   "))
        assertEquals("Attachment", AttachmentFormat.displayName("///"))
        assertEquals("Photo", AttachmentFormat.displayName(null, fallback = "Photo"))
    }

    // ---- exportFileName ------------------------------------------------------

    @Test
    fun `exportFileName replaces device-unsafe separators`() {
        val name = AttachmentFormat.exportFileName("a/b\\c:d*e?f\"g<h>i|j.png")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun `exportFileName has a fallback and stays bounded`() {
        assertEquals("attachment", AttachmentFormat.exportFileName(null))
        assertTrue(AttachmentFormat.exportFileName("x".repeat(500)).length <= 120)
    }

    // ---- size ----------------------------------------------------------------

    @Test
    fun `size formats bytes and binary units`() {
        assertEquals("0 B", AttachmentFormat.size(0))
        assertEquals("812 B", AttachmentFormat.size(812))
        assertEquals("1.0 KB", AttachmentFormat.size(1_024))
        assertEquals("4.2 KB", AttachmentFormat.size(4_300))
        assertEquals("1.0 MB", AttachmentFormat.size(1_048_576))
        assertEquals("1.4 MB", AttachmentFormat.size(1_500_000))
        assertEquals("1.0 GB", AttachmentFormat.size(1_073_741_824))
        assertEquals("0 B", AttachmentFormat.size(-5))
    }

    @Test
    fun `size of the per-file limit is ten mebibytes`() {
        assertEquals("10.0 MB", AttachmentFormat.size(10L * 1_024 * 1_024))
    }

    // ---- sampleSize ----------------------------------------------------------

    @Test
    fun `sampleSize stays one for images already within budget`() {
        assertEquals(1, AttachmentFormat.sampleSize(100, 100, 240, 240))
    }

    @Test
    fun `sampleSize bounds the longest edge to the target`() {
        // Thumbnail target 240: 1000/4=250 > 240, so sample 8 (125 px long edge).
        assertEquals(8, AttachmentFormat.sampleSize(1000, 800, 240, 240))
        // 4000/16=250 > 240, so sample 32 (125 px long edge).
        assertEquals(32, AttachmentFormat.sampleSize(4000, 2000, 240, 240))
        // Portrait images sample on the long (vertical) edge too.
        assertEquals(8, AttachmentFormat.sampleSize(800, 1000, 240, 240))
    }

    @Test
    fun `sampleSize of the viewer target on a large photo`() {
        // 6000x4000 at 2048: 6000/2=3000 > 2048, 6000/4=1500 <= 2048 => sample 4.
        assertEquals(4, AttachmentFormat.sampleSize(6000, 4000, 2_048, 2_048))
        assertEquals(1, AttachmentFormat.sampleSize(2_000, 1_500, 2_048, 2_048))
    }

    @Test
    fun `sampleSize never returns zero for degenerate input`() {
        assertEquals(1, AttachmentFormat.sampleSize(0, 0, 240, 240))
        assertEquals(1, AttachmentFormat.sampleSize(-1, 500, 240, 240))
        assertEquals(1, AttachmentFormat.sampleSize(1, 1, 240, 240))
        assertEquals(1, AttachmentFormat.sampleSize(500, 500, 0, 0))
        // Extreme dimensions must not overflow the doubling loop.
        assertEquals(1 shl 30, AttachmentFormat.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1))
    }
}
