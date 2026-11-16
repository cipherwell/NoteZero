package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

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

    private fun item(
        id: String,
        title: String = "Title $id",
        body: String = "",
        folderId: String? = null,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        favorite: Boolean = false,
        trashed: Boolean = false,
        updatedAt: Long = 1_000L,
    ): Item = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        pinned = pinned,
        favorite = favorite,
        trashed = trashed,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `pinned favourites and recent are grouped and ordered newest first`() = runTest(dispatcher) {
        repository.setItems(
            listOf(
                item("pinned-old", pinned = true, updatedAt = 100),
                item("pinned-new", pinned = true, updatedAt = 300),
                item("favorite", favorite = true, updatedAt = 200),
                item("plain-old", updatedAt = 400),
                item("plain-new", updatedAt = 500),
            ),
        )
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isUnlocked)
        assertEquals(listOf("pinned-new", "pinned-old"), state.pinned.map { it.item.id })
        assertEquals(listOf("favorite"), state.favorites.map { it.item.id })
        assertEquals(listOf("plain-new", "plain-old"), state.recent.map { it.item.id })
        assertEquals(5, state.totalNotes)
        assertFalse(state.hasNoNotes)
    }

    @Test
    fun `trashed notes are excluded from notes and listed in trash`() = runTest(dispatcher) {
        repository.setItems(
            listOf(
                item("live", updatedAt = 200),
                item("deleted", trashed = true, updatedAt = 100),
            ),
        )
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.totalNotes)
        assertTrue(state.pinned.isEmpty() && state.favorites.isEmpty())
        assertEquals(listOf("live"), state.recent.map { it.item.id })
        assertEquals(listOf("deleted"), state.trash.map { it.item.id })
        assertEquals(1, state.trashCount)
    }

    @Test
    fun `smart search matches title body tags and folder path`() = runTest(dispatcher) {
        repository.setFolders(listOf(Folder(id = "f-work", name = "Work")))
        repository.setItems(
            listOf(
                item("milk", title = "Groceries", body = "- [ ] milk", tags = listOf("errand"), updatedAt = 300),
                item("code", title = "Kotlin", body = "coroutines notes", updatedAt = 200),
                item("journal", title = "Daily", body = "standup", folderId = "f-work", updatedAt = 100),
            ),
        )
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        viewModel.setQuery("milk")
        advanceUntilIdle()
        assertEquals(listOf("milk"), viewModel.uiState.value.searchResults.map { it.item.id })

        viewModel.setQuery("KOTLIN")
        advanceUntilIdle()
        assertEquals(listOf("code"), viewModel.uiState.value.searchResults.map { it.item.id })

        viewModel.setQuery("errand")
        advanceUntilIdle()
        assertEquals(listOf("milk"), viewModel.uiState.value.searchResults.map { it.item.id })

        viewModel.setQuery("work")
        advanceUntilIdle()
        assertEquals(listOf("journal"), viewModel.uiState.value.searchResults.map { it.item.id })

        viewModel.setQuery("nothing-matches")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertTrue(viewModel.uiState.value.hasNoSearchResults)
    }

    @Test
    fun `search never returns trashed notes`() = runTest(dispatcher) {
        repository.setItems(
            listOf(
                item("live", title = "Alpha", updatedAt = 2),
                item("deleted", title = "Alpha deleted", trashed = true, updatedAt = 1),
            ),
        )
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()
        viewModel.setQuery("alpha")
        advanceUntilIdle()
        assertEquals(listOf("live"), viewModel.uiState.value.searchResults.map { it.item.id })
    }

    @Test
    fun `tag filter applies to note sections and toggles off`() = runTest(dispatcher) {
        repository.setItems(
            listOf(
                item("work", title = "Work note", tags = listOf("work"), updatedAt = 300),
                item("home", title = "Home note", tags = listOf("home"), updatedAt = 200),
            ),
        )
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.tags.size)

        viewModel.toggleTag("home")
        advanceUntilIdle()
        val filtered = viewModel.uiState.value
        assertEquals(listOf("home"), filtered.recent.map { it.item.id })
        assertEquals("home", filtered.activeTag)

        viewModel.toggleTag("home")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.recent.size)
        assertNull(viewModel.uiState.value.activeTag)
    }

    @Test
    fun `favourites filter keeps only favourite notes`() = runTest(dispatcher) {
        repository.setItems(
            listOf(
                item("fav", favorite = true, updatedAt = 200),
                item("plain", updatedAt = 100),
            ),
        )
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()
        viewModel.toggleFavoritesOnly()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.favoritesOnly)
        assertEquals(listOf("fav"), state.favorites.map { it.item.id })
        assertTrue(state.recent.isEmpty())
    }

    @Test
    fun `folder navigation exposes children and notes and walks back up`() = runTest(dispatcher) {
        repository.setFolders(
            listOf(
                Folder(id = "root", name = "Projects"),
                Folder(id = "child", name = "NoteZero", parentId = "root"),
            ),
        )
        repository.setItems(
            listOf(
                item("in-root", folderId = "root", updatedAt = 100),
                item("in-child", folderId = "child", updatedAt = 50),
                item("rootless", folderId = null, updatedAt = 25),
            ),
        )
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        viewModel.openFolder("root")
        advanceUntilIdle()
        val rootState = viewModel.uiState.value
        assertEquals("root", rootState.currentFolderId)
        assertEquals(listOf("Projects"), rootState.breadcrumb.map { it.name })
        assertEquals(listOf("child"), rootState.folders.map { it.folder.id })
        assertEquals(listOf("in-root"), rootState.folderItems.map { it.item.id })

        viewModel.openFolder("child")
        advanceUntilIdle()
        val childState = viewModel.uiState.value
        assertEquals(listOf("Projects", "NoteZero"), childState.breadcrumb.map { it.name })
        assertEquals(listOf("in-child"), childState.folderItems.map { it.item.id })

        viewModel.closeFolder()
        advanceUntilIdle()
        assertEquals("root", viewModel.uiState.value.currentFolderId)
        viewModel.closeFolder()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.currentFolderId)
    }

    @Test
    fun `trash restore delete forever and empty trash drive the repository`() = runTest(dispatcher) {
        repository.setItems(listOf(item("a", updatedAt = 100), item("b", updatedAt = 200)))
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        viewModel.moveToTrash(repository.itemById("a")!!)
        advanceUntilIdle()
        assertTrue(repository.itemById("a")!!.trashed)
        assertEquals(1, viewModel.uiState.value.trashCount)

        viewModel.restoreFromTrash(repository.itemById("a")!!)
        advanceUntilIdle()
        assertFalse(repository.itemById("a")!!.trashed)
        assertEquals(0, viewModel.uiState.value.trashCount)

        viewModel.moveToTrash(repository.itemById("a")!!)
        advanceUntilIdle()
        viewModel.deleteForever(repository.itemById("a")!!)
        advanceUntilIdle()
        assertNull(repository.itemById("a"))
        assertEquals(1, viewModel.uiState.value.totalNotes)

        viewModel.moveToTrash(repository.itemById("b")!!)
        advanceUntilIdle()
        viewModel.emptyTrash()
        advanceUntilIdle()
        assertNull(repository.itemById("b"))
        assertEquals(0, viewModel.uiState.value.totalNotes)
    }

    @Test
    fun `rename trims and ignores blank titles`() = runTest(dispatcher) {
        repository.setItems(listOf(item("a", title = "Old")))
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        viewModel.renameItem(repository.itemById("a")!!, "   ")
        advanceUntilIdle()
        assertEquals("Old", repository.itemById("a")!!.title)

        viewModel.renameItem(repository.itemById("a")!!, "  New title  ")
        advanceUntilIdle()
        assertEquals("New title", repository.itemById("a")!!.title)
    }

    @Test
    fun `createNote uses the folder and returns the created item`() = runTest(dispatcher) {
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        val created = viewModel.createNote(folderId = null)
        assertNotNull(created)
        assertNotNull(repository.itemById(created!!.id))
        assertEquals(ItemKind.NOTE, created.kind)

        val folder = repository.createFolder("Work")
        val inFolder = viewModel.createNote(folderId = folder.id)
        assertEquals(folder.id, inFolder!!.folderId)
    }

    @Test
    fun `mutations are dropped while the vault is locked`() = runTest(dispatcher) {
        repository.setItems(listOf(item("a", title = "A")))
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        repository.setStatus(com.cipherwell.notezero.data.VaultStatus.LOCKED)
        advanceUntilIdle()
        val lockedState = viewModel.uiState.value
        assertFalse(lockedState.isUnlocked)
        assertTrue(lockedState.recent.isEmpty())

        viewModel.togglePinned(repository.itemById("a")!!)
        advanceUntilIdle()
        assertFalse(repository.itemById("a")!!.pinned)

        assertNull(viewModel.createNote())
    }

    @Test
    fun `folder creation rename and delete are applied`() = runTest(dispatcher) {
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        viewModel.createFolder("Work", parentId = null)
        advanceUntilIdle()
        val folder = repository.folders.value.single()
        assertEquals("Work", folder.name)

        viewModel.renameFolder(folder, "Projects")
        advanceUntilIdle()
        assertEquals("Projects", repository.folderById(folder.id)!!.name)

        viewModel.deleteFolder(repository.folderById(folder.id)!!)
        advanceUntilIdle()
        assertNull(repository.folderById(folder.id))
    }

    // ---- auto-lock race (audit finding F10) ----------------------------------

    @Test
    fun `a mutation that loses the auto-lock race reports stale state instead of crashing`() =
        runTest(dispatcher) {
            val note = item("a", title = "Groceries")
            repository.setItems(listOf(note))
            val viewModel = NotesViewModel(repository)
            advanceUntilIdle()
            assertFalse(viewModel.sessionStale.value)

            // The pre-check passes, then the repository mutex proves the vault already locked
            // and throws IllegalStateException. That must not become an uncaught coroutine
            // exception (the test dispatcher would fail the test) — it becomes a non-secret
            // refresh flag.
            repository.lockRaceOnNextPin = true
            viewModel.togglePinned(note)
            advanceUntilIdle()

            assertTrue("the UI can show a refresh notice", viewModel.sessionStale.value)
            assertEquals(VaultStatus.LOCKED, repository.status.value)
        }

    @Test
    fun `stale state clears on the next unlocked session`() = runTest(dispatcher) {
        val note = item("a", title = "Groceries")
        repository.setItems(listOf(note))
        val viewModel = NotesViewModel(repository)
        advanceUntilIdle()

        repository.lockRaceOnNextPin = true
        viewModel.togglePinned(note)
        advanceUntilIdle()
        assertTrue(viewModel.sessionStale.value)

        repository.setStatus(VaultStatus.UNLOCKED)
        advanceUntilIdle()
        assertFalse(viewModel.sessionStale.value)
    }

    @Test
    fun `createCodeFile stores the resolved name as a code item`() = runTest(dispatcher) {
        val viewModel = NotesViewModel(repository)

        val created = viewModel.createCodeFile("script.py", folderId = null)

        assertNotNull(created)
        assertEquals(ItemKind.CODE_FILE, created!!.kind)
        assertEquals("script.py", created.title)
        assertEquals("script.py", repository.items.value.single().title)
        assertFalse(viewModel.createError.value)
    }

    @Test
    fun `createPdf stores a blank pdf attachment on a pdf item`() = runTest(dispatcher) {
        val viewModel = NotesViewModel(repository)

        val created = viewModel.createPdf(nowMillis = 1_700_000_000_000L)

        assertNotNull(created)
        assertEquals(ItemKind.PDF_FILE, created!!.kind)
        assertEquals(BlankPdf.fileName(1_700_000_000_000L), created.title)
        val attachment = created.attachments.single()
        assertEquals(BlankPdf.MIME_TYPE, attachment.mimeType)
        val bytes = repository.attachmentBytes(created.id, attachment.id)
        assertNotNull(bytes)
        assertTrue(String(bytes!!, Charsets.US_ASCII).startsWith("%PDF-1.4"))
        assertFalse(viewModel.createError.value)
        assertEquals(
            "The created PDF is listed as a normal item",
            ItemKind.PDF_FILE,
            repository.items.value.single().kind,
        )
    }

    @Test
    fun `createPdf is refused while the vault is locked`() = runTest(dispatcher) {
        repository.lock()
        val viewModel = NotesViewModel(repository)

        assertNull(viewModel.createPdf())
        assertTrue(repository.items.value.isEmpty())
    }
}
