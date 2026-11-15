package com.cipherwell.notezero.code

import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the workspace-wide code search. Every assertion checks a concrete
 * line/column/snippet or a bounded count — no "does not throw" only tests except where the
 * contract is literally "never throws", and even there the returned hits are inspected.
 */
class CodeSearchTest {

    private fun codeFile(
        id: String = "f1",
        title: String = "Main.kt",
        body: String = "",
        trashed: Boolean = false,
    ): Item = Item(
        id = id,
        kind = ItemKind.CODE_FILE,
        title = title,
        body = body,
        trashed = trashed,
    )

    private fun note(id: String = "n1", title: String = "Note", body: String = ""): Item = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = body,
    )

    @Test
    fun `body hit reports one-based line column and the trimmed line as snippet`() {
        val file = codeFile(body = "fun main() {\n    val secret = 42\n}")
        val matches = CodeSearch.search(listOf(file), "secret", caseSensitive = true)

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("f1", match.itemId)
        assertEquals("Main.kt", match.fileName)
        assertEquals(2, match.line)
        assertEquals(9, match.column)
        assertEquals("val secret = 42", match.snippet)
    }

    @Test
    fun `name hit is reported on line one with the column inside the name`() {
        val file = codeFile(title = "SearchTarget.kt", body = "nothing here")
        val matches = CodeSearch.search(listOf(file), "target", caseSensitive = false, limit = 1)

        assertEquals(1, matches.size)
        assertEquals(1, matches.single().line)
        assertEquals(7, matches.single().column)
        assertEquals("SearchTarget.kt", matches.single().snippet)
    }

    @Test
    fun `name hit comes before that file's body hits`() {
        val file = codeFile(title = "secret.kt", body = "secret = 1")
        val matches = CodeSearch.search(listOf(file), "secret", caseSensitive = true)

        assertEquals(2, matches.size)
        assertEquals("secret.kt", matches[0].snippet)
        assertEquals(1, matches[0].line)
        assertEquals(1, matches[0].column)
        assertEquals("secret = 1", matches[1].snippet)
        assertEquals(1, matches[1].column)
    }

    @Test
    fun `multi-line body yields ascending hits with the right line and column each`() {
        val body = "target one\nmiddle\ntrailing target"
        val matches = CodeSearch.search(listOf(codeFile(body = body)), "target", caseSensitive = true)

        assertEquals(2, matches.size)
        assertEquals(listOf(1, 3), matches.map { it.line })
        assertEquals(listOf(1, 10), matches.map { it.column })
        assertEquals("target one", matches[0].snippet)
        assertEquals("trailing target", matches[1].snippet)
    }

    @Test
    fun `case toggle changes the hit count`() {
        val file = codeFile(body = "Secret\nsecret")

        val insensitive = CodeSearch.search(listOf(file), "Secret", caseSensitive = false)
        assertEquals(2, insensitive.size)

        val sensitive = CodeSearch.search(listOf(file), "Secret", caseSensitive = true)
        assertEquals(1, sensitive.size)
        assertEquals(1, sensitive.single().line)
        assertEquals(listOf(1, 2), insensitive.map { it.line })
    }

    @Test
    fun `limit bounds results across files and a non-positive limit is empty`() {
        val files = (1..10).map { index ->
            codeFile(id = "f$index", title = "File$index.kt", body = "hit\nhit\nhit")
        }

        assertEquals(12, CodeSearch.search(files, "hit", caseSensitive = true, limit = 12).size)
        assertEquals(1, CodeSearch.search(files, "hit", caseSensitive = true, limit = 1).size)
        assertTrue(CodeSearch.search(files, "hit", caseSensitive = true, limit = 0).isEmpty())
        assertTrue(CodeSearch.search(files, "hit", caseSensitive = true, limit = -5).isEmpty())
    }

    @Test
    fun `trashed code files and notes are never searched`() {
        val trashed = codeFile(id = "t1", title = "Gone.kt", body = "target", trashed = true)
        val noteItem = note(id = "n1", title = "target note", body = "target in the note")
        val live = codeFile(id = "f1", title = "Live.kt", body = "target here")

        val matches = CodeSearch.search(listOf(trashed, noteItem, live), "target", false)
        assertEquals(1, matches.size)
        assertEquals("f1", matches.single().itemId)
        assertEquals("Live.kt", matches.single().fileName)
    }

    @Test
    fun `empty query and a query longer than the text return nothing`() {
        val file = codeFile(body = "abc")
        assertTrue(CodeSearch.search(listOf(file), "", caseSensitive = true).isEmpty())
        assertTrue(CodeSearch.search(listOf(file), "abcdef", caseSensitive = true).isEmpty())
        assertTrue(CodeSearch.search(emptyList(), "a", caseSensitive = true).isEmpty())
    }

    @Test
    fun `crlf lines keep the content line number and a clean snippet`() {
        val body = "first\r\nsecond target\r\nthird"
        val matches = CodeSearch.search(listOf(codeFile(body = body)), "target", caseSensitive = true)

        assertEquals(1, matches.size)
        assertEquals(2, matches.single().line)
        assertEquals(8, matches.single().column)
        assertEquals("second target", matches.single().snippet)
        assertFalse(matches.single().snippet.contains('\r'))
        assertFalse(matches.single().snippet.contains('\n'))
    }

    @Test
    fun `trailing newline and blank lines do not shift the reported line`() {
        val body = "one\n\ntarget\n"
        val matches = CodeSearch.search(listOf(codeFile(body = body)), "target", true)

        assertEquals(1, matches.size)
        assertEquals(3, matches.single().line)
        assertEquals(1, matches.single().column)
        assertEquals("target", matches.single().snippet)
    }

    @Test
    fun `a very long line is windowed around the hit and stays bounded`() {
        val body = "a".repeat(1_000) + "needle" + "b".repeat(1_000)
        val matches = CodeSearch.search(listOf(codeFile(body = body)), "needle", true)

        assertEquals(1, matches.size)
        val snippet = matches.single().snippet
        assertTrue("snippet must contain the hit", snippet.contains("needle"))
        assertTrue(
            "snippet must stay bounded, was ${snippet.length}",
            snippet.length <= CodeSearch.MAX_SNIPPET_CHARS + 2,
        )
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `weird input never throws and every hit stays inside the reported line`() {
        val weird = listOf(
            codeFile(id = "f1", title = "\u0000weird\uD800.kt", body = "\u0000\u0000target\u0000"),
            codeFile(id = "f2", title = "emoji\uD83D\uDE00.kt", body = "😀 target 😀\n\uD800 target"),
            codeFile(id = "f3", title = "crlf.kt", body = "a\r\rb target\r\n\r\n"),
            codeFile(id = "f4", title = "empty.kt", body = ""),
            codeFile(id = "f5", title = "", body = "target"),
        )
        val queries = listOf("target", "\n", "\uD800", "😀", " ", "\u0000", "\r")

        queries.forEach { query ->
            listOf(true, false).forEach { caseSensitive ->
                val matches = CodeSearch.search(weird, query, caseSensitive)
                matches.forEach { match ->
                    assertTrue("line >= 1 for query=$query", match.line >= 1)
                    assertTrue("column >= 1 for query=$query", match.column >= 1)
                    assertFalse("snippet is one line", match.snippet.contains('\n'))
                }
            }
        }
    }

    @Test
    fun `tabs count as one column unit like the editor caret`() {
        val body = "\t\tval target = 1"
        val matches = CodeSearch.search(listOf(codeFile(body = body)), "target", true)

        assertEquals(1, matches.size)
        assertEquals(7, matches.single().column)
        assertEquals("val target = 1", matches.single().snippet)
    }
}
