package com.cipherwell.notezero.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineIndexTest {

    @Test
    fun emptyTextHasOneEmptyLine() {
        val index = LineIndex.of("")
        assertEquals(1, index.lineCount)
        assertEquals(0, index.textLength)
        assertEquals(0, index.lineOf(0))
        assertEquals(0, index.lineOf(-3))
        assertEquals(0, index.lineOf(10))
        assertEquals(0, index.startOfLine(0))
        assertEquals(0, index.startOfLine(5))
        assertEquals(0, index.startOfLine(-5))
        assertEquals(0, index.endOfLine("", 0))
        assertEquals(0, index.endOfLine("", 99))
    }

    @Test
    fun singleLineWithoutTrailingNewline() {
        val text = "abc"
        val index = LineIndex.of(text)
        assertEquals(1, index.lineCount)
        assertEquals(0, index.lineOf(0))
        assertEquals(0, index.lineOf(2))
        assertEquals(0, index.lineOf(3))
        assertEquals(0, index.startOfLine(0))
        assertEquals(3, index.endOfLine(text, 0))
        assertEquals(3, index.endOfLine(text, 7))
    }

    @Test
    fun trailingNewlineAddsAnEmptyFinalLine() {
        val text = "a\n"
        val index = LineIndex.of(text)
        assertEquals(2, index.lineCount)
        assertEquals(0, index.lineOf(0))
        assertEquals(0, index.lineOf(1)) // the '\n' belongs to line 0
        assertEquals(1, index.lineOf(2)) // cursor after the newline is on the empty last line
        assertEquals(0, index.startOfLine(0))
        assertEquals(2, index.startOfLine(1))
        assertEquals(1, index.endOfLine(text, 0))
        assertEquals(2, index.endOfLine(text, 1))
    }

    @Test
    fun multipleLinesAndClamping() {
        val text = "a\nb"
        val index = LineIndex.of(text)
        assertEquals(2, index.lineCount)
        assertEquals(0, index.lineOf(0))
        assertEquals(0, index.lineOf(1))
        assertEquals(1, index.lineOf(2))
        assertEquals(1, index.lineOf(3))
        assertEquals(0, index.startOfLine(-4))
        assertEquals(2, index.startOfLine(1))
        assertEquals(2, index.startOfLine(42))
        assertEquals(3, index.endOfLine(text, 1))
        assertEquals(3, index.endOfLine(text, 42))
    }

    @Test
    fun crlfCountsAsASingleBreakAndExcludesTheTerminator() {
        val text = "one\r\ntwo\r\n"
        val index = LineIndex.of(text)
        assertEquals(3, index.lineCount)
        assertEquals(0, index.lineOf(3)) // '\r'
        assertEquals(0, index.lineOf(4)) // '\n'
        assertEquals(1, index.lineOf(5))
        assertEquals(1, index.lineOf(8))
        assertEquals(2, index.lineOf(10))
        assertEquals(0, index.startOfLine(0))
        assertEquals(5, index.startOfLine(1))
        assertEquals(10, index.startOfLine(2))
        assertEquals(3, index.endOfLine(text, 0))
        assertEquals(8, index.endOfLine(text, 1))
        assertEquals(10, index.endOfLine(text, 2))
    }

    @Test
    fun loneCarriageReturnIsNotALineBreak() {
        val text = "a\rb"
        val index = LineIndex.of(text)
        assertEquals(1, index.lineCount)
        assertEquals(0, index.lineOf(2))
        assertEquals(3, index.endOfLine(text, 0))
    }

    @Test
    fun endOfLineToleratesAMismatchedTextArgument() {
        val index = LineIndex.of("a\nb")
        assertEquals(1, index.endOfLine("a", 0))
        assertEquals(1, index.endOfLine("a\nbbbb", 0))
    }

    @Test
    fun lineOfEveryOffsetPointsToAContainingLine() {
        val text = "one\ntwo\r\nthree\n"
        val index = LineIndex.of(text)
        for (offset in 0..text.length) {
            val line = index.lineOf(offset)
            assertTrue("offset $offset must not precede line $line", offset >= index.startOfLine(line))
            if (line + 1 < index.lineCount) {
                assertTrue(
                    "offset $offset must precede the next line start",
                    offset < index.startOfLine(line + 1),
                )
            }
            assertEquals(line, index.lineOf(index.startOfLine(line)))
        }
    }
}
