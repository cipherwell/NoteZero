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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Tests for the encrypted standalone-sketch codec (deterministic, bounded, fail-closed). */
class SketchCodecTest {

    @Test
    fun `empty sketch round trips`() {
        val empty = SketchDocument(template = SketchTemplate.DOTTED)
        assertNotNull(SketchCodec.encode(empty))
        assertEquals(empty, SketchCodec.decode(SketchCodec.encode(empty)))
    }

    @Test
    fun `strokes and shapes round trip`() {
        val sketch = SketchDocument(
            template = SketchTemplate.RULED,
            strokes = listOf(
                SketchStroke(
                    points = listOf(
                        SketchPoint(0f, 0f),
                        SketchPoint(0.25f, 0.5f),
                        SketchPoint(1f, 1f),
                    ),
                    ink = SketchInk.BLUE,
                    width = SketchWidth.MEDIUM,
                    kind = SketchInkKind.HIGHLIGHTER,
                ),
            ),
            shapes = listOf(
                SketchShape(
                    kind = SketchShapeKind.ELLIPSE,
                    start = SketchPoint(0.1f, 0.2f),
                    end = SketchPoint(0.8f, 0.9f),
                    ink = SketchInk.ORANGE,
                    width = SketchWidth.THIN,
                ),
            ),
        )

        val decoded = SketchCodec.decode(SketchCodec.encode(sketch))

        assertNotNull(decoded)
        assertEquals(sketch, decoded)
    }

    @Test
    fun `bounds are enforced before encoding`() {
        assertThrowsIllegalArgument {
            SketchDocument(
                strokes = listOf(
                    SketchStroke(
                        points = List(SketchDocument.MAX_POINTS_PER_STROKE + 1) { SketchPoint(0.5f, 0.5f) },
                        ink = SketchInk.BLACK,
                        width = SketchWidth.THIN,
                    ),
                ),
            )
        }
        assertThrowsIllegalArgument {
            SketchDocument(
                strokes = listOf(
                    SketchStroke(
                        points = listOf(SketchPoint(-0.1f, 0f), SketchPoint(1f, 1f)),
                        ink = SketchInk.BLACK,
                        width = SketchWidth.THIN,
                    ),
                ),
            )
        }
        assertThrowsIllegalArgument {
            SketchDocument(shapes = List(SketchDocument.MAX_SHAPES + 1) {
                SketchShape(SketchShapeKind.LINE, SketchPoint(0f, 0f), SketchPoint(1f, 1f), SketchInk.GREY, SketchWidth.THIN)
            })
        }
    }

    @Test
    fun `decode of truncated or corrupt blobs returns null`() {
        val encoded = SketchCodec.encode(
            SketchDocument(
                strokes = listOf(
                    SketchStroke(listOf(SketchPoint(0.1f, 0.1f), SketchPoint(0.9f, 0.9f)), SketchInk.RED, SketchWidth.BOLD),
                ),
            ),
        )
        for (length in 0 until encoded.size) {
            assertNull("truncated at $length", SketchCodec.decode(encoded.copyOf(length)))
        }
        assertNull(SketchCodec.decode(encoded + byteArrayOf(0)))
        assertNull(SketchCodec.decode(encoded.copyOf().also { it[0] = 0 }))
        assertNull(SketchCodec.decode(encoded.copyOf().also { it[4] = 99 }))
    }

    @Test
    fun `random bytes never throw`() {
        val random = Random(20261117)
        repeat(1_000) {
            SketchCodec.decode(ByteArray(random.nextInt(0, 256)) { random.nextInt(0, 256).toByte() })
        }
    }

    @Test
    fun `dense sketches stay within the encoded cap`() {
        val points = List(SketchDocument.MAX_POINTS_PER_STROKE) { index ->
            SketchPoint(index.toFloat() / SketchDocument.MAX_POINTS_PER_STROKE, 0.5f)
        }
        val sketch = SketchDocument(
            strokes = List(40) {
                SketchStroke(points, SketchInk.GREEN, SketchWidth.MEDIUM)
            },
        )
        val encoded = SketchCodec.encode(sketch)
        assertTrue(encoded.size <= SketchCodec.MAX_BYTES)
        assertEquals(sketch, SketchCodec.decode(encoded))
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
