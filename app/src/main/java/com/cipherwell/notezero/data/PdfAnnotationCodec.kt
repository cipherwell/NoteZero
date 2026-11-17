package com.cipherwell.notezero.data

import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfHighlightRect
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint

/**
 * Versioned binary codec for the encrypted PDF annotation layer.
 *
 * The encoded blob lives **inside** the item's AES-256-GCM envelope (`ItemCodec` payload v5), so
 * it is content: never a Room column, never logged. Geometry is normalised to the page and
 * quantised to 1/10,000 so a phone-sized and a tablet-sized render produce the same state.
 *
 * ```
 * magic        4 bytes  "NZPA"
 * version      1 byte   1 (written; nothing older exists)
 * strokeCount  2 bytes  u16 (<= PdfAnnotations.MAX_STROKES)
 *   page       4 bytes  u32
 *   kind       1 byte   0 = ink, 1 = highlighter
 *   colourIdx  1 byte   < PdfAnnotations.PALETTE_SIZE
 *   widthIdx   1 byte   < PdfAnnotations.WIDTH_LEVELS
 *   pointCount 2 bytes  u16 (2..MAX_POINTS_PER_STROKE)
 *   points     4 bytes each: u16 x, u16 y (0..10000 = 0.0..1.0)
 * highlightCount 2 bytes u16 (<= MAX_HIGHLIGHTS)
 *   page       4 bytes  u32
 *   bounds     8 bytes  u16 left, u16 top, u16 right, u16 bottom
 *   colourIdx  1 byte
 * noteCount    2 bytes  u16 (<= MAX_NOTES)
 *   page       4 bytes  u32
 *   x, y       4 bytes  two u16
 *   textLen    2 bytes  u16 UTF-8 bytes (<= MAX_NOTE_BYTES) + text
 * ```
 *
 * Decoding fails closed: null on unknown magic/version, truncation, out-of-range counts/values,
 * trailing bytes, oversized payloads or invalid UTF-8 bounds; it never throws.
 */
object PdfAnnotationCodec {

    const val ENVELOPE_VERSION: Int = 1
    const val MAX_BYTES: Int = PdfAnnotations.MAX_ENCODED_BYTES

    private const val MAGIC: Long = 0x4E5A5041L // "NZPA"
    private const val KIND_INK = 0
    private const val KIND_HIGHLIGHTER = 1
    private const val QUANT_MAX = 10_000

    fun encode(annotations: PdfAnnotations): ByteArray {
        require(annotations.strokes.size <= PdfAnnotations.MAX_STROKES) { "too many strokes" }
        require(annotations.highlights.size <= PdfAnnotations.MAX_HIGHLIGHTS) { "too many highlights" }
        require(annotations.notes.size <= PdfAnnotations.MAX_NOTES) { "too many notes" }
        val writer = BinaryWriter(initialCapacityFor(annotations)).apply {
            u32(MAGIC)
            u8(ENVELOPE_VERSION)
            u16(annotations.strokes.size)
            for (stroke in annotations.strokes) {
                if (stroke.kind != PdfInkKind.INK && stroke.kind != PdfInkKind.HIGHLIGHTER) {
                    throw IllegalArgumentException("invalid ink kind")
                }
                u32(stroke.page.toLong())
                u8(if (stroke.kind == PdfInkKind.INK) KIND_INK else KIND_HIGHLIGHTER)
                u8(stroke.colorIndex)
                u8(stroke.widthIndex)
                u16(stroke.points.size)
                for (point in stroke.points) {
                    u16(quantise(point.x))
                    u16(quantise(point.y))
                }
                if (size() > MAX_BYTES) throw IllegalArgumentException("annotations exceed limit")
            }
            u16(annotations.highlights.size)
            for (highlight in annotations.highlights) {
                u32(highlight.page.toLong())
                u16(quantise(highlight.left))
                u16(quantise(highlight.top))
                u16(quantise(highlight.right))
                u16(quantise(highlight.bottom))
                u8(highlight.colorIndex)
                if (size() > MAX_BYTES) throw IllegalArgumentException("annotations exceed limit")
            }
            u16(annotations.notes.size)
            for (note in annotations.notes) {
                val text = note.text.toByteArray(Charsets.UTF_8)
                if (text.size > PdfAnnotations.MAX_NOTE_BYTES) {
                    throw IllegalArgumentException("note text exceeds limit")
                }
                u32(note.page.toLong())
                u16(quantise(note.x))
                u16(quantise(note.y))
                u16(text.size)
                bytes(text)
                if (size() > MAX_BYTES) throw IllegalArgumentException("annotations exceed limit")
            }
        }
        val encoded = writer.toByteArray()
        require(encoded.size <= MAX_BYTES) { "annotations exceed limit" }
        return encoded
    }

    /** Returns the decoded layer, or null when the blob is corrupt/oversized/trailing bytes. */
    fun decode(payload: ByteArray): PdfAnnotations? {
        return try {
            val reader = BinaryReader(payload, MAX_BYTES)
            if (reader.u32() != MAGIC) return null
            if (reader.u8() != ENVELOPE_VERSION) return null

            val strokeCount = reader.u16()
            if (strokeCount > PdfAnnotations.MAX_STROKES) return null
            val strokes = ArrayList<PdfInkStroke>(strokeCount)
            var totalPoints = 0
            repeat(strokeCount) {
                val page = reader.u32()
                if (page > PdfAnnotations.MAX_PAGE) return null
                val kind = when (reader.u8()) {
                    KIND_INK -> PdfInkKind.INK
                    KIND_HIGHLIGHTER -> PdfInkKind.HIGHLIGHTER
                    else -> return null
                }
                val colorIndex = reader.u8()
                if (colorIndex >= PdfAnnotations.PALETTE_SIZE) return null
                val widthIndex = reader.u8()
                if (widthIndex >= PdfAnnotations.WIDTH_LEVELS) return null
                val pointCount = reader.u16()
                if (pointCount !in PdfAnnotations.MIN_POINTS_PER_STROKE..PdfAnnotations.MAX_POINTS_PER_STROKE) {
                    return null
                }
                totalPoints += pointCount
                if (totalPoints > PdfAnnotations.MAX_TOTAL_POINTS) return null
                val points = ArrayList<PdfPoint>(pointCount)
                repeat(pointCount) {
                    points.add(PdfPoint(dequantise(reader.u16()), dequantise(reader.u16())))
                }
                strokes.add(
                    PdfInkStroke(
                        page = page.toInt(),
                        kind = kind,
                        colorIndex = colorIndex,
                        widthIndex = widthIndex,
                        points = points,
                    ),
                )
            }

            val highlightCount = reader.u16()
            if (highlightCount > PdfAnnotations.MAX_HIGHLIGHTS) return null
            val highlights = ArrayList<PdfHighlightRect>(highlightCount)
            repeat(highlightCount) {
                val page = reader.u32()
                if (page > PdfAnnotations.MAX_PAGE) return null
                val left = dequantise(reader.u16())
                val top = dequantise(reader.u16())
                val right = dequantise(reader.u16())
                val bottom = dequantise(reader.u16())
                val colorIndex = reader.u8()
                if (colorIndex >= PdfAnnotations.PALETTE_SIZE) return null
                highlights.add(
                    PdfHighlightRect(
                        page = page.toInt(),
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        colorIndex = colorIndex,
                    ),
                )
            }

            val noteCount = reader.u16()
            if (noteCount > PdfAnnotations.MAX_NOTES) return null
            val notes = ArrayList<PdfNote>(noteCount)
            repeat(noteCount) {
                val page = reader.u32()
                if (page > PdfAnnotations.MAX_PAGE) return null
                val x = dequantise(reader.u16())
                val y = dequantise(reader.u16())
                val text = reader.utf8LenPrefixedU16(PdfAnnotations.MAX_NOTE_BYTES)
                notes.add(PdfNote(page = page.toInt(), x = x, y = y, text = text))
            }

            reader.requireFullyConsumed()
            PdfAnnotations(strokes = strokes, highlights = highlights, notes = notes)
        } catch (t: Throwable) {
            null
        }
    }

    /** Worst-case encoded size estimate used only to size the writer (never exposed). */
    private fun initialCapacityFor(annotations: PdfAnnotations): Int {
        var size = 32
        for (stroke in annotations.strokes) size += 16 + stroke.points.size * 4
        for (highlight in annotations.highlights) size += 20
        for (note in annotations.notes) size += 16 + note.text.toByteArray(Charsets.UTF_8).size
        return size.coerceAtMost(MAX_BYTES)
    }

    private fun quantise(value: Float): Int {
        require(value.isFinite() && value in 0f..1f) { "value must be normalised 0..1" }
        return kotlin.math.round(value * QUANT_MAX).toInt().coerceIn(0, QUANT_MAX)
    }

    private fun dequantise(value: Int): Float = (value.toFloat() / QUANT_MAX).coerceIn(0f, 1f)
}
