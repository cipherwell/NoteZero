package com.cipherwell.notezero.ui.notes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric UI tests for the note-export gate: the row action, the Markdown/HTML choice
 * and the explicit UNENCRYPTED-copy warning must all come before SAF is invoked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoteExportScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeNoteRepository
    private lateinit var target: RecordingTarget

    private class RecordingTarget : NoteExportTarget {
        val requests = mutableListOf<NoteExportRequest>()
        var lastResult: ((NoteExportResult) -> Unit)? = null
        var calls = 0

        override fun requestExport(request: NoteExportRequest, onResult: (NoteExportResult) -> Unit) {
            calls += 1
            requests += request
            lastResult = onResult
        }
    }

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
        target = RecordingTarget()
    }

    private fun seed(
        title: String = "Alpha",
        body: String = "secret body",
        locked: Boolean = false,
    ) {
        repository.setItems(
            listOf(
                Item(
                    id = "n1",
                    kind = ItemKind.NOTE,
                    title = title,
                    body = body,
                    locked = locked,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                ),
            ),
        )
    }

    private fun setScreen() {
        composeRule.setContent {
            NoteZeroTheme {
                NotesListScreen(
                    repository = repository,
                    onOpenSettings = {},
                    noteExportTarget = target,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openMenu(rowTag: String = "note_row") {
        composeRule.onNodeWithTag(rowTag).performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    private fun chooseMarkdownFromMenu() {
        openMenu()
        composeRule.onNodeWithTag("note_export_action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("note_export_markdown").performClick()
        composeRule.waitForIdle()
    }


    @Test
    fun `row menu offers export and the warning gates SAF`() {
        seed()
        setScreen()

        openMenu()
        composeRule.onNodeWithTag("note_export_action").assertExists()
        composeRule.onNodeWithTag("note_export_action").performClick()
        composeRule.waitForIdle()

        // Format choice exists before the warning and no export has happened yet.
        composeRule.onNodeWithTag("note_export_markdown").assertExists()
        composeRule.onNodeWithTag("note_export_html").assertExists()
        assertEquals(0, target.calls)

        composeRule.onNodeWithTag("note_export_markdown").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Write an UNENCRYPTED copy?").assertExists()
        composeRule.onNodeWithTag("note_export_confirm").assertExists()
        assertEquals("SAF must not be reached before the warning is confirmed", 0, target.calls)

        composeRule.onNodeWithTag("note_export_cancel").performClick()
        composeRule.waitForIdle()
        assertEquals(0, target.calls)
        composeRule.onAllNodesWithTag("note_export_confirm").assertCountEquals(0)
    }

    @Test
    fun `confirming exports markdown and shows a non-secret success status`() {
        seed(title = "Alpha", body = "secret body")
        setScreen()
        chooseMarkdownFromMenu()

        composeRule.onNodeWithTag("note_export_confirm").performClick()
        composeRule.waitForIdle()

        assertEquals(1, target.calls)
        val request = target.requests.single()
        assertEquals(NoteExportFormat.MARKDOWN, request.format)
        assertEquals("Alpha.md", request.suggestedName)
        assertTrue(request.content.contains("# Alpha"))
        assertTrue(request.content.contains("secret body"))

        target.lastResult!!(NoteExportResult.Written)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("note_export_status").assertExists()
        composeRule.onNodeWithText("Unencrypted copy written outside the vault.").assertExists()
    }

    @Test
    fun `choosing html exports an html document`() {
        seed()
        setScreen()
        openMenu()
        composeRule.onNodeWithTag("note_export_action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("note_export_html").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("note_export_confirm").performClick()
        composeRule.waitForIdle()

        val request = target.requests.single()
        assertEquals(NoteExportFormat.HTML, request.format)
        assertEquals("Alpha.html", request.suggestedName)
        assertTrue(request.content.contains("<!DOCTYPE html>"))
        assertTrue(request.content.contains("<h1>Alpha</h1>"))
    }

    @Test
    fun `cancelling the picker shows a cancelled status`() {
        seed()
        setScreen()
        chooseMarkdownFromMenu()
        composeRule.onNodeWithTag("note_export_confirm").performClick()
        composeRule.waitForIdle()

        target.lastResult!!(NoteExportResult.Cancelled)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Export cancelled. Nothing was written.").assertExists()
    }

    @Test
    fun `masked locked note has no export action`() {
        seed(locked = true)
        setScreen()

        openMenu(rowTag = "locked_note_row")
        assertTrue(
            "masked note must not offer an unencrypted export",
            composeRule.onAllNodesWithTag("note_export_action").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `export status can be dismissed`() {
        seed()
        setScreen()
        chooseMarkdownFromMenu()
        composeRule.onNodeWithTag("note_export_confirm").performClick()
        composeRule.waitForIdle()
        target.lastResult!!(NoteExportResult.Failed)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("note_export_status").assertExists()
        composeRule.onNodeWithTag("note_export_status_dismiss").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("note_export_status").assertCountEquals(0)
    }
}
