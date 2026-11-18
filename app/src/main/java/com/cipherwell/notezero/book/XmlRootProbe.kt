package com.cipherwell.notezero.book

/**
 * Cheap, bounded probe for the XML root element name, used by [BookDetector] to recognise
 * FictionBook files without parsing (and allocating a tree for) untrusted input.
 *
 * It understands the XML prolog: byte-order mark, whitespace, processing instructions,
 * comments and a `DOCTYPE` declaration (with or without an internal subset, whose `[...]` is
 * skipped). Only the first 64 KiB are examined; a document whose root does not start within
 * that window is not recognised. UTF-16 input is rejected for the same reason the XML parsers
 * reject it: the byte-level DOCTYPE guard would be blind to it. The parser itself (not this
 * probe) is the authority on well-formedness.
 */
internal object XmlRootProbe {

    private const val WINDOW_BYTES = 64 * 1024

    /** Namespace-tolerant local name of the root element, or null when none is found. */
    fun rootLocalName(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        if (SecureXml.isUtf16(bytes)) return null
        var index = 0
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            index = 3
        }
        val end = minOf(bytes.size, index + WINDOW_BYTES)
        while (true) {
            while (index < end && isXmlSpace(byteAt(bytes, index))) index++
            if (index >= end || byteAt(bytes, index) != '<'.code) return null
            index++
            if (index >= end) return null
            when (byteAt(bytes, index)) {
                '?'.code -> {
                    index = indexOf(bytes, index, end, "?>")
                    if (index < 0) return null
                    index += 2
                }
                '!'.code -> {
                    index = skipDeclaration(bytes, index, end) ?: return null
                }
                else -> {
                    val nameStart = index
                    while (index < end && isXmlNameChar(byteAt(bytes, index))) index++
                    if (index == nameStart) return null
                    val name = String(bytes, nameStart, index - nameStart, Charsets.US_ASCII)
                    return name.substringAfterLast(':')
                }
            }
        }
    }

    private fun skipDeclaration(bytes: ByteArray, bangIndex: Int, end: Int): Int? {
        // bangIndex points at '!'.
        if (startsWith(bytes, bangIndex + 1, end, "--")) {
            val close = indexOf(bytes, bangIndex + 3, end, "-->")
            return if (close < 0) null else close + 3
        }
        if (startsWith(bytes, bangIndex + 1, end, "[CDATA[")) return null
        if (matchesIgnoreCase(bytes, bangIndex + 1, end, "DOCTYPE")) {
            return skipDoctype(bytes, bangIndex, end)
        }
        val close = indexOfChar(bytes, bangIndex, end, '>'.code)
        return if (close < 0) null else close + 1
    }

    /** Skips `<!DOCTYPE ...>`, honouring an internal subset (`[...]`) and quoted literals. */
    private fun skipDoctype(bytes: ByteArray, bangIndex: Int, end: Int): Int? {
        var index = bangIndex
        var subsetDepth = 0
        var quote = 0
        while (index < end) {
            val value = byteAt(bytes, index)
            if (quote != 0) {
                if (value == quote) quote = 0
            } else {
                when (value) {
                    '"'.code, '\''.code -> quote = value
                    '['.code -> subsetDepth++
                    ']'.code -> if (subsetDepth > 0) subsetDepth--
                    '>'.code -> if (subsetDepth == 0) return index + 1
                }
            }
            index++
        }
        return null
    }

    private fun startsWith(bytes: ByteArray, index: Int, end: Int, needle: String): Boolean {
        if (index + needle.length > end) return false
        for (offset in needle.indices) {
            if (byteAt(bytes, index + offset) != needle[offset].code) return false
        }
        return true
    }

    private fun matchesIgnoreCase(bytes: ByteArray, index: Int, end: Int, needle: String): Boolean {
        if (index + needle.length > end) return false
        for (offset in needle.indices) {
            val value = byteAt(bytes, index + offset)
            val expected = needle[offset].code
            if (value != expected && value != Character.toUpperCase(expected)) return false
        }
        return true
    }

    private fun indexOf(bytes: ByteArray, from: Int, end: Int, needle: String): Int {
        val last = end - needle.length
        var index = from
        while (index <= last) {
            if (startsWith(bytes, index, end, needle)) return index
            index++
        }
        return -1
    }

    private fun indexOfChar(bytes: ByteArray, from: Int, end: Int, value: Int): Int {
        var index = from
        while (index < end) {
            if (byteAt(bytes, index) == value) return index
            index++
        }
        return -1
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private fun isXmlSpace(value: Int): Boolean =
        value == ' '.code || value == '\t'.code || value == '\n'.code || value == '\r'.code

    private fun isXmlNameChar(value: Int): Boolean =
        (value in 'a'.code..'z'.code) ||
            (value in 'A'.code..'Z'.code) ||
            (value in '0'.code..'9'.code) ||
            value == '_'.code || value == '-'.code || value == '.'.code || value == ':'.code ||
            value >= 0x80
}
