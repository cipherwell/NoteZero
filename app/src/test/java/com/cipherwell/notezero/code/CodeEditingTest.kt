package com.cipherwell.notezero.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEditingTest {

    // ------------------------------------------------------------------ indentUnit

    @Test
    fun indentUnitRespectsTabsSpacesAndClampsWidth() {
        assertEquals("\t", CodeEditing.indentUnit(CodeLanguage.KOTLIN, useTabs = true, tabWidth = 4))
        assertEquals("    ", CodeEditing.indentUnit(CodeLanguage.KOTLIN, useTabs = false, tabWidth = 4))
        assertEquals("  ", CodeEditing.indentUnit(CodeLanguage.PYTHON, useTabs = false, tabWidth = 2))
        // Width is clamped to 1..16 so a hostile preference cannot allocate huge strings.
        assertEquals(" ", CodeEditing.indentUnit(CodeLanguage.JAVA, useTabs = false, tabWidth = 0))
        assertEquals(" ".repeat(16), CodeEditing.indentUnit(CodeLanguage.JAVA, useTabs = false, tabWidth = 500))
    }

    // ------------------------------------------------------------------ autoIndent

    @Test
    fun autoIndentAfterOpeningBraces() {
        assertEquals("    ", CodeEditing.autoIndent("fun f() {", 9, CodeLanguage.KOTLIN, false, 4))
        assertEquals("    ", CodeEditing.autoIndent("if (x) {", 8, CodeLanguage.KOTLIN, false, 4))
        assertEquals("    ", CodeEditing.autoIndent("foo(", 4, CodeLanguage.KOTLIN, false, 4))
        assertEquals("    ", CodeEditing.autoIndent("val a = [", 9, CodeLanguage.KOTLIN, false, 4))
        assertEquals("    ", CodeEditing.autoIndent("function f() {", 14, CodeLanguage.JAVASCRIPT, false, 4))
    }

    @Test
    fun autoIndentPreservesCurrentLineIndent() {
        assertEquals(
            "        ",
            CodeEditing.autoIndent("fun f() {\n    if (x) {", 22, CodeLanguage.KOTLIN, false, 4),
        )
        assertEquals(
            "    ",
            CodeEditing.autoIndent("  if (x) {", 10, CodeLanguage.KOTLIN, false, 2),
        )
    }

    @Test
    fun autoIndentOnBlankLineDoesNotAccumulateIndent() {
        assertEquals("", CodeEditing.autoIndent("    ", 4, CodeLanguage.KOTLIN, false, 4))
        assertEquals("", CodeEditing.autoIndent("", 0, CodeLanguage.KOTLIN, false, 4))
        assertEquals("", CodeEditing.autoIndent("        \n    ", 13, CodeLanguage.PYTHON, false, 4))
    }

    @Test
    fun autoIndentIgnoresNonTriggers() {
        assertEquals("", CodeEditing.autoIndent("val x = 1", 9, CodeLanguage.KOTLIN, false, 4))
        assertEquals("", CodeEditing.autoIndent("return x", 8, CodeLanguage.KOTLIN, false, 4))
        // ':' only triggers for Python suites.
        assertEquals("", CodeEditing.autoIndent("label:", 6, CodeLanguage.KOTLIN, false, 4))
        assertEquals("    ", CodeEditing.autoIndent("def f():", 8, CodeLanguage.PYTHON, false, 4))
    }

    @Test
    fun autoIndentAfterTrailingWhitespaceStillSeesTheTrigger() {
        assertEquals("    ", CodeEditing.autoIndent("fun f() {  ", 11, CodeLanguage.KOTLIN, false, 4))
    }

    @Test
    fun autoIndentUsesTabsWhenConfigured() {
        assertEquals("\t", CodeEditing.autoIndent("fun f() {", 9, CodeLanguage.KOTLIN, true, 4))
        assertEquals("\t\t", CodeEditing.autoIndent("\tfun f() {", 10, CodeLanguage.KOTLIN, true, 4))
    }

    @Test
    fun autoIndentXmlOpeningTags() {
        assertEquals("    ", CodeEditing.autoIndent("<div>", 5, CodeLanguage.XML, false, 4))
        assertEquals("    ", CodeEditing.autoIndent("<div class=\"a\">", 15, CodeLanguage.XML, false, 4))
        assertEquals("    ", CodeEditing.autoIndent("<ns:tag>", 8, CodeLanguage.XML, false, 4))
        assertEquals("", CodeEditing.autoIndent("</div>", 6, CodeLanguage.XML, false, 4))
        assertEquals("", CodeEditing.autoIndent("<br/>", 5, CodeLanguage.XML, false, 4))
        assertEquals("", CodeEditing.autoIndent("<!-- c -->", 10, CodeLanguage.XML, false, 4))
        assertEquals("", CodeEditing.autoIndent("<?xml version=\"1.0\"?>", 21, CodeLanguage.XML, false, 4))
        assertEquals("", CodeEditing.autoIndent("<div>text", 10, CodeLanguage.XML, false, 4))
        assertEquals("      ", CodeEditing.autoIndent("  <p>", 5, CodeLanguage.XML, false, 4))
    }

    @Test
    fun autoIndentClampsCursor() {
        assertEquals("    ", CodeEditing.autoIndent("fun f() {", 500, CodeLanguage.KOTLIN, false, 4))
        assertEquals("", CodeEditing.autoIndent("fun f() {", -5, CodeLanguage.KOTLIN, false, 4))
    }

    // ------------------------------------------------------------------ indentSelection

    @Test
    fun indentSelectionIndentsAllTouchedLinesAndMapsTheSelection() {
        val text = "a\nb\nc"
        val (newText, selection) =
            CodeEditing.indentSelection(text, TextRange(0, 3), outdent = false, useTabs = false, tabWidth = 4)
        assertEquals("    a\n    b\nc", newText)
        assertEquals(TextRange(4, 11), selection)
    }

    @Test
    fun indentSelectionSkipsALineOnlyTouchedAtItsStart() {
        val text = "a\nb\nc"
        val (newText, selection) =
            CodeEditing.indentSelection(text, TextRange(0, 2), outdent = false, useTabs = false, tabWidth = 4)
        assertEquals("    a\nb\nc", newText)
        assertEquals(TextRange(4, 6), selection)
    }

    @Test
    fun indentSelectionIndentsBlankLines() {
        val text = "a\n\nb"
        val (newText, _) =
            CodeEditing.indentSelection(text, TextRange(0, 4), outdent = false, useTabs = false, tabWidth = 4)
        assertEquals("    a\n    \n    b", newText)
    }

    @Test
    fun outdentSelectionRemovesOneIndentLevelPerLine() {
        val text = "        a\n        b"
        val (newText, selection) =
            CodeEditing.indentSelection(text, TextRange(0, text.length), outdent = true, useTabs = false, tabWidth = 4)
        assertEquals("    a\n    b", newText)
        assertEquals(TextRange(0, 11), selection)
    }

    @Test
    fun outdentSelectionHandlesTabsAndPartialSpaceIndent() {
        val tabbed = "\ta\n\tb"
        val (tabbedText, _) =
            CodeEditing.indentSelection(tabbed, TextRange(0, tabbed.length), outdent = true, useTabs = true, tabWidth = 4)
        assertEquals("a\nb", tabbedText)

        val partial = "  a"
        val (partialText, _) =
            CodeEditing.indentSelection(partial, TextRange(0, partial.length), outdent = true, useTabs = false, tabWidth = 4)
        assertEquals("a", partialText)
    }

    @Test
    fun outdentSelectionClampsACursorInsideRemovedWhitespace() {
        val text = "    abc"
        val (newText, selection) =
            CodeEditing.indentSelection(text, TextRange(2, 2), outdent = true, useTabs = false, tabWidth = 4)
        assertEquals("abc", newText)
        assertEquals(TextRange(0, 0), selection)
    }

    @Test
    fun indentSelectionNormalizesReversedSelections() {
        val text = "a\nb\nc"
        val reversed = CodeEditing.indentSelection(text, TextRange(3, 0), false, false, 4)
        val ordered = CodeEditing.indentSelection(text, TextRange(0, 3), false, false, 4)
        assertEquals(ordered, reversed)
    }

    @Test
    fun indentSelectionClampsOutOfRangeSelections() {
        val (newText, selection) =
            CodeEditing.indentSelection("abc", TextRange(-5, 99), outdent = false, useTabs = false, tabWidth = 4)
        assertEquals("    abc", newText)
        assertEquals(TextRange(4, 7), selection)
    }

    @Test
    fun indentSelectionPreservesCrlfLineEndings() {
        val text = "a\r\nb\r\n"
        val (newText, selection) =
            CodeEditing.indentSelection(text, TextRange(0, text.length), outdent = false, useTabs = false, tabWidth = 4)
        assertEquals("    a\r\n    b\r\n", newText)
        assertEquals(TextRange(4, 14), selection)
    }

    @Test
    fun indentSelectionOnEmptySelectionIndentsTheCursorsLine() {
        val (newText, selection) =
            CodeEditing.indentSelection("x", TextRange(0, 0), outdent = false, useTabs = false, tabWidth = 4)
        assertEquals("    x", newText)
        assertEquals(TextRange(4, 4), selection)
    }

    @Test
    fun outdentSelectionLeavesUnindentedLinesAlone() {
        val text = "a\n    b"
        val (newText, _) =
            CodeEditing.indentSelection(text, TextRange(0, text.length), outdent = true, useTabs = false, tabWidth = 4)
        assertEquals("a\nb", newText)
    }

    // ------------------------------------------------------------------ matchingBracket

    @Test
    fun matchingBracketHandlesNestingAndCursorPositions() {
        val text = "{[()]}"
        assertEquals(5, CodeEditing.matchingBracket(text, 0))
        assertEquals(4, CodeEditing.matchingBracket(text, 1))
        assertEquals(3, CodeEditing.matchingBracket(text, 2))
        assertEquals(1, CodeEditing.matchingBracket(text, 4))
        assertEquals(0, CodeEditing.matchingBracket(text, 5))
        assertEquals(0, CodeEditing.matchingBracket(text, 6)) // cursor after the last bracket
    }

    @Test
    fun matchingBracketForDeeplyNestedSameKind() {
        val text = "(((())))"
        assertEquals(7, CodeEditing.matchingBracket(text, 0))
        assertEquals(6, CodeEditing.matchingBracket(text, 1))
        assertEquals(5, CodeEditing.matchingBracket(text, 2))
        assertEquals(4, CodeEditing.matchingBracket(text, 3))
    }

    @Test
    fun matchingBracketIgnoresBracketsInsideStrings() {
        val text = "f(\"(\", g)"
        assertEquals(8, CodeEditing.matchingBracket(text, 1))
        assertNull(CodeEditing.matchingBracket(text, 3)) // '(' inside the string literal
    }

    @Test
    fun matchingBracketIgnoresBracketsInsideComments() {
        val blockComment = "f(a /* ) */ b)"
        assertEquals(13, CodeEditing.matchingBracket(blockComment, 1))

        val lineComment = "a // )\n(b)"
        assertEquals(9, CodeEditing.matchingBracket(lineComment, 7))
    }

    @Test
    fun matchingBracketIgnoresQuotedBracketCharacters() {
        val text = "( ')' )"
        assertEquals(6, CodeEditing.matchingBracket(text, 0))
    }

    @Test
    fun matchingBracketReturnsNullWhenThereIsNoBracket() {
        assertNull(CodeEditing.matchingBracket("abc", 1))
        assertNull(CodeEditing.matchingBracket("", 0))
            }

    @Test
    fun matchingBracketReturnsNullWhenUnclosed() {
        assertNull(CodeEditing.matchingBracket("f(a", 1))
        assertNull(CodeEditing.matchingBracket("a)", 1))
    }

    @Test
    fun matchingBracketClampsOutOfRangeOffsets() {
        assertEquals(1, CodeEditing.matchingBracket("()", -5))
        assertEquals(0, CodeEditing.matchingBracket("()", 99))
    }

    // ------------------------------------------------------------------ findMatches

    @Test
    fun findMatchesCaseSensitiveAndInsensitive() {
        val text = "Foo foo FOO"
        assertEquals(
            listOf(TextRange(4, 7)),
            CodeEditing.findMatches(text, "foo", caseSensitive = true),
        )
        assertEquals(
            listOf(TextRange(0, 3), TextRange(4, 7), TextRange(8, 11)),
            CodeEditing.findMatches(text, "foo", caseSensitive = false),
        )
    }

    @Test
    fun findMatchesIsNonOverlappingAndRespectsLimit() {
        assertEquals(
            listOf(TextRange(0, 2), TextRange(2, 4)),
            CodeEditing.findMatches("aaaa", "aa", caseSensitive = true),
        )
        assertEquals(
            listOf(TextRange(0, 1), TextRange(1, 2)),
            CodeEditing.findMatches("aaaaa", "a", caseSensitive = true, limit = 2),
        )
        assertEquals(
            emptyList<TextRange>(),
            CodeEditing.findMatches("aaaaa", "a", caseSensitive = true, limit = 0),
        )
    }

    @Test
    fun findMatchesEmptyOrTooLongQueryReturnsEmpty() {
        assertTrue(CodeEditing.findMatches("abc", "", caseSensitive = true).isEmpty())
        assertTrue(CodeEditing.findMatches("abc", "abcd", caseSensitive = true).isEmpty())
        assertTrue(CodeEditing.findMatches("", "a", caseSensitive = true).isEmpty())
    }

    @Test
    fun findMatchesHandlesUnicodeCaseFolding() {
        assertEquals(
            listOf(TextRange(0, 5), TextRange(6, 11)),
            CodeEditing.findMatches("héllo hÉllo", "hÉllo", caseSensitive = false),
        )
        assertEquals(
            listOf(TextRange(6, 11)),
            CodeEditing.findMatches("héllo hÉllo", "hÉllo", caseSensitive = true),
        )
    }

    // ------------------------------------------------------------------ replaceAll

    @Test
    fun replaceAllIsLiteralAndDoesNotInterpretRegexOrReplacementGroups() {
        assertEquals("a\$1b", CodeEditing.replaceAll("a.b", ".", "\$1", caseSensitive = true))
        assertEquals("x\\1x", CodeEditing.replaceAll("x\$1x", "\$1", "\\1", caseSensitive = true))
        // A regex-looking query must not match like a regex.
        assertEquals("axb", CodeEditing.replaceAll("axb", "a.b", "Z", caseSensitive = true))
        assertEquals("Z", CodeEditing.replaceAll("a.b", "a.b", "Z", caseSensitive = true))
    }

    @Test
    fun replaceAllCaseModesAndEmptyQuery() {
        assertEquals("x x", CodeEditing.replaceAll("Foo FOO", "foo", "x", caseSensitive = false))
        assertEquals("Foo FOO", CodeEditing.replaceAll("Foo FOO", "foo", "x", caseSensitive = true))
        assertEquals("unchanged", CodeEditing.replaceAll("unchanged", "", "x", caseSensitive = true))
    }

    @Test
    fun replaceAllHandlesAdjacentAndRepeatedMatches() {
        assertEquals("b", CodeEditing.replaceAll("a", "a", "b", caseSensitive = true))
        assertEquals("bb", CodeEditing.replaceAll("aa", "a", "b", caseSensitive = true))
    }

    // ------------------------------------------------------------------ offsetForLine

    @Test
    fun offsetForLineClampsAndSupportsCrlf() {
        val text = "a\nb\nc"
        assertEquals(0, CodeEditing.offsetForLine(text, 0))
        assertEquals(2, CodeEditing.offsetForLine(text, 1))
        assertEquals(4, CodeEditing.offsetForLine(text, 2))
        assertEquals(0, CodeEditing.offsetForLine(text, -3))
        assertEquals(4, CodeEditing.offsetForLine(text, 99))

        val crlf = "a\r\nb"
        assertEquals(0, CodeEditing.offsetForLine(crlf, 0))
        assertEquals(3, CodeEditing.offsetForLine(crlf, 1))
    }

    // ------------------------------------------------------------------ TextRange

    @Test
    fun textRangeHelpersNormalizeAndClamp() {
        assertEquals(TextRange(1, 4), TextRange(4, 1).normalized())
        assertEquals(3, TextRange(1, 4).length)
        assertTrue(TextRange(2, 2).isEmpty())
        assertEquals(TextRange(0, 5), TextRange(-2, 5).clampedTo(10))
        assertEquals(TextRange(3, 10), TextRange(3, 50).clampedTo(10))
        assertTrue(TextRange(1, 4).containsInside(2))
        assertTrue(!TextRange(1, 4).containsInside(1))
        assertTrue(!TextRange(1, 4).containsInside(4))
    }
}
