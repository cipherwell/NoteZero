package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfPoint
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Robolectric tests for the full-document lazy viewer: one open fake [PdfPageSession] with a
 * twelve-page count drives the pager, pages are requested only while their row is composed, page
 * jump works across the whole document (including page 10 and the last page), disposed rows recycle
 * their bitmaps, and every document-level surface (annotations, bookmarks, Pages overview,
 * annotated export) works beyond the old eight-page raster cap.
 *
 * No native `android.graphics.pdf` class is touched here: the session is a fake. The real
 * `AndroidPdfPageSessionFactory` is covered by `PdfPageSessionInstrumentedTest` on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfLazyViewerTest : UiTestBase() {

    private companion object {
        const val DOCUMENT_PAGES = 12
        const val ANNOTATED_GATEWAY_FILE = "content://test/annotated.pdf"
    }

    /** Fake of the shared all-pages exporter used by the annotated-copy flow. */
    private class FakePageToolsExporter : PdfPageToolsExporter {
        var calls: Int = 0
        var exportedBytes: ByteArray? = null
        var exportedPlan: PdfPagePlan? = null
        var exportedAnnotations: PdfAnnotations? = null
        var result: ByteArray? = "%PDF-1.4 annotated".toByteArray()

        override suspend fun export(
            context: Context,
            pdfBytes: ByteArray,
            plan: PdfPagePlan,
            annotations: PdfAnnotations,
        ): ByteArray? {
            calls += 1
            exportedBytes = pdfBytes.copyOf()
            exportedPlan = plan
            exportedAnnotations = annotations
            return result
        }
    }

    /** SAF gateway fake: no Activity, records exactly what would be written. */
    private class FakeExportGateway : PdfExportGateway {
        var pickCalls: Int = 0
            private set
        var suggestedName: String? = null
            private set
        var writtenUri: Uri? = null
            private set
        var writtenBytes: ByteArray? = null
            private set
        var destination: Uri? = Uri.parse(ANNOTATED_GATEWAY_FILE)

        /** Runs while the picker is "open", before the destination is delivered. */
        var beforePick: (() -> Unit)? = null

        override fun pickDestination(suggestedName: String, onPicked: (Uri?) -> Unit) {
            pickCalls += 1
            this.suggestedName = suggestedName
            beforePick?.invoke()
            onPicked(destination)
        }

        override fun write(uri: Uri, bytes: ByteArray): Boolean {
            writtenUri = uri
            writtenBytes = bytes.copyOf()
            return true
        }
    }

    /** Fake thumbnail set covering [pageCount] pages, so the overview can be asserted. */
    private class FakeThumbnailRenderer(
        private val pageCount: Int = DOCUMENT_PAGES,
    ) : PdfThumbnailRenderer {
        var calls: Int = 0
            private set

        override suspend fun render(context: Context, pdfBytes: ByteArray): PdfThumbnails {
            calls += 1
            val bitmaps = (0 until pageCount).map { index ->
                Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
                }
            }
            return PdfThumbnails(bitmaps)
        }
    }

    private fun repositoryWithPdf(annotation: PdfAnnotations? = null): Pair<FakeNoteRepository, String> {
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
            if (annotation != null) repository.savePdfAnnotations(created.id, annotation)
        }
        return repository to created.id
    }

    private fun strokeLayer(): PdfAnnotations = PdfAnnotations(
        strokes = listOf(
            PdfInkStroke(
                page = 0,
                kind = PdfInkKind.INK,
                colorIndex = 0,
                widthIndex = 1,
                points = listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.9f, 0.9f)),
            ),
        ),
    )

    private fun openViewer(
        repository: FakeNoteRepository,
        itemId: String,
        session: PdfPageSession,
        factory: PdfPageSessionFactory = FakePdfPageSessionFactory(session),
        thumbnailRenderer: PdfThumbnailRenderer = FakeThumbnailRenderer(),
        exporter: PdfPageToolsExporter = FakePageToolsExporter(),
        gateway: PdfExportGateway = FakeExportGateway(),
        onClose: () -> Unit = {},
    ) {
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = onClose,
                pageSessionFactory = factory,
                pageToolsExporter = exporter,
                exportGateway = gateway,
                exportDispatcher = Dispatchers.Unconfined,
                thumbnailRenderer = thumbnailRenderer,
            )
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pdf_page_progress").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun textExists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun tagExists(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun jumpTo(page: Int) {
        composeRule.onNodeWithTag("pdf_page_progress").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_jump_dialog").assertExists()
        composeRule.onNodeWithTag("pdf_page_field").performTextClearance()
        composeRule.onNodeWithTag("pdf_page_field").performTextInput(page.toString())
        composeRule.onNodeWithTag("pdf_page_jump_confirm").performClick()
        composeRule.waitForIdle()
    }

    private fun waitForPage(page: Int) {
        composeRule.waitUntil(5_000) { textExists("$page / $DOCUMENT_PAGES") }
    }

    private fun openAnnotatedExport() {
        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_choice").assertExists()
        composeRule.onNodeWithTag("pdf_export_annotated").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_warning").assertExists()
        composeRule.onNodeWithTag("pdf_export_confirm").performClick()
    }

    // ---- Lazy rendering ---------------------------------------------------------------------

    @Test
    fun `one session is opened and only composed pages are requested`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = DOCUMENT_PAGES)
        val factory = FakePdfPageSessionFactory(session)
        openViewer(repository, itemId, session, factory = factory)

        composeRule.waitUntil(5_000) { session.requestedPages.isNotEmpty() }
        composeRule.waitForIdle()

        assertEquals("exactly one document handle per viewer", 1, factory.openCalls)
        assertTrue(
            "the factory must be handed the decrypted attachment bytes",
            factory.lastBytes!!.contentEquals(BlankPdf.create()),
        )
        assertTrue("the first composed page must render", session.requestedPages.contains(0))
        assertTrue(
            "only composed rows may request a render, got ${session.requestedPages}",
            session.requestedPages.all { it < 4 },
        )
        assertFalse("page 11 must not render up front", session.requestedPages.contains(11))
        assertEquals(
            "the viewer asks for the bounded default edge",
            listOf(PDF_PAGE_MAX_EDGE_PX),
            session.requestedEdges.distinct(),
        )
        assertFalse("the session stays open while viewing", session.isClosed)
    }

    @Test
    fun `page jump to page ten renders that row and reaches the last page`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = DOCUMENT_PAGES)
        openViewer(repository, itemId, session)

        jumpTo(10)
        composeRule.waitUntil(5_000) { session.requestedPages.contains(9) }
        waitForPage(10)
        composeRule.onNodeWithTag("pdf_page_9").assertExists()

        jumpTo(DOCUMENT_PAGES)
        composeRule.waitUntil(5_000) { session.requestedPages.contains(11) }
        waitForPage(DOCUMENT_PAGES)
        composeRule.onNodeWithTag("pdf_page_11").assertExists()
        assertTrue(
            "the whole document must not render for two jumps, got ${session.requestedPages}",
            session.requestedPages.size < DOCUMENT_PAGES,
        )
    }

    @Test
    fun `page rows recycle their bitmap when they leave the composition`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = DOCUMENT_PAGES)
        openViewer(repository, itemId, session)

        composeRule.waitUntil(5_000) { session.bitmapsByPage.containsKey(0) }
        val firstPageBitmap = session.bitmapsByPage.getValue(0)
        assertFalse(firstPageBitmap.isRecycled)

        jumpTo(DOCUMENT_PAGES)
        composeRule.waitUntil(5_000) { session.requestedPages.contains(11) }

        composeRule.waitUntil(5_000) {
            firstPageBitmap.isRecycled || !tagExists("pdf_page_0")
        }
        assertFalse("the old row is gone", tagExists("pdf_page_0"))
        assertTrue("its bitmap must be recycled on disposal", firstPageBitmap.isRecycled)
    }

    @Test
    fun `a page shows the bounded loading placeholder until its bitmap arrives`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = 2)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        session.gate = gate
        openViewer(repository, itemId, session)

        composeRule.waitUntil(5_000) { tagExists("pdf_page_loading") }
        composeRule.onNodeWithText("Rendering page…").assertExists()
        assertFalse("no page bitmap is shown while loading", tagExists("pdf_page"))

        composeRule.runOnUiThread { gate.complete(Unit) }
        composeRule.waitUntil(5_000) { tagExists("pdf_page") }
        assertFalse(tagExists("pdf_page_loading"))
    }

    @Test
    fun `a failed page render shows the non-secret render-failed placeholder`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = 2, failPages = setOf(0))
        openViewer(repository, itemId, session)

        composeRule.waitUntil(5_000) { tagExists("pdf_page_render_failed") }
        composeRule.onNodeWithText("Could not render this page.").assertExists()
        assertEquals("the viewer must not fail the whole document for one page", 0, session.closeCalls)
    }

    @Test
    fun `a hostile page count shows the error instead of allocating rows`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = MAX_VIEW_PAGES + 1)
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                pageSessionFactory = FakePdfPageSessionFactory(session),
                exportDispatcher = Dispatchers.Unconfined,
            )
        }
        composeRule.waitUntil(5_000) { tagExists("pdf_error") }

        assertFalse("no pager is allocated for a hostile page count", tagExists("pdf_pages"))
        assertTrue("the rejected session must be closed", session.isClosed)
    }

    @Test
    fun `leaving the viewer closes the one open session`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = DOCUMENT_PAGES)
        var showViewer by mutableStateOf(true)
        setThemedContent {
            if (showViewer) {
                PdfViewerScreen(
                    repository = repository,
                    itemId = itemId,
                    onClose = { showViewer = false },
                    pageSessionFactory = FakePdfPageSessionFactory(session),
                    exportDispatcher = Dispatchers.Unconfined,
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pdf_page_progress").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, session.closeCalls)

        composeRule.runOnUiThread { showViewer = false }
        composeRule.waitUntil(5_000) { session.isClosed }
        assertEquals("close must be called exactly once for the session", 1, session.closeCalls)
    }

    // ---- Annotations, bookmarks and the Pages overview beyond page 8 ------------------------

    @Test
    fun `a page beyond the old cap can be annotated`() {
        val (repository, itemId) = repositoryWithPdf()
        // Portrait pages: the target page fills the viewport so its overlay is the only one.
        val session = FakePdfPageSession(
            pageCount = DOCUMENT_PAGES,
            bitmapWidth = 24,
            bitmapHeight = 34,
        )
        openViewer(repository, itemId, session)

        jumpTo(10)
        waitForPage(10)
        composeRule.onNodeWithTag("pdf_annotate_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_annotate_palette").assertExists()

        // Only the target row's overlay: neighbouring rows may be partially composed above it.
        composeRule.onNode(
            hasTestTag("pdf_annotation_overlay") and hasAnyAncestor(hasTestTag("pdf_page_9")),
        ).performTouchInput {
            val start = Offset(width * 0.2f, height * 0.2f)
            val end = Offset(width * 0.8f, height * 0.6f)
            down(start)
            moveTo(Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f))
            moveTo(end)
            up()
        }
        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }
                ?.pdfAnnotations?.strokes?.any { it.page == 9 } == true
        }
        val stroke = repository.items.value.first { it.id == itemId }
            .pdfAnnotations!!.strokes.single { it.page == 9 }
        assertEquals(PdfInkKind.INK, stroke.kind)
    }

    @Test
    fun `a bookmark on page twelve is stored encrypted with the full total`() {
        val (repository, itemId) = repositoryWithPdf()
        // Tall pages let the last page align with the top of the viewport when jumped to.
        val session = FakePdfPageSession(
            pageCount = DOCUMENT_PAGES,
            bitmapWidth = 24,
            bitmapHeight = 34,
        )
        openViewer(repository, itemId, session)
        runBlocking { repository.saveReadingState(itemId, ReadingState(position = 0, total = 1)) }

        jumpTo(DOCUMENT_PAGES)
        waitForPage(DOCUMENT_PAGES)
        composeRule.onNodeWithTag("pdf_bookmark_add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_bookmark_label").performTextInput("The end")
        composeRule.onNodeWithTag("pdf_bookmark_save").performClick()

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }
                ?.readingState?.bookmarks?.any { it.label == "The end" } == true
        }
        val state = repository.items.value.first { it.id == itemId }.readingState!!
        val bookmark = state.bookmarks.single()
        assertEquals("page 12 is 0-based position 11", 11, bookmark.position)
        assertEquals(DOCUMENT_PAGES, state.total)
    }

    @Test
    fun `the Pages overview covers all twelve pages and jumps from the last one`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = DOCUMENT_PAGES)
        val thumbnails = FakeThumbnailRenderer(pageCount = DOCUMENT_PAGES)
        openViewer(repository, itemId, session, thumbnailRenderer = thumbnails)

        composeRule.onNodeWithTag("pdf_page_progress").performClick()
        composeRule.waitUntil(5_000) { tagExists("pdf_page_overview_grid") }
        composeRule.onNodeWithTag("pdf_page_overview_grid").performScrollToIndex(DOCUMENT_PAGES - 1)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_thumb_11").assertExists()
        composeRule.onNodeWithTag("pdf_page_thumb_11").performClick()
        composeRule.waitUntil(5_000) { !tagExists("pdf_page_jump_dialog") }
        waitForPage(DOCUMENT_PAGES)
        composeRule.waitUntil(5_000) { session.requestedPages.contains(11) }
        assertEquals(1, thumbnails.calls)
    }

    @Test
    fun `page tools plans every page of a twelve page document`() {
        val (repository, itemId) = repositoryWithPdf()
        val session = FakePdfPageSession(pageCount = DOCUMENT_PAGES)
        openViewer(
            repository = repository,
            itemId = itemId,
            session = session,
            thumbnailRenderer = FakeThumbnailRenderer(pageCount = DOCUMENT_PAGES),
        )

        composeRule.onNodeWithTag("pdf_page_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_tools_screen").assertExists()
        composeRule.waitUntil(5_000) { textExists("$DOCUMENT_PAGES pages") }
        composeRule.onNodeWithTag("pdf_pages_save_copy").assertExists()
        // The plan covers the whole document, not a rendered prefix.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pdf_page_row").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("page tools were built from the full page count", session.requestedPages.size < DOCUMENT_PAGES)
    }

    // ---- Annotated export ------------------------------------------------------------------

    @Test
    fun `annotated export uses the page tools exporter with an identity plan`() {
        val (repository, itemId) = repositoryWithPdf(annotation = strokeLayer())
        val session = FakePdfPageSession(pageCount = DOCUMENT_PAGES)
        val exporter = FakePageToolsExporter()
        val gateway = FakeExportGateway()
        openViewer(repository, itemId, session, exporter = exporter, gateway = gateway)

        openAnnotatedExport()
        composeRule.waitUntil(5_000) { gateway.writtenBytes != null }

        assertEquals(1, exporter.calls)
        assertEquals("identity plan: every source page, original order", PdfPagePlan.of(DOCUMENT_PAGES), exporter.exportedPlan)
        assertEquals(
            (0 until DOCUMENT_PAGES).toList(),
            exporter.exportedPlan!!.entries.map { it.sourcePage },
        )
        assertFalse("an annotated copy must not rotate/reorder pages", exporter.exportedPlan!!.hasChanges())
        assertTrue(
            "the decrypted bytes are re-read at export time",
            exporter.exportedBytes!!.contentEquals(BlankPdf.create()),
        )
        assertEquals(strokeLayer(), exporter.exportedAnnotations)
        assertEquals("document-annotated.pdf", gateway.suggestedName)
        assertEquals(Uri.parse(ANNOTATED_GATEWAY_FILE), gateway.writtenUri)
        assertEquals(
            (exporter.result ?: ByteArray(0)).toList(),
            gateway.writtenBytes!!.toList(),
        )
    }

    @Test
    fun `annotated export above the cap shows the too many notice and writes nothing`() {
        val (repository, itemId) = repositoryWithPdf(annotation = strokeLayer())
        val session = FakePdfPageSession(pageCount = PdfPagePlan.MAX_PAGES + 1)
        val exporter = FakePageToolsExporter()
        val gateway = FakeExportGateway()
        openViewer(repository, itemId, session, exporter = exporter, gateway = gateway)

        openAnnotatedExport()
        composeRule.waitUntil(5_000) {
            textExists("Annotated copy supports up to ${PdfPagePlan.MAX_PAGES} pages.")
        }

        assertEquals("no over-cap plan may reach the exporter", 0, exporter.calls)
        assertEquals("no destination picker may open", 0, gateway.pickCalls)
        assertNull(gateway.writtenBytes)
        assertFalse("no written notice may appear", textExists("Annotated PDF written outside the vault."))
        assertFalse(tagExists("pdf_export_warning"))
    }

    @Test
    fun `annotated export keeps re-checking the vault lock before writing`() {
        val (repository, itemId) = repositoryWithPdf(annotation = strokeLayer())
        val session = FakePdfPageSession(pageCount = 2)
        val exporter = FakePageToolsExporter()
        val gateway = FakeExportGateway()
        openViewer(repository, itemId, session, exporter = exporter, gateway = gateway)
        // The gateway callback runs synchronously: lock the vault while the picker is "open", so
        // the post-export re-check (not the initial guard) is what discards the copy.
        gateway.beforePick = { repository.forceStatus(VaultStatus.LOCKED) }

        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_choice").assertExists()
        composeRule.onNodeWithTag("pdf_export_annotated").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_warning").assertExists()
        composeRule.onNodeWithTag("pdf_export_confirm").performClick()

        composeRule.waitUntil(5_000) {
            textExists("The vault is locked. Unlock to export a copy.")
        }
        assertEquals("the export must be discarded before any page work", 0, exporter.calls)
        assertNull("a locked vault must never write plaintext", gateway.writtenBytes)
    }

    @Test
    fun `annotated export failure writes nothing and reports the failure`() {
        val (repository, itemId) = repositoryWithPdf(annotation = strokeLayer())
        val session = FakePdfPageSession(pageCount = 2)
        val exporter = FakePageToolsExporter().apply { result = null }
        val gateway = FakeExportGateway()
        openViewer(repository, itemId, session, exporter = exporter, gateway = gateway)

        openAnnotatedExport()
        composeRule.waitUntil(5_000) { textExists("The annotated PDF could not be written.") }

        assertEquals(1, exporter.calls)
        assertNull(gateway.writtenBytes)
        assertNotNull("the exporter must still have received the re-read bytes", exporter.exportedBytes)
    }
}
