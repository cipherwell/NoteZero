package com.cipherwell.notezero.ui.attachments

import com.cipherwell.notezero.data.AttachmentError
import com.cipherwell.notezero.data.AttachmentLimits
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.AttachmentRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * State-machine tests for [AttachmentController] with the attachment engine faked.
 *
 * Everything runs on coroutines-test virtual time: no sleeps, no polling. The fake
 * records `beginExternalActivity`/`endExternalActivity` in order, so the
 * "exactly one bracket per picker" invariant is asserted directly (decision 13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentControllerTest {

    private lateinit var repository: FakeAttachmentRepository

    @Before
    fun setUp() {
        repository = FakeAttachmentRepository()
    }

    /** Controller bound to the test scheduler so advanceUntilIdle drives every launch. */
    private fun TestScope.controller(): AttachmentController = AttachmentController(
        repository = repository,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun source(
        bytes: ByteArray = PNG_BYTES,
        name: String = "photo.png",
        mime: String = "image/png",
        declared: Long? = bytes.size.toLong(),
    ): CountingAttachmentSource = CountingAttachmentSource(
        bytes = bytes,
        overrideDisplayName = name,
        mimeType = mime,
        declaredSizeBytes = declared,
    )

    // =========================================================================
    // Import
    // =========================================================================

    @Test
    fun `import success brackets the picker once and moves idle to imported`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        val picked = source()

        assertTrue(controller.beginPicker(AttachmentPick.PHOTO))
        assertEquals(listOf("begin"), repository.externalActivityEvents)
        assertEquals(AttachmentUiState.Idle, controller.state.value)

        controller.onPickerResult("n1", picked.source)
        assertEquals(listOf("begin", "end"), repository.externalActivityEvents)
        assertEquals(AttachmentUiState.Importing, controller.state.value)

        advanceUntilIdle()

        assertEquals(1, repository.importCalls)
        val imported = controller.state.value as AttachmentUiState.Imported
        val stored = repository.item("n1")!!.attachments
        assertEquals(1, stored.size)
        assertEquals(imported.attachmentId, stored.single().id)
        assertEquals("photo.png", stored.single().displayName)
        assertArrayEquals(PNG_BYTES, repository.blob("n1", imported.attachmentId))
        assertEquals(1, repository.beginExternalActivityCount)
        assertEquals(1, repository.endExternalActivityCount)
    }

    @Test
    fun `declared size over the limit is rejected before a stream is opened`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        val picked = source(declared = AttachmentLimits.MAX_ATTACHMENT_BYTES + 1)

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("n1", picked.source)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.TOO_LARGE), controller.state.value)
        assertEquals(0, picked.openCount)
        assertEquals(0, repository.importCalls)
        assertEquals(1, repository.endExternalActivityCount)
        assertTrue(repository.item("n1")!!.attachments.isEmpty())
    }

    @Test
    fun `engine too-large maps to the friendly kind when no size was declared`() = runTest {
        repository.seedNote("n1")
        repository.forcedImportError = AttachmentError.TOO_LARGE
        val controller = controller()
        val picked = source(declared = null)

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("n1", picked.source)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.TOO_LARGE), controller.state.value)
        assertEquals(1, picked.openCount)
        assertEquals(1, repository.importCalls)
    }

    @Test
    fun `engine quota failure maps to the friendly quota kind`() = runTest {
        repository.seedNote("n1")
        repository.forcedImportError = AttachmentError.QUOTA_EXCEEDED
        val controller = controller()

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("n1", source().source)
        advanceUntilIdle()

        val error = controller.state.value as AttachmentUiState.Error
        assertEquals(AttachmentErrorKind.QUOTA_EXCEEDED, error.kind)
        assertTrue(error.message.contains("512"))
    }

    @Test
    fun `engine note-not-found maps to note missing`() = runTest {
        repository.seedNote("n1")
        repository.forcedImportError = AttachmentError.NOTE_NOT_FOUND
        val controller = controller()

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("n1", source().source)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.NOTE_MISSING), controller.state.value)
    }

    @Test
    fun `missing note is rejected locally without calling the engine`() = runTest {
        repository.seedNote("n1")
        val controller = controller()

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("deleted-note", source().source)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.NOTE_MISSING), controller.state.value)
        assertEquals(0, repository.importCalls)
    }

    @Test
    fun `per-note maximum is rejected locally before reading the file`() = runTest {
        val refs = (1..AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE).map { index ->
            AttachmentRef(
                id = "seeded-$index",
                displayName = "file-$index.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 1,
                createdAt = index.toLong(),
                sha256 = "00",
            )
        }
        repository.seedNote("n1", attachments = refs)
        val controller = controller()
        val picked = source()

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("n1", picked.source)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.TOO_MANY), controller.state.value)
        assertEquals(0, picked.openCount)
        assertEquals(0, repository.importCalls)
        assertEquals(
            AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE,
            repository.item("n1")!!.attachments.size,
        )
    }

    @Test
    fun `unopenable source maps to io`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        val picked = source()
        picked.failToOpen = true

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("n1", picked.source)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.IO), controller.state.value)
        assertEquals(0, repository.importCalls)
    }

    @Test
    fun `source that throws on open maps to io without leaking`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        val picked = source()
        picked.throwOnOpen = true

        controller.beginPicker(AttachmentPick.FILE)
        controller.onPickerResult("n1", picked.source)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.IO), controller.state.value)
        assertEquals(0, repository.importCalls)
    }

    // =========================================================================
    // Picker bracketing
    // =========================================================================

    @Test
    fun `locked vault refuses to arm the picker without touching the repository`() = runTest {
        repository.seedNote("n1")
        repository.setStatus(VaultStatus.LOCKED)
        val controller = controller()

        assertFalse(controller.beginPicker(AttachmentPick.PHOTO))
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.LOCKED), controller.state.value)
        assertEquals(0, repository.beginExternalActivityCount)
        assertEquals(0, repository.endExternalActivityCount)
    }

    @Test
    fun `a picker result after an idle lock ends the bracket and imports nothing`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        assertTrue(controller.beginPicker(AttachmentPick.PHOTO))

        // The user left the picker open past the idle timeout.
        repository.setStatus(VaultStatus.LOCKED)
        controller.onPickerResult("n1", source().source)
        advanceUntilIdle()

        assertEquals(listOf("begin", "end"), repository.externalActivityEvents)
        assertEquals(1, repository.endExternalActivityCount)
        assertEquals(0, repository.importCalls)
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.LOCKED), controller.state.value)
    }

    @Test
    fun `cancelling the picker ends the bracket exactly once`() = runTest {
        repository.seedNote("n1")
        val controller = controller()

        assertTrue(controller.beginPicker(AttachmentPick.FILE))
        controller.onPickerCancelled()
        controller.onPickerCancelled() // duplicate delivery must not double-end

        assertEquals(listOf("begin", "end"), repository.externalActivityEvents)
        assertEquals(1, repository.endExternalActivityCount)
        assertEquals(AttachmentUiState.Idle, controller.state.value)
    }

    @Test
    fun `a second picker cannot arm while one is open`() = runTest {
        repository.seedNote("n1")
        val controller = controller()

        assertTrue(controller.beginPicker(AttachmentPick.PHOTO))
        assertFalse(controller.beginPicker(AttachmentPick.FILE))
        assertFalse(controller.beginExport())
        assertEquals(1, repository.beginExternalActivityCount)
    }

    // =========================================================================
    // Decrypt for display
    // =========================================================================

    @Test
    fun `loadBytes returns plaintext and keeps the state quiet`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        val controller = controller()

        val bytes = controller.loadBytes("n1", ref.id)

        assertArrayEquals(PNG_BYTES, bytes)
        assertEquals(AttachmentUiState.Idle, controller.state.value)
        assertEquals(1, repository.attachmentBytesCalls)
    }

    @Test
    fun `loadBytes reports integrity when the blob cannot be decrypted`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        repository.forceMissingBytes = true
        val controller = controller()

        assertNull(controller.loadBytes("n1", ref.id, reportErrors = true))
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.INTEGRITY), controller.state.value)
    }

    @Test
    fun `loadBytes while locked reports locked and never asks the engine`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        repository.setStatus(VaultStatus.LOCKED)
        val controller = controller()

        assertNull(controller.loadBytes("n1", ref.id, reportErrors = true))
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.LOCKED), controller.state.value)
        assertEquals(0, repository.attachmentBytesCalls)
    }

    // =========================================================================
    // Export
    // =========================================================================

    @Test
    fun `export brackets CreateDocument and writes the decrypted bytes`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        val controller = controller()
        val out = ByteArrayOutputStream()
        var finished: Boolean? = null

        assertTrue(controller.beginExport())
        assertEquals(listOf("begin"), repository.externalActivityEvents)

        controller.onExportTarget(
            noteId = "n1",
            attachmentId = ref.id,
            openOut = { out },
            onFinished = { finished = it },
        )
        assertEquals(listOf("begin", "end"), repository.externalActivityEvents)
        advanceUntilIdle()

        assertEquals(1, repository.exportCalls)
        assertArrayEquals(PNG_BYTES, out.toByteArray())
        assertEquals(true, finished)
        assertEquals(AttachmentUiState.Idle, controller.state.value)
        assertEquals(1, repository.endExternalActivityCount)
    }

    @Test
    fun `export refused when the vault is locked never arms the picker`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        repository.setStatus(VaultStatus.LOCKED)
        val controller = controller()

        assertFalse(controller.beginExport())
        assertEquals(0, repository.beginExternalActivityCount)
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.LOCKED), controller.state.value)
    }

    @Test
    fun `cancelling CreateDocument ends the export bracket`() = runTest {
        repository.seedNote("n1")
        repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        val controller = controller()

        assertTrue(controller.beginExport())
        controller.onExportPickerCancelled()
        controller.onExportPickerCancelled()

        assertEquals(listOf("begin", "end"), repository.externalActivityEvents)
        assertEquals(0, repository.exportCalls)
    }

    @Test
    fun `failed export write surfaces a friendly io error`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        repository.exportResult = false
        val controller = controller()
        var finished: Boolean? = null

        assertTrue(controller.beginExport())
        controller.onExportTarget("n1", ref.id, { ByteArrayOutputStream() }, onFinished = { finished = it })
        advanceUntilIdle()

        assertEquals(false, finished)
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.IO), controller.state.value)
    }

    @Test
    fun `export destination that cannot be opened never calls the engine`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        val controller = controller()
        var finished: Boolean? = null

        assertTrue(controller.beginExport())
        controller.onExportTarget("n1", ref.id, { null }, onFinished = { finished = it })
        advanceUntilIdle()

        assertEquals(0, repository.exportCalls)
        assertEquals(false, finished)
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.IO), controller.state.value)
    }

    // =========================================================================
    // Delete
    // =========================================================================

    @Test
    fun `delete removes the ref and the blob and returns to idle`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        val controller = controller()

        controller.delete("n1", ref.id)
        advanceUntilIdle()

        assertEquals(1, repository.deleteCalls)
        assertTrue(repository.item("n1")!!.attachments.isEmpty())
        assertNull(repository.blob("n1", ref.id))
        assertEquals(AttachmentUiState.Idle, controller.state.value)
    }

    @Test
    fun `failed delete keeps the ref and surfaces io`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        repository.deleteResult = false
        val controller = controller()

        controller.delete("n1", ref.id)
        advanceUntilIdle()

        assertNotNull(repository.item("n1")!!.attachments.firstOrNull { it.id == ref.id })
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.IO), controller.state.value)
    }

    @Test
    fun `delete refuses while locked`() = runTest {
        repository.seedNote("n1")
        val ref = repository.seedAttachment(displayName = "photo.png", mimeType = "image/png", bytes = PNG_BYTES)
        repository.setStatus(VaultStatus.LOCKED)
        val controller = controller()

        controller.delete("n1", ref.id)
        advanceUntilIdle()

        assertEquals(0, repository.deleteCalls)
        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.LOCKED), controller.state.value)
    }

    private companion object {
        /** 2x2 opaque red PNG; in-memory only. */
        val PNG_BYTES: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP4z8DwH4QZYAwAR8oH" +
                "+Rq28akAAAAASUVORK5CYII=",
        )
    }
}
