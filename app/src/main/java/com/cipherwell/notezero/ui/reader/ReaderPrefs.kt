package com.cipherwell.notezero.ui.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable

/** Reading theme applied to the book content (paper/sepia/night). */
enum class ReaderTheme { PAPER, SEPIA, NIGHT }

/**
 * Non-secret reader appearance preferences.
 *
 * Everything here is presentation-only: no book title, author, position or content is
 * ever persisted outside the encrypted item envelope. The values are clamped on both
 * write and read so a corrupted preferences file cannot produce an unreadable layout.
 */
@Immutable
data class ReaderPreferences(
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val lineHeightMultiplier: Float = DEFAULT_LINE_HEIGHT_MULTIPLIER,
    val marginDp: Int = DEFAULT_MARGIN_DP,
    val theme: ReaderTheme = ReaderTheme.PAPER,
) {
    /** Returns a copy with every value inside the supported bounds (never throws). */
    fun clamped(): ReaderPreferences = copy(
        fontScale = if (fontScale.isFinite()) {
            fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        } else {
            DEFAULT_FONT_SCALE
        },
        lineHeightMultiplier = if (lineHeightMultiplier.isFinite()) {
            lineHeightMultiplier.coerceIn(MIN_LINE_HEIGHT_MULTIPLIER, MAX_LINE_HEIGHT_MULTIPLIER)
        } else {
            DEFAULT_LINE_HEIGHT_MULTIPLIER
        },
        marginDp = marginDp.coerceIn(MIN_MARGIN_DP, MAX_MARGIN_DP),
    )

    fun withLargerFont(): ReaderPreferences = copy(fontScale = fontScale + FONT_SCALE_STEP).clamped()

    fun withSmallerFont(): ReaderPreferences = copy(fontScale = fontScale - FONT_SCALE_STEP).clamped()

    fun withLargerLineSpacing(): ReaderPreferences =
        copy(lineHeightMultiplier = lineHeightMultiplier + LINE_HEIGHT_STEP).clamped()

    fun withSmallerLineSpacing(): ReaderPreferences =
        copy(lineHeightMultiplier = lineHeightMultiplier - LINE_HEIGHT_STEP).clamped()

    fun withMargin(marginDp: Int): ReaderPreferences = copy(marginDp = marginDp).clamped()

    companion object {
        /** Font scale bounds: small enough to fit dense pages, large enough for a11y. */
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.6f
        const val FONT_SCALE_STEP = 0.1f
        const val DEFAULT_FONT_SCALE = 1.0f

        const val MIN_LINE_HEIGHT_MULTIPLIER = 1.2f
        const val MAX_LINE_HEIGHT_MULTIPLIER = 2.2f
        const val LINE_HEIGHT_STEP = 0.1f
        const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 1.5f

        const val MIN_MARGIN_DP = 8
        const val MAX_MARGIN_DP = 48
        const val MARGIN_STEP_DP = 4
        const val DEFAULT_MARGIN_DP = 24
    }
}

/**
 * App-private, cleartext [SharedPreferences] store for [ReaderPreferences].
 *
 * Nothing secret is written here (no title, author, bookmark label, position or book
 * bytes); those live in the encrypted `ReadingState` inside the item envelope. The file
 * name and keys are deliberately content-free so a device backup of the prefs file does
 * not reveal what is being read.
 */
class ReaderPrefs(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Reads and clamps the persisted appearance, falling back to defaults if corrupt. */
    fun load(): ReaderPreferences {
        val themeName = preferences.getString(KEY_THEME, ReaderTheme.PAPER.name)
        val theme = ReaderTheme.entries.firstOrNull { it.name == themeName } ?: ReaderTheme.PAPER
        return ReaderPreferences(
            fontScale = preferences.getFloat(KEY_FONT_SCALE, ReaderPreferences.DEFAULT_FONT_SCALE),
            lineHeightMultiplier = preferences.getFloat(
                KEY_LINE_HEIGHT,
                ReaderPreferences.DEFAULT_LINE_HEIGHT_MULTIPLIER,
            ),
            marginDp = preferences.getInt(KEY_MARGIN_DP, ReaderPreferences.DEFAULT_MARGIN_DP),
            theme = theme,
        ).clamped()
    }

    /** Persists an already-user-chosen appearance; values are clamped defensively. */
    fun save(preferences: ReaderPreferences) {
        val safe = preferences.clamped()
        this.preferences.edit()
            .putFloat(KEY_FONT_SCALE, safe.fontScale)
            .putFloat(KEY_LINE_HEIGHT, safe.lineHeightMultiplier)
            .putInt(KEY_MARGIN_DP, safe.marginDp)
            .putString(KEY_THEME, safe.theme.name)
            .apply()
    }

    companion object {
        /** No reader content is stored here: only the four presentation values. */
        const val FILE_NAME = "notezero_reader_prefs"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_LINE_HEIGHT = "line_height_multiplier"
        const val KEY_MARGIN_DP = "margin_dp"
        const val KEY_THEME = "theme"
    }
}
