package com.cipherwell.notezero.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTest {

    private fun parse(text: String) = MarkdownInline.parse(text)

    @Test
    fun `plain text is a single literal token`() {
        assertEquals(listOf(InlineToken.Text("plain text")), parse("plain text"))
        assertTrue(parse("").isEmpty())
    }

    @Test
    fun `bold italic and bold italic markers`() {
        assertEquals(listOf(InlineToken.Bold("bold")), parse("**bold**"))
        assertEquals(listOf(InlineToken.Italic("italic")), parse("*italic*"))
        assertEquals(listOf(InlineToken.Italic("italic")), parse("_italic_"))
        assertEquals(listOf(InlineToken.BoldItalic("both")), parse("***both***"))
    }

    @Test
    fun `inline code takes precedence over emphasis`() {
        assertEquals(listOf(InlineToken.Code("**not bold**")), parse("`**not bold**`"))
    }

    @Test
    fun `links keep label and url`() {
        assertEquals(
            listOf(InlineToken.Link("site", "https://example.com")),
            parse("[site](https://example.com)"),
        )
    }

    @Test
    fun `strikethrough`() {
        assertEquals(listOf(InlineToken.Strikethrough("gone")), parse("~~gone~~"))
    }

    @Test
    fun `mixed tokens preserve order`() {
        assertEquals(
            listOf(
                InlineToken.Text("a "),
                InlineToken.Bold("b"),
                InlineToken.Text(" c "),
                InlineToken.Italic("d"),
                InlineToken.Text(" e"),
            ),
            parse("a **b** c *d* e"),
        )
    }

    @Test
    fun `unbalanced markers stay literal`() {
        assertEquals(listOf(InlineToken.Text("**nope")), parse("**nope"))
        assertEquals(listOf(InlineToken.Text("*nope")), parse("*nope"))
        assertEquals(listOf(InlineToken.Text("[not a link]")), parse("[not a link]"))
    }

    @Test
    fun `escaped markers are literal`() {
        assertEquals(listOf(InlineToken.Text("*not italic*")), parse("\\*not italic\\*"))
        assertEquals(listOf(InlineToken.Text("**literal**")), parse("\\*\\*literal\\*\\*"))
    }

    @Test
    fun `intraword underscores are not emphasis`() {
        assertEquals(listOf(InlineToken.Text("snake_case_name")), parse("snake_case_name"))
    }

    @Test
    fun `arbitrary input never throws`() {
        val weird = listOf(
            "****", "`", "`unclosed", "[]()", "[a](", "~~", "***", "\\", "a\\", "*a**b*", "#".repeat(500),
        )
        weird.forEach { text ->
            MarkdownInline.parse(text)
        }
    }
}
