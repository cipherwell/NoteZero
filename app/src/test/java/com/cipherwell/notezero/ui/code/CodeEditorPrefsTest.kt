package com.cipherwell.notezero.ui.code

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the non-secret editor preferences: font size and wrap are the
 * only persisted values, they round-trip and font size is clamped to 12–24 sp.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CodeEditorPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CodeEditorPrefs.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `defaults are 14sp with wrap on`() {
        val prefs = CodeEditorPrefs(context)
        assertEquals(14, prefs.fontSizeSp)
        assertTrue(prefs.wordWrap)
    }

    @Test
    fun `font size and wrap round-trip through shared preferences`() {
        val prefs = CodeEditorPrefs(context)
        prefs.fontSizeSp = 20
        prefs.wordWrap = false

        val reopened = CodeEditorPrefs(context)
        assertEquals(20, reopened.fontSizeSp)
        assertFalse(reopened.wordWrap)
    }

    @Test
    fun `font size is clamped to the 12 to 24 sp range`() {
        val prefs = CodeEditorPrefs(context)

        prefs.fontSizeSp = 4
        assertEquals(CodeEditorPrefs.MIN_FONT_SIZE_SP, prefs.fontSizeSp)

        prefs.fontSizeSp = 99
        assertEquals(CodeEditorPrefs.MAX_FONT_SIZE_SP, prefs.fontSizeSp)
    }

    @Test
    fun `only non-secret editor keys are persisted`() {
        val prefs = CodeEditorPrefs(context)
        prefs.fontSizeSp = 18
        prefs.wordWrap = false

        val stored = context.getSharedPreferences(CodeEditorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        assertEquals(setOf(CodeEditorPrefs.KEY_FONT_SIZE, CodeEditorPrefs.KEY_WORD_WRAP), stored.all.keys)
    }

    @Test
    fun `in-memory preferences clamp like the persisted ones`() {
        val prefs = InMemoryCodeEditorPreferences(fontSizeSp = 2, wordWrap = true)
        assertEquals(CodeEditorPrefs.MIN_FONT_SIZE_SP, prefs.fontSizeSp)
        prefs.fontSizeSp = 40
        assertEquals(CodeEditorPrefs.MAX_FONT_SIZE_SP, prefs.fontSizeSp)
    }
}
