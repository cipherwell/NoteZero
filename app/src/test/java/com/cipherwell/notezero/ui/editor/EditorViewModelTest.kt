package com.cipherwell.notezero.ui.editor

import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Block
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.MarkdownBlockCodec
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakeNoteRepository

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeNoteRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedNote(
        id: String = "n1",
        title: String = "Title",
        body: String = "hello",
    ): Item {
        val item = Item(
            id = id,
            kind = ItemKind.NOTE,
            title = title,
            body = body,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
        repository.setItems(repository.items.value + item)
        return item
    }

    @Test
    fun `loads the decrypted note into the draft`() = runTest(dispatcher) {
        seedNote(title = "Groceries", body = "- [ ] milk")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.missing)
        assertEquals("Groceries", state.title)
        assertEquals("- [ ] milk", state.body)
        assertFalse(state.dirty)
    }

    @Test
    fun `missing note is reported without crashing`() = runTest(dispatcher) {
        val viewModel = EditorViewModel(repository, "does-not-exist")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.missing)
        assertFalse(viewModel.flush())
    }

    @Test
    fun `autosave persists only after the 800ms debounce`() = runTest(dispatcher) {
        seedNote()
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        viewModel.updateBody("hello world")
        assertTrue(viewModel.state.value.dirty)
        assertEquals(SaveStatus.PENDING, viewModel.state.value.saveStatus)

        advanceTimeBy(EditorViewModel.AUTOSAVE_DEBOUNCE_MS - 1)
        assertEquals(0, repository.saveCount)

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, repository.saveCount)
        assertEquals("hello world", repository.itemById("n1")!!.body)
        val savedState = viewModel.state.value
        assertFalse(savedState.dirty)
        assertEquals(SaveStatus.SAVED, savedState.saveStatus)
        assertEquals(1, savedState.successfulSaves)
    }

    @Test
    fun `rapid edits are coalesced into a single debounced save`() = runTest(dispatcher) {
        seedNote()
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        viewModel.updateBody("a")
        advanceTimeBy(400)
        viewModel.updateBody("ab")
        advanceTimeBy(EditorViewModel.AUTOSAVE_DEBOUNCE_MS - 2)
        assertEquals(0, repository.saveCount)

        advanceTimeBy(3)
        advanceUntilIdle()
        assertEquals(1, repository.saveCount)
        assertEquals("ab", repository.itemById("n1")!!.body)
    }

    @Test
    fun `clean draft does not touch the repository`() = runTest(dispatcher) {
        seedNote()
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        assertTrue(viewModel.flush())
        assertTrue(viewModel.saveOnLeave())
        assertEquals(0, repository.saveCount)
        assertEquals(0, viewModel.state.value.successfulSaves)
    }

    @Test
    fun `saveOnLeave flushes immediately without waiting for the debounce`() = runTest(dispatcher) {
        seedNote()
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        viewModel.updateBody("leaving now")
        val flushed = viewModel.saveOnLeave()

        assertTrue(flushed)
        assertEquals(1, repository.saveCount)
        assertEquals("leaving now", repository.itemById("n1")!!.body)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test
    fun `external content change triggers a version conflict instead of overwriting`() = runTest(dispatcher) {
        seedNote(title = "Original", body = "original body")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        // Simulate another writer changing the note underneath the editor.
        val current = repository.itemById("n1")!!
        repository.saveItem(current.copy(title = "External", body = "external body"))

        viewModel.updateBody("local draft")
        advanceTimeBy(EditorViewModel.AUTOSAVE_DEBOUNCE_MS + 1)
        advanceUntilIdle()

        val conflicted = viewModel.state.value
        assertTrue(conflicted.conflict)
        assertEquals(SaveStatus.CONFLICT, conflicted.saveStatus)
        assertTrue(conflicted.dirty)
        // Only the external writer's save happened; the local draft was NOT persisted.
        assertEquals(1, repository.saveCount)
        assertEquals("external body", repository.itemById("n1")!!.body)

        viewModel.overwriteWithLocalDraft()
        advanceUntilIdle()

        val resolved = viewModel.state.value
        assertFalse(resolved.conflict)
        assertFalse(resolved.dirty)
        assertEquals("local draft", repository.itemById("n1")!!.body)
        assertEquals(2, repository.saveCount)
    }

    @Test
    fun `reloading latest discards the local draft`() = runTest(dispatcher) {
        seedNote(title = "Original", body = "original body")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        repository.saveItem(repository.itemById("n1")!!.copy(title = "External", body = "external body"))
        viewModel.updateBody("local draft")
        advanceTimeBy(EditorViewModel.AUTOSAVE_DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.conflict)

        viewModel.reloadLatest()
        advanceUntilIdle()

        val reloaded = viewModel.state.value
        assertFalse(reloaded.conflict)
        assertFalse(reloaded.dirty)
        assertEquals("External", reloaded.title)
        assertEquals("external body", reloaded.body)
    }

    @Test
    fun `flag-only changes from another surface do not cause a conflict`() = runTest(dispatcher) {
        seedNote(title = "Title", body = "hello")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        repository.setPinned("n1", true)
        viewModel.updateBody("edited body")
        advanceTimeBy(EditorViewModel.AUTOSAVE_DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.conflict)
        assertEquals(1, repository.saveCount)
        val saved = repository.itemById("n1")!!
        assertTrue(saved.pinned)
        assertEquals("edited body", saved.body)
    }

    @Test
    fun `autosave is suspended while the vault is locked and resumes on unlock`() = runTest(dispatcher) {
        seedNote()
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        repository.setStatus(VaultStatus.LOCKED)
        viewModel.updateBody("secret draft")
        advanceTimeBy(EditorViewModel.AUTOSAVE_DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertEquals(0, repository.saveCount)
        assertTrue(viewModel.state.value.dirty)

        repository.setStatus(VaultStatus.UNLOCKED)
        assertTrue(viewModel.flush())
        assertEquals("secret draft", repository.itemById("n1")!!.body)
    }

    @Test
    fun `undo and redo walk the text history and new edits clear redo`() = runTest(dispatcher) {
        seedNote(body = "hello")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        viewModel.updateBody("first")
        viewModel.updateBody("second")
        assertTrue(viewModel.state.value.canUndo)

        viewModel.undo()
        assertEquals("first", viewModel.state.value.body)
        assertTrue(viewModel.state.value.canRedo)

        viewModel.undo()
        assertEquals("hello", viewModel.state.value.body)

        viewModel.redo()
        assertEquals("first", viewModel.state.value.body)

        viewModel.updateBody("third")
        assertFalse(viewModel.state.value.canRedo)
        assertEquals("third", viewModel.state.value.body)
    }

    @Test
    fun `checklist toggles rewrite markdown and persist through the codec`() = runTest(dispatcher) {
        seedNote(body = "- [ ] Task one\n- [ ] Task two")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        viewModel.toggleChecklistItem(1)
        assertEquals("- [ ] Task one\n- [x] Task two", viewModel.state.value.body)

        viewModel.toggleChecklistItem(0)
        assertEquals("- [x] Task one\n- [x] Task two", viewModel.state.value.body)

        assertTrue(viewModel.flush())
        val savedBody = repository.itemById("n1")!!.body
        val checklist = MarkdownBlockCodec.parse(savedBody).blocks.filterIsInstance<Block.Checklist>().single()
        assertTrue(checklist.items[0].checked)
        assertTrue(checklist.items[1].checked)

        viewModel.toggleChecklistItem(0)
        assertEquals("- [ ] Task one\n- [x] Task two", viewModel.state.value.body)
    }

    @Test
    fun `word and character counts follow the draft body`() = runTest(dispatcher) {
        seedNote(body = "")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        viewModel.updateBody("one two three")
        assertEquals(3, viewModel.state.value.wordCount)
        assertEquals(13, viewModel.state.value.characterCount)

        viewModel.updateBody("")
        assertEquals(0, viewModel.state.value.wordCount)
    }

    @Test
    fun `title edits are saved as part of the same draft`() = runTest(dispatcher) {
        seedNote(title = "Old")
        val viewModel = EditorViewModel(repository, "n1")
        advanceUntilIdle()

        viewModel.updateTitle("New title")
        assertTrue(viewModel.state.value.dirty)

        advanceTimeBy(EditorViewModel.AUTOSAVE_DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals("New title", repository.itemById("n1")!!.title)
    }

    @Test
    fun `unknown item id leaves save state clean`() = runTest(dispatcher) {
        val viewModel = EditorViewModel(repository, "missing")
        advanceUntilIdle()
        assertNull(repository.itemById("missing"))
        assertTrue(viewModel.state.value.missing)
        assertTrue(repository.savedItems.isEmpty())
    }
}
