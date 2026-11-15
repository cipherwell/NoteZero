package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.data.VaultStatus
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel-level tests for the note-export flow: the warning gate is UI, but the
 * conversion, SAF seam and non-secret status are asserted here with a fake target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteExportViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakeNoteRepository

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

    private lateinit var target: RecordingTarget

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeNoteRepository()
        target = RecordingTarget()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun note(
        id: String = "n1",
        title: String = "My note",
        body: String = "hello **world**",
        locked: Boolean = false,
        trashed: Boolean = false,
    ) = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = body,
        locked = locked,
        trashed = trashed,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun viewModel(): NotesViewModel = NotesViewModel(repository)

    @Test
    fun `export builds markdown, a sanitised name and reports success`() = runTest(dispatcher) {
        val item = note(title = "My note", body = "hello **world**")
        repository.setItems(listOf(item))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)

        assertEquals(1, target.calls)
        val request = target.requests.single()
        assertEquals(NoteExportFormat.MARKDOWN, request.format)
        assertEquals("n1", request.itemId)
        assertEquals("My note.md", request.suggestedName)
        assertTrue(request.content.contains("# My note"))
        assertTrue(request.content.contains("hello **world**"))

        target.lastResult!!(NoteExportResult.Written)
        assertEquals(
            NoteExportStatus(NoteExportOutcome.EXPORTED, 1),
            viewModel.exportStatus.value,
        )
    }

    @Test
    fun `html export chooses the html extension and mime format`() = runTest(dispatcher) {
        val item = note(title = "Notes/2024", body = "- [x] done")
        repository.setItems(listOf(item))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.HTML, target)

        val request = target.requests.single()
        assertEquals(NoteExportFormat.HTML, request.format)
        assertEquals("text/html", request.format.mimeType)
        assertEquals("Notes_2024.html", request.suggestedName)
        assertTrue(request.content.contains("<!DOCTYPE html>"))
        assertTrue(request.content.contains("<input type=\"checkbox\" disabled checked>"))
    }

    @Test
    fun `locked vault exports nothing and reports a locked status`() = runTest(dispatcher) {
        val item = note()
        repository.setItems(listOf(item))
        repository.setStatus(VaultStatus.LOCKED)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)

        assertEquals(0, target.calls)
        assertEquals(NoteExportOutcome.LOCKED, viewModel.exportStatus.value.outcome)
        // Status is non-secret: only an enum + sequence, no content fields exist to leak.
        assertEquals(NoteExportStatus(NoteExportOutcome.LOCKED, 1), viewModel.exportStatus.value)
    }

    @Test
    fun `masked locked note is never converted or exported`() = runTest(dispatcher) {
        val item = note(title = "Secret", body = "classified", locked = true)
        repository.setItems(listOf(item))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)

        assertEquals(0, target.calls)
        assertEquals(NoteExportOutcome.LOCKED, viewModel.exportStatus.value.outcome)
    }

    @Test
    fun `revealed locked note can be exported`() = runTest(dispatcher) {
        val item = note(title = "Secret", body = "revealed", locked = true)
        repository.setItems(listOf(item))
        repository.revealForTest(item.id)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)

        assertEquals(1, target.calls)
        assertTrue(target.requests.single().content.contains("revealed"))
    }

    @Test
    fun `missing note reports unavailable without converting`() = runTest(dispatcher) {
        val stale = note(id = "gone")
        repository.setItems(emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(stale, NoteExportFormat.MARKDOWN, target)

        assertEquals(0, target.calls)
        assertEquals(NoteExportOutcome.UNAVAILABLE, viewModel.exportStatus.value.outcome)
    }

    @Test
    fun `cancel and failure map to non-secret outcomes`() = runTest(dispatcher) {
        val item = note()
        repository.setItems(listOf(item))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)
        target.lastResult!!(NoteExportResult.Cancelled)
        assertEquals(NoteExportOutcome.CANCELLED, viewModel.exportStatus.value.outcome)

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)
        target.lastResult!!(NoteExportResult.Failed)
        assertEquals(
            NoteExportStatus(NoteExportOutcome.FAILED, 2),
            viewModel.exportStatus.value,
        )

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)
        target.lastResult!!(NoteExportResult.Locked)
        assertEquals(NoteExportOutcome.LOCKED, viewModel.exportStatus.value.outcome)
    }

    @Test
    fun `dismiss clears the status`() = runTest(dispatcher) {
        val item = note()
        repository.setItems(listOf(item))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)
        target.lastResult!!(NoteExportResult.Written)
        viewModel.dismissExportStatus()

        assertEquals(NoteExportOutcome.NONE, viewModel.exportStatus.value.outcome)
    }

    @Test
    fun `exported content never appears in the status`() = runTest(dispatcher) {
        val item = note(title = "Top secret title", body = "Top secret body")
        repository.setItems(listOf(item))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exportNote(item, NoteExportFormat.MARKDOWN, target)
        target.lastResult!!(NoteExportResult.Failed)

        val status = viewModel.exportStatus.value
        assertEquals(NoteExportStatus(NoteExportOutcome.FAILED, 1), status)
        assertFalse(status.toString().contains("secret"))
    }
}
