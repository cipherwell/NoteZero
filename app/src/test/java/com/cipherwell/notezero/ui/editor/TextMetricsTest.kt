package com.cipherwell.notezero.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TextMetricsTest {

    @Test
    fun `word count handles whitespace runs and empty input`() {
        assertEquals(0, TextMetrics.wordCount(""))
        assertEquals(0, TextMetrics.wordCount("   \n\t "))
        assertEquals(1, TextMetrics.wordCount("word"))
        assertEquals(3, TextMetrics.wordCount("one two three"))
        assertEquals(3, TextMetrics.wordCount("  one   two\n\nthree  "))
    }

    @Test
    fun `character count includes whitespace`() {
        assertEquals(0, TextMetrics.characterCount(""))
        assertEquals(13, TextMetrics.characterCount("one two three"))
        assertEquals(11, TextMetrics.characterCountNoSpaces("one two three"))
    }
}
