package com.cipherwell.notezero.ui.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfInlineMarkdownTest {

    private fun plain(text: String) = PdfInlineMarkdown.parse(text).joinToString("") { it.text }

    @Test
    fun `plain text is one run`() {
        val runs = PdfInlineMarkdown.parse("hello world")
        assertEquals(1, runs.size)
        assertEquals("hello world", runs.single().text)
        assertFalse(runs.single().bold)
    }

    @Test
    fun `bold and italic runs`() {
        val runs = PdfInlineMarkdown.parse("a **bold** and *italic* end")
        assertEquals("a bold and italic end", runs.joinToString("") { it.text })
        val bold = runs.single { it.bold }
        val italic = runs.single { it.italic }
        assertEquals("bold", bold.text)
        assertEquals("italic", italic.text)
    }

    @Test
    fun `code span keeps its content literally`() {
        val runs = PdfInlineMarkdown.parse("use `**not bold**` here")
        val code = runs.single { it.code }
        assertEquals("**not bold**", code.text)
        assertFalse(code.bold)
        assertEquals("use **not bold** here", runs.joinToString("") { it.text })
    }

    @Test
    fun `strikethrough and links keep only the label`() {
        val runs = PdfInlineMarkdown.parse("~~gone~~ [label](https://example.com/a_b_c) tail")
        assertEquals("gone label tail", runs.joinToString("") { it.text })
        assertTrue(runs.single { it.strike }.text == "gone")
        val link = runs.single { it.link }
        assertEquals("label", link.text)
    }

    @Test
    fun `underscores inside words stay literal`() {
        val runs = PdfInlineMarkdown.parse("snake_case_name and a_b")
        assertEquals("snake_case_name and a_b", runs.joinToString("") { it.text })
        assertTrue(runs.none { it.italic || it.bold })
    }

    @Test
    fun `escaped markers are literal`() {
        assertEquals("**not bold**", plain("\\*\\*not bold\\*\\*"))
        assertEquals("[x](y)", plain("\\[x\\]\\(y\\)"))
    }

    @Test
    fun `unclosed markers are literal and never throw`() {
        val hostile = listOf(
            "**unclosed bold",
            "`unclosed code",
            "~~unclosed strike",
            "[label](unclosed",
            "***",
            "\\",
            "",
            "a".repeat(50_000),
            "\u0000\u0001\uD800",
            "- [ ] unchecked checklist text",
        )
        hostile.forEach { input ->
            val runs = PdfInlineMarkdown.parse(input)
            assertEquals(input, runs.joinToString("") { it.text })
        }
    }

    @Test
    fun `run count is bounded for hostile nesting`() {
        val runs = PdfInlineMarkdown.parse("*a ".repeat(10_000))
        assertTrue("runs=${runs.size}", runs.size <= 4_000)
        assertTrue(runs.isNotEmpty())
    }

    @Test
    fun `empty emphasis markers are not styled`() {
        assertEquals("****", plain("****"))
        assertEquals("__", plain("__"))
    }
}
