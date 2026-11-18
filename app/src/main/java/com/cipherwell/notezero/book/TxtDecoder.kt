package com.cipherwell.notezero.book

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Bounded, BOM-aware decoding of untrusted plain-text books.
 *
 * Supported encodings: UTF-8 (with BOM, or without one when the byte stream is valid UTF-8) and
 * UTF-16LE/BE (BOM required). A no-BOM file that is not valid UTF-8 keeps its UTF-8 reading
 * only when the replacement-character ratio is small (a few stray bytes); otherwise the whole
 * file falls back to windows-1252, the most common legacy TXT encoding. Bytes that decode like
 * binary (NUL or a high ratio of control characters) are rejected, so random/corrupt files are
 * never shown as text.
 *
 * Every decode is capped at [BookLimits.MAX_TXT_DECODED_CHARS] characters; the rest of the
 * input is left unread (bounded memory, no unbounded allocation on huge files).
 */
internal object TxtDecoder {

    /** Bytes examined by [looksLikeText] (detection only; the parser decodes up to the cap). */
    private const val PROBE_BYTES = 64 * 1024

    /** A UTF-8 replacement ratio at or below this keeps the UTF-8 reading. */
    private const val MAX_REPLACEMENT_RATIO = 0.02

    /** Control-character ratio above which the payload is treated as binary. */
    private const val MAX_CONTROL_RATIO = 0.02

    /** windows-1252 is present in every JDK and in Android's charset set. */
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    private enum class Bom { NONE, UTF8, UTF16_LE, UTF16_BE }

    class Decoded internal constructor(val text: String, val truncated: Boolean)

    /** Full decoding for [TxtBookParser]. Returns null for empty/binary/undecodable input. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty()) return null
        val decoded = when (bomKind(bytes)) {
            Bom.UTF8 -> decodeBytes(bytes, 3, Charsets.UTF_8, strict = true) ?: return null
            Bom.UTF16_LE -> decodeBytes(bytes, 2, Charsets.UTF_16LE, strict = true) ?: return null
            Bom.UTF16_BE -> decodeBytes(bytes, 2, Charsets.UTF_16BE, strict = true) ?: return null
            Bom.NONE -> decodeWithoutBom(bytes) ?: return null
        }
        if (!isPlausible(decoded.text)) return null
        return decoded
    }

    /**
     * Cheap detection probe used by [BookDetector]: decodes a bounded prefix and applies the
     * same text/binary rules as [decode]. A file can still fail [decode] later (for example a
     * binary tail), in which case the parser returns null and the book is unsupported.
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val limit = minOf(bytes.size, PROBE_BYTES)
        val sample = if (limit == bytes.size) bytes else bytes.copyOfRange(0, limit)
        val probe = probeDecode(sample) ?: return false
        if (probe.text.isBlank()) return false
        val ratio = probe.replacements.toDouble() / probe.text.length.coerceAtLeast(1)
        if (ratio > MAX_REPLACEMENT_RATIO) return false
        return isPlausible(probe.text)
    }

    private fun decodeWithoutBom(bytes: ByteArray): Decoded? {
        val utf8 = decodeBytes(bytes, 0, Charsets.UTF_8, strict = false) ?: return null
        val replacements = countReplacements(utf8.text)
        val ratio = replacements.toDouble() / utf8.text.length.coerceAtLeast(1)
        if (ratio <= MAX_REPLACEMENT_RATIO) return utf8
        return decodeBytes(bytes, 0, WINDOWS_1252, strict = false)
    }

    private fun probeDecode(bytes: ByteArray): Probe? = when (bomKind(bytes)) {
        Bom.UTF8 -> probeBytes(bytes, 3, Charsets.UTF_8)
        Bom.UTF16_LE -> probeBytes(bytes, 2, Charsets.UTF_16LE)
        Bom.UTF16_BE -> probeBytes(bytes, 2, Charsets.UTF_16BE)
        Bom.NONE -> {
            val utf8 = probeBytes(bytes, 0, Charsets.UTF_8) ?: return null
            val ratio = utf8.replacements.toDouble() / utf8.text.length.coerceAtLeast(1)
            if (ratio <= MAX_REPLACEMENT_RATIO) utf8 else probeBytes(bytes, 0, WINDOWS_1252)
        }
    }

    private class Probe(val text: String, val replacements: Int)

    private fun probeBytes(bytes: ByteArray, offset: Int, charset: Charset): Probe? {
        val decoded = decodeBytes(bytes, offset, charset, strict = false) ?: return null
        return Probe(decoded.text, countReplacements(decoded.text))
    }

    /**
     * Streams [bytes] through a decoder and keeps at most [BookLimits.MAX_TXT_DECODED_CHARS]
     * characters. In strict mode malformed input is reported (and becomes null); otherwise
     * U+FFFD replacements are produced.
     */
    private fun decodeBytes(bytes: ByteArray, offset: Int, charset: Charset, strict: Boolean): Decoded? {
        if (offset < 0 || offset >= bytes.size) return null
        val decoder = charset.newDecoder()
        if (strict) {
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
        } else {
            decoder.onMalformedInput(CodingErrorAction.REPLACE)
            decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)
        }
        val builder = StringBuilder(minOf(bytes.size - offset, 1 shl 20).coerceAtLeast(16))
        return try {
            InputStreamReader(ByteArrayInputStream(bytes, offset, bytes.size - offset), decoder).use { stream ->
                val buffer = CharArray(16 * 1024)
                var truncated = false
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    val remaining = BookLimits.MAX_TXT_DECODED_CHARS - builder.length
                    if (read <= remaining) {
                        builder.append(buffer, 0, read)
                    } else {
                        builder.append(buffer, 0, remaining)
                        truncated = true
                        break
                    }
                }
                Decoded(builder.toString(), truncated)
            }
        } catch (_: IOException) {
            // MalformedInputException/UnmappableCharacterException are IOExceptions.
            null
        }
    }

    private fun bomKind(bytes: ByteArray): Bom {
        if (bytes.size >= 3) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            val third = bytes[2].toInt() and 0xFF
            if (first == 0xEF && second == 0xBB && third == 0xBF) return Bom.UTF8
        }
        if (bytes.size >= 2) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0xFF && second == 0xFE) return Bom.UTF16_LE
            if (first == 0xFE && second == 0xFF) return Bom.UTF16_BE
        }
        return Bom.NONE
    }

    private fun countReplacements(text: String): Int {
        var count = 0
        for (ch in text) if (ch == '\uFFFD') count++
        return count
    }

    /** True when decoded characters look like text rather than binary noise. */
    private fun isPlausible(text: String): Boolean {
        if (text.isEmpty()) return false
        var controls = 0
        for (ch in text) {
            val code = ch.code
            if (code == 0) return false // the contract forbids NUL outright
            if (code < 0x20) {
                if (code != 0x09 && code != 0x0A && code != 0x0C && code != 0x0D) controls++
            } else if (code in 0x7F..0x9F) {
                controls++
            }
        }
        return controls.toDouble() / text.length <= MAX_CONTROL_RATIO
    }
}
