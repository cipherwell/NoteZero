package com.cipherwell.notezero.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormattingTest {

    private val now = 1_760_000_000_000L

    @Test
    fun `relative time covers just now minutes hours yesterday and days`() {
        assertEquals("Just now", NoteFormatting.relativeTime(now - 30_000, now))
        assertEquals("1 min ago", NoteFormatting.relativeTime(now - 60_000, now))
        assertEquals("59 min ago", NoteFormatting.relativeTime(now - 59 * 60_000, now))
        assertEquals("2 h ago", NoteFormatting.relativeTime(now - 2 * 3_600_000, now))
        assertEquals("23 h ago", NoteFormatting.relativeTime(now - 23 * 3_600_000, now))
        assertEquals("Yesterday", NoteFormatting.relativeTime(now - 25 * 3_600_000, now))
        assertEquals("3 days ago", NoteFormatting.relativeTime(now - 3 * 86_400_000L, now))
        assertTrue(
            NoteFormatting.relativeTime(now - 30 * 86_400_000L, now).matches(Regex("\\d{1,2} [A-Z][a-z]{2}")),
        )
    }

    @Test
    fun `relative time is safe for missing or future timestamps`() {
        assertEquals("", NoteFormatting.relativeTime(0L, now))
        assertEquals("", NoteFormatting.relativeTime(-5L, now))
        assertEquals("Just now", NoteFormatting.relativeTime(now + 10_000, now))
    }

    @Test
    fun `preview strips block and inline markdown`() {
        assertEquals("Hello", NoteFormatting.preview("# Hello"))
        assertEquals(
            "Hello world This is bold and code.",
            NoteFormatting.preview("# Hello world\n\nThis is **bold** and `code`."),
        )
        assertEquals("Buy milk Done", NoteFormatting.preview("- [ ] Buy milk\n- [x] Done"))
        assertEquals("First Second", NoteFormatting.preview("1. First\n2. Second"))
        assertEquals("quoted", NoteFormatting.preview("> quoted"))
        assertEquals("", NoteFormatting.preview("---"))
        assertEquals("link", NoteFormatting.preview("[link](https://example.com)"))
        assertEquals("", NoteFormatting.preview("   \n\n  "))
    }

    @Test
    fun `preview truncates long bodies with an ellipsis`() {
        val text = "word ".repeat(60)
        val preview = NoteFormatting.preview(text, maxLength = 40)
        assertEquals(40, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `display title falls back for untitled notes`() {
        assertEquals("New Note", NoteFormatting.displayTitle(""))
        assertEquals("New Note", NoteFormatting.displayTitle("   "))
        assertEquals("Groceries", NoteFormatting.displayTitle("  Groceries  "))
    }
}
