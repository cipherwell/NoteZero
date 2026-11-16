package com.cipherwell.notezero.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFormatsTest {

    @Test
    fun `all well-known extensions are offered and unique`() {
        val extensions = CodeFormats.all.map { it.extension }
        assertEquals(extensions.size, extensions.toSet().size)
        listOf("txt", "md", "html", "css", "js", "ts", "json", "py", "kt", "java", "c", "cpp")
            .forEach { assertTrue("missing $it", extensions.contains(it)) }
        assertEquals("txt", CodeFormats.default.extension)
    }

    @Test
    fun `lookup is case-insensitive and language-aware`() {
        assertEquals(CodeLanguage.KOTLIN, CodeFormats.forExtension("KT")?.language)
        assertEquals(CodeLanguage.XML, CodeFormats.forExtension("html")?.language)
        assertEquals(CodeLanguage.PLAIN, CodeFormats.forExtension("css")?.language)
        assertNull(CodeFormats.forExtension(""))
        assertNull(CodeFormats.forExtension("nope"))
    }

    @Test
    fun `resolveName keeps an explicitly typed known extension`() {
        val py = CodeFormats.forExtension("py")!!
        assertEquals("hello.kt", CodeFormats.resolveName("hello.kt", py))
        assertEquals("script.py", CodeFormats.resolveName("script", py))
        assertEquals("untitled.py", CodeFormats.resolveName("", py))
        // An unknown extension is treated as part of the base name and replaced.
        assertEquals("notes.py", CodeFormats.resolveName("notes.weird", py))
    }

    @Test
    fun `applyFormat appends or replaces the extension`() {
        val py = CodeFormats.forExtension("py")!!
        assertEquals("untitled.py", CodeFormats.applyFormat("", py))
        assertEquals("script.py", CodeFormats.applyFormat("script", py))
        assertEquals("script.py", CodeFormats.applyFormat("script.txt", py))
        assertEquals("my.script.py", CodeFormats.applyFormat("my.script.js", py))
        assertEquals(".gitignore.txt", CodeFormats.applyFormat(".gitignore", CodeFormats.default))
        assertEquals("untitled.md", CodeFormats.defaultName(CodeFormats.forExtension("md")!!))
    }
}
