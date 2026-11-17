package com.cipherwell.notezero.ui.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Non-secret reader appearance persistence: defaults, clamping and round trip. The
 * preferences file must never contain book content, so only the four presentation values
 * are exercised here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReaderPrefsTest {

    private fun store(): ReaderPrefs =
        ReaderPrefs(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `defaults are paper theme, scale one, line height one point five and 24dp margins`() {
        assertEquals(ReaderPreferences(), store().load())
    }

    @Test
    fun `preferences round trip through shared preferences`() {
        val store = store()
        val expected = ReaderPreferences(
            fontScale = 1.3f,
            lineHeightMultiplier = 1.9f,
            marginDp = 40,
            theme = ReaderTheme.NIGHT,
        )
        store.save(expected)
        assertEquals(expected, store.load())
    }

    @Test
    fun `out of range persisted values are clamped on write and read`() {
        val store = store()
        store.save(
            ReaderPreferences(
                fontScale = 9.0f,
                lineHeightMultiplier = -3f,
                marginDp = 500,
                theme = ReaderTheme.SEPIA,
            ),
        )
        val loaded = store.load()
        assertEquals(ReaderPreferences.MAX_FONT_SCALE, loaded.fontScale)
        assertEquals(ReaderPreferences.MIN_LINE_HEIGHT_MULTIPLIER, loaded.lineHeightMultiplier)
        assertEquals(ReaderPreferences.MAX_MARGIN_DP, loaded.marginDp)
        assertEquals(ReaderTheme.SEPIA, loaded.theme)
    }

    @Test
    fun `non-finite values fall back to the defaults`() {
        val clamped = ReaderPreferences(
            fontScale = Float.NaN,
            lineHeightMultiplier = Float.POSITIVE_INFINITY,
            marginDp = 24,
        ).clamped()
        assertEquals(ReaderPreferences.DEFAULT_FONT_SCALE, clamped.fontScale)
        assertEquals(ReaderPreferences.DEFAULT_LINE_HEIGHT_MULTIPLIER, clamped.lineHeightMultiplier)
    }

    @Test
    fun `step helpers respect their bounds`() {
        var preferences = ReaderPreferences(fontScale = ReaderPreferences.MAX_FONT_SCALE)
        repeat(5) { preferences = preferences.withLargerFont() }
        assertEquals(ReaderPreferences.MAX_FONT_SCALE, preferences.fontScale)

        preferences = ReaderPreferences(fontScale = ReaderPreferences.MIN_FONT_SCALE)
        repeat(5) { preferences = preferences.withSmallerFont() }
        assertEquals(ReaderPreferences.MIN_FONT_SCALE, preferences.fontScale)
    }
}
