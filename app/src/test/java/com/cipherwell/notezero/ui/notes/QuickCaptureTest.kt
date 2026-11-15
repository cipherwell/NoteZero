package com.cipherwell.notezero.ui.notes

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Quick-capture (launcher shortcut / post-unlock pending request) behaviour: the notes screen
 * creates exactly one encrypted note through the normal repository path and opens its editor.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickCaptureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeNoteRepository

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
    }

    @Test
    fun `startNewNote creates exactly one note and opens the editor`() {
        var consumed = 0
        composeRule.setContent {
            NoteZeroTheme {
                NotesListScreen(
                    repository = repository,
                    onOpenSettings = {},
                    startNewNote = true,
                    onStartNewNoteConsumed = { consumed += 1 },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals("the request must be consumed once", 1, consumed)
        assertEquals("exactly one note is created", 1, repository.items.value.size)
        assertEquals(ItemKind.NOTE, repository.items.value.single().kind)
        composeRule.onNodeWithTag("editor_title").assertIsDisplayed()
    }

    @Test
    fun `no request means no note is created`() {
        composeRule.setContent {
            NoteZeroTheme {
                NotesListScreen(repository = repository, onOpenSettings = {})
            }
        }
        composeRule.waitForIdle()

        assertTrue("no note may be created without a request", repository.items.value.isEmpty())
        composeRule.onNodeWithTag("notes_list").assertIsDisplayed()
    }
}
