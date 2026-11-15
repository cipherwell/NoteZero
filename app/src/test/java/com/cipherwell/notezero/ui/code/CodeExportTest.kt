package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.data.VaultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the export file-name suggestion. The SAF write itself is exercised on
 * device (system picker); here we only prove the suggested name can never carry a path or a
 * control character into another provider's namespace.
 */
class CodeExportTest {

    @Test
    fun `plain file names are offered unchanged`() {
        assertEquals("Main.kt", sanitizeExportName("Main.kt"))
        assertEquals("notes.md", sanitizeExportName("notes.md"))
        assertEquals("a b.txt", sanitizeExportName("a b.txt"))
    }

    @Test
    fun `path separators and control characters are neutralised`() {
        assertEquals(".._.._etc_passwd", sanitizeExportName("../../etc/passwd"))
        assertEquals("C:_Windows_evil.kt", sanitizeExportName("C:\\Windows\nevil.kt"))
        assertFalse(sanitizeExportName("a/b").contains('/'))
        assertFalse(sanitizeExportName("a\\b").contains('\\'))
    }

    @Test
    fun `blank names fall back to the default`() {
        assertEquals(CODE_EXPORT_FALLBACK_NAME, sanitizeExportName(""))
        assertEquals(CODE_EXPORT_FALLBACK_NAME, sanitizeExportName("   "))
        // Trailing control whitespace is trimmed; the remaining NUL becomes an underscore.
        assertEquals("_", sanitizeExportName("\u0000\n"))
    }

    // ---- locked-vault guard (audit finding F6) -------------------------------

    /**
     * The SAF result can arrive after the vault locked (idle timer while the picker is open, or
     * `endExternalActivity()` locking as the result lands). The write decision must be made at
     * result time, not when the export was requested.
     */
    @Test
    fun `export is allowed only while unlocked with the item still visible`() {
        val repository = FakeCodeRepository()
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "fun main() {}")
        assertTrue(canWriteCodeExport(repository, "f1"))

        // The vault locked while the picker was open: the plaintext must be dropped.
        repository.setStatus(VaultStatus.LOCKED)
        assertFalse("a locked vault never writes plaintext", canWriteCodeExport(repository, "f1"))

        // A new session without the item (deleted/trashed) must not write either.
        repository.setStatus(VaultStatus.UNLOCKED)
        assertFalse(canWriteCodeExport(repository, "gone"))
    }

    @Test
    fun `export is refused for a locked file this session has not verified`() {
        val repository = FakeCodeRepository()
        repository.seedCodeFile(id = "f1", title = "Secret.kt", body = "secret", locked = true)
        assertFalse("locked+unverified content is masked", canWriteCodeExport(repository, "f1"))

        repository.revealForTest("f1")
        assertTrue("a verified lock is visible to the session", canWriteCodeExport(repository, "f1"))
    }
}
