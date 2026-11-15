package com.cipherwell.notezero.ui.backup

import com.cipherwell.notezero.backup.BackupFormat
import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.EnvelopeType
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.data.BackupImportReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM tests for the backup flow state machine: SAF is simulated with in-memory streams
 * and a fake repository, so the security-relevant rules (external-activity bracket, lock
 * resume, 3-attempt credential cap, phrase joining, encrypted-envelope-only output) are
 * asserted without a device.
 */
class BackupFlowControllerTest {

    private lateinit var repository: FakeBackupRepository
    private lateinit var scope: CoroutineScope
    private lateinit var validEnvelope: ByteArray

    private val written = ByteArrayOutputStream()
    private val opened = AtomicInteger(0)

    @Before
    fun setUp() {
        repository = FakeBackupRepository()
        // Unconfined: the controller's suspend work has no real suspension points against
        // the fake repository, so state transitions are asserted synchronously (no sleeps).
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        validEnvelope = buildValidEnvelope()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        openOut: (String) -> OutputStream? = { written },
        openIn: (String) -> InputStream? = {
            opened.incrementAndGet()
            ByteArrayInputStream(validEnvelope)
        },
    ): BackupFlowController<String> = BackupFlowController(
        repository = repository,
        openOut = openOut,
        openIn = openIn,
        clock = { FIXED_NOW },
        scope = scope,
        ioDispatcher = Dispatchers.Unconfined,
    )

    // ---- export --------------------------------------------------------------

    @Test
    fun exportVaultKeyModeWritesNonEmptyEnvelopeAndBalancesExternalActivity() {
        val controller = controller()

        assertTrue(controller.startExport(BackupMode.EXPORT_VAULT_KEY))
        assertEquals(1, repository.beginExternalCalls.get())
        assertEquals(BackupUiState.PickingLocation, controller.state.value)

        controller.onLocationChosen(URI_EXPORT)

        assertEquals(1, repository.endExternalCalls.get())
        assertEquals(1, repository.exportCalls.get())
        assertEquals(0, repository.importCalls.get())
        assertEquals(BackupUiState.Success(BackupMode.EXPORT_VAULT_KEY, null), controller.state.value)
        assertFalse(written.toByteArray().isEmpty())
        assertNull(repository.lastExportPasswordText) // vault-key mode carries no file password
    }

    @Test
    fun exportPasswordModePassesTheSeparatePasswordAndWipesItAfterSuccess() {
        val controller = controller()
        val password = "backup-only-secret".toCharArray()

        assertTrue(controller.startExport(BackupMode.EXPORT_PASSWORD, password))
        controller.onLocationChosen(URI_EXPORT)

        assertEquals(BackupUiState.Success(BackupMode.EXPORT_PASSWORD, null), controller.state.value)
        assertEquals("backup-only-secret", repository.lastExportPasswordText)
        assertTrue("controller must zeroize the password", password.all { it == '\u0000' })
    }

    @Test
    fun exportOnEmptyVaultIsRefusedWithoutLaunchingThePicker() {
        repository = FakeBackupRepository(withContent = false)
        val controller = controller()

        assertFalse(controller.startExport(BackupMode.EXPORT_VAULT_KEY))

        assertEquals(BackupUiState.Error(BackupErrorKind.EMPTY_VAULT), controller.state.value)
        assertEquals(0, repository.beginExternalCalls.get())
        assertEquals(0, repository.exportCalls.get())
        assertTrue(written.toByteArray().isEmpty())
    }

    @Test
    fun exportWhileLockedIsRefusedWithoutLaunchingThePicker() {
        repository.lockVault()
        val controller = controller()

        assertFalse(controller.startExport(BackupMode.EXPORT_VAULT_KEY))

        assertEquals(BackupUiState.Error(BackupErrorKind.LOCKED), controller.state.value)
        assertEquals(0, repository.beginExternalCalls.get())
    }

    @Test
    fun exportIoFailureIsReportedAsIoAndSettlesTheBracket() {
        repository.exportResult = false
        val controller = controller()
        val password = "backup-only-secret".toCharArray()

        assertTrue(controller.startExport(BackupMode.EXPORT_PASSWORD, password))
        controller.onLocationChosen(URI_EXPORT)

        assertEquals(1, repository.endExternalCalls.get())
        assertEquals(BackupUiState.Error(BackupErrorKind.IO), controller.state.value)
        assertTrue("failed export still zeroizes the password", password.all { it == '\u0000' })
    }

    @Test
    fun unopenableDestinationIsReportedAsIo() {
        val controller = controller(openOut = { null })

        assertTrue(controller.startExport(BackupMode.EXPORT_VAULT_KEY))
        controller.onLocationChosen(URI_EXPORT)

        assertEquals(BackupUiState.Error(BackupErrorKind.IO), controller.state.value)
        assertEquals(0, repository.exportCalls.get())
        assertEquals(1, repository.endExternalCalls.get())
    }

    @Test
    fun throwingExportIsReportedAsIoNotCrashed() {
        repository.throwOnExport = true
        val controller = controller()

        assertTrue(controller.startExport(BackupMode.EXPORT_VAULT_KEY))
        controller.onLocationChosen(URI_EXPORT)

        assertEquals(BackupUiState.Error(BackupErrorKind.IO), controller.state.value)
    }

    // ---- picker lifecycle ----------------------------------------------------

    @Test
    fun cancelledPickerReturnsToIdleAndSettlesTheBracket() {
        val controller = controller()

        assertTrue(controller.startExport(BackupMode.EXPORT_VAULT_KEY))
        controller.onLocationCancelled()

        assertEquals(BackupUiState.Idle, controller.state.value)
        assertEquals(1, repository.beginExternalCalls.get())
        assertEquals(1, repository.endExternalCalls.get())
        assertEquals(0, repository.exportCalls.get())
    }

    @Test
    fun pickerLaunchFailureSettlesTheBracketAndReportsIo() {
        val controller = controller()

        assertTrue(controller.startImport())
        controller.onPickerLaunchFailed()

        assertEquals(BackupUiState.Error(BackupErrorKind.IO), controller.state.value)
        assertEquals(1, repository.beginExternalCalls.get())
        assertEquals(1, repository.endExternalCalls.get())
    }

    @Test
    fun suggestedFileNameMatchesTheContractPattern() {
        val controller = controller()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(FIXED_NOW))

        assertEquals("notezero-backup-$stamp.nzbk", controller.suggestedFileName())
        assertTrue(controller.suggestedFileName().matches(Regex("notezero-backup-\\d{8}-\\d{4}\\.nzbk")))
    }

    // ---- import --------------------------------------------------------------

    @Test
    fun importOfSameVaultBackupSucceedsWithoutCredential() {
        repository.importWithoutCredentialReport = BackupImportReport(4, 2, 1, 3)
        val controller = controller()

        assertTrue(controller.startImport())
        assertEquals(1, repository.beginExternalCalls.get())
        controller.onLocationChosen(URI_IMPORT)

        assertEquals(1, repository.endExternalCalls.get())
        assertEquals(
            BackupUiState.Success(BackupMode.IMPORT, BackupImportReport(4, 2, 1, 3)),
            controller.state.value,
        )
        assertEquals(1, repository.importCalls.get())
    }

    @Test
    fun crossVaultImportPromptsForCredentialThenRestores() {
        repository.importWithoutCredentialReport = null
        repository.importReport = BackupImportReport(2, 1, 0, 1)
        val controller = controller()

        assertTrue(controller.startImport())
        controller.onLocationChosen(URI_IMPORT)

        assertEquals(BackupUiState.NeedsCredential, controller.state.value)
        assertEquals(1, repository.importCalls.get())

        controller.submitCredential("source master password")

        assertEquals("source master password", repository.lastImportCredentialText)
        assertEquals(
            BackupUiState.Success(BackupMode.IMPORT, BackupImportReport(2, 1, 0, 1)),
            controller.state.value,
        )
        assertEquals(1, repository.endExternalCalls.get())
    }

    @Test
    fun phraseIsSubmittedAsWordsJoinedBySingleSpaces() {
        repository.importWithoutCredentialReport = null
        repository.importReport = BackupImportReport(1, 0, 0, 0)
        val controller = controller()

        assertTrue(controller.startImport())
        controller.onLocationChosen(URI_IMPORT)
        assertEquals(BackupUiState.NeedsCredential, controller.state.value)

        controller.submitPhrase(PHRASE)

        assertEquals(PHRASE.joinToString(" "), repository.lastImportCredentialText)
        assertTrue(controller.state.value is BackupUiState.Success)
    }

    @Test
    fun wrongCredentialsAreCappedAtThreeAttempts() {
        repository.importWithoutCredentialReport = null
        repository.importReport = null
        val controller = controller()

        assertTrue(controller.startImport())
        controller.onLocationChosen(URI_IMPORT)
        assertEquals(BackupUiState.NeedsCredential, controller.state.value)

        controller.submitCredential("wrong-1")
        assertEquals(BackupUiState.NeedsCredential, controller.state.value)
        controller.submitCredential("wrong-2")
        assertEquals(BackupUiState.NeedsCredential, controller.state.value)
        controller.submitCredential("wrong-3")
        assertEquals(BackupUiState.Error(BackupErrorKind.WRONG_CREDENTIAL), controller.state.value)

        // Probe + exactly three credential attempts; no more work after the cap.
        assertEquals(4, repository.importCalls.get())
        controller.submitCredential("wrong-4")
        assertEquals(4, repository.importCalls.get())
    }

    @Test
    fun garbageFileIsReportedAsInvalidInsteadOfPromptingForCredentials() {
        val controller = controller(openIn = { ByteArrayInputStream("not a backup".toByteArray()) })

        assertTrue(controller.startImport())
        controller.onLocationChosen(URI_IMPORT)

        assertEquals(BackupUiState.Error(BackupErrorKind.INVALID_FILE), controller.state.value)
        assertEquals(1, repository.importCalls.get())
        assertEquals(1, repository.endExternalCalls.get())
    }

    // ---- lock resume ---------------------------------------------------------

    @Test
    fun exportResumesAfterUnlockWhenThePickerReturnedWhileLocked() {
        val controller = controller()
        val password = "backup-only-secret".toCharArray()

        assertTrue(controller.startExport(BackupMode.EXPORT_PASSWORD, password))
        repository.lockVault()
        controller.onLocationChosen(URI_EXPORT)

        // Nothing is exported into a locked vault; the chosen file stays pending.
        assertEquals(1, repository.endExternalCalls.get())
        assertEquals(0, repository.exportCalls.get())
        assertEquals(BackupUiState.Error(BackupErrorKind.LOCKED), controller.state.value)

        repository.unlockVault()
        controller.onVaultUnlocked()

        assertEquals(BackupUiState.Success(BackupMode.EXPORT_PASSWORD, null), controller.state.value)
        assertEquals(1, repository.exportCalls.get())
        assertEquals("backup-only-secret", repository.lastExportPasswordText)
        assertEquals(BackupMode.EXPORT_PASSWORD, (controller.state.value as BackupUiState.Success).mode)
        assertTrue("resumed export zeroizes the password", password.all { it == '\u0000' })
    }

    @Test
    fun importResumesAfterUnlockWhenThePickerReturnedWhileLocked() {
        repository.importWithoutCredentialReport = BackupImportReport(5, 0, 1, 2)
        val controller = controller()

        assertTrue(controller.startImport())
        repository.lockVault()
        controller.onLocationChosen(URI_IMPORT)

        assertEquals(BackupUiState.Error(BackupErrorKind.LOCKED), controller.state.value)
        assertEquals(0, repository.importCalls.get())

        repository.unlockVault()
        controller.onVaultUnlocked()

        assertEquals(
            BackupUiState.Success(BackupMode.IMPORT, BackupImportReport(5, 0, 1, 2)),
            controller.state.value,
        )
        assertEquals(1, repository.importCalls.get())
    }

    @Test
    fun lockDuringCredentialPromptResumesIntoThePromptWithoutCountingAnAttempt() {
        repository.importWithoutCredentialReport = null
        repository.importReport = null
        val controller = controller()

        assertTrue(controller.startImport())
        controller.onLocationChosen(URI_IMPORT)
        assertEquals(BackupUiState.NeedsCredential, controller.state.value)

        // The user backgrounds the credential dialog: the vault locks and the resume probe
        // runs again after unlock (still cross-vault, so the prompt returns).
        repository.lockVault()
        controller.onVaultUnlocked() // no-op while locked
        assertEquals(BackupUiState.NeedsCredential, controller.state.value)

        repository.unlockVault()
        controller.onVaultUnlocked()
        assertEquals(BackupUiState.NeedsCredential, controller.state.value)
        assertEquals(2, repository.importCalls.get()) // probe once more, no attempt consumed
    }

    @Test
    fun resetDropsAPendingResumeAndReturnsToIdle() {
        val controller = controller()
        assertTrue(controller.startExport(BackupMode.EXPORT_VAULT_KEY))
        repository.lockVault()
        controller.onLocationChosen(URI_EXPORT)
        assertEquals(BackupUiState.Error(BackupErrorKind.LOCKED), controller.state.value)

        controller.reset()
        assertEquals(BackupUiState.Idle, controller.state.value)

        repository.unlockVault()
        controller.onVaultUnlocked()
        assertEquals(BackupUiState.Idle, controller.state.value)
        assertEquals(0, repository.exportCalls.get())
    }

    @Test
    fun onVaultUnlockedIsANoOpWhenNothingIsPending() {
        val controller = controller()
        controller.onVaultUnlocked()
        assertEquals(BackupUiState.Idle, controller.state.value)
        assertEquals(0, repository.exportCalls.get())
        assertEquals(0, repository.importCalls.get())
    }

    // ---- fixtures ------------------------------------------------------------

    /** Structurally valid v1 backup envelope (real AES-GCM, empty vault payload). */
    private fun buildValidEnvelope(): ByteArray {
        val vmk = ByteArray(NoteCrypto.VMK_LENGTH_BYTES) { (it * 3 + 1).toByte() }
        val salt = ByteArray(NoteCrypto.SALT_LENGTH_BYTES) { (it * 5 + 2).toByte() }
        val credential = "source-master-password".toCharArray()
        val envelope = KeyEnvelope(
            type = EnvelopeType.PASSWORD,
            iterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
            salt = salt,
            wrappedVmk = NoteCrypto.wrapVmk(
                vmk,
                NoteCrypto.deriveKek(credential, salt, NoteCrypto.MIN_ACCEPTED_ITERATIONS),
                CryptoAad.BACKUP.toByteArray(Charsets.UTF_8),
            ),
        )
        val out = ByteArrayOutputStream()
        BackupFormat.write(
            out = out,
            contents = BackupFormat.Contents(exportedAt = FIXED_NOW, items = emptyList(), folders = emptyList()),
            vmk = vmk,
            mode = BackupFormat.MODE_EXTRA_PASSWORD,
            envelopes = listOf(envelope),
        )
        return out.toByteArray()
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
        const val URI_EXPORT = "content://test/export.nzbk"
        const val URI_IMPORT = "content://test/import.nzbk"

        val PHRASE: List<String> = listOf(
            "abandon", "ability", "able", "about", "above", "absent",
            "absorb", "abstract", "absurd", "abuse", "access", "accident",
        )
    }
}
