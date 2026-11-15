package com.cipherwell.notezero.crypto

/**
 * Minimal, dependency-free Base64/hex codecs.
 *
 * Deliberately NOT android.util.Base64: keeping the crypto package pure JVM means the
 * whole crypto core is testable with plain JUnit, without Robolectric or a device.
 * Standard Base64 with padding (RFC 4648 §4), strict on decode: any invalid character
 * or bad length returns null instead of silently skipping bytes.
 */
object Encoding {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun base64Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
            out.append(ALPHABET[(n ushr 12) and 0x3F])
            out.append(ALPHABET[(n ushr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append(ALPHABET[(n ushr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    fun base64Decode(text: String): ByteArray? {
        val s = text.filterNot { it.isWhitespace() }
        if (s.length % 4 != 0) return null
        if (s.isEmpty()) return ByteArray(0)
        val padding = when {
            s.endsWith("==") -> 2
            s.endsWith("=") -> 1
            else -> 0
        }
        val outLen = s.length / 4 * 3 - padding
        val out = ByteArray(outLen)
        var outPos = 0
        var i = 0
        while (i < s.length) {
            val c0 = decodeChar(s[i])
            val c1 = decodeChar(s[i + 1])
            val c2 = if (s[i + 2] == '=') -1 else decodeChar(s[i + 2])
            val c3 = if (s[i + 3] == '=') -1 else decodeChar(s[i + 3])
            if (c0 < 0 || c1 < 0 || c2 < -1 || c3 < -1) return null
            val lastBlock = i + 4 == s.length
            if (!lastBlock && (c2 < 0 || c3 < 0)) return null
            if (lastBlock) {
                when {
                    c2 < 0 && c3 >= 0 -> return null
                    c2 >= 0 && c3 < 0 && outPos + 1 >= outLen -> return null
                }
            }
            val n = (c0 shl 18) or (c1 shl 12) or ((if (c2 < 0) 0 else c2) shl 6) or (if (c3 < 0) 0 else c3)
            if (outPos < outLen) out[outPos++] = ((n ushr 16) and 0xFF).toByte()
            if (outPos < outLen) out[outPos++] = ((n ushr 8) and 0xFF).toByte()
            if (outPos < outLen) out[outPos++] = (n and 0xFF).toByte()
            i += 4
        }
        return out
    }

    private fun decodeChar(c: Char): Int = if (c.code < 128) DECODE_TABLE[c.code] else -1

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
}
