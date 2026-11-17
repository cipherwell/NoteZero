package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfPoint
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Robolectric tests for the PDF annotation editor and the flattened-export wiring.
 *
 * The platform `PdfRenderer`/`android.graphics.pdf` have no Robolectric implementation, so the
 * screen runs with an injected page renderer, an injected [PdfFlattenedExporter] and an injected
 * [PdfExportGateway]; the real exporter is covered by `PdfFlattenedExportInstrumentedTest` on a
 * device. Annotations are asserted through the encrypted repository item (`pdfAnnotations`), not
 * through pixel probes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfAnnotationScreenTest : UiTestBase() {

    private class FakeFlattenedExporter : PdfFlattenedExporter {
        var calls: Int = 0
        var exportedPages: PdfPages? = null
        var exportedAnnotations: PdfAnnotations? = null
        var result: ByteArray? = "%PDF-1.4 fake flattened".toByteArray()

        override fun export(pages: PdfPages, annotations: PdfAnnotations): ByteArray? {
            calls += 1
            exportedPages = pages
            exportedAnnotations = annotations
            return result
        }
    }

    private class FakeExportGateway : PdfExportGateway {
        var suggestedName: String? = null
        var writtenUri: Uri? = null
        var writtenBytes: ByteArray? = null
        var writeResult: Boolean = true
        var destination: Uri? = DESTINATION

        override fun pickDestination(suggestedName: String, onPicked: (Uri?) -> Unit) {
            this.suggestedName = suggestedName
            onPicked(destination)
        }

        override fun write(uri: Uri, bytes: ByteArray): Boolean {
            writtenUri = uri
            writtenBytes = bytes.copyOf()
            return writeResult
        }

        companion object {
            val DESTINATION: Uri = Uri.parse("content://test/annotated.pdf")
        }
    }

    private var closed = 0

    private fun repositoryWithPdf(): Pair<FakeNoteRepository, String> {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.UNLOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val created = runBlocking {
            val item = repository.createItem(ItemKind.PDF_FILE, null)
            repository.saveItem(item.copy(title = "document.pdf"))
        }
        runBlocking {
            repository.importAttachment(
                noteId = created.id,
                displayName = "document.pdf",
                mimeType = BlankPdf.MIME_TYPE,
                source = ByteArrayInputStream(BlankPdf.create()),
            )
        }
        return repository to created.id
    }

    private fun pageRenderer(pages: Int = 1, total: Int = pages): PdfPageRenderer = PdfPageRenderer { _, _ ->
        val bitmaps = (0 until pages).map {
            Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        }
        PdfPages(bitmaps, total)
    }

    private fun openScreen(
        repository: FakeNoteRepository,
        itemId: String,
        exporter: FakeFlattenedExporter = FakeFlattenedExporter(),
        gateway: FakeExportGateway = FakeExportGateway(),
        pages: Int = 1,
        total: Int = pages,
    ) {
        closed = 0
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = { closed += 1 },
                renderer = pageRenderer(pages = pages, total = total),
                flattenedExporter = exporter,
                exportGateway = gateway,
                exportDispatcher = Dispatchers.Unconfined,
            )
        }
        composeRule.waitForIdle()
    }

    private fun layer(repository: FakeNoteRepository, itemId: String): PdfAnnotations? =
        repository.items.value.firstOrNull { it.id == itemId }?.pdfAnnotations

    private fun waitForLayer(
        repository: FakeNoteRepository,
        itemId: String,
        predicate: (PdfAnnotations?) -> Boolean,
    ) {
        composeRule.waitUntil(5_000) { predicate(layer(repository, itemId)) }
    }

    /** Palette rows scroll horizontally (large-font safe); scroll the control into view first. */
    private fun clickPalette(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun ComposeContentTestRule.enterAnnotateMode() {
        onNodeWithTag("pdf_annotate_toggle").performClick()
        waitForIdle()
        onNodeWithTag("pdf_annotate_palette").assertExists()
    }

    private fun dragOnPage(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        composeRule.onNodeWithTag("pdf_annotation_overlay").performTouchInput {
            val start = Offset(width * fromX, height * fromY)
            val end = Offset(width * toX, height * toY)
            down(start)
            moveTo(Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f))
            moveTo(end)
            up()
        }
        composeRule.waitForIdle()
    }

    private fun tapOnPage(x: Float, y: Float) {
        composeRule.onNodeWithTag("pdf_annotation_overlay").performTouchInput {
            click(Offset(width * x, height * y))
        }
        composeRule.waitForIdle()
    }

    private fun strokeLayer(vararg strokes: PdfInkStroke): PdfAnnotations =
        PdfAnnotations(strokes = strokes.toList())

    private fun diagonalStroke(page: Int = 0): PdfInkStroke = PdfInkStroke(
        page = page,
        kind = PdfInkKind.INK,
        colorIndex = 0,
        widthIndex = 0,
        points = listOf(PdfPoint(0.2f, 0.2f), PdfPoint(0.5f, 0.5f), PdfPoint(0.8f, 0.8f)),
    )

    @Test
    fun `annotate toggle shows the tool palette and Done returns to scroll mode`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)

        composeRule.enterAnnotateMode()
        composeRule.onNodeWithTag("pdf_tool_ink").assertExists()
        composeRule.onNodeWithTag("pdf_tool_highlighter").assertExists()
        composeRule.onNodeWithTag("pdf_tool_rect").assertExists()
        composeRule.onNodeWithTag("pdf_tool_eraser").assertExists()
        composeRule.onNodeWithTag("pdf_tool_note").assertExists()
        composeRule.onNodeWithTag("pdf_tool_undo").assertExists()
        composeRule.onNodeWithTag("pdf_tool_redo").assertExists()
        composeRule.onNodeWithTag("pdf_annotate_hint").assertExists()
        for (index in 0 until PdfAnnotationPalette.PALETTE_SIZE) {
            composeRule.onNodeWithTag("pdf_color_$index").assertExists()
        }
        for (index in 0 until PdfAnnotationPalette.WIDTH_LEVELS) {
            composeRule.onNodeWithTag("pdf_width_$index").assertExists()
        }
        composeRule.onNodeWithTag("pdf_annotate_done").assertExists()

        composeRule.onNodeWithTag("pdf_annotate_done").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("pdf_annotate_palette").fetchSemanticsNodes().size)
    }

    @Test
    fun `back leaves annotate mode before closing the viewer`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)
        composeRule.enterAnnotateMode()

        composeRule.onNodeWithTag("pdf_back").performClick()
        composeRule.waitForIdle()
        assertEquals(0, closed)
        assertEquals(0, composeRule.onAllNodesWithTag("pdf_annotate_palette").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("pdf_back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `ink drag creates and persists a stroke`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)
        composeRule.enterAnnotateMode()

        dragOnPage(fromX = 0.2f, fromY = 0.2f, toX = 0.8f, toY = 0.8f)
        waitForLayer(repository, itemId) { it?.strokes?.size == 1 }

        val stroke = layer(repository, itemId)!!.strokes.single()
        assertEquals(0, stroke.page)
        assertEquals(PdfInkKind.INK, stroke.kind)
        assertEquals(0, stroke.colorIndex)
        assertEquals(1, stroke.widthIndex)
        assertTrue("a drag must collect at least two points", stroke.points.size >= 2)
        stroke.points.forEach { point ->
            assertTrue(point.x in 0f..1f)
            assertTrue(point.y in 0f..1f)
        }
        composeRule.onNodeWithTag("pdf_tool_undo").assertIsEnabled()
    }

    @Test
    fun `highlighter and colour selection are stored on the stroke`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)
        composeRule.enterAnnotateMode()

        clickPalette("pdf_tool_highlighter")
        clickPalette("pdf_color_2")
        clickPalette("pdf_width_2")

        dragOnPage(fromX = 0.1f, fromY = 0.5f, toX = 0.9f, toY = 0.5f)
        waitForLayer(repository, itemId) { it?.strokes?.size == 1 }

        val stroke = layer(repository, itemId)!!.strokes.single()
        assertEquals(PdfInkKind.HIGHLIGHTER, stroke.kind)
        assertEquals(2, stroke.colorIndex)
        assertEquals(2, stroke.widthIndex)
    }

    @Test
    fun `rectangle tool stores a normalised highlight`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)
        composeRule.enterAnnotateMode()

        clickPalette("pdf_tool_rect")
        // Drag right-to-left so the editor's normalisation is exercised too.
        dragOnPage(fromX = 0.8f, fromY = 0.7f, toX = 0.3f, toY = 0.2f)
        waitForLayer(repository, itemId) { it?.highlights?.size == 1 }

        val highlight = layer(repository, itemId)!!.highlights.single()
        assertEquals(0, highlight.page)
        assertTrue(highlight.left < highlight.right)
        assertTrue(highlight.top < highlight.bottom)
        assertTrue(highlight.left in 0f..1f && highlight.right in 0f..1f)
        assertTrue(highlight.top in 0f..1f && highlight.bottom in 0f..1f)
    }

    @Test
    fun `sticky note can be created, reopened and deleted`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)
        composeRule.enterAnnotateMode()

        clickPalette("pdf_tool_note")
        tapOnPage(0.5f, 0.5f)
        composeRule.onNodeWithTag("pdf_note_dialog").assertExists()
        composeRule.onNodeWithTag("pdf_note_text").performTextInput("Remember this spot")
        composeRule.onNodeWithTag("pdf_note_save").performClick()
        waitForLayer(repository, itemId) { it?.notes?.size == 1 }

        val note = layer(repository, itemId)!!.notes.single()
        assertEquals("Remember this spot", note.text)

        // Tapping the marker again reopens the stored note (with its delete action).
        tapOnPage(0.5f, 0.5f)
        composeRule.onNodeWithTag("pdf_note_dialog").assertExists()
        composeRule.onNodeWithTag("pdf_note_text").assertExists()
        composeRule.onNodeWithTag("pdf_note_delete").assertExists()
        composeRule.onNodeWithTag("pdf_note_delete").performClick()
        waitForLayer(repository, itemId) { it == null }

        assertNull("an empty layer must clear the stored value", layer(repository, itemId))
    }

    @Test
    fun `eraser removes a stroke and undo and redo restore it`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)
        composeRule.enterAnnotateMode()
        composeRule.onNodeWithTag("pdf_tool_undo").assertIsNotEnabled()
        composeRule.onNodeWithTag("pdf_tool_redo").assertIsNotEnabled()

        dragOnPage(fromX = 0.2f, fromY = 0.5f, toX = 0.8f, toY = 0.5f)
        waitForLayer(repository, itemId) { it?.strokes?.size == 1 }
        composeRule.onNodeWithTag("pdf_tool_undo").assertIsEnabled()

        clickPalette("pdf_tool_eraser")
        tapOnPage(0.5f, 0.5f)
        waitForLayer(repository, itemId) { it == null }
        composeRule.onNodeWithTag("pdf_tool_redo").assertIsNotEnabled()

        clickPalette("pdf_tool_undo")
        waitForLayer(repository, itemId) { it?.strokes?.size == 1 }
        composeRule.onNodeWithTag("pdf_tool_redo").assertIsEnabled()

        clickPalette("pdf_tool_redo")
        waitForLayer(repository, itemId) { it == null }
    }

    @Test
    fun `annotations saved in the repository are loaded on open`() {
        val (repository, itemId) = repositoryWithPdf()
        val stored = strokeLayer(diagonalStroke())
        runBlocking { repository.savePdfAnnotations(itemId, stored) }
        openScreen(repository, itemId)

        // A non-empty layer changes the export action into the original/annotated choice.
        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_choice").assertExists()
        composeRule.onNodeWithTag("pdf_export_choice_cancel").performClick()
        composeRule.waitForIdle()

        // The loaded stroke is erasable through the UI.
        composeRule.enterAnnotateMode()
        clickPalette("pdf_tool_eraser")
        tapOnPage(0.5f, 0.5f)
        waitForLayer(repository, itemId) { it == null }
    }

    @Test
    fun `export without annotations goes straight to the warning`() {
        val (repository, itemId) = repositoryWithPdf()
        openScreen(repository, itemId)

        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_warning").assertExists()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("pdf_export_choice").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `annotated export invokes the injected exporter and writes the bytes`() {
        val (repository, itemId) = repositoryWithPdf()
        val stored = strokeLayer(diagonalStroke())
        runBlocking { repository.savePdfAnnotations(itemId, stored) }
        val exporter = FakeFlattenedExporter()
        val gateway = FakeExportGateway()
        openScreen(repository, itemId, exporter = exporter, gateway = gateway)

        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_choice").assertExists()
        composeRule.onNodeWithTag("pdf_export_annotated").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_warning").assertExists()
        composeRule.onNodeWithTag("pdf_export_confirm").performClick()

        composeRule.waitUntil(5_000) { gateway.writtenBytes != null }
        composeRule.waitForIdle()

        assertEquals(1, exporter.calls)
        assertEquals(stored, exporter.exportedAnnotations)
        assertEquals(1, exporter.exportedPages?.totalPages)
        assertEquals(FakeExportGateway.DESTINATION, gateway.writtenUri)
        assertNotNull(gateway.writtenBytes)
        assertEquals(
            exporter.result!!.toList(),
            gateway.writtenBytes!!.toList(),
        )
        assertEquals("document-annotated.pdf", gateway.suggestedName)
        composeRule.onNodeWithText("Annotated PDF written outside the vault.").assertExists()
    }

    @Test
    fun `original export option keeps the existing decrypted-copy flow`() {
        val (repository, itemId) = repositoryWithPdf()
        runBlocking { repository.savePdfAnnotations(itemId, strokeLayer(diagonalStroke())) }
        val exporter = FakeFlattenedExporter()
        val gateway = FakeExportGateway()
        openScreen(repository, itemId, exporter = exporter, gateway = gateway)

        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_original").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_export_warning").assertExists()
        assertEquals("the flattened exporter must not run", 0, exporter.calls)
        assertNull(gateway.writtenBytes)
    }

    @Test
    fun `annotated export failure writes nothing and reports the failure`() {
        val (repository, itemId) = repositoryWithPdf()
        runBlocking { repository.savePdfAnnotations(itemId, strokeLayer(diagonalStroke())) }
        val exporter = FakeFlattenedExporter().apply { result = null }
        val gateway = FakeExportGateway()
        openScreen(repository, itemId, exporter = exporter, gateway = gateway)

        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_annotated").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_confirm").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("The annotated PDF could not be written.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(gateway.writtenBytes)
    }

    @Test
    fun `cancelled annotated export writes nothing`() {
        val (repository, itemId) = repositoryWithPdf()
        runBlocking { repository.savePdfAnnotations(itemId, strokeLayer(diagonalStroke())) }
        val exporter = FakeFlattenedExporter()
        val gateway = FakeExportGateway().apply { destination = null }
        openScreen(repository, itemId, exporter = exporter, gateway = gateway)

        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_annotated").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_confirm").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Export cancelled.").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(gateway.writtenBytes)
        assertEquals("a cancelled picker must not flatten anything", 0, exporter.calls)
    }

    @Test
    fun `annotate toggle is hidden after the document fails to render`() {
        val (repository, itemId) = repositoryWithPdf()
        closed = 0
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = { closed += 1 },
                renderer = PdfPageRenderer { _, _ -> PdfPages(emptyList(), 0) },
                flattenedExporter = FakeFlattenedExporter(),
                exportGateway = FakeExportGateway(),
                exportDispatcher = Dispatchers.Unconfined,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_error").assertExists()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("pdf_annotate_toggle").fetchSemanticsNodes().size,
        )
    }
}
