package com.cipherwell.notezero.data

import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfHighlightRect
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for the encrypted PDF annotation codec: deterministic round-trips, fail-closed
 * bounds and hostile-input handling (the blob lives inside the item AEAD, but a tampered or
 * corrupt payload must never throw or materialise unbounded data).
 */
class PdfAnnotationCodecTest {

    @Test
    fun `empty annotations round trip`() {
        val encoded = PdfAnnotationCodec.encode(PdfAnnotations())
        assertNotNull(encoded)
        assertEquals(PdfAnnotations(), PdfAnnotationCodec.decode(encoded))
    }

    @Test
    fun `strokes highlights and notes round trip`() {
        val annotations = PdfAnnotations(
            strokes = listOf(
                PdfInkStroke(
                    page = 0,
                    kind = PdfInkKind.INK,
                    colorIndex = 2,
                    widthIndex = 1,
                    points = listOf(PdfPoint(0.1f, 0.2f), PdfPoint(0.3f, 0.4f), PdfPoint(1f, 1f)),
                ),
                PdfInkStroke(
                    page = 7,
                    kind = PdfInkKind.HIGHLIGHTER,
                    colorIndex = 5,
                    widthIndex = 2,
                    points = listOf(PdfPoint(0f, 0f), PdfPoint(0.5f, 0.5f)),
                ),
            ),
            highlights = listOf(
                PdfHighlightRect(
                    page = 3,
                    left = 0.1f,
                    top = 0.2f,
                    right = 0.8f,
                    bottom = 0.3f,
                    colorIndex = 1,
                ),
            ),
            notes = listOf(
                PdfNote(page = 2, x = 0.25f, y = 0.75f, text = "Check this paragraph",),
            ),
        )

        val decoded = PdfAnnotationCodec.decode(PdfAnnotationCodec.encode(annotations))

        assertNotNull(decoded)
        assertEquals(annotations.strokes.size, decoded!!.strokes.size)
        assertEquals(annotations.strokes[0].page, decoded.strokes[0].page)
        assertEquals(annotations.strokes[0].kind, decoded.strokes[0].kind)
        assertEquals(annotations.strokes[0].colorIndex, decoded.strokes[0].colorIndex)
        assertEquals(annotations.strokes[0].widthIndex, decoded.strokes[0].widthIndex)
        assertEquals(annotations.strokes[0].points.size, decoded.strokes[0].points.size)
        annotations.strokes[0].points.forEachIndexed { index, point ->
            assertEquals(point.x, decoded.strokes[0].points[index].x, 0.0002f)
            assertEquals(point.y, decoded.strokes[0].points[index].y, 0.0002f)
        }
        assertEquals(annotations.highlights, decoded.highlights)
        assertEquals(annotations.notes, decoded.notes)
    }

    @Test
    fun `encoding exceeds point caps fail closed`() {
        val tooManyPoints = PdfInkStroke(
            page = 0,
            kind = PdfInkKind.INK,
            colorIndex = 0,
            widthIndex = 0,
            points = List(PdfAnnotations.MAX_POINTS_PER_STROKE + 1) { PdfPoint(0.5f, 0.5f) },
        )
        assertThrowsIllegalArgument { PdfAnnotationCodec.encode(PdfAnnotations(strokes = listOf(tooManyPoints))) }
        assertThrowsIllegalArgument {
            PdfAnnotationCodec.encode(
                PdfAnnotations(
                    notes = listOf(
                        PdfNote(0, 0.5f, 0.5f, "x".repeat(PdfAnnotations.MAX_NOTE_BYTES + 1)),
                    ),
                ),
            )
        }
        assertThrowsIllegalArgument {
            PdfAnnotations(
                strokes = listOf(
                    PdfInkStroke(0, PdfInkKind.INK, colorIndex = PdfAnnotations.PALETTE_SIZE, widthIndex = 0, points = listOf(PdfPoint(0f, 0f), PdfPoint(1f, 1f))),
                ),
            )
        }
        assertThrowsIllegalArgument {
            PdfAnnotations(
                strokes = listOf(
                    PdfInkStroke(
                        0,
                        PdfInkKind.INK,
                        0,
                        0,
                        listOf(PdfPoint(Float.NaN, 0f), PdfPoint(1f, 1f)),
                    ),
                ),
            )
        }
    }

    @Test
    fun `decode of truncated or corrupt blobs returns null`() {
        val encoded = PdfAnnotationCodec.encode(
            PdfAnnotations(
                strokes = listOf(
                    PdfInkStroke(0, PdfInkKind.INK, 0, 0, listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.9f, 0.9f))),
                ),
            ),
        )
        for (length in 0 until encoded.size) {
            assertNull("truncated at $length", PdfAnnotationCodec.decode(encoded.copyOf(length)))
        }
        assertNull(PdfAnnotationCodec.decode(encoded + byteArrayOf(0)))
        val wrongMagic = encoded.copyOf().also { it[0] = 0 }
        assertNull(PdfAnnotationCodec.decode(wrongMagic))
        val wrongVersion = encoded.copyOf().also { it[4] = 99 }
        assertNull(PdfAnnotationCodec.decode(wrongVersion))
    }

    @Test
    fun `random bytes never throw`() {
        val random = Random(20261117)
        repeat(1_000) {
            val bytes = ByteArray(random.nextInt(0, 256)) { random.nextInt(0, 256).toByte() }
            PdfAnnotationCodec.decode(bytes)
        }
    }

    @Test
    fun `encoded size stays bounded for a dense layer`() {
        val points = List(PdfAnnotations.MAX_POINTS_PER_STROKE) { index ->
            PdfPoint(index.toFloat() / PdfAnnotations.MAX_POINTS_PER_STROKE, 0.5f)
        }
        val annotations = PdfAnnotations(
            strokes = List(40) { page ->
                PdfInkStroke(page, PdfInkKind.INK, 0, 0, points)
            },
        )
        val encoded = PdfAnnotationCodec.encode(annotations)
        assertTrue(encoded.size <= PdfAnnotationCodec.MAX_BYTES)
        assertEquals(annotations, PdfAnnotationCodec.decode(encoded))
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
