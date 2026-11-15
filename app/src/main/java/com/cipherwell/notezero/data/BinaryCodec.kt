package com.cipherwell.notezero.data

/**
 * Thrown while decoding a persisted binary payload that is truncated, oversized or has
 * an unknown version/magic. The data layer catches this and treats the row as corrupt
 * (skipped and counted); it never escapes to the UI.
 */
internal class MalformedPayloadException(message: String) : Exception(message)

/** Upper bound for any single decrypted payload we are willing to materialise. */
internal const val MAX_DECODED_PAYLOAD_BYTES: Int = 32 * 1024 * 1024

/**
 * Minimal big-endian, bounds-checked binary writer for the versioned envelopes in this
 * package. Deliberately dependency-free (no JSON/serialization library): every field is
 * length-prefixed and range-checked so a crafted or truncated payload becomes a null
 * decode, never an OOM or an exception in the vault.
 */
internal class BinaryWriter(initialCapacity: Int = 64) {

    private val buffer = java.io.ByteArrayOutputStream(initialCapacity)

    fun u8(value: Int): BinaryWriter = apply {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        buffer.write(value)
    }

    fun u16(value: Int): BinaryWriter = apply {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        buffer.write(value ushr 8)
        buffer.write(value and 0xFF)
    }

    fun u32(value: Long): BinaryWriter = apply {
        require(value in 0..0xFFFFFFFFL) { "u32 out of range: $value" }
        for (shift in intArrayOf(24, 16, 8, 0)) {
            buffer.write(((value ushr shift) and 0xFF).toInt())
        }
    }

    fun i32(value: Int): BinaryWriter = u32(value.toLong() and 0xFFFFFFFFL)

    fun u64(value: Long): BinaryWriter = apply {
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            buffer.write(((value ushr shift) and 0xFF).toInt())
        }
    }

    fun bytes(value: ByteArray): BinaryWriter = apply { buffer.write(value, 0, value.size) }

    fun lenPrefixedU16(value: ByteArray, maxBytes: Int): BinaryWriter = apply {
        require(value.size <= maxBytes) { "field exceeds limit" }
        require(value.size <= 0xFFFF) { "field too long for u16 length" }
        u16(value.size)
        bytes(value)
    }

    fun lenPrefixedU32(value: ByteArray, maxBytes: Int): BinaryWriter = apply {
        require(value.size <= maxBytes) { "field exceeds limit" }
        u32(value.size.toLong())
        bytes(value)
    }

    fun size(): Int = buffer.size()

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

/**
 * Mirror of [BinaryWriter] with hard bounds on every read: any truncation, oversized
 * length prefix or trailing garbage raises [MalformedPayloadException] so decoders can
 * fail closed.
 */
internal class BinaryReader(
    private val data: ByteArray,
    private val maxBytes: Int = MAX_DECODED_PAYLOAD_BYTES,
) {

    private var position = 0

    init {
        if (data.size > maxBytes) throw MalformedPayloadException("payload exceeds size limit")
    }

    fun remaining(): Int = data.size - position

    fun u8(): Int {
        requireBytes(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        requireBytes(2)
        val value = ((data[position].toInt() and 0xFF) shl 8) or (data[position + 1].toInt() and 0xFF)
        position += 2
        return value
    }

    fun u32(): Long {
        requireBytes(4)
        var value = 0L
        repeat(4) {
            value = (value shl 8) or (data[position++].toLong() and 0xFF)
        }
        return value
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        requireBytes(8)
        var value = 0L
        repeat(8) {
            value = (value shl 8) or (data[position++].toLong() and 0xFF)
        }
        return value
    }

    fun bytes(length: Int): ByteArray {
        requireBytes(length)
        val out = data.copyOfRange(position, position + length)
        position += length
        return out
    }

    fun lenPrefixedU16(maxBytes: Int): ByteArray {
        val length = u16()
        if (length > maxBytes) throw MalformedPayloadException("length prefix exceeds limit")
        return bytes(length)
    }

    fun lenPrefixedU32(maxBytes: Int): ByteArray {
        val length = u32()
        if (length < 0 || length > maxBytes || length > Int.MAX_VALUE) {
            throw MalformedPayloadException("length prefix exceeds limit")
        }
        return bytes(length.toInt())
    }

    fun utf8LenPrefixedU16(maxBytes: Int): String =
        String(lenPrefixedU16(maxBytes), Charsets.UTF_8)

    fun utf8LenPrefixedU32(maxBytes: Int): String =
        String(lenPrefixedU32(maxBytes), Charsets.UTF_8)

    fun requireFullyConsumed() {
        if (position != data.size) throw MalformedPayloadException("trailing bytes in payload")
    }

    private fun requireBytes(length: Int) {
        if (length < 0 || position + length > data.size) {
            throw MalformedPayloadException("truncated payload")
        }
    }
}
