package com.cipherwell.notezero.ui.locks

import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State-machine tests for [LockReauthController] (decision #15).
 *
 * The controller is driven directly (no Compose, no sleeps): [Dispatchers.Unconfined] runs
 * the password verification at the call site, and [FakeNoteRepository] is the fail-secure
 * stand-in for the encrypted repository.
 */
class LockReauthControllerTest {

    private lateinit var repository: FakeNoteRepository
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var followUpCalls = 0

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
        repository.masterPassword = "Correct-Horse-9!"
        repository.setItems(
            listOf(
                Item(
                    id = "note-1",
                    kind = ItemKind.NOTE,
                    title = "Secret",
                    body = "secret body",
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    locked = true,
                ),
            ),
        )
        followUpCalls = 0
    }

    private fun controller(prompt: LockBiometricPrompt = LockBiometricPrompt.Unavailable) =
        LockReauthController(repository, prompt, scope)

    private fun LockReauthController.requestNote() = request(
        LockedTarget("note-1", LockTargetKind.NOTE),
        afterReveal = { followUpCalls++ },
    )

    @Test
    fun `no biometric prompt falls back to the password dialog`() {
        val controller = controller()

        controller.requestNote()

        assertTrue(controller.state.dialogVisible)
        assertFalse(controller.state.biometricInFlight)
        assertEquals(LockedTarget("note-1", LockTargetKind.NOTE), controller.state.target)
        assertNull(controller.state.error)
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
    }

    @Test
    fun `correct password verifies the id and runs the follow-up`() {
        val controller = controller()
        controller.requestNote()

        controller.updatePassword("Correct-Horse-9!")
        controller.submitPassword()

        assertTrue("note-1" in repository.verifiedLockedIds.value)
        assertEquals(1, followUpCalls)
        assertNull(controller.state.target)
        assertFalse(controller.state.dialogVisible)
    }

    @Test
    fun `wrong password shows the error and keeps content hidden`() {
        val controller = controller()
        controller.requestNote()

        controller.updatePassword("wrong")
        controller.submitPassword()

        assertTrue(controller.state.dialogVisible)
        assertEquals(1, controller.state.failures)
        assertFalse(controller.state.cooldown)
        assertEquals(LockReauthController.WRONG_PASSWORD_MESSAGE, controller.state.error)
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
        assertEquals(0, followUpCalls)
    }

    @Test
    fun `five failures enter cooldown and further submissions are not attempted`() {
        val controller = controller()
        controller.requestNote()

        repeat(5) {
            controller.updatePassword("wrong")
            controller.submitPassword()
        }

        assertTrue(controller.state.cooldown)
        assertEquals(5, controller.state.failures)
        assertEquals(LockReauthController.COOLDOWN_MESSAGE, controller.state.error)
        assertEquals(5, repository.verifyMasterPasswordCalls)

        // A sixth attempt while cooling down is refused before touching the repository.
        controller.submitPassword()
        assertEquals(5, repository.verifyMasterPasswordCalls)
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
    }

    @Test
    fun `blank password is rejected without calling the repository`() {
        val controller = controller()
        controller.requestNote()

        controller.submitPassword()

        assertEquals(0, repository.verifyMasterPasswordCalls)
        assertEquals(LockReauthController.BLANK_PASSWORD_MESSAGE, controller.state.error)
        assertFalse(controller.state.cooldown)
        assertTrue(controller.state.dialogVisible)
    }

    @Test
    fun `biometric success reveals without a password dialog`() {
        val prompt = LockBiometricPrompt { onResult ->
            onResult(true)
            true
        }
        val controller = controller(prompt)

        controller.requestNote()

        assertTrue("note-1" in repository.verifiedLockedIds.value)
        assertEquals(1, followUpCalls)
        assertFalse(controller.state.dialogVisible)
    }

    @Test
    fun `biometric cancel keeps content hidden and offers the password dialog`() {
        val prompt = LockBiometricPrompt { onResult ->
            onResult(false)
            true
        }
        val controller = controller(prompt)

        controller.requestNote()

        assertTrue(controller.state.dialogVisible)
        assertEquals(LockReauthController.BIOMETRIC_CANCELLED_MESSAGE, controller.state.error)
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
        assertEquals(0, followUpCalls)
    }

    @Test
    fun `already verified target reveals immediately without a prompt`() {
        repository.markLockVerified("note-1")
        val controller = controller()

        controller.requestNote()

        assertEquals(1, followUpCalls)
        assertFalse(controller.state.dialogVisible)
    }

    @Test
    fun `cancel never reveals and clears the pending request`() {
        val controller = controller()
        controller.requestNote()
        controller.updatePassword("Correct-Horse-9!")

        controller.cancel()

        assertNull(controller.state.target)
        assertFalse(controller.state.dialogVisible)
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
        assertEquals(0, followUpCalls)
    }

    @Test
    fun `locked folder target shares the same verification set`() {
        val controller = controller()
        controller.request(LockedTarget("folder-1", LockTargetKind.FOLDER), afterReveal = { followUpCalls++ })

        controller.updatePassword("Correct-Horse-9!")
        controller.submitPassword()

        assertTrue("folder-1" in repository.verifiedLockedIds.value)
        assertEquals(1, followUpCalls)
    }
}
