package com.cipherwell.notezero.data

import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchInkKind
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShape
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import com.cipherwell.notezero.model.SketchTemplate
import com.cipherwell.notezero.model.SketchWidth

/**
 * Versioned binary codec for an encrypted standalone sketch (`ItemCodec` payload v6).
 *
 * Geometry is normalised to the A4 page and quantised to 1/10,000 so the vector document can be
 * re-rendered at any size without drift, exactly like the PDF annotation layer. The blob lives
 * inside the item's AEAD envelope (never a Room column, never logged).
 *
 * ```
 * magic        4 bytes  "NZSK"
 * version      1 byte   1 (nothing older exists)
 * template     1 byte   SketchTemplate ordinal
 * strokeCount  2 bytes  u16 (<= MAX_STROKES)
 *   ink        1 byte   SketchInk ordinal
 *   width      1 byte   SketchWidth ordinal
 *   kind       1 byte   SketchInkKind ordinal
 *   pointCount 2 bytes  u16 (2..MAX_POINTS_PER_STROKE)
 *   points     4 bytes each: u16 x, u16 y (0..10000)
 * shapeCount   2 bytes  u16 (<= MAX_SHAPES)
 *   kind       1 byte   SketchShapeKind ordinal
 *   ink        1 byte
 *   width      1 byte
 *   start/end  4 bytes each: u16 x, u16 y (two points)
 * ```
 *
 * Decoding fails closed (null, never throws) on unknown magic/version/enum ordinals, truncation,
 * out-of-range counts, trailing bytes or oversized payloads.
 */
object SketchCodec {

    const val ENVELOPE_VERSION: Int = 1
    const val MAX_BYTES: Int = SketchDocument.MAX_ENCODED_BYTES

    private const val MAGIC: Long = 0x4E5A534BL // "NZSK"
    private const val QUANT_MAX = 10_000

    fun encode(sketch: SketchDocument): ByteArray {
        require(sketch.strokes.size <= SketchDocument.MAX_STROKES) { "too many strokes" }
        require(sketch.shapes.size <= SketchDocument.MAX_SHAPES) { "too many shapes" }
        val writer = BinaryWriter(initialCapacityFor(sketch)).apply {
            u32(MAGIC)
            u8(ENVELOPE_VERSION)
            u8(sketch.template.ordinal)
            u16(sketch.strokes.size)
            for (stroke in sketch.strokes) {
                if (stroke.points.size !in SketchDocument.MIN_POINTS_PER_STROKE..SketchDocument.MAX_POINTS_PER_STROKE) {
                    throw IllegalArgumentException("stroke point count out of range")
                }
                u8(stroke.ink.ordinal)
                u8(stroke.width.ordinal)
                u8(stroke.kind.ordinal)
                u16(stroke.points.size)
                for (point in stroke.points) {
                    u16(quantise(point.x))
                    u16(quantise(point.y))
                }
                if (size() > MAX_BYTES) throw IllegalArgumentException("sketch exceeds limit")
            }
            u16(sketch.shapes.size)
            for (shape in sketch.shapes) {
                u8(shape.kind.ordinal)
                u8(shape.ink.ordinal)
                u8(shape.width.ordinal)
                u16(quantise(shape.start.x))
                u16(quantise(shape.start.y))
                u16(quantise(shape.end.x))
                u16(quantise(shape.end.y))
                if (size() > MAX_BYTES) throw IllegalArgumentException("sketch exceeds limit")
            }
        }
        val encoded = writer.toByteArray()
        require(encoded.size <= MAX_BYTES) { "sketch exceeds limit" }
        return encoded
    }

    /** Returns the decoded sketch, or null when the blob is corrupt/oversized/trailing. */
    fun decode(payload: ByteArray): SketchDocument? {
        return try {
            val reader = BinaryReader(payload, MAX_BYTES)
            if (reader.u32() != MAGIC) return null
            if (reader.u8() != ENVELOPE_VERSION) return null
            val template = SketchTemplate.entries.getOrNull(reader.u8()) ?: return null

            val strokeCount = reader.u16()
            if (strokeCount > SketchDocument.MAX_STROKES) return null
            val strokes = ArrayList<SketchStroke>(strokeCount)
            var totalPoints = 0
            repeat(strokeCount) {
                val ink = SketchInk.entries.getOrNull(reader.u8()) ?: return null
                val width = SketchWidth.entries.getOrNull(reader.u8()) ?: return null
                val kind = SketchInkKind.entries.getOrNull(reader.u8()) ?: return null
                val pointCount = reader.u16()
                if (pointCount !in SketchDocument.MIN_POINTS_PER_STROKE..SketchDocument.MAX_POINTS_PER_STROKE) {
                    return null
                }
                totalPoints += pointCount
                if (totalPoints > SketchDocument.MAX_TOTAL_POINTS) return null
                val points = ArrayList<SketchPoint>(pointCount)
                repeat(pointCount) { points.add(SketchPoint(dequantise(reader.u16()), dequantise(reader.u16()))) }
                strokes.add(SketchStroke(points = points, ink = ink, width = width, kind = kind))
            }

            val shapeCount = reader.u16()
            if (shapeCount > SketchDocument.MAX_SHAPES) return null
            val shapes = ArrayList<SketchShape>(shapeCount)
            repeat(shapeCount) {
                val kind = SketchShapeKind.entries.getOrNull(reader.u8()) ?: return null
                val ink = SketchInk.entries.getOrNull(reader.u8()) ?: return null
                val width = SketchWidth.entries.getOrNull(reader.u8()) ?: return null
                val start = SketchPoint(dequantise(reader.u16()), dequantise(reader.u16()))
                val end = SketchPoint(dequantise(reader.u16()), dequantise(reader.u16()))
                shapes.add(SketchShape(kind = kind, start = start, end = end, ink = ink, width = width))
            }

            reader.requireFullyConsumed()
            SketchDocument(template = template, strokes = strokes, shapes = shapes)
        } catch (t: Throwable) {
            null
        }
    }

    private fun initialCapacityFor(sketch: SketchDocument): Int {
        var size = 32
        for (stroke in sketch.strokes) size += 16 + stroke.points.size * 4
        size += sketch.shapes.size * 24
        return size.coerceAtMost(MAX_BYTES)
    }

    private fun quantise(value: Float): Int {
        require(value.isFinite() && value in 0f..1f) { "value must be normalised 0..1" }
        return kotlin.math.round(value * QUANT_MAX).toInt().coerceIn(0, QUANT_MAX)
    }

    private fun dequantise(value: Int): Float = (value.toFloat() / QUANT_MAX).coerceIn(0f, 1f)
}
