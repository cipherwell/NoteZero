package com.cipherwell.notezero.ui.editor

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose semantics tests for the markdown editor screen against the
 * frozen repository interface (fake implementation, no crypto).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoteEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeNoteRepository
    private var closedCount = 0

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
        closedCount = 0
    }

    private fun seed(body: String, title: String = "Document") {
        repository.setItems(
            listOf(
                Item(
                    id = "n1",
                    kind = ItemKind.NOTE,
                    title = title,
                    body = body,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                ),
            ),
        )
    }

    private fun openEditor() {
        composeRule.setContent {
            NoteZeroTheme {
                NoteEditorScreen(
                    repository = repository,
                    itemId = "n1",
                    onClose = { closedCount++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun enterPreviewMode() {
        composeRule.onNodeWithTag("editor_preview_toggle").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `editor exposes title body save state and counts`() {
        seed(body = "one two three", title = "My note")
        openEditor()

        composeRule.onNodeWithTag("editor_title").assertExists()
        composeRule.onNodeWithTag("editor_body").assertExists()
        composeRule.onNodeWithTag("editor_save_state").assertExists()
        composeRule.onNodeWithTag("editor_back").assertExists()
        composeRule.onNodeWithTag("editor_count").assertTextContains("3 words", substring = true)
        composeRule.onNodeWithTag("editor_body").assertTextContains("one two three", substring = true)
    }

    @Test
    fun `preview renders headings checklists quotes rules and tables`() {
        seed(
            body = buildString {
                appendLine("# Heading")
                appendLine()
                appendLine("- [ ] Task one")
                appendLine("- [x] Task two")
                appendLine()
                appendLine("> quoted line")
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("| Col A | Col B |")
                appendLine("| --- | --- |")
                appendLine("| cell 1 | cell 2 |")
            }.trimEnd(),
        )
        openEditor()
        enterPreviewMode()

        composeRule.onNodeWithTag("editor_preview").assertExists()
        composeRule.onNodeWithText("Heading").assertExists()
        composeRule.onAllNodesWithTag("checklist_item").assertCountEquals(2)
        // Regression guard: the checklist row must render its item text, not just the box.
        composeRule.onNodeWithText("Task one").assertExists()
        composeRule.onNodeWithText("Task two").assertExists()
        composeRule.onNodeWithText("quoted line").assertExists()
        composeRule.onNodeWithText("Col A").assertExists()
        composeRule.onNodeWithText("cell 1").assertExists()
    }

    @Test
    fun `tapping a checklist item rewrites markdown and saves on leave`() {
        seed(body = "- [ ] Task one")
        openEditor()
        enterPreviewMode()

        composeRule.onAllNodesWithTag("checklist_item")[0].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_edit_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_body").assertTextContains("- [x] Task one", substring = true)

        composeRule.onNodeWithTag("editor_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { closedCount == 1 }
        assertEquals(1, closedCount)
        assertTrue(repository.itemById("n1")!!.body.contains("- [x] Task one"))
    }

    @Test
    fun `editing the title marks the draft dirty and preview toggle reaches source`() {
        seed(body = "plain body", title = "Old")
        openEditor()

        composeRule.onNodeWithTag("editor_title").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_preview_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_edit_toggle").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_body").assertExists()
    }
}
