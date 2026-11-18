package com.cipherwell.notezero.book

/** Small text helpers shared by the TXT and FB2 parsers (the EPUB engine has its own). */
internal object BookText {

    const val UNTITLED: String = "Untitled"

    /** Fallback title handling shared by TXT/FB2/CBZ: trimmed, never blank. */
    fun fallbackTitle(raw: String): String = raw.trim().ifEmpty { UNTITLED }

    /** Collapses every whitespace run to a single space and trims the edges. */
    fun collapse(raw: String): String {
        val builder = StringBuilder(raw.length.coerceAtMost(512))
        var pendingSpace = false
        for (ch in raw) {
            if (ch.isWhitespace()) {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /** Truncates to at most [max] characters without splitting a surrogate pair. */
    fun truncate(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd()
    }
}
