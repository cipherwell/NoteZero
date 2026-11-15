package com.cipherwell.notezero.ui.attachments

import com.cipherwell.notezero.data.AttachmentLimits
import com.cipherwell.notezero.data.VaultStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Controller tests for the drawing import path ([AttachmentController.importDrawing]).
 *
 * The renderer's PNG is an in-memory byte array that must travel through exactly the same
 * encrypted attachment engine as a picked file — one import, `image/png`, a timestamped
 * display name, no picker bracket, and the array wiped once the engine has read it. All
 * coroutines run on virtual time: no sleeps, no polling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawingImportTest {

    private lateinit var repository: FakeAttachmentRepository

    @Before
    fun setUp() {
        repository = FakeAttachmentRepository()
    }

    private fun TestScope.controller(): AttachmentController = AttachmentController(
        repository = repository,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Tiny stand-in for a rendered PNG page. */
    private fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4,
    )

    @Test
    fun `imports exactly one png and reports the imported id`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        val bytes = png()
        val expected = bytes.copyOf()

        controller.importDrawing("n1", bytes)
        assertEquals(AttachmentUiState.Importing, controller.state.value)
        advanceUntilIdle()

        assertEquals(1, repository.importCalls)
        val ref = repository.item("n1")!!.attachments.single()
        assertEquals("image/png", ref.mimeType)
        assertTrue(ref.displayName, ref.displayName.matches(Regex("drawing-\\d{8}-\\d{6}\\.png")))
        assertEquals(expected.size.toLong(), ref.sizeBytes)
        assertArrayEquals(expected, repository.blob("n1", ref.id))
        assertEquals(AttachmentUiState.Imported(ref.id), controller.state.value)
    }

    @Test
    fun `no picker bracket is armed for an in-memory drawing`() = runTest {
        repository.seedNote("n1")
        val controller = controller()

        controller.importDrawing("n1", png())
        advanceUntilIdle()

        assertEquals(0, repository.beginExternalActivityCount)
        assertEquals(0, repository.endExternalActivityCount)
        assertTrue(repository.externalActivityEvents.isEmpty())
    }

    @Test
    fun `the rendered bytes are wiped once the import has read them`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        val bytes = png()
        assertTrue(bytes.any { it != 0.toByte() })

        controller.importDrawing("n1", bytes)
        advanceUntilIdle()

        assertNotNull(repository.item("n1")!!.attachments.single())
        assertTrue("plaintext render must not linger", bytes.all { it == 0.toByte() })
    }

    @Test
    fun `empty bytes are rejected without touching the engine`() = runTest {
        repository.seedNote("n1")
        val controller = controller()

        controller.importDrawing("n1", ByteArray(0))
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.IO), controller.state.value)
        assertEquals(0, repository.importCalls)
        assertTrue(repository.item("n1")!!.attachments.isEmpty())
    }

    @Test
    fun `a locked vault imports nothing`() = runTest {
        repository.seedNote("n1")
        repository.setStatus(VaultStatus.LOCKED)
        val controller = controller()
        val bytes = png()

        controller.importDrawing("n1", bytes)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.LOCKED), controller.state.value)
        assertEquals(0, repository.importCalls)
        assertEquals(0, repository.beginExternalActivityCount)
    }

    @Test
    fun `a drawing over the 10 MiB cap is rejected before the engine is called`() = runTest {
        repository.seedNote("n1")
        val controller = controller()
        val oversized = ByteArray(AttachmentLimits.MAX_ATTACHMENT_BYTES.toInt() + 1)

        controller.importDrawing("n1", oversized)
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.TOO_LARGE), controller.state.value)
        assertEquals(0, repository.importCalls)
        assertTrue(repository.item("n1")!!.attachments.isEmpty())
    }

    @Test
    fun `a drawing for a deleted note is rejected locally`() = runTest {
        repository.seedNote("n1")
        val controller = controller()

        controller.importDrawing("deleted-note", png())
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.NOTE_MISSING), controller.state.value)
        assertEquals(0, repository.importCalls)
    }

    @Test
    fun `the engine limit failure maps to the friendly kind`() = runTest {
        repository.seedNote("n1")
        repository.forcedImportError = com.cipherwell.notezero.data.AttachmentError.QUOTA_EXCEEDED
        val controller = controller()

        controller.importDrawing("n1", png())
        advanceUntilIdle()

        assertEquals(AttachmentUiState.Error(AttachmentErrorKind.QUOTA_EXCEEDED), controller.state.value)
        assertEquals(1, repository.importCalls)
    }
}
