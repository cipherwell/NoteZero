package com.cipherwell.notezero.book

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Minimal, bounded, random-access ZIP reader for EPUB (OCF) containers.
 *
 * Implemented from the central directory instead of [java.util.zip.ZipInputStream] because
 * the engine needs (a) first-entry-wins duplicate semantics, (b) rejection of unsafe entry
 * names before anything is inflated, and (c) bounded random access for lazy image decoding.
 *
 * Safety model: the archive is trusted only up to the central directory. Entries that are
 * encrypted (ZIP crypto), use an unsupported compression method, or carry an unsafe path
 * are simply absent from [entries]; they can never be read. Every read is bounded by
 * [BookLimits.MAX_ENTRY_BYTES] and by the declared uncompressed size, and archives whose
 * declared total exceeds [BookLimits.MAX_TOTAL_UNCOMPRESSED_BYTES] (or that contain more
 * than [BookLimits.MAX_ENTRIES] entries, or that are truncated / ZIP64) are rejected.
 *
 * The reader holds no mutable per-read state, so concurrent [read] calls are safe.
 */
internal class ZipArchive private constructor(
    private val data: ByteArray,
    private val entries: Map<String, Entry>,
) {

    internal class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    val entryCount: Int get() = entries.size

    fun names(): Set<String> = entries.keys

    fun contains(name: String): Boolean = entries.containsKey(name)

    /**
     * Decompresses [name]. Returns a fresh caller-owned array, or null when the entry is
     * absent, unsafe, encrypted, uses an unknown compression method, exceeds [maxBytes],
     * or the compressed stream is truncated / wraps a larger payload than declared.
     */
    fun read(name: String, maxBytes: Int = BookLimits.MAX_ENTRY_BYTES): ByteArray? {
        val normalized = EpubPaths.normalize(name) ?: return null
        val entry = entries[normalized] ?: return null
        if (maxBytes <= 0) return null
        if (entry.uncompressedSize > maxBytes.toLong()) return null
        val declaredSize = entry.uncompressedSize.toInt()

        val header = entry.localHeaderOffset
        if (header < 0 || header + LOCAL_HEADER_BYTES > data.size) return null
        if (u32At(header.toInt()) != LOCAL_FILE_HEADER_SIGNATURE) return null
        val nameLength = u16At(header.toInt() + 26)
        val extraLength = u16At(header.toInt() + 28)
        val dataStart = header + LOCAL_HEADER_BYTES + nameLength + extraLength
        if (dataStart < 0 || dataStart > data.size) return null
        val compressedSize = entry.compressedSize
        if (compressedSize < 0 || compressedSize > data.size - dataStart) return null

        return when (entry.method) {
            METHOD_STORED -> {
                if (compressedSize != declaredSize.toLong()) return null
                data.copyOfRange(dataStart.toInt(), (dataStart + compressedSize).toInt())
            }
            METHOD_DEFLATED -> inflate(data, dataStart.toInt(), compressedSize.toInt(), declaredSize)
            else -> null
        }
    }

    private fun inflate(input: ByteArray, offset: Int, length: Int, declaredSize: Int): ByteArray? {
        if (length == 0) {
            // Deflate streams always carry at least the 2-byte empty block; a zero-length
            // stream with a non-zero declared size is corrupt.
            return if (declaredSize == 0) ByteArray(0) else null
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(input, offset, length)
            val initialCapacity = minOf(declaredSize + 1, 64 * 1024).coerceAtLeast(64)
            val output = ByteArrayOutputStream(initialCapacity)
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            var stalledRounds = 0
            while (!inflater.finished()) {
                val produced = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    return null
                }
                if (produced > 0) {
                    total += produced
                    if (total > declaredSize.toLong()) return null
                    output.write(buffer, 0, produced)
                    stalledRounds = 0
                } else {
                    if (inflater.finished()) break
                    if (inflater.needsDictionary()) return null
                    if (inflater.needsInput() && inflater.remaining == 0) return null
                    stalledRounds++
                    if (stalledRounds > 4) return null
                }
            }
            // Trailing garbage after the deflate end marker is corrupt input, not padding.
            if (inflater.remaining != 0) return null
            if (total != declaredSize.toLong()) return null
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun u16At(offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32At(offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    internal companion object {
        private const val EOCD_SIGNATURE = 0x06054B50L
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014B50L
        private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034B50L
        private const val EOCD_BYTES = 22
        private const val CENTRAL_HEADER_BYTES = 46
        private const val LOCAL_HEADER_BYTES = 30
        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_STRONG_ENCRYPTION = 0x0040
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val ZIP64_SENTINEL_32 = 0xFFFFFFFFL
        private const val ZIP64_SENTINEL_16 = 0xFFFF

        /** Opens [data], or returns null for anything that is not a safe, supported archive. */
        fun open(data: ByteArray): ZipArchive? {
            if (data.size < EOCD_BYTES) return null
            val eocd = findEndOfCentralDirectory(data) ?: return null
            val diskNumber = u16(data, eocd + 4)
            val centralDirectoryDisk = u16(data, eocd + 6)
            val entriesOnDisk = u16(data, eocd + 8)
            val totalEntries = u16(data, eocd + 10)
            val centralSize = u32(data, eocd + 12)
            val centralOffset = u32(data, eocd + 16)
            if (diskNumber != 0 || centralDirectoryDisk != 0) return null
            if (entriesOnDisk != totalEntries) return null
            if (totalEntries == ZIP64_SENTINEL_16) return null
            if (centralSize == ZIP64_SENTINEL_32 || centralOffset == ZIP64_SENTINEL_32) return null
            if (totalEntries > BookLimits.MAX_ENTRIES) return null
            if (centralOffset + centralSize > eocd) return null
            if (centralOffset > data.size) return null

            val entries = LinkedHashMap<String, Entry>(totalEntries)
            var position = centralOffset.toInt()
            var declaredTotal = 0L
            var index = 0
            while (index < totalEntries) {
                if (position < 0 || position + CENTRAL_HEADER_BYTES > data.size) return null
                if (u32(data, position) != CENTRAL_HEADER_SIGNATURE) return null
                val flags = u16(data, position + 8)
                val method = u16(data, position + 10)
                val compressedSize = u32(data, position + 20)
                val uncompressedSize = u32(data, position + 24)
                val nameLength = u16(data, position + 28)
                val extraLength = u16(data, position + 30)
                val commentLength = u16(data, position + 32)
                val localHeaderOffset = u32(data, position + 42)
                val next = position + CENTRAL_HEADER_BYTES + nameLength + extraLength + commentLength
                if (nameLength <= 0 || nameLength > 4096) return null
                if (next < 0 || next > data.size || next > eocd) return null
                if (compressedSize == ZIP64_SENTINEL_32 || uncompressedSize == ZIP64_SENTINEL_32) return null
                if (localHeaderOffset == ZIP64_SENTINEL_32) return null

                declaredTotal += uncompressedSize
                if (declaredTotal > BookLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) return null

                // Local headers are validated lazily in [read]: an unreadable entry must not
                // make an otherwise valid archive unusable (the spec says skip, not fail).
                val name = decodeName(data, position + CENTRAL_HEADER_BYTES, nameLength)
                if (name != null) {
                    val path = EpubPaths.normalize(name)
                    val encrypted = (flags and FLAG_ENCRYPTED) != 0 || (flags and FLAG_STRONG_ENCRYPTION) != 0
                    val supportedCompression = method == METHOD_STORED || method == METHOD_DEFLATED
                    val sizeFits = uncompressedSize <= BookLimits.MAX_ENTRY_BYTES.toLong()
                    if (path != null && !encrypted && supportedCompression && sizeFits) {
                        if (!entries.containsKey(path)) {
                            entries[path] = Entry(
                                name = path,
                                flags = flags,
                                method = method,
                                compressedSize = compressedSize,
                                uncompressedSize = uncompressedSize,
                                localHeaderOffset = localHeaderOffset,
                            )
                        }
                    }
                }
                position = next
                index++
            }
            return ZipArchive(data, entries)
        }

        private fun findEndOfCentralDirectory(data: ByteArray): Int? {
            val lowest = maxOf(0, data.size - EOCD_BYTES - 0xFFFF)
            var position = data.size - EOCD_BYTES
            while (position >= lowest) {
                if (u32(data, position) == EOCD_SIGNATURE) {
                    val commentLength = u16(data, position + 20)
                    if (position + EOCD_BYTES + commentLength == data.size) return position
                }
                position--
            }
            return null
        }

        private fun decodeName(data: ByteArray, offset: Int, length: Int): String? {
            if (offset + length > data.size) return null
            return try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(data, offset, length)).toString()
            } catch (_: Exception) {
                null
            }
        }

        private fun u16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        private fun u32(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}
