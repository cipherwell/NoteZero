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
}
