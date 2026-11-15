package com.cipherwell.notezero.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [NoteExport]: Markdown title/body composition and the HTML renderer,
 * with emphasis on escaping and script/URL injection safety.
 */
class NoteExportTest {

    // ---- Markdown ------------------------------------------------------------

    @Test
    fun `markdown puts the title as a heading before the body`() {
        assertEquals("# My note\n\nBody line", NoteExport.toMarkdown("My note", "Body line"))
    }

    @Test
    fun `markdown collapses a multi line title to one heading line`() {
        assertEquals("# My Title\n\nbody", NoteExport.toMarkdown("  My\nTitle  ", "body"))
    }

    @Test
    fun `markdown normalises crlf line endings`() {
        assertEquals("# T\n\na\nb\nc", NoteExport.toMarkdown("T", "a\r\nb\rc"))
    }

    @Test
    fun `markdown omits a blank heading or body`() {
        assertEquals("body only", NoteExport.toMarkdown("   ", "body only"))
        assertEquals("# Only title", NoteExport.toMarkdown("Only title", ""))
        assertEquals("", NoteExport.toMarkdown("", ""))
    }

    @Test
    fun `markdown keeps title punctuation and body markdown verbatim`() {
        val markdown = NoteExport.toMarkdown("A & B: #1", "**bold** | `code`")
        assertEquals("# A & B: #1\n\n**bold** | `code`", markdown)
    }

    // ---- HTML structure ------------------------------------------------------

    @Test
    fun `html is a complete escaped document with a title heading`() {
        val html = NoteExport.toHtml("Note", "")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
        assertTrue(html.contains("<title>Note</title>"))
        assertTrue(html.contains("<h1>Note</h1>"))
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `html renders headings paragraphs and inline formatting`() {
        val html = NoteExport.toHtml(
            "Doc",
            "# Head\n\nHello **bold** and *italic* and `code` and ~~gone~~ and [site](https://example.com).",
        )
        assertTrue(html.contains("<h1>Doc</h1>"))
        assertTrue(html.contains("<h1>Head</h1>"))
        assertTrue(html.contains("<p>Hello <strong>bold</strong> and <em>italic</em>"))
        assertTrue(html.contains("<code>code</code>"))
        assertTrue(html.contains("<del>gone</del>"))
        assertTrue(html.contains("<a href=\"https://example.com\">site</a>"))
    }

    @Test
    fun `html renders lists checklists quotes rules and tables`() {
        val html = NoteExport.toHtml(
            "Lists",
            buildString {
                appendLine("- one")
                appendLine("- two")
                appendLine("  - nested")
                appendLine()
                appendLine("1. first")
                appendLine("2. second")
                appendLine()
                appendLine("- [ ] todo")
                appendLine("- [x] done")
                appendLine()
                appendLine("> quoted **bold**")
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("| A | B |")
                appendLine("| --- | --- |")
                appendLine("| 1 | **2** |")
            }.trimEnd(),
        )

        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>one</li>"))
        assertTrue(html.contains("<li>two<ul>"))
        assertTrue(html.contains("<li>nested</li>"))
        assertTrue(html.contains("<ol>"))
        assertTrue(html.contains("<li>first</li>"))
        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<hr>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>A</th>"))
        assertTrue(html.contains("<td>1</td>"))
        assertTrue(html.contains("<td><strong>2</strong></td>"))

        // Checklist: two disabled checkboxes, exactly one checked.
        assertEquals(2, Regex("type=\"checkbox\"").findAll(html).count())
        assertEquals(2, Regex("disabled").findAll(html).count())
        assertEquals(1, Regex(" checked").findAll(html).count())
        assertTrue(html.contains("todo"))
        assertTrue(html.contains("done"))
    }

    @Test
    fun `html escapes fenced code and sanitises the language class`() {
        val html = NoteExport.toHtml(
            "Code",
            "```kotlin\nval x = \"<tag>\" & 'y'\n```",
        )
        assertTrue(html.contains("<pre><code class=\"language-kotlin\">"))
        assertTrue(html.contains("&lt;tag&gt;"))
        assertTrue(html.contains("&amp; &#39;y&#39;"))
        assertFalse(html.contains("<tag>"))
    }

    @Test
    fun `html code language cannot inject an attribute`() {
        val html = NoteExport.toHtml(
            "Code",
            "```kt\" onclick=\"alert(1)\nbody\n```",
        )
        assertTrue(Regex("class=\"language-[A-Za-z0-9+._#-]+\"").containsMatchIn(html))
        assertTrue(html.contains("class=\"language-ktonclickalert1\""))
        assertFalse(html.contains("onclick=\"alert"))
    }

    // ---- Escaping / injection ------------------------------------------------

    @Test
    fun `html escapes script tags in title and body`() {
        val html = NoteExport.toHtml(
            title = "T <script>alert(1)</script>",
            body = "<script>alert('x')</script>",
        )
        assertFalse(html.contains("<script", ignoreCase = true))
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"))
        assertTrue(html.contains("<title>T &lt;script&gt;alert(1)&lt;/script&gt;</title>"))
    }

    @Test
    fun `html escapes quotes ampersands and angle brackets in text`() {
        val html = NoteExport.toHtml("A & B \"q\" <x>", "He said \"hi\" & it's <5> > 2")
        assertTrue(html.contains("<title>A &amp; B &quot;q&quot; &lt;x&gt;</title>"))
        assertTrue(html.contains("He said &quot;hi&quot; &amp; it&#39;s &lt;5&gt; &gt; 2"))
    }

    @Test
    fun `html escapes link urls and keeps allowed schemes`() {
        val html = NoteExport.toHtml(
            "Links",
            "[site](https://example.com/a?x=1&y=\"2\")",
        )
        assertTrue(
            html.contains(
                "<a href=\"https://example.com/a?x=1&amp;y=&quot;2&quot;\">site</a>",
            ),
        )
    }

    @Test
    fun `html drops javascript and data urls but keeps the label`() {
        val html = NoteExport.toHtml(
            "Links",
            "[click](javascript:alert(1)) [image](data:text/html,<script>) [rel](/docs?a=1)",
        )
        assertFalse(html.contains("javascript", ignoreCase = true))
        assertFalse(html.contains("data:text", ignoreCase = true))
        assertFalse(html.contains("href=\"javascript"))
        assertTrue(html.contains("click"))
        assertTrue(html.contains("image"))
        assertTrue(html.contains("<a href=\"/docs?a=1\">rel</a>"))
    }

    @Test
    fun `sanitise link url uses a scheme allowlist`() {
        assertNull(NoteExport.sanitiseLinkUrl("javascript:alert(1)"))
        assertNull(NoteExport.sanitiseLinkUrl("JaVaScRiPt:alert(1)"))
        assertNull(NoteExport.sanitiseLinkUrl("java\nscript:alert(1)"))
        assertNull(NoteExport.sanitiseLinkUrl("data:text/html,x"))
        assertNull(NoteExport.sanitiseLinkUrl("vbscript:msgbox(1)"))
        assertNull(NoteExport.sanitiseLinkUrl(""))
        assertEquals("https://example.com", NoteExport.sanitiseLinkUrl("https://example.com"))
        assertEquals("http://example.com", NoteExport.sanitiseLinkUrl(" http://example.com "))
        assertEquals("mailto:a@b.example", NoteExport.sanitiseLinkUrl("mailto:a@b.example"))
        assertEquals("#fragment", NoteExport.sanitiseLinkUrl("#fragment"))
        assertEquals("/relative/path", NoteExport.sanitiseLinkUrl("/relative/path"))
    }

    @Test
    fun `html keeps unicode text intact`() {
        val body = "café — 日本語 🚀 “curly”"
        val html = NoteExport.toHtml("Ünïcode", body)
        assertTrue(html.contains("<h1>Ünïcode</h1>"))
        assertTrue(html.contains(body))
    }

    @Test
    fun `html degrades raw html blocks to escaped paragraphs`() {
        val html = NoteExport.toHtml("Doc", "<img src=x onerror=alert(1)>")
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }

    @Test
    fun `html never throws on unbalanced markdown`() {
        val html = NoteExport.toHtml("T", "**unclosed [link]( and `code ~~strike")
        // Unbalanced markers stay literal text instead of becoming half-built tags.
        assertTrue(html.contains("**unclosed [link]( and `code ~~strike</p>"))
        assertFalse(html.contains("<strong>"))
        assertFalse(html.contains("<a href"))
        assertTrue(html.trimEnd().endsWith("</html>"))
    }
}
