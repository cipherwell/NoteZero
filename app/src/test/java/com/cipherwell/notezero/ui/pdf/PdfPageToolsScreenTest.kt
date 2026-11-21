package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
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
 * Page-tools UI tests.
 *
 * Two layers, both Robolectric (`@Config(sdk = [34])` only, no display qualifiers):
 *  - the full-screen [PdfPageToolsScreen] hosted directly, with hoisted plan state and injected
 *    cancel/save callbacks — every row interaction, bounds/disabled semantics, notices, the
 *    dirty flag, the 100-page cap and the saving label;
 *  - [PdfViewerScreen] hosting the overlay with an injected fake exporter and fake saver — the
 *    save wiring, encrypted-byte re-read at save time, success/failure/locked notices and that
 *    cancelling never saves.
 *
 * The native `android.graphics.pdf` implementation is never touched here; it is covered by
 * `PdfPageToolsExportInstrumentedTest` on a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfPageToolsScreenTest : UiTestBase() {

    private lateinit var planState: MutableState<PdfPagePlan>
    private var savedPlan: PdfPagePlan? = null
    private var saveCalls: Int = 0
    private var cancelCalls: Int = 0

    // ---- Fakes ---------------------------------------------------------------------------

    private class FakePageToolsExporter(
        var result: ByteArray? = "%PDF-1.4 fake page-tools copy".toByteArray(),
    ) : PdfPageToolsExporter {
        var calls: Int = 0
            private set
        var exportedPlan: PdfPagePlan? = null
            private set
        var exportedAnnotations: PdfAnnotations? = null
            private set
        var exportedBytes: ByteArray? = null
            private set

        override suspend fun export(
            context: Context,
            pdfBytes: ByteArray,
            plan: PdfPagePlan,
            annotations: PdfAnnotations,
        ): ByteArray? {
            calls++
            exportedPlan = plan
            exportedAnnotations = annotations
            exportedBytes = pdfBytes
            return result
        }
    }

    private class FakePageCopySaver(
        var result: String? = "new-copy-id",
    ) : PdfPageCopySaver {
        var calls: Int = 0
            private set
        var savedDisplayName: String? = null
            private set
        var savedBytes: ByteArray? = null
            private set
        var savedSourceId: String? = null
            private set

        override suspend fun save(
            repository: com.cipherwell.notezero.data.NoteRepository,
            source: com.cipherwell.notezero.model.Item,
            displayName: String,
            bytes: ByteArray,
        ): String? {
            calls++
            savedSourceId = source.id
            savedDisplayName = displayName
            savedBytes = bytes
            return result
        }
    }

    /** Fake in-memory thumbnail renderer: counts calls and returns tiny fake bitmaps. */
    private class FakeThumbnailRenderer(
        private val pageCount: Int = 2,
    ) : PdfThumbnailRenderer {
        var calls: Int = 0
            private set

        override suspend fun render(context: Context, pdfBytes: ByteArray): PdfThumbnails {
            calls++
            val bitmaps = (0 until pageCount).map { index ->
                Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
                }
            }
            return PdfThumbnails(bitmaps)
        }
    }

    // ---- Direct screen harness -----------------------------------------------------------

    private fun openScreen(
        plan: PdfPagePlan,
        saving: Boolean = false,
        notice: String? = null,
        thumbnails: PdfThumbnails? = null,
    ) {
        planState = mutableStateOf(plan)
        savedPlan = null
        saveCalls = 0
        cancelCalls = 0
        setThemedContent {
            PdfPageToolsScreen(
                plan = planState.value,
                saving = saving,
                notice = notice,
                onPlanChange = { updated -> planState.value = updated },
                onSave = {
                    saveCalls++
                    savedPlan = planState.value
                },
                onCancel = { cancelCalls++ },
                thumbnails = thumbnails,
            )
        }
        composeRule.waitForIdle()
    }

    private fun sources(): List<Int?> = planState.value.entries.map { it.sourcePage }

    private fun rotations(): List<Int> = planState.value.entries.map { it.quarterTurns }

    private fun scrollToRow(rowIndex: Int) {
        composeRule.onNodeWithTag("pdf_pages_list").performScrollToIndex(rowIndex)
        composeRule.waitForIdle()
    }

    /**
     * The five page controls repeat their contract tag on every row, so one row's control is
     * addressed as `hasTestTag(control) and hasAnyAncestor(hasTestTag(row_<index>))`.
     */
    private fun control(tag: String, rowIndex: Int) =
        composeRule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("pdf_page_row_$rowIndex")))

    private fun clickControl(tag: String, rowIndex: Int) {
        scrollToRow(rowIndex)
        control(tag, rowIndex).performClick()
        composeRule.waitForIdle()
    }

    private fun textExists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // ---- Viewer harness ------------------------------------------------------------------

    private fun repositoryWithPdf(annotations: PdfAnnotations? = null): Pair<FakeNoteRepository, String> {
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
            if (annotations != null) repository.savePdfAnnotations(created.id, annotations)
        }
        return repository to created.id
    }

    private fun openViewer(
        repository: FakeNoteRepository,
        itemId: String,
        exporter: FakePageToolsExporter = FakePageToolsExporter(),
        saver: FakePageCopySaver = FakePageCopySaver(),
        pageSession: PdfPageSession = FakePdfPageSession(pageCount = 2),
        thumbnailRenderer: PdfThumbnailRenderer = FakeThumbnailRenderer(),
        onClose: () -> Unit = {},
    ) {
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = onClose,
                pageSessionFactory = FakePdfPageSessionFactory(pageSession),
                pageToolsExporter = exporter,
                pageCopySaver = saver,
                thumbnailRenderer = thumbnailRenderer,
            )
        }
        composeRule.waitForIdle()
    }

    private fun openTools() {
        composeRule.onNodeWithTag("pdf_page_tools").assertExists()
        composeRule.onNodeWithTag("pdf_page_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_tools_screen").assertExists()
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

    // ---- Direct screen tests -------------------------------------------------------------

    @Test
    fun `shows the title warning hint count and one row per planned page`() {
        openScreen(PdfPagePlan.of(3))

        composeRule.onNodeWithTag("pdf_page_tools_screen").assertExists()
        composeRule.onNodeWithTag("pdf_pages_title").assertExists()
        composeRule.onNodeWithTag("pdf_pages_warning").assertExists()
        composeRule.onNodeWithTag("pdf_pages_hint").assertExists()
        composeRule.onNodeWithText("3 pages").assertExists()
        scrollToRow(0)
        control("pdf_page_rotate_left", 0).assertExists()
        control("pdf_page_rotate_right", 0).assertExists()
    }

    @Test
    fun `rotating a page clockwise updates only that page and marks the plan dirty`() {
        openScreen(PdfPagePlan.of(3))

        clickControl("pdf_page_rotate_right", 0)

        assertEquals(listOf(1, 0, 0), rotations())
        assertEquals(listOf(0, 1, 2), sources())
        assertTrue(textExists("Rotation: 90°"))
        composeRule.onNodeWithTag("pdf_pages_changed").assertExists()
    }

    @Test
    fun `rotating left turns counter-clockwise and wraps at four turns`() {
        openScreen(PdfPagePlan.of(1))

        clickControl("pdf_page_rotate_left", 0)
        assertEquals(listOf(3), rotations())
        assertTrue(textExists("Rotation: 270°"))

        clickControl("pdf_page_rotate_left", 0)
        clickControl("pdf_page_rotate_left", 0)
        assertEquals(listOf(1), rotations())
        assertTrue(textExists("Rotation: 90°"))

        // A fourth left turn wraps back to 0 degrees.
        clickControl("pdf_page_rotate_left", 0)
        assertEquals(listOf(0), rotations())
        assertTrue(textExists("Rotation: 0°"))
        assertFalse(planState.value.hasChanges())
    }

    @Test
    fun `moving a page down then back up reorders the plan`() {
        openScreen(PdfPagePlan.of(3))

        clickControl("pdf_page_move_down", 0)
        assertEquals(listOf(1, 0, 2), sources())

        clickControl("pdf_page_move_up", 1)
        assertEquals(listOf(0, 1, 2), sources())
        assertFalse(planState.value.hasChanges())
    }

    @Test
    fun `move controls are disabled at the page bounds while rotate and delete stay enabled`() {
        openScreen(PdfPagePlan.of(3))

        scrollToRow(0)
        control("pdf_page_move_up", 0).assertIsNotEnabled()
        control("pdf_page_move_down", 0).assertIsEnabled()
        control("pdf_page_rotate_left", 0).assertIsEnabled()
        control("pdf_page_rotate_right", 0).assertIsEnabled()
        control("pdf_page_delete", 0).assertIsEnabled()

        scrollToRow(2)
        control("pdf_page_move_down", 2).assertIsNotEnabled()
        control("pdf_page_move_up", 2).assertIsEnabled()
    }

    @Test
    fun `deleting a page removes it and keeps the source page identity`() {
        openScreen(PdfPagePlan.of(3))

        clickControl("pdf_page_delete", 1)

        assertEquals(2, planState.value.size)
        assertEquals(listOf(0, 2), sources())
        composeRule.onNodeWithTag("pdf_pages_changed").assertExists()
    }

    @Test
    fun `deleting the last remaining page shows the last-page notice instead`() {
        openScreen(PdfPagePlan.of(1))

        clickControl("pdf_page_delete", 0)

        assertEquals(1, planState.value.size)
        composeRule.onNodeWithText("The copy needs at least one page.").assertExists()
        composeRule.onNodeWithTag("pdf_pages_changed").assertDoesNotExist()
    }

    @Test
    fun `inserting a blank page appends it and announces the change`() {
        openScreen(PdfPagePlan.of(2))

        composeRule.onNodeWithTag("pdf_page_insert_blank").performClick()
        composeRule.waitForIdle()

        assertEquals(3, planState.value.size)
        assertEquals(listOf(0, 1, null), sources())
        assertTrue(textExists("Blank page added."))
        composeRule.onNodeWithTag("pdf_pages_changed").assertExists()
    }

    @Test
    fun `reset restores the original order and clears every edit notice`() {
        openScreen(PdfPagePlan.of(3))
        clickControl("pdf_page_move_down", 0)
        clickControl("pdf_page_rotate_right", 0)
        composeRule.onNodeWithTag("pdf_pages_changed").assertExists()

        composeRule.onNodeWithTag("pdf_pages_reset").performClick()
        composeRule.waitForIdle()

        assertEquals(PdfPagePlan.of(3), planState.value)
        composeRule.onNodeWithTag("pdf_pages_changed").assertDoesNotExist()
        composeRule.onNodeWithTag("pdf_pages_reset").assertIsNotEnabled()
    }

    @Test
    fun `cancel closes the overlay without saving`() {
        openScreen(PdfPagePlan.of(2))

        composeRule.onNodeWithTag("pdf_pages_cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(1, cancelCalls)
        assertEquals(0, saveCalls)
        assertNull(savedPlan)
    }

    @Test
    fun `save hands the edited plan to the caller`() {
        openScreen(PdfPagePlan.of(3))
        clickControl("pdf_page_move_down", 0)

        composeRule.onNodeWithTag("pdf_pages_save_copy").performClick()
        composeRule.waitForIdle()

        assertEquals(1, saveCalls)
        assertEquals(listOf(1, 0, 2), savedPlan?.entries?.map { it.sourcePage })
    }

    @Test
    fun `while saving the primary action is disabled and labelled saving`() {
        openScreen(PdfPagePlan.of(2), saving = true)

        composeRule.onNodeWithTag("pdf_pages_save_copy").assertIsNotEnabled()
        composeRule.onNodeWithText("Saving…").assertExists()
        composeRule.onNodeWithTag("pdf_pages_cancel").assertIsNotEnabled()
    }

    @Test
    fun `too many pages shows the limit message and blocks saving`() {
        openScreen(PdfPagePlan.of(PdfPagePlan.MAX_PAGES + 1))

        composeRule.onNodeWithTag("pdf_pages_too_many").assertExists()
        composeRule.onNodeWithTag("pdf_pages_save_copy").assertIsNotEnabled()
        composeRule.onNodeWithTag("pdf_page_insert_blank").assertIsNotEnabled()
        assertEquals(0, composeRule.onAllNodesWithTag("pdf_page_row").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("pdf_pages_count").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("pdf_pages_cancel").assertIsEnabled()
    }

    @Test
    fun `a caller notice is shown verbatim inside the overlay`() {
        openScreen(
            PdfPagePlan.of(2),
            notice = "Could not build the page-tools copy.",
        )

        composeRule.onNodeWithText("Could not build the page-tools copy.").assertExists()
        composeRule.onNodeWithTag("pdf_pages_notice").assertExists()
    }

    @Test
    fun `page controls keep 48dp touch targets`() {
        openScreen(PdfPagePlan.of(2))
        scrollToRow(0)

        listOf(
            "pdf_page_rotate_left",
            "pdf_page_rotate_right",
            "pdf_page_move_up",
            "pdf_page_move_down",
            "pdf_page_delete",
        ).forEach { tag ->
            control(tag, 0).assertHeightIsAtLeast(48.dp)
            control(tag, 0).assertWidthIsAtLeast(48.dp)
        }
    }

    // ---- Viewer-hosted save flow ---------------------------------------------------------

    @Test
    fun `opening page tools uses the viewer page count and cancelling never saves`() {
        val (repository, itemId) = repositoryWithPdf()
        val exporter = FakePageToolsExporter()
        val saver = FakePageCopySaver()
        openViewer(repository, itemId, exporter = exporter, saver = saver)

        openTools()
        composeRule.onNodeWithText("2 pages").assertExists()

        composeRule.onNodeWithTag("pdf_pages_cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_tools_screen").assertDoesNotExist()
        composeRule.onNodeWithTag("pdf_viewer").assertExists()
        assertEquals(0, exporter.calls)
        assertEquals(0, saver.calls)
    }

    @Test
    fun `saving exports the edited plan and the annotation layer then closes the overlay`() {
        val (repository, itemId) = repositoryWithPdf(annotations = strokeLayer())
        val exporter = FakePageToolsExporter()
        val saver = FakePageCopySaver()
        openViewer(repository, itemId, exporter = exporter, saver = saver)

        openTools()
        clickControl("pdf_page_rotate_right", 0)
        composeRule.onNodeWithTag("pdf_pages_save_copy").performClick()

        composeRule.waitUntil(5_000) { saver.calls == 1 }
        composeRule.waitForIdle()

        assertEquals(1, exporter.calls)
        assertEquals(listOf(1, 0), exporter.exportedPlan?.entries?.map { it.quarterTurns })
        assertEquals(strokeLayer(), exporter.exportedAnnotations)
        // Decrypted attachment bytes are re-read at save time and handed to the exporter.
        assertTrue(exporter.exportedBytes!!.contentEquals(BlankPdf.create()))
        assertEquals(itemId, saver.savedSourceId)
        assertEquals("document (pages).pdf", saver.savedDisplayName)
        assertNotNull(saver.savedBytes)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pdf_page_tools_screen").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_viewer").assertExists()
        assertTrue(textExists("Saved a new copy: document (pages).pdf"))
    }

    @Test
    fun `an export failure keeps the overlay open with the failure notice`() {
        val (repository, itemId) = repositoryWithPdf()
        val exporter = FakePageToolsExporter(result = null)
        val saver = FakePageCopySaver()
        openViewer(repository, itemId, exporter = exporter, saver = saver)

        openTools()
        composeRule.onNodeWithTag("pdf_pages_save_copy").performClick()
        composeRule.waitUntil(5_000) { exporter.calls == 1 }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_tools_screen").assertExists()
        composeRule.onNodeWithText("Could not build the page-tools copy.").assertExists()
        assertEquals(0, saver.calls)
    }

    @Test
    fun `a saver failure keeps the overlay open with the save-failed notice`() {
        val (repository, itemId) = repositoryWithPdf()
        val exporter = FakePageToolsExporter()
        val saver = FakePageCopySaver(result = null)
        openViewer(repository, itemId, exporter = exporter, saver = saver)

        openTools()
        composeRule.onNodeWithTag("pdf_pages_save_copy").performClick()
        composeRule.waitUntil(5_000) { saver.calls == 1 }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_tools_screen").assertExists()
        composeRule.onNodeWithText("Could not save the page-tools copy.").assertExists()
    }

    @Test
    fun `a vault lock before saving discards the copy and shows the locked notice`() {
        val (repository, itemId) = repositoryWithPdf()
        val exporter = FakePageToolsExporter()
        val saver = FakePageCopySaver()
        openViewer(repository, itemId, exporter = exporter, saver = saver)

        openTools()
        repository.forceStatus(VaultStatus.LOCKED)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_pages_save_copy").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_tools_screen").assertExists()
        composeRule.onNodeWithText("Vault locked; the page-tools copy was discarded.")
            .assertExists()
        assertEquals(0, exporter.calls)
        assertEquals(0, saver.calls)
    }

    // ---- Page previews (in-memory thumbnails) --------------------------------------------

    @Test
    fun `page rows show the source page thumbnail in a 56x72 dp preview`() {
        val thumbnailBitmaps = (0 until 2).map {
            Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        }
        openScreen(PdfPagePlan.of(2), thumbnails = PdfThumbnails(thumbnailBitmaps))
        scrollToRow(0)

        composeRule.onAllNodes(hasContentDescription("Page 1 preview"), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .let { assertFalse("the source page preview must show its thumbnail", it.isEmpty()) }
        val previews = composeRule.onAllNodesWithTag("pdf_page_thumb")
        assertTrue(previews.fetchSemanticsNodes().isNotEmpty())
        previews[0].assertWidthIsEqualTo(56.dp)
        previews[0].assertHeightIsEqualTo(72.dp)
    }

    @Test
    fun `blank page row shows a labelled placeholder instead of a thumbnail`() {
        // A plan with exactly one output page: an inserted blank (the source page was deleted).
        val blankOnly = PdfPagePlan.of(1).insertBlank().delete(0).plan
        val thumbnail = Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        openScreen(blankOnly, thumbnails = PdfThumbnails(listOf(thumbnail)))
        scrollToRow(0)

        assertEquals(1, composeRule.onAllNodesWithTag("pdf_page_thumb").fetchSemanticsNodes().size)
        assertTrue(
            "a blank page must not render a thumbnail image",
            composeRule.onAllNodes(hasContentDescription("Page 1 preview"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        assertTrue(
            "the placeholder keeps the blank label in addition to the row label",
            composeRule.onAllNodesWithText("Blank page").fetchSemanticsNodes().size >= 2,
        )
    }

    @Test
    fun `opening page tools lazily renders thumbnails and shows them in the rows`() {
        val (repository, itemId) = repositoryWithPdf()
        val thumbnailRenderer = FakeThumbnailRenderer(pageCount = 2)
        openViewer(repository, itemId, thumbnailRenderer = thumbnailRenderer)
        assertEquals("no thumbnail work before page tools opens", 0, thumbnailRenderer.calls)

        openTools()
        composeRule.waitUntil(5_000) { thumbnailRenderer.calls == 1 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasContentDescription("Page 1 preview"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("pdf_page_thumb").fetchSemanticsNodes().isNotEmpty())
        assertEquals(1, thumbnailRenderer.calls)
    }
}
