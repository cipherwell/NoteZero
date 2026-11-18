package com.cipherwell.notezero.book

/**
 * Minimal strict base64 decoder for FB2 `<binary>` payloads.
 *
 * `java.util.Base64` is API 26+ on Android while NoteZero supports API 24, so the engine
 * carries its own decoder. Only the standard RFC 4648 alphabet with `=` padding is accepted
 * (no URL-safe variant, no MIME tolerance: whitespace is stripped by the caller). The decoded
 * length is computed before allocating, so oversized payloads are rejected without creating a
 * large array.
 */
internal object Base64Codec {

    /** Decodes [text], or returns null when it is malformed or would exceed [maxBytes]. */
    fun decode(text: CharSequence, maxBytes: Int): ByteArray? {
        if (maxBytes <= 0) return null
        val length = text.length
        if (length == 0 || length % 4 != 0) return null
        var padding = 0
        if (text[length - 1] == '=') padding++
        if (length >= 2 && text[length - 2] == '=') padding++
        val decodedSize = (length / 4) * 3 - padding
        if (decodedSize <= 0 || decodedSize > maxBytes) return null
        val output = ByteArray(decodedSize)
        var outputIndex = 0
        var buffer = 0
        var bits = 0
        var index = 0
        while (index < length) {
            val ch = text[index]
            if (ch == '=') {
                // Padding is only valid as the trailing '='/ '==' of the last quantum.
                for (rest in index until length) {
                    if (text[rest] != '=') return null
                }
                break
            }
            val value = valueOf(ch) ?: return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outputIndex >= decodedSize) return null
                output[outputIndex++] = ((buffer shr bits) and 0xFF).toByte()
            }
            index++
        }
        return if (outputIndex == decodedSize) output else null
    }

    private fun valueOf(ch: Char): Int? = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> null
    }
}
