package com.cipherwell.notezero.ui.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
