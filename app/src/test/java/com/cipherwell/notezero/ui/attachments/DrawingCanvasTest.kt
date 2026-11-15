package com.cipherwell.notezero.ui.attachments

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose tests for the Phase-6 drawing canvas entry in the attachment strip.
 *
 * The controller runs on an unconfined test dispatcher and the PNG render on a real
 * background dispatcher (like the existing thumbnail decode), so the only waiting is a
 * bounded `waitUntil` that polls a Compose-visible outcome (the same pattern as the
 * thumbnail decode) — no sleeps, no fixed delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DrawingCanvasTest : UiTestBase() {

    private lateinit var repository: FakeAttachmentRepository
    private lateinit var controller: AttachmentController
    private lateinit var controllerScope: CoroutineScope

    @Before
    fun setUpDrawing() {
        repository = FakeAttachmentRepository()
        controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        controller = AttachmentController(
            repository = repository,
            scope = controllerScope,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDownDrawing() {
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

    private fun openAddMenu() {
        composeRule.onNodeWithTag("attachment_add").performClick()
        composeRule.waitForIdle()
    }

    private fun openDrawing() {
        openAddMenu()
        composeRule.onNodeWithTag("attachment_add_drawing").performClick()
        composeRule.waitForIdle()
    }

    private fun drawStroke() {
        composeRule.onNodeWithTag("drawing_canvas").performTouchInput {
            down(center)
            moveTo(center + Offset(24f, 12f))
            moveTo(center + Offset(48f, 36f))
            up()
        }
        composeRule.waitForIdle()
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    @Test
    fun `add menu offers drawing below photo and file`() {
        repository.seedNote("n1", body = "plain body")
        openEditor()
        openAddMenu()

        composeRule.onNodeWithTag("attachment_add_photo").assertExists()
        composeRule.onNodeWithTag("attachment_add_file").assertExists()
        composeRule.onNodeWithTag("attachment_add_drawing").assertExists()
        composeRule.onNodeWithText("Drawing").assertExists()

        val photoTop = composeRule.onNodeWithTag("attachment_add_photo")
            .fetchSemanticsNode().boundsInRoot.top
        val fileTop = composeRule.onNodeWithTag("attachment_add_file")
            .fetchSemanticsNode().boundsInRoot.top
        val drawingTop = composeRule.onNodeWithTag("attachment_add_drawing")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("photo=$photoTop file=$fileTop drawing=$drawingTop", photoTop < fileTop)
        assertTrue("photo=$photoTop file=$fileTop drawing=$drawingTop", fileTop < drawingTop)
    }

    @Test
    fun `choosing drawing opens the canvas with every control`() {
        repository.seedNote("n1", body = "plain body")
        openEditor()
        openDrawing()

        composeRule.onNodeWithTag("drawing_dialog").assertExists()
        composeRule.onNodeWithTag("drawing_canvas").assertExists()
        composeRule.onNodeWithContentDescription("Drawing canvas").assertExists()
        composeRule.onNodeWithTag("drawing_cancel").assertExists()
        composeRule.onNodeWithTag("drawing_save").assertExists()
        composeRule.onNodeWithTag("drawing_undo").assertExists()
        composeRule.onNodeWithTag("drawing_clear").assertExists()

        // 7 inks, 3 widths, all selectable and described.
        listOf("black", "grey", "red", "orange", "green", "blue", "purple").forEach { name ->
            composeRule.onNodeWithTag("drawing_ink_$name").assertExists()
        }
        listOf("thin", "medium", "bold").forEach { name ->
            composeRule.onNodeWithTag("drawing_width_$name").assertExists()
        }
        composeRule.onNodeWithContentDescription("Black ink").assertExists()
        composeRule.onNodeWithContentDescription("Bold stroke").assertExists()
        composeRule.onNodeWithTag("drawing_ink_black").assertIsSelected()
        composeRule.onNodeWithTag("drawing_width_medium").assertIsSelected()

        // Nothing drawn yet: saving and undo/clear are disabled.
        composeRule.onNodeWithTag("drawing_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("drawing_undo").assertIsNotEnabled()
        composeRule.onNodeWithTag("drawing_clear").assertIsNotEnabled()

        // 48dp targets on the primary controls.
        composeRule.onNodeWithTag("drawing_cancel").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("drawing_save").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("drawing_undo").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("drawing_ink_black").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("drawing_width_bold").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `tool selection follows the chosen ink and width`() {
        repository.seedNote("n1", body = "plain body")
        openEditor()
        openDrawing()

        composeRule.onNodeWithTag("drawing_ink_blue").performClick()
        composeRule.onNodeWithTag("drawing_width_thin").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("drawing_ink_blue").assertIsSelected()
        composeRule.onNodeWithTag("drawing_ink_black").assertIsNotSelected()
        composeRule.onNodeWithTag("drawing_width_thin").assertIsSelected()
    }

    @Test
    fun `cancel closes the canvas and imports nothing`() {
        repository.seedNote("n1", body = "plain body")
        openEditor()
        openDrawing()
        drawStroke()

        composeRule.onNodeWithTag("drawing_cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("drawing_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("drawing_canvas").assertDoesNotExist()
        assertEquals(0, repository.importCalls)
        assertEquals(0, repository.beginExternalActivityCount)
        assertEquals(0, repository.endExternalActivityCount)
        assertTrue(repository.item("n1")!!.attachments.isEmpty())
    }

    @Test
    fun `a stroke enables save undo and clear and clear resets them`() {
        repository.seedNote("n1", body = "plain body")
        openEditor()
        openDrawing()

        composeRule.onNodeWithTag("drawing_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("drawing_undo").assertIsNotEnabled()

        drawStroke()

        composeRule.onNodeWithTag("drawing_save").assertIsEnabled()
        composeRule.onNodeWithTag("drawing_undo").assertIsEnabled()
        composeRule.onNodeWithTag("drawing_clear").assertIsEnabled()

        composeRule.onNodeWithTag("drawing_clear").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawing_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("drawing_undo").assertIsNotEnabled()

        // Redraw and undo: the page is empty again, so save is disabled.
        drawStroke()
        composeRule.onNodeWithTag("drawing_undo").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawing_save").assertIsNotEnabled()
    }

    // =========================================================================
    // Save → encrypted import
    // =========================================================================

    @Test
    fun `save imports exactly one png and the drawing joins the strip`() {
        repository.seedNote("n1", body = "plain body")
        openEditor()
        openDrawing()
        drawStroke()

        composeRule.onNodeWithTag("drawing_save").performClick()
        composeRule.waitForIdle()
        // The PNG is encoded off the main thread; wait on the Compose-visible outcome (the
        // dialog closes after the render) *and* the committed attachment, so the test pumps
        // the main looper and never reads a half-finished import.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            repository.item("n1")?.attachments?.isNotEmpty() == true &&
                composeRule.onAllNodesWithTag("drawing_dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()

        // Dialog closed, one import, no system picker bracket (nothing external launched).
        composeRule.onNodeWithTag("drawing_dialog").assertDoesNotExist()
        assertEquals("state=${controller.state.value}", 1, repository.importCalls)
        assertEquals(0, repository.beginExternalActivityCount)
        assertEquals(0, repository.endExternalActivityCount)
        assertTrue(controller.state.value is AttachmentUiState.Imported)

        val ref: AttachmentRef = repository.item("n1")!!.attachments.single()
        assertEquals("image/png", ref.mimeType)
        assertTrue(ref.displayName, ref.displayName.matches(Regex("drawing-\\d{8}-\\d{6}\\.png")))
        val stored = repository.blob("n1", ref.id)
        assertTrue("stored png is empty", stored != null && stored.isNotEmpty())
        assertEquals(0x89.toByte(), stored!![0])

        // The drawing behaves like any other image attachment: chip + thumbnail in the strip.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("attachment_item_${ref.id}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("attachment_status").assertTextEquals("1 attachment")
    }
}
