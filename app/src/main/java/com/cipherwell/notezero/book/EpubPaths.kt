package com.cipherwell.notezero.book

/**
 * Archive-path handling for OCF/EPUB. Every path that reaches the ZIP reader is first
 * normalised here; absolute paths, backslashes, NUL bytes and `..` escapes are rejected
 * (never resolved), so a crafted href or entry name cannot address anything outside the
 * archive's virtual root.
 */
internal object EpubPaths {

    /** Returns the normalised archive path, or null when the path is unsafe/malformed. */
    fun normalize(path: String): String? {
        if (path.isEmpty()) return null
        if (path.length > 4096) return null
        if (path.indexOf('\u0000') >= 0) return null
        if (path.indexOf('\\') >= 0) return null
        if (path.startsWith("/")) return null
        // Drive letters / URI schemes are not valid archive-relative paths.
        val colon = path.indexOf(':')
        if (colon >= 0 && path.substring(0, colon).indexOf('/') < 0) return null
        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.size - 1)
                }
                else -> segments.add(segment)
            }
        }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    /**
     * Resolves an href found in [documentPath] against that document's directory.
     * Fragments and queries are stripped (the archive path itself never carries them).
     * Percent-escapes are decoded exactly once, as EPUB hrefs are URLs whose ZIP entry
     * names live on disk-ish paths (e.g. `my%20file.xhtml` -> `my file.xhtml`).
     */
    fun resolve(documentPath: String, href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.indexOf('\u0000') >= 0) return null
        if (trimmed.indexOf('\\') >= 0) return null
        // Absolute URL (scheme) or protocol-relative / absolute path: not an in-archive href.
        if (trimmed.startsWith("//") || trimmed.startsWith("/")) return null
        val schemeIndex = schemeSeparator(trimmed)
        if (schemeIndex > 0) return null
        var reference = trimmed
        val fragment = reference.indexOf('#')
        if (fragment >= 0) reference = reference.substring(0, fragment)
        val query = reference.indexOf('?')
        if (query >= 0) reference = reference.substring(0, query)
        val decoded = percentDecode(reference) ?: return null
        if (decoded.isEmpty()) return null
        val directory = directoryOf(documentPath)
        return normalize(if (directory.isEmpty()) decoded else "$directory/$decoded")
    }

    /** Directory part of an archive path (`OEBPS/text/a.xhtml` -> `OEBPS/text`). */
    fun directoryOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash <= 0) "" else path.substring(0, slash)
    }

    /** Fragment part of an href, decoded, or null. */
    fun fragmentOf(href: String): String? {
        val hash = href.indexOf('#')
        if (hash < 0 || hash == href.length - 1) return null
        val raw = href.substring(hash + 1)
        val noQuery = raw.substringBefore('?')
        return percentDecode(noQuery) ?: noQuery
    }

    private fun schemeSeparator(value: String): Int {
        for (index in 0 until value.length) {
            val ch = value[index]
            if (ch == '/') return -1
            if (ch == ':' && index > 0) {
                val scheme = value.substring(0, index)
                if (scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return index
                return -1
            }
        }
        return -1
    }

    /** Strict UTF-8 percent decoding; returns null for malformed escapes or invalid UTF-8. */
    fun percentDecode(value: String): String? {
        if (value.indexOf('%') < 0) return value
        val output = ByteArray(value.length)
        var length = 0
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%') {
                if (index + 2 >= value.length) return null
                val high = hexDigit(value[index + 1]) ?: return null
                val low = hexDigit(value[index + 2]) ?: return null
                output[length++] = ((high shl 4) or low).toByte()
                index += 3
            } else {
                val code = ch.code
                if (code < 0x80) {
                    output[length++] = code.toByte()
                } else {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    if (length + bytes.size > output.size) return null
                    System.arraycopy(bytes, 0, output, length, bytes.size)
                    length += bytes.size
                }
                index++
            }
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(output, 0, length)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun hexDigit(ch: Char): Int? = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> null
    }
}
