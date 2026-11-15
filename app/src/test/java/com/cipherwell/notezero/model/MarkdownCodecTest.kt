package com.cipherwell.notezero.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Behavioural tests for the canonical Markdown <-> block codec. The editor relies on
 * parse never throwing and on parse(render(parse(md))) == parse(md) for arbitrary input;
 * both invariants are fuzzed here, not just spot-checked.
 */
class MarkdownCodecTest {

    private fun blocks(markdown: String): List<Block> = MarkdownBlockCodec.parse(markdown).blocks

    private fun rendered(markdown: String): String = MarkdownBlockCodec.render(MarkdownBlockCodec.parse(markdown))

    private fun assertStable(markdown: String) {
        val document = MarkdownBlockCodec.parse(markdown)
        val canonical = MarkdownBlockCodec.render(document)
        assertEquals(
            "parse(render(parse(md))) must equal parse(md) for input: <$markdown>",
            document,
            MarkdownBlockCodec.parse(canonical),
        )
        assertEquals(
            "render(parse(render(parse(md)))) must equal render(parse(md)) for input: <$markdown>",
            canonical,
            MarkdownBlockCodec.render(MarkdownBlockCodec.parse(canonical)),
        )
    }

    // ---- headings ------------------------------------------------------------

    @Test
    fun `parses headings level 1 to 6`() {
        val document = MarkdownBlockCodec.parse(
            "# One\n\n## Two\n\n### Three\n\n#### Four\n\n##### Five\n\n###### Six",
        )
        assertEquals(
            listOf(
                Block.Heading(1, "One"),
                Block.Heading(2, "Two"),
                Block.Heading(3, "Three"),
                Block.Heading(4, "Four"),
                Block.Heading(5, "Five"),
                Block.Heading(6, "Six"),
            ),
            document.blocks,
        )
        assertEquals(
            "# One\n\n## Two\n\n### Three\n\n#### Four\n\n##### Five\n\n###### Six",
            MarkdownBlockCodec.render(document),
        )
    }

    @Test
    fun `seven hashes is a paragraph and hash without space is a paragraph`() {
        assertEquals(listOf(Block.Paragraph("####### nope")), blocks("####### nope"))
        assertEquals(listOf(Block.Paragraph("#nope")), blocks("#nope"))
    }

    @Test
    fun `empty heading round trips`() {
        assertEquals(listOf(Block.Heading(2, "")), blocks("## "))
        assertStable("## ")
    }

    // ---- paragraphs ----------------------------------------------------------

    @Test
    fun `multi-line paragraph preserves inline markdown verbatim`() {
        val source = "Hello **bold** and _italic_ with `code`.\nSecond line stays in the same paragraph."
        val parsed = blocks(source)
        assertEquals(1, parsed.size)
        assertEquals(Block.Paragraph(source.replace("\n", "\n")), parsed[0])
        assertEquals(source, MarkdownBlockCodec.render(MarkdownBlockCodec.parse(source)))
    }

    @Test
    fun `blank lines separate paragraphs and are canonicalised`() {
        val document = MarkdownBlockCodec.parse("first\n\n\n\nsecond")
        assertEquals(listOf(Block.Paragraph("first"), Block.Paragraph("second")), document.blocks)
        assertEquals("first\n\nsecond", MarkdownBlockCodec.render(document))
    }

    // ---- lists ---------------------------------------------------------------

    @Test
    fun `bullets with nesting`() {
        val markdown = "- one\n- two\n  - two a\n  - two b\n- three"
        assertEquals(
            listOf(
                Block.Bullets(
                    listOf(
                        ListItem("one", 0),
                        ListItem("two", 0),
                        ListItem("two a", 1),
                        ListItem("two b", 1),
                        ListItem("three", 0),
                    ),
                ),
            ),
            blocks(markdown),
        )
        assertEquals(markdown, rendered(markdown))
    }

    @Test
    fun `numbered lists are renumbered canonically with nesting`() {
        val document = MarkdownBlockCodec.parse("3. first\n7. second\n   9. nested\n10. third")
        assertEquals(
            listOf(
                Block.Numbered(
                    listOf(
                        ListItem("first", 0),
                        ListItem("second", 0),
                        ListItem("nested", 1),
                        ListItem("third", 0),
                    ),
                ),
            ),
            document.blocks,
        )
        assertEquals("1. first\n2. second\n  1. nested\n3. third", MarkdownBlockCodec.render(document))
        assertStable("3. first\n7. second")
    }

    @Test
    fun `checklists preserved including x case`() {
        val parsed = blocks("- [ ] todo\n- [x] done\n- [X] also done")
        assertEquals(
            listOf(
                Block.Checklist(
                    listOf(
                        CheckItem("todo", false),
                        CheckItem("done", true),
                        CheckItem("also done", true),
                    ),
                ),
            ),
            parsed,
        )
        assertEquals("- [ ] todo\n- [x] done\n- [x] also done", MarkdownBlockCodec.render(BlockDocument(parsed)))
    }

    @Test
    fun `checklist toggle rewrites only that item's markdown`() {
        val markdown = "- [ ] a\n- [ ] b"
        val document = MarkdownBlockCodec.parse(markdown)
        val checklist = document.blocks.single() as Block.Checklist
        val toggled = BlockDocument(
            listOf(Block.Checklist(checklist.items.mapIndexed { index, item -> if (index == 1) item.copy(checked = true) else item })),
        )
        assertEquals("- [ ] a\n- [x] b", MarkdownBlockCodec.render(toggled))
    }

    // ---- code ----------------------------------------------------------------

    @Test
    fun `fenced code keeps language and content byte-identically`() {
        val markdown = "```kotlin\nfun main() {\n    println(\"hi\")\n}\n```"
        assertEquals(listOf(Block.Code("kotlin", "fun main() {\n    println(\"hi\")\n}")), blocks(markdown))
        assertEquals(markdown, rendered(markdown))
    }

    @Test
    fun `code containing fence-like backticks gets a longer fence and round-trips`() {
        val code = "```\ninner fence\n```"
        val document = BlockDocument(listOf(Block.Code(null, code)))
        val output = MarkdownBlockCodec.render(document)
        assertTrue(output.startsWith("````"))
        assertEquals(document, MarkdownBlockCodec.parse(output))
    }

    @Test
    fun `unclosed fence consumes the rest of the document without losing text`() {
        val document = MarkdownBlockCodec.parse("```python\nprint(1)\nprint(2)")
        assertEquals(listOf(Block.Code("python", "print(1)\nprint(2)")), document.blocks)
    }

    @Test
    fun `blank lines inside code are preserved`() {
        val markdown = "```\na\n\nb\n\n```"
        assertEquals(listOf(Block.Code(null, "a\n\nb\n")), blocks(markdown))
        assertEquals(markdown, rendered(markdown))
    }

    // ---- quote / rule / table ------------------------------------------------

    @Test
    fun `block quote multi-line`() {
        assertEquals(listOf(Block.Quote("quoted\nsecond")), blocks("> quoted\n> second"))
        assertEquals("> quoted\n> second", rendered("> quoted\n> second"))
    }

    @Test
    fun `horizontal rule variants canonicalise to dashes`() {
        for (rule in listOf("---", "***", "___", "- - -", "* * *")) {
            assertEquals("rule <$rule>", listOf(Block.Rule), blocks(rule))
            assertEquals(listOf(Block.Rule), blocks(rule))
        }
        assertEquals("---", rendered("***"))
    }

    @Test
    fun `table parses headers rows and escapes pipes`() {
        val markdown = "| Name | Value |\n| --- | --- |\n| a | 1 |\n| b | 2 |"
        val document = MarkdownBlockCodec.parse(markdown)
        assertEquals(
            listOf(
                Block.Table(
                    headers = listOf("Name", "Value"),
                    rows = listOf(listOf("a", "1"), listOf("b", "2")),
                ),
            ),
            document.blocks,
        )
        assertEquals(markdown, MarkdownBlockCodec.render(document))

        val withPipe = BlockDocument(listOf(Block.Table(listOf("a|b"), listOf(listOf("c|d")))))
        val output = MarkdownBlockCodec.render(withPipe)
        assertEquals(withPipe, MarkdownBlockCodec.parse(output))
    }

    @Test
    fun `single pipe line without delimiter stays a paragraph`() {
        assertEquals(listOf(Block.Paragraph("| just text |")), blocks("| just text |"))
    }

    // ---- unknown constructs --------------------------------------------------

    @Test
    fun `unknown constructs degrade to paragraph text`() {
        val html = "<div class=\"x\">hello</div>"
        assertEquals(listOf(Block.Paragraph(html)), blocks(html))

        val indented = "    indented code-ish"
        assertEquals(listOf(Block.Paragraph(indented)), blocks(indented))

        // Setext headings are not supported: the construct stays readable paragraph text.
        val setext = "Title\n==="
        assertEquals(listOf(Block.Paragraph("Title\n===")), blocks(setext))
    }

    @Test
    fun `parse and render never throw on pathological input`() {
        val samples = listOf(
            "",
            "\n\n\n",
            "```",
            "``````",
            "|",
            "|---|",
            "- ",
            "1. ",
            "> ",
            "###### ",
            "\u0000\u0001\u0002",
            "a".repeat(10_000),
            "\\|\\|\\|",
            "🔥 emoji café ünïcode",
        )
        for (sample in samples) {
            MarkdownBlockCodec.parse(sample)
            MarkdownBlockCodec.render(MarkdownBlockCodec.parse(sample))
            assertStable(sample)
        }
    }

    @Test
    fun `randomised markdown corpus keeps the parse-render-parse invariant`() {
        val random = Random(20261115)
        val alphabet = "#>*+-_`| 0123456789abc\n\t\\[]xX.\"'~"
        repeat(300) {
            val length = random.nextInt(0, 120)
            val input = buildString(length) {
                repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            assertStable(input)
        }
    }

    @Test
    fun `empty document renders empty`() {
        assertEquals("", MarkdownBlockCodec.render(MarkdownBlockCodec.parse("")))
        assertEquals(BlockDocument(emptyList()), MarkdownBlockCodec.parse(""))
    }

    // ---- mixed document ------------------------------------------------------

    @Test
    fun `mixed document round trips byte-identically through parse and render`() {
        val markdown = buildString {
            appendLine("# Project plan")
            appendLine()
            appendLine("Intro paragraph with **bold**, a [link](https://example.invalid) and `code`.")
            appendLine()
            appendLine("## Tasks")
            appendLine()
            appendLine("- [ ] write codec")
            appendLine("- [x] write tests")
            appendLine()
            appendLine("1. first")
            appendLine("2. second")
            appendLine()
            appendLine("> a quote")
            appendLine()
            appendLine("```json")
            appendLine("{\"a\": 1}")
            appendLine("```")
            appendLine()
            appendLine("| a | b |")
            appendLine("| --- | --- |")
            appendLine("| 1 | 2 |")
            appendLine()
            appendLine("---")
        }.trimEnd('\n')
        val document = MarkdownBlockCodec.parse(markdown)
        assertEquals(
            listOf(
                Block.Heading(1, "Project plan"),
                Block.Paragraph("Intro paragraph with **bold**, a [link](https://example.invalid) and `code`."),
                Block.Heading(2, "Tasks"),
                Block.Checklist(listOf(CheckItem("write codec", false), CheckItem("write tests", true))),
                Block.Numbered(listOf(ListItem("first", 0), ListItem("second", 0))),
                Block.Quote("a quote"),
                Block.Code("json", "{\"a\": 1}"),
                Block.Table(listOf("a", "b"), listOf(listOf("1", "2"))),
                Block.Rule,
            ),
            document.blocks,
        )
        assertEquals(markdown, MarkdownBlockCodec.render(document))
        assertStable(markdown)
    }

    @Test
    fun `crlf input parses like lf and renders canonically with lf`() {
        val crlf = "# Title\r\n\r\ntext\r\n"
        assertEquals(MarkdownBlockCodec.parse("# Title\n\ntext\n"), MarkdownBlockCodec.parse(crlf))
        assertEquals("# Title\n\ntext", MarkdownBlockCodec.render(MarkdownBlockCodec.parse(crlf)))
    }

    @Test
    fun `convenience helpers agree with the object`() {
        val markdown = "# a\n\n- b"
        assertEquals(MarkdownBlockCodec.parse(markdown), parseMarkdown(markdown))
        assertEquals(MarkdownBlockCodec.render(MarkdownBlockCodec.parse(markdown)), parseMarkdown(markdown).toMarkdown())
        assertFalse(parseMarkdown(markdown).blocks.isEmpty())
    }
}
