package com.cipherwell.notezero.ui.attachments

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.ui.UiTestBase
import com.cipherwell.notezero.ui.editor.NoteEditorScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Robolectric Compose tests for the attachment surface inside the note editor:
 * strip rendering, the add menu, the delete confirmation gate, the full-screen viewer
 * (tiny in-memory PNG) and the explicit UNENCRYPTED-copy warning gate.
 *
 * The controller runs on an unconfined test dispatcher so state changes are
 * deterministic at the call site — no sleeps and no polling for import/delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AttachmentEditorTest : UiTestBase() {

    private lateinit var repository: FakeAttachmentRepository
    private lateinit var controller: AttachmentController
    private lateinit var controllerScope: CoroutineScope

    @Before
    fun setUpAttachments() {
        repository = FakeAttachmentRepository()
        controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        controller = AttachmentController(
            repository = repository,
            scope = controllerScope,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDownAttachments() {
        controllerScope.cancel()
    }

    private fun openEditor() {
        setThemedContent {
            NoteEditorScreen(
                repository = repository,
                itemId = "n1",
                onClose = {},
                attachmentController = controller,
            )
        }
        composeRule.waitForIdle()
    }

    private fun seedNote(): AttachmentRef {
        repository.seedNote("n1", body = "plain body")
        return repository.seedAttachment(
            noteId = "n1",
            displayName = "photo.png",
            mimeType = "image/png",
            bytes = TINY_PNG,
        )
    }

    private fun seedTwoAttachments(): List<AttachmentRef> {
        repository.seedNote("n1", body = "plain body")
        val photo = repository.seedAttachment(
            noteId = "n1",
            displayName = "photo.png",
            mimeType = "image/png",
            bytes = TINY_PNG,
        )
        val report = repository.seedAttachment(
            noteId = "n1",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        return listOf(photo, report)
    }

    private fun openViewer(attachment: AttachmentRef) {
        composeRule.onNodeWithTag("attachment_item_${attachment.id}").performClick()
        composeRule.waitForIdle()
    }

    // =========================================================================
    // Strip
    // =========================================================================

    @Test
    fun `strip renders between title and body with names sizes and actions`() {
        seedTwoAttachments()
        openEditor()

        composeRule.onNodeWithTag("note_attachments").assertExists()
        composeRule.onNodeWithText("photo.png").assertExists()
        composeRule.onNodeWithText("report.pdf").assertExists()
        // 4-byte file renders a human size.
        composeRule.onNodeWithText("4 B").assertExists()
        composeRule.onNodeWithTag("attachment_status")
            .assertTextContains("2 attachments", substring = true)
        composeRule.onAllNodesWithTag("attachment_delete").assertCountEquals(2)
        composeRule.onNodeWithTag("attachment_add").assertExists()
    }

    @Test
    fun `empty attachment strip still exposes the add affordance`() {
        repository.seedNote("n1", body = "plain body")
        openEditor()

        composeRule.onNodeWithTag("note_attachments").assertExists()
        composeRule.onNodeWithTag("attachment_status")
            .assertTextContains("No attachments", substring = true)
        composeRule.onNodeWithTag("attachment_add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_add_photo").assertExists()
        composeRule.onNodeWithTag("attachment_add_file").assertExists()
        composeRule.onNodeWithText("Photo").assertExists()
        composeRule.onNodeWithText("File").assertExists()
    }

    // =========================================================================
    // Delete gate
    // =========================================================================

    @Test
    fun `delete asks for confirmation and only then removes the ref`() {
        val attachments = seedTwoAttachments()
        openEditor()

        composeRule.onAllNodesWithTag("attachment_delete")[0].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("attachment_delete_dialog").assertExists()
        composeRule.onNodeWithText("Remove attachment?").assertExists()
        // Nothing has been deleted merely by opening the dialog.
        assertEquals(0, repository.deleteCalls)
        assertEquals(2, repository.item("n1")!!.attachments.size)

        composeRule.onNodeWithTag("attachment_delete_cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_delete_dialog").assertDoesNotExist()
        assertEquals(0, repository.deleteCalls)

        composeRule.onAllNodesWithTag("attachment_delete")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_delete_confirm").performClick()
        composeRule.waitForIdle()

        assertEquals(1, repository.deleteCalls)
        assertEquals(1, repository.item("n1")!!.attachments.size)
        assertEquals("report.pdf", repository.item("n1")!!.attachments.single().displayName)
        composeRule.onNodeWithText("photo.png").assertDoesNotExist()
        composeRule.onNodeWithTag("attachment_item_${attachments[0].id}").assertDoesNotExist()
    }

    // =========================================================================
    // Viewer
    // =========================================================================

    @Test
    fun `tapping an image opens the viewer with a decrypted preview`() {
        val photo = seedNote()
        openEditor()

        composeRule.onNodeWithTag("attachment_item_${photo.id}").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("attachment_viewer").assertExists()
        composeRule.onNodeWithTag("attachment_viewer_title")
            .assertTextContains("photo.png", substring = true)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("attachment_viewer_image").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("attachment_viewer_image")
            .assertContentDescriptionEquals("photo.png")

        composeRule.onNodeWithTag("attachment_viewer_close").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_viewer").assertDoesNotExist()
    }

    @Test
    fun `non-image attachment opens the viewer without an image`() {
        val attachments = seedTwoAttachments()
        openEditor()

        openViewer(attachments[1])

        composeRule.onNodeWithTag("attachment_viewer").assertExists()
        composeRule.onNodeWithTag("attachment_viewer_title")
            .assertTextContains("report.pdf", substring = true)
    }

    // =========================================================================
    // Export warning gate
    // =========================================================================

    @Test
    fun `export is gated behind the unencrypted copy warning`() {
        val photo = seedNote()
        openEditor()
        openViewer(photo)

        composeRule.onNodeWithTag("attachment_export").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("attachment_export_warning").assertExists()
        composeRule.onNodeWithText("Write an UNENCRYPTED copy?").assertExists()
        // Showing the warning must not touch the repository or launch SAF.
        assertEquals(0, repository.exportCalls)
        assertEquals(0, repository.beginExternalActivityCount)

        composeRule.onNodeWithTag("attachment_export_cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_export_warning").assertDoesNotExist()
        assertEquals(0, repository.exportCalls)
        assertEquals(0, repository.beginExternalActivityCount)

        // Confirming arms the external-activity bracket only at picker launch time.
        composeRule.onNodeWithTag("attachment_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_export_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, repository.beginExternalActivityCount)
    }

    private companion object {
        /** 2x2 opaque red PNG, in-memory only. */
        val TINY_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP4z8DwH4QZYAwAR8oH" +
                "+Rq28akAAAAASUVORK5CYII=",
        )
    }
}
