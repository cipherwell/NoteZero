package com.cipherwell.notezero.ui.sketch

import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import kotlin.math.abs

/**
 * Robolectric tests for the flattened-preview PNG renderer.
 *
 * The renderer is the only Android-aware part of the preview path and must stay bounded and
 * in-memory: these tests decode the produced bytes with the platform decoder (invalid image
 * data disabled so a stubbed bitmap cannot pass), assert the A4 portrait page and the 1600 px
 * bound, and feed a maximum-size hostile document through it. Fixtures are rendered small
 * except for the bound test so the suite stays fast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SketchRendererTest {

    private fun stroke(
        points: List<Pair<Float, Float>>,
        ink: SketchInk = SketchInk.BLACK,
        width: SketchWidth = SketchWidth.MEDIUM,
        kind: SketchInkKind = SketchInkKind.PEN,
    ): SketchStroke = SketchStroke(
        points = points.map { (x, y) -> SketchPoint(x, y) },
        ink = ink,
        width = width,
        kind = kind,
    )

    private fun lineDocument(
        template: SketchTemplate = SketchTemplate.BLANK,
    ): SketchDocument = SketchDocument(
        template = template,
        strokes = listOf(stroke(listOf(0.1f to 0.1f, 0.45f to 0.3f, 0.8f to 0.9f))),
    )

    private inline fun <T> decodeStrict(png: ByteArray, block: (android.graphics.Bitmap) -> T): T {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        try {
            val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertNotNull("the renderer must produce a real PNG", decoded)
            return block(decoded!!)
        } finally {
            ShadowBitmapFactory.setAllowInvalidImageData(true)
        }
    }

    @Test
    fun `renders a bounded a4 portrait png page`() {
        val png = SketchRenderer.renderPng(lineDocument(), maxDimensionPx = 128)

        assertNotNull(png)
        assertTrue(png!!.size > 8)
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
        decodeStrict(png) { bitmap ->
            assertTrue(bitmap.width in 1..128)
            assertTrue(bitmap.height in 1..128)
            assertTrue(
                "A4 portrait: ${bitmap.width}x${bitmap.height}",
                abs(bitmap.width.toFloat() / bitmap.height - SketchDocument.PAGE_ASPECT_RATIO) < 0.02f,
            )
        }
    }

    @Test
    fun `an empty document renders nothing`() {
        assertNull(SketchRenderer.renderPng(SketchDocument(), maxDimensionPx = 64))
        assertNull(
            SketchRenderer.renderPng(
                SketchDocument(template = SketchTemplate.GRID),
                maxDimensionPx = 64,
            ),
        )
    }

    @Test
    fun `every template renders`() {
        SketchTemplate.entries.forEach { template ->
            val png = SketchRenderer.renderPng(lineDocument(template), maxDimensionPx = 96)
            assertNotNull("template=$template", png)
            assertTrue("template=$template", png!!.size > 8)
        }
    }

    @Test
    fun `every ink width kind and shape renders`() {
        SketchInk.entries.forEach { ink ->
            SketchWidth.entries.forEach { width ->
                SketchInkKind.entries.forEach { kind ->
                    val document = SketchDocument(
                        template = SketchTemplate.DOTTED,
                        strokes = listOf(
                            stroke(
                                points = listOf(0.1f to 0.2f, 0.5f to 0.6f, 0.9f to 0.3f),
                                ink = ink,
                                width = width,
                                kind = kind,
                            ),
                        ),
                    )
                    val png = SketchRenderer.renderPng(document, maxDimensionPx = 64)
                    assertNotNull("ink=$ink width=$width kind=$kind", png)
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
                        ink = SketchInk.BLUE,
                        width = SketchWidth.BOLD,
                    ),
                ),
            )
            assertNotNull("shape=$shapeKind", SketchRenderer.renderPng(document, maxDimensionPx = 64))
        }
    }

    @Test
    fun `a big request is clamped and stays under the attachment cap`() {
        val png = SketchRenderer.renderPng(lineDocument(SketchTemplate.RULED), maxDimensionPx = 1_000_000)

        assertNotNull(png)
        assertTrue(png!!.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES)
        decodeStrict(png) { bitmap ->
            assertTrue(bitmap.width <= SketchRenderer.MAX_DIMENSION_PX)
            assertTrue(bitmap.height <= SketchRenderer.MAX_DIMENSION_PX)
            assertTrue(bitmap.width >= 1 && bitmap.height >= 1)
        }
        assertEquals(1600, SketchRenderer.pageSize(1_000_000).heightPx)
    }

    @Test
    fun `renders off the main thread`() {
        // The studio renders the preview on Dispatchers.Default; the renderer must be callable
        // from a worker without touching a Context, a file or a UI resource.
        val png = runBlocking {
            withContext(Dispatchers.Default) {
                SketchRenderer.renderPng(lineDocument(SketchTemplate.GRID), maxDimensionPx = 96)
            }
        }
        assertNotNull(png)
    }

    @Test
    fun `a maximum-size document renders without crashing`() {
        val strokes = (0 until SketchDocument.MAX_STROKES).map { index ->
            val y = (index % 100) / 100f
            SketchStroke(
                points = listOf(SketchPoint(0.1f, y), SketchPoint(0.9f, 1f - y)),
                ink = SketchInk.entries[index % SketchInk.entries.size],
                width = SketchWidth.entries[index % SketchWidth.entries.size],
                kind = if (index % 2 == 0) SketchInkKind.PEN else SketchInkKind.HIGHLIGHTER,
            )
        }
        val shapes = (0 until SketchDocument.MAX_SHAPES).map { index ->
            SketchShape(
                kind = SketchShapeKind.entries[index % SketchShapeKind.entries.size],
                start = SketchPoint(0.05f, 0.05f),
                end = SketchPoint(0.5f + (index % 40) / 100f, 0.5f),
                ink = SketchInk.GREEN,
                width = SketchWidth.THIN,
            )
        }
        val hostile = SketchDocument(
            template = SketchTemplate.DOTTED,
            strokes = strokes,
            shapes = shapes,
        )
        val png = SketchRenderer.renderPng(hostile, maxDimensionPx = 256)
        assertNotNull(png)
        assertTrue(png!!.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES)
    }

    @Test
    fun `preview names are recognised by the studio`() {
        assertTrue(SketchRenderer.isPreviewName("sketch-1700000000000.png"))
        assertTrue(!SketchRenderer.isPreviewName("drawing-1.png"))
        assertTrue(!SketchRenderer.isPreviewName("sketch-1.png.bak"))
        assertEquals("image/png", SketchRenderer.MIME_TYPE)
    }
}
