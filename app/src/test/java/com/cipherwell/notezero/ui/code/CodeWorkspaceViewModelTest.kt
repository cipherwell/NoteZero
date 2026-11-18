package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.data.VaultStatus
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

/**
 * ViewModel-level tests for the code workspace tree: create/open/save/rename/move/delete,
 * expansion state and locked-vault guards — against the frozen repository interface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeWorkspaceViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakeCodeRepository

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeCodeRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CodeWorkspaceViewModel(repository)

    @Test
    fun `locked vault exposes an empty locked state`() = runTest(dispatcher) {
        repository.setStatus(VaultStatus.LOCKED)
        val model = viewModel()
        advanceUntilIdle()

        val state = model.uiState.value
        assertFalse(state.isUnlocked)
        assertTrue(state.rows.isEmpty())
        assertTrue(state.isEmpty)
    }

    @Test
    fun `createFile stores a code file with the typed name`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        val created = model.createFile("hello.kt", null)
        advanceUntilIdle()

        assertNotNull(created)
        val stored = repository.itemById(created!!.id)
        assertNotNull(stored)
        assertEquals(ItemKind.CODE_FILE, stored!!.kind)
        assertEquals("hello.kt", stored.title)
        assertEquals("hello.kt", created.title)
        assertEquals(listOf("hello.kt"), model.uiState.value.rows.map { (it as CodeTreeNode.FileNode).item.title })
        assertEquals(1, model.uiState.value.fileCount)
    }

    @Test
    fun `blank new file name falls back to the default kotlin name`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        val created = model.createFile("   ", null)
        advanceUntilIdle()

        assertEquals("untitled.kt", created?.title)
    }

    @Test
    fun `createFile inside a folder expands that folder`() = runTest(dispatcher) {
        repository.seedFolder(id = "docs", name = "Docs")
        val model = viewModel()
        advanceUntilIdle()

        val created = model.createFile("util.kt", "docs")
        advanceUntilIdle()

        assertEquals("docs", created?.folderId)
        val folderRow = model.uiState.value.rows.filterIsInstance<CodeTreeNode.FolderNode>().single()
        assertTrue(folderRow.expanded)
        val fileRow = model.uiState.value.rows.filterIsInstance<CodeTreeNode.FileNode>().single()
        assertEquals("util.kt", fileRow.item.title)
        assertEquals(1, fileRow.depth)
    }

    @Test
    fun `createFile rejects invalid names and a locked vault`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        assertNull(model.createFile("bad/name.kt", null))
        advanceUntilIdle()
        assertTrue(repository.items.value.isEmpty())

        repository.setStatus(VaultStatus.LOCKED)
        assertNull(model.createFile("later.kt", null))
        advanceUntilIdle()
        assertTrue(repository.items.value.isEmpty())
    }

    @Test
    fun `renameFile updates the stored title and ignores invalid input`() = runTest(dispatcher) {
        val item = repository.seedCodeFile(id = "f1", title = "Main.kt")
        val model = viewModel()
        advanceUntilIdle()

        model.renameFile(item, "  App.kt  ")
        advanceUntilIdle()
        assertEquals("App.kt", repository.itemById("f1")!!.title)

        val saves = repository.saveCount
        model.renameFile(repository.itemById("f1")!!, "bad/name")
        model.renameFile(repository.itemById("f1")!!, "   ")
        advanceUntilIdle()
        assertEquals(saves, repository.saveCount)
        assertEquals("App.kt", repository.itemById("f1")!!.title)
    }

    @Test
    fun `moveFile moves the file between folders`() = runTest(dispatcher) {
        repository.seedFolder(id = "docs", name = "Docs")
        val item = repository.seedCodeFile(id = "f1", title = "Main.kt")
        val model = viewModel()
        advanceUntilIdle()

        model.moveFile(item, "docs")
        advanceUntilIdle()
        assertEquals("docs", repository.itemById("f1")!!.folderId)

        model.moveFile(repository.itemById("f1")!!, null)
        advanceUntilIdle()
        assertNull(repository.itemById("f1")!!.folderId)
    }

    @Test
    fun `trashFile hides the file from the tree`() = runTest(dispatcher) {
        val item = repository.seedCodeFile(id = "f1", title = "Main.kt")
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.fileCount)

        model.trashFile(item)
        advanceUntilIdle()

        assertTrue(repository.itemById("f1")!!.trashed)
        assertEquals(0, model.uiState.value.fileCount)
        assertTrue(model.uiState.value.rows.isEmpty())
    }

    @Test
    fun `createFolder stores a folder and rejects blank names`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.createFolder("Docs", null))
        advanceUntilIdle()
        assertEquals("Docs", repository.folders.value.single().name)

        assertFalse(model.createFolder("   ", null))
        assertFalse(model.createFolder("bad/name", null))
        advanceUntilIdle()
        assertEquals(1, repository.folders.value.size)
    }

    @Test
    fun `renameFolder and deleteFolder update the shared folder tree`() = runTest(dispatcher) {
        repository.seedFolder(id = "root", name = "Root")
        repository.seedFolder(id = "docs", name = "Docs", parentId = "root")
        repository.seedCodeFile(id = "f1", title = "Main.kt", folderId = "docs")
        val model = viewModel()
        advanceUntilIdle()

        model.renameFolder(repository.folderById("docs")!!, "Guides")
        advanceUntilIdle()
        assertEquals("Guides", repository.folderById("docs")!!.name)

        model.deleteFolder(repository.folderById("docs")!!)
        advanceUntilIdle()
        assertNull(repository.folderById("docs"))
        // Repository contract: children move to the deleted folder's parent, never deleted.
        assertEquals("root", repository.itemById("f1")!!.folderId)
    }

    @Test
    fun `toggleFolder expands then collapses without touching the repository`() = runTest(dispatcher) {
        repository.seedFolder(id = "docs", name = "Docs")
        repository.seedCodeFile(id = "f1", title = "Main.kt", folderId = "docs")
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.rows.size)

        model.toggleFolder("docs")
        advanceUntilIdle()
        assertEquals(2, model.uiState.value.rows.size)
        assertTrue(model.uiState.value.rows.filterIsInstance<CodeTreeNode.FolderNode>().single().expanded)

        model.toggleFolder("docs")
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.rows.size)
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `uiState counts files and exposes move targets`() = runTest(dispatcher) {
        repository.seedFolder(id = "docs", name = "Docs")
        repository.seedCodeFile(id = "f1", title = "Main.kt")
        repository.seedCodeFile(id = "f2", title = "Util.kt", folderId = "docs")
        repository.seedNote(id = "n1", title = "A note")
        val model = viewModel()
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(2, state.fileCount)
        assertEquals(1, state.folderCount)
        assertFalse(state.isEmpty)
        assertEquals(listOf("docs"), state.moveTargets.map { it.folder.id })
    }

    // ---- Workspace search ----------------------------------------------------

    @Test
    fun `search toggle opens the bar and a query returns hits with line and column`() = runTest(dispatcher) {
        repository.seedCodeFile(
            id = "f1",
            title = "Main.kt",
            body = "fun main() {\n    val needle = 1\n}",
        )
        repository.seedCodeFile(id = "f2", title = "Other.kt", body = "val other = 2")
        val model = viewModel()
        advanceUntilIdle()

        assertFalse(model.uiState.value.searchVisible)
        model.toggleSearch()
        advanceUntilIdle()
        assertTrue(model.uiState.value.searchVisible)
        assertFalse("no query yet, the tree stays visible", model.uiState.value.searchActive)

        model.setSearchQuery("needle")
        advanceUntilIdle()
        val state = model.uiState.value
        assertTrue(state.searchActive)
        assertEquals(1, state.searchResults.size)
        val hit = state.searchResults.single()
        assertEquals("f1", hit.itemId)
        assertEquals("Main.kt", hit.fileName)
        assertEquals(2, hit.line)
        assertEquals(9, hit.column)
        assertEquals("val needle = 1", hit.snippet)
        // Search never mutates the vault.
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `search case toggle switches between insensitive and sensitive hits`() = runTest(dispatcher) {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "Needle\nneedle")
        val model = viewModel()
        advanceUntilIdle()
        model.toggleSearch()
        model.setSearchQuery("Needle")
        advanceUntilIdle()
        assertEquals(2, model.uiState.value.searchResults.size)
        assertFalse(model.uiState.value.searchCaseSensitive)

        model.toggleSearchCaseSensitive()
        advanceUntilIdle()
        assertTrue(model.uiState.value.searchCaseSensitive)
        assertEquals(1, model.uiState.value.searchResults.size)
        assertEquals(1, model.uiState.value.searchResults.single().line)
    }

    @Test
    fun `closing search discards the query and results`() = runTest(dispatcher) {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "target")
        val model = viewModel()
        advanceUntilIdle()
        model.toggleSearch()
        model.setSearchQuery("target")
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.searchResults.size)

        model.closeSearch()
        advanceUntilIdle()
        val state = model.uiState.value
        assertFalse(state.searchVisible)
        assertEquals("", state.searchQuery)
        assertTrue(state.searchResults.isEmpty())
        assertFalse(state.searchActive)

        // The toggle is a close action while the bar is open.
        model.toggleSearch()
        advanceUntilIdle()
        assertTrue(model.uiState.value.searchVisible)
        model.toggleSearch()
        advanceUntilIdle()
        assertFalse(model.uiState.value.searchVisible)
    }

    @Test
    fun `search results follow item updates, skip trashed files and stay bounded`() = runTest(dispatcher) {
        val trashed = repository.seedCodeFile(id = "t1", title = "Gone.kt", body = "target", trashed = true)
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "target")
        val model = viewModel()
        advanceUntilIdle()
        model.toggleSearch()
        model.setSearchQuery("target")
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.searchResults.size)
        assertEquals("f1", model.uiState.value.searchResults.single().itemId)

        // A decrypted-index update is reflected without re-opening the screen.
        repository.setItems(
            repository.items.value.map { item ->
                if (item.id == trashed.id) item.copy(trashed = false) else item
            } + com.cipherwell.notezero.model.Item(
                id = "f2",
                kind = ItemKind.CODE_FILE,
                title = "Second.kt",
                body = "target\ntarget",
            ),
        )
        advanceUntilIdle()
        assertEquals(4, model.uiState.value.searchResults.size)
    }

    @Test
    fun `locked vault exposes no search results`() = runTest(dispatcher) {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "target")
        val model = viewModel()
        advanceUntilIdle()
        model.toggleSearch()
        model.setSearchQuery("target")
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.searchResults.size)

        repository.setStatus(VaultStatus.LOCKED)
        advanceUntilIdle()
        val state = model.uiState.value
        assertFalse(state.isUnlocked)
        assertTrue(state.searchResults.isEmpty())
        assertEquals("target", state.searchQuery)
    }
    // ---- Locked content must never reach the code workspace --------------------------

    @Test
    fun `locked code file is hidden until its id is verified`() = runTest(dispatcher) {
        repository.seedCodeFile(id = "secret", title = "secret.kt", body = "TOKEN_XYZ")
        repository.seedCodeFile(id = "plain", title = "plain.kt", body = "hello")
        repository.setItems(
            repository.items.value.map { if (it.id == "secret") it.copy(locked = true) else it },
        )
        val model = viewModel()
        advanceUntilIdle()

        var state = model.uiState.value
        assertEquals(1, state.fileCount)
        assertEquals(1, state.lockedHiddenCount)
        assertTrue(
            "locked file title must not appear in the tree",
            state.rows.none { it is CodeTreeNode.FileNode && it.item.id == "secret" },
        )

        // Workspace search must not expose locked file content or name.
        model.toggleSearch()
        model.setSearchQuery("TOKEN_XYZ")
        advanceUntilIdle()
        assertTrue("locked content must not be searchable", model.uiState.value.searchResults.isEmpty())
        model.setSearchQuery("secret.kt")
        advanceUntilIdle()
        assertTrue("locked file name must not be searchable", model.uiState.value.searchResults.isEmpty())

        // After re-auth the workspace reveals it.
        repository.setVerifiedLockedIds(setOf("secret"))
        advanceUntilIdle()
        state = model.uiState.value
        assertEquals(2, state.fileCount)
        assertEquals(0, state.lockedHiddenCount)
        assertTrue(state.rows.any { it is CodeTreeNode.FileNode && it.item.id == "secret" })
    }

    @Test
    fun `locked folder hides its code descendants until verified`() = runTest(dispatcher) {
        repository.seedFolder(id = "parent", name = "Locked")
        repository.seedFolder(id = "child", name = "Nested", parentId = "parent")
        repository.seedCodeFile(id = "direct", title = "direct.kt", folderId = "parent")
        repository.seedCodeFile(id = "nested", title = "nested.kt", folderId = "child")
        repository.setFolders(
            repository.folders.value.map { if (it.id == "parent") it.copy(locked = true) else it },
        )
        val model = viewModel()
        advanceUntilIdle()

        var state = model.uiState.value
        assertEquals(0, state.fileCount)
        assertEquals(0, state.folderCount)
        // 1 locked folder + 1 descendant folder + 2 code files = 4 hidden entries.
        assertEquals(4, state.lockedHiddenCount)

        repository.setVerifiedLockedIds(setOf("parent"))
        advanceUntilIdle()
        state = model.uiState.value
        assertEquals(2, state.fileCount)
        assertEquals(2, state.folderCount)
        assertEquals(0, state.lockedHiddenCount)
    }

    @Test
    fun `lock verification flow updates the workspace reactively`() = runTest(dispatcher) {
        repository.seedCodeFile(id = "f1", title = "One.kt", body = "alpha")
        repository.setItems(repository.items.value.map { it.copy(locked = true) })
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(0, model.uiState.value.fileCount)

        repository.setVerifiedLockedIds(setOf("f1"))
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.fileCount)

        repository.setVerifiedLockedIds(emptySet())
        advanceUntilIdle()
        assertEquals(0, model.uiState.value.fileCount)
        assertEquals(1, model.uiState.value.lockedHiddenCount)
    }

    // ---- auto-lock race (audit finding F10) ----------------------------------

    @Test
    fun `a file mutation that loses the auto-lock race reports stale state instead of crashing`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            val file = repository.seedCodeFile(id = "f1", title = "One.kt", body = "alpha")
            advanceUntilIdle()
            assertFalse(model.sessionStale.value)

            // The pre-check passes, then the repository mutex proves the vault already locked
            // and throws IllegalStateException; it must surface as a non-secret flag (the test
            // dispatcher would otherwise rethrow the uncaught coroutine exception).
            repository.lockRaceOnNextTrash = true
            model.trashFile(file)
            advanceUntilIdle()

            assertTrue("the UI can show a refresh notice", model.sessionStale.value)
            assertEquals(VaultStatus.LOCKED, repository.status.value)
        }

    @Test
    fun `workspace stale state clears on the next unlocked session`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        val file = repository.seedCodeFile(id = "f1", title = "One.kt", body = "alpha")
        advanceUntilIdle()

        repository.lockRaceOnNextTrash = true
        model.trashFile(file)
        advanceUntilIdle()
        assertTrue(model.sessionStale.value)

        repository.setStatus(VaultStatus.UNLOCKED)
        advanceUntilIdle()
        assertFalse(model.sessionStale.value)
    }

    @Test
    fun `createFile saves the template body through the encrypted save path`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        val body = "class Hello {\n    fun greet() = \"hi\"\n}\n"

        val created = model.createFile("Main.kt", null, body)
        advanceUntilIdle()

        assertNotNull(created)
        assertEquals(body, created!!.body)
        assertEquals(body, repository.itemById(created.id)!!.body)
    }

    @Test
    fun `createFile in a folder saves the template body there`() = runTest(dispatcher) {
        repository.seedFolder(id = "docs", name = "Docs")
        val model = viewModel()
        advanceUntilIdle()

        val created = model.createFile("notes.md", "docs", "# Untitled\n")

        advanceUntilIdle()
        assertNotNull(created)
        val stored = repository.itemById(created!!.id)!!
        assertEquals("docs", stored.folderId)
        assertEquals("# Untitled\n", stored.body)
    }

    @Test
    fun `quick open entries list visible code files newest first`() = runTest(dispatcher) {
        repository.clock = 1_000L
        repository.seedCodeFile(id = "older", title = "Older.kt")
        repository.clock = 3_000L
        repository.seedCodeFile(id = "newer", title = "Newer.kt")
        repository.seedNote(id = "note", title = "Shopping")
        val model = viewModel()
        advanceUntilIdle()

        val entries = model.uiState.value.quickOpenEntries

        assertEquals(listOf("newer", "older"), entries.map { it.itemId })
    }

    @Test
    fun `quick open entries exclude locked and hidden-folder files`() = runTest(dispatcher) {
        repository.seedCodeFile(id = "public", title = "Public.kt")
        repository.seedCodeFile(id = "locked", title = "Locked.kt", locked = true)
        repository.seedFolder(id = "secret", name = "Secret", locked = true)
        repository.seedCodeFile(id = "hidden", title = "Hidden.kt", folderId = "secret")
        val model = viewModel()
        advanceUntilIdle()

        val entries = model.uiState.value.quickOpenEntries

        assertEquals(listOf("Public.kt"), entries.map { it.title })
        // Two hidden code files (the locked one and the hidden folder's) + one folder.
        assertEquals(3, model.uiState.value.lockedHiddenCount)
        assertFalse(model.uiState.value.quickOpenEntries.any { it.title == "Locked.kt" })
    }
}
