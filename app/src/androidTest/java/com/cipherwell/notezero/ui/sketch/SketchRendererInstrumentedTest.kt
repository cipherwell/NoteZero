package com.cipherwell.notezero.ui.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cipherwell.notezero.data.AttachmentLimits
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
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Device/emulator tests for the real in-memory PNG preview renderer.
 *
 * Robolectric covers the logic, but the renderer's promise is about the *platform* encoder and
 * decoder on a real device: the PNG must decode with `BitmapFactory`, stay inside the
 * [SketchRenderer.MAX_DIMENSION_PX] bound and the 10 MiB attachment cap, and a maximum-size
 * hostile vector (2,000 strokes / 500 shapes) must never crash the studio preview path or write
 * a plaintext file. The renderer has no Context by construction; the file-system assertion
 * documents that the preview path stays in memory.
 */
@RunWith(AndroidJUnit4::class)
class SketchRendererInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun stroke(
        from: SketchPoint,
        to: SketchPoint,
        ink: SketchInk = SketchInk.BLACK,
        width: SketchWidth = SketchWidth.MEDIUM,
        kind: SketchInkKind = SketchInkKind.PEN,
    ): SketchStroke = SketchStroke(listOf(from, to), ink, width, kind)

    private fun document(): SketchDocument = SketchDocument(
        template = SketchTemplate.GRID,
        strokes = listOf(
            stroke(SketchPoint(0.1f, 0.2f), SketchPoint(0.8f, 0.7f)),
            stroke(
                from = SketchPoint(0.2f, 0.8f),
                to = SketchPoint(0.7f, 0.3f),
                ink = SketchInk.ORANGE,
                width = SketchWidth.BOLD,
                kind = SketchInkKind.HIGHLIGHTER,
            ),
        ),
        shapes = listOf(
            SketchShape(
                kind = SketchShapeKind.ELLIPSE,
                start = SketchPoint(0.3f, 0.3f),
                end = SketchPoint(0.9f, 0.9f),
                ink = SketchInk.BLUE,
                width = SketchWidth.THIN,
            ),
            SketchShape(
                kind = SketchShapeKind.ARROW,
                start = SketchPoint(0.1f, 0.1f),
                end = SketchPoint(0.5f, 0.4f),
                ink = SketchInk.RED,
                width = SketchWidth.MEDIUM,
            ),
        ),
    )

    private fun decode(png: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(png, 0, png.size)

    @Test
    fun realRendererProducesAValidBoundedPng() {
        val png = SketchRenderer.renderPng(document())
        assertNotNull("the real renderer must produce PNG bytes", png)
        assertTrue(png!!.size > 8)
        // Real PNG signature, not a stub array.
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])

        val bitmap = decode(png)
        assertNotNull("the platform decoder must accept the preview PNG", bitmap)
        bitmap!!
        try {
            assertTrue("bounded width, was ${bitmap.width}", bitmap.width in 1..SketchRenderer.MAX_DIMENSION_PX)
            assertTrue("bounded height, was ${bitmap.height}", bitmap.height in 1..SketchRenderer.MAX_DIMENSION_PX)
            assertTrue("A4 portrait page", bitmap.height > bitmap.width)
            assertTrue(
                "A4 ratio, was ${bitmap.width}x${bitmap.height}",
                abs(bitmap.width.toFloat() / bitmap.height - SketchDocument.PAGE_ASPECT_RATIO) < 0.01f,
            )
        } finally {
            bitmap.recycle()
        }
        assertTrue(
            "preview must stay under the 10 MiB attachment cap",
            png.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES,
        )
    }

    @Test
    fun everyTemplateInkWidthKindAndShapeRenders() {
        SketchTemplate.entries.forEach { template ->
            SketchInk.entries.forEach { ink ->
                SketchWidth.entries.forEach { width ->
                    SketchInkKind.entries.forEach { kind ->
                        val document = SketchDocument(
                            template = template,
                            strokes = listOf(
                                stroke(
                                    from = SketchPoint(0.1f, 0.2f),
                                    to = SketchPoint(0.9f, 0.8f),
                                    ink = ink,
                                    width = width,
                                    kind = kind,
                                ),
                            ),
                        )
                        assertNotNull(
                            "template=$template ink=$ink width=$width kind=$kind",
                            SketchRenderer.renderPng(document, maxDimensionPx = 96),
                        )
                    }
                }
            }
        }
        SketchShapeKind.entries.forEach { shapeKind ->
            val document = SketchDocument(
                shapes = listOf(
                    SketchShape(
                        kind = shapeKind,
                        start = SketchPoint(0.2f, 0.2f),
                        end = SketchPoint(0.8f, 0.7f),
                        ink = SketchInk.GREEN,
                        width = SketchWidth.BOLD,
                    ),
                ),
            )
            assertNotNull("shape=$shapeKind", SketchRenderer.renderPng(document, maxDimensionPx = 96))
        }
    }

    @Test
    fun aHostileMaximumSizeSketchNeverCrashesAndStaysBounded() {
        // The frozen model bounds: 2,000 strokes / 100,000 points / 500 shapes. Build the worst
        // case the studio can ever persist and render it at the production preview size.
        val strokes = (0 until SketchDocument.MAX_STROKES).map { index ->
            SketchStroke(
                points = listOf(
                    SketchPoint(0.01f, (index % 100) / 100f),
                    SketchPoint(0.99f, 1f - (index % 100) / 100f),
                ),
                ink = SketchInk.entries[index % SketchInk.entries.size],
                width = SketchWidth.entries[index % SketchWidth.entries.size],
                kind = if (index % 2 == 0) SketchInkKind.PEN else SketchInkKind.HIGHLIGHTER,
            )
        }
        val shapes = (0 until SketchDocument.MAX_SHAPES).map { index ->
            SketchShape(
                kind = SketchShapeKind.entries[index % SketchShapeKind.entries.size],
                start = SketchPoint(0.05f, 0.05f),
                end = SketchPoint(0.9f, 0.9f),
                ink = SketchInk.PURPLE,
                width = SketchWidth.THIN,
            )
        }
        val hostile = SketchDocument(
            template = SketchTemplate.DOTTED,
            strokes = strokes,
            shapes = shapes,
        )

        val filesBefore = context.filesDir.walkTopDown().count()
        val png = SketchRenderer.renderPng(hostile)
        assertNotNull("a maximum-size document still renders a preview", png)
        assertTrue(png!!.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES)
        val bitmap = decode(png)
        assertNotNull(bitmap)
        bitmap!!
        try {
            assertTrue(bitmap.width <= SketchRenderer.MAX_DIMENSION_PX)
            assertTrue(bitmap.height <= SketchRenderer.MAX_DIMENSION_PX)
        } finally {
            bitmap.recycle()
        }
        // The renderer is in-memory only: it never creates a plaintext preview file.
        assertEquals(filesBefore, context.filesDir.walkTopDown().count())
    }

    @Test
    fun anUnboundedRequestIsClampedAndEmptyDocumentsRenderNothing() {
        assertNull(SketchRenderer.renderPng(SketchDocument()))
        assertNull(
            SketchRenderer.renderPng(SketchDocument(template = SketchTemplate.RULED)),
        )

        val png = SketchRenderer.renderPng(document(), maxDimensionPx = Int.MAX_VALUE)
        assertNotNull(png)
        val bitmap = decode(png!!)
        assertNotNull(bitmap)
        bitmap!!
        try {
            assertEquals(SketchRenderer.MAX_DIMENSION_PX, bitmap.height)
            assertTrue(bitmap.width <= SketchRenderer.MAX_DIMENSION_PX)
        } finally {
            bitmap.recycle()
        }
    }
}
