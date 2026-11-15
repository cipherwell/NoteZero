package com.cipherwell.notezero.ui.code

import android.content.Context
import android.content.SharedPreferences

/**
 * Editor view preferences that are safe to persist in cleartext: font size and word
 * wrap. They contain no vault content, no file names and no secrets — decrypted source
 * never leaves the ViewModel's memory.
 */
interface CodeEditorPreferences {
    var fontSizeSp: Int
    var wordWrap: Boolean
}

/** In-memory implementation used by unit tests and as a safe fallback. */
class InMemoryCodeEditorPreferences(
    fontSizeSp: Int = CodeEditorPrefs.DEFAULT_FONT_SIZE_SP,
    wordWrap: Boolean = true,
) : CodeEditorPreferences {
    private var font = fontSizeSp.coerceIn(CodeEditorPrefs.MIN_FONT_SIZE_SP, CodeEditorPrefs.MAX_FONT_SIZE_SP)
    private var wrap = wordWrap

    override var fontSizeSp: Int
        get() = font
        set(value) {
            font = value.coerceIn(CodeEditorPrefs.MIN_FONT_SIZE_SP, CodeEditorPrefs.MAX_FONT_SIZE_SP)
        }

    override var wordWrap: Boolean
        get() = wrap
        set(value) {
            wrap = value
        }
}

/**
 * Non-secret [SharedPreferences] persistence for the code editor's font size (12–24 sp)
 * and wrap toggle. Stored in a dedicated private preferences file; never holds content.
 */
class CodeEditorPrefs(context: Context) : CodeEditorPreferences {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP)
            .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        set(value) {
            prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)).apply()
        }

    override var wordWrap: Boolean
        get() = prefs.getBoolean(KEY_WORD_WRAP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WORD_WRAP, value).apply()
        }

    companion object {
        const val FILE_NAME = "code_editor_prefs"
        const val KEY_FONT_SIZE = "font_size_sp"
        const val KEY_WORD_WRAP = "word_wrap"
        const val MIN_FONT_SIZE_SP = 12
        const val MAX_FONT_SIZE_SP = 24
        const val DEFAULT_FONT_SIZE_SP = 14
    }
}
