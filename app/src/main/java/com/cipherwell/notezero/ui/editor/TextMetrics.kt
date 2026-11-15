package com.cipherwell.notezero.ui.editor

/** Word/character counting for the editor status line. Pure Kotlin. */
object TextMetrics {

    /** Words are whitespace-separated non-blank runs. */
    fun wordCount(text: String): Int {
        if (text.isBlank()) return 0
        var count = 0
        var inWord = false
        text.forEach { char ->
            if (char.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    fun characterCount(text: String): Int = text.length

    /** Characters excluding whitespace — useful for the status line secondary hint. */
    fun characterCountNoSpaces(text: String): Int = text.count { !it.isWhitespace() }
}
