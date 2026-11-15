package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the export file-name sanitiser and the picker-result gate. */
class NoteExportNamingTest {

    private fun note(
        id: String = "n1",
        title: String = "Note",
        locked: Boolean = false,
    ) = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = "body",
        locked = locked,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `blank title falls back to a generic name`() {
        assertEquals("note.md", sanitizeNoteExportName("   ", "md"))
        assertEquals("note.html", sanitizeNoteExportName("", "html"))
    }

    @Test
    fun `path separators and reserved characters are replaced`() {
        assertEquals(
            "My _ note_ 2024_.md",
            sanitizeNoteExportName("My / note: 2024?", "md"),
        )
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j.md",
            sanitizeNoteExportName("a/b\\c:d*e?f\"g<h>i|j", "md"),
        )
    }

    @Test
    fun `control characters are replaced`() {
        assertEquals("ab_c.md", sanitizeNoteExportName("ab\u0000c", "md"))
        assertEquals("line_break.md", sanitizeNoteExportName("line\nbreak", "md"))
    }

    @Test
    fun `extension is appended exactly once`() {
        assertEquals("already.md", sanitizeNoteExportName("already.md", "md"))
        assertEquals("already.html", sanitizeNoteExportName("already.html", "html"))
        assertEquals("already.MD", sanitizeNoteExportName("already.MD", "md"))
    }

    @Test
    fun `long titles are bounded and trailing dots stripped`() {
        val name = sanitizeNoteExportName("x".repeat(300) + "...", "md")
        assertEquals(80 + ".md".length, name.length)
        assertFalse(name.contains(".."))
        assertTrue(name.endsWith(".md"))
    }

    @Test
    fun `canWrite requires unlocked vault, present item and revealed content`() {
        val repository = FakeNoteRepository()
        val plain = note(id = "plain")
        val locked = note(id = "locked", locked = true)
        repository.setItems(listOf(plain, locked))

        assertTrue(canWriteNoteExport(repository, "plain"))
        assertFalse(canWriteNoteExport(repository, "locked"))
        repository.revealForTest("locked")
        assertTrue(canWriteNoteExport(repository, "locked"))
        assertFalse(canWriteNoteExport(repository, "missing"))

        repository.setStatus(VaultStatus.LOCKED)
        assertFalse(canWriteNoteExport(repository, "plain"))
    }
}
