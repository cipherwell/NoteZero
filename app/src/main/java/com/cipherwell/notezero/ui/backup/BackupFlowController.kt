package com.cipherwell.notezero.ui.backup

import com.cipherwell.notezero.backup.BackupFormat
import com.cipherwell.notezero.data.BackupImportReport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which backup operation the user asked for. */
enum class BackupMode {
    /** Encrypted with the vault's own key material (password or recovery phrase restores it). */
    EXPORT_VAULT_KEY,

    /** Encrypted with a password chosen for this file only. */
    EXPORT_PASSWORD,

    /** Restore an envelope into the current (unlocked) vault. */
    IMPORT,
}

/** Non-secret UI state of one backup operation. No path, content or credential is ever held here. */
sealed interface BackupUiState {
    /** Nothing in flight. */
    data object Idle : BackupUiState

    /** An external activity (SAF picker) has been launched by the UI; waiting for its result. */
    data object PickingLocation : BackupUiState

    /** Encryption/decryption is running; [mode] drives the progress/status copy. */
    data class Running(val mode: BackupMode) : BackupUiState

    /** The operation completed. [report] is non-null for imports (imported/duplicate/skipped counts). */
    data class Success(val mode: BackupMode, val report: BackupImportReport?) : BackupUiState

    /** The selected file belongs to another vault: ask for the source password or 12-word phrase. */
    data object NeedsCredential : BackupUiState

    /** Terminal failure described by a non-secret kind. */
    data class Error(val kind: BackupErrorKind) : BackupUiState
}

/** Non-secret failure categories; the settings UI maps each one to plain wording. */
enum class BackupErrorKind {
    /** The picked file is not a structurally valid NoteZero backup envelope. */
    INVALID_FILE,

    /** Three credential attempts failed for a valid cross-vault envelope. */
    WRONG_CREDENTIAL,

    /** The SAF stream could not be opened/read/written, or the picker could not be launched. */
    IO,

    /** The vault was locked when the operation needed it. Pending work resumes after unlock. */
    LOCKED,

    /** Export requested on a vault with no notes or folders — there is nothing to back up. */
    EMPTY_VAULT,
}

/**
 * Testable state machine for the SAF encrypted-backup flow.
 *
 * The controller owns no Android types: the UI resolves a `content://` [U]ri to streams via
 * [openOut]/[openIn] (a real `ContentResolver` adapter in the app, fakes in tests). This is
 * what keeps the security-relevant logic — when the vault may be closed, how many credential
 * attempts are allowed, what happens when the picker returns after the vault locked — unit
 * testable without a device.
 *
 * Lock handling
 * -------------
 * Launching the picker backgrounds the Activity. The controller brackets it with
 * [NoteRepository.beginExternalActivity]/[endExternalActivity] so the repository does not
 * apply its usual background lock, and it re-opens the SAF stream per attempt (input streams
 * are not replayable). If the repository is locked anyway when the picker returns (idle
 * timeout while the picker was open), the pending mode + uri stay in memory, the state
 * becomes [BackupUiState.Error] with [BackupErrorKind.LOCKED], and the operation resumes when
 * the host calls [onVaultUnlocked] after the user unlocks — the chosen file is never silently
 * dropped. The [BackupFlowViewModel] wires that call to `repository.status`.
 *
 * @param scope coroutine scope for the export/import work; the host may pass its own (the
 *   ViewModel passes `viewModelScope`) so a config change does not orphan a pending resume.
 *   Without a scope the controller creates and owns one (cancelled by [close]).
 * @param ioDispatcher dispatcher for all stream/crypto work. Defaults to [Dispatchers.IO]:
 *   the host passes `viewModelScope` (Main) as [scope], so the blocking SAF stream and the
 *   structural probe must never run on the main thread.
 */
class BackupFlowController<U : Any>(
    private val repository: NoteRepository,
    private val openOut: (U) -> OutputStream?,
    private val openIn: (U) -> InputStream?,
    private val clock: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ownsScope = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guarded by [lock]; the repository callbacks arrive on the main thread. */
    private val lock = Any()

    /** Set while the picker is launching (before a uri exists). */
    private var launch: PendingLaunch? = null

    /** Set when a uri was chosen and the work is pending/running (also survives a lock). */
    private var pending: PendingOperation<U>? = null

    private var inFlight = false
    private var closed = false

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    // =========================================================================
    // Commands from the UI
    // =========================================================================

    /**
     * Begins an export and arms the external-activity deferral. Call this in the click
     * handler immediately before launching SAF CreateDocument; the returned name is the
     * suggested `notezero-backup-YYYYMMDD-HHmm.nzbk` file name.
     *
     * @param password separate backup password for [BackupMode.EXPORT_PASSWORD]; null for
     *   [BackupMode.EXPORT_VAULT_KEY]. The array is owned (and wiped) by the controller.
     * @return true when the UI should launch the picker.
     */
    fun startExport(mode: BackupMode, password: CharArray? = null): Boolean {
        require(mode != BackupMode.IMPORT) { "startExport requires an export mode" }
        require(mode == BackupMode.EXPORT_PASSWORD || password == null) {
            "vault-key exports must not carry a separate password"
        }
        require(mode != BackupMode.EXPORT_PASSWORD || password != null) {
            "separate-password exports need a password"
        }
        if (inProgress()) {
            wipe(password)
            return false
        }
        discardPending()
        if (repository.status.value != VaultStatus.UNLOCKED) {
            wipe(password)
            _state.value = BackupUiState.Error(BackupErrorKind.LOCKED)
            return false
        }
        if (repository.items.value.isEmpty() && repository.folders.value.isEmpty()) {
            wipe(password)
            _state.value = BackupUiState.Error(BackupErrorKind.EMPTY_VAULT)
            return false
        }
        synchronized(lock) { launch = PendingLaunch(mode, password) }
        repository.beginExternalActivity()
        _state.value = BackupUiState.PickingLocation
        return true
    }

    /** Begins an import and arms the external-activity deferral before SAF OpenDocument. */
    fun startImport(): Boolean {
        if (inProgress()) return false
        discardPending()
        if (repository.status.value != VaultStatus.UNLOCKED) {
            _state.value = BackupUiState.Error(BackupErrorKind.LOCKED)
            return false
        }
        synchronized(lock) { launch = PendingLaunch(BackupMode.IMPORT, null) }
        repository.beginExternalActivity()
        _state.value = BackupUiState.PickingLocation
        return true
    }

    /** Suggested SAF file name; derived from the injected clock, timezone-local. */
    fun suggestedFileName(): String {
        // A fresh formatter per call: SimpleDateFormat is not thread-safe and this may be
        // called from the main thread while an export finishes on the IO scope.
        val formatter = SimpleDateFormat(FILE_NAME_PATTERN, Locale.US)
        return "notezero-backup-${formatter.format(Date(clock()))}.nzbk"
    }

    /** The picker returned a destination/source uri (success; cancel calls [onLocationCancelled]). */
    fun onLocationChosen(uri: U) {
        val launched = synchronized(lock) {
            val current = launch
            launch = null
            current
        } ?: return
        repository.endExternalActivity()
        if (repository.status.value != VaultStatus.UNLOCKED) {
            // The picker stayed open past the idle timeout: keep the user's file and resume
            // after unlock instead of dropping it.
            synchronized(lock) {
                pending = PendingOperation(
                    mode = launched.mode,
                    uri = uri,
                    password = launched.password,
                    attempts = 0,
                )
            }
            _state.value = BackupUiState.Error(BackupErrorKind.LOCKED)
            return
        }
        when (launched.mode) {
            BackupMode.IMPORT -> beginImport(PendingOperation(launched.mode, uri, null, 0))
            else -> beginExport(PendingOperation(launched.mode, uri, launched.password, 0))
        }
    }

    /** The picker was dismissed without a uri: nothing to do, the vault is untouched. */
    fun onLocationCancelled() {
        val hadLaunch = synchronized(lock) {
            val current = launch
            launch = null
            current != null
        }
        if (!hadLaunch) return
        repository.endExternalActivity()
        _state.value = BackupUiState.Idle
    }

    /** Launching the picker itself failed (no SAF handler / security exception). */
    fun onPickerLaunchFailed() {
        val hadLaunch = synchronized(lock) {
            val current = launch
            launch = null
            current != null
        }
        if (!hadLaunch) return
        repository.endExternalActivity()
        _state.value = BackupUiState.Error(BackupErrorKind.IO)
    }

    /**
     * Provides the cross-vault credential: the source master password, or the source
     * 12-word phrase as one text (words separated by single spaces). The repository decides
     * which wrapper the text opens; this controller never inspects or stores it beyond the
     * attempt.
     */
    fun submitCredential(credentialText: String) {
        val trimmed = credentialText.trim()
        if (trimmed.isEmpty()) return
        submitCredential(trimmed.toCharArray())
    }

    /** CharArray form of [submitCredential]; the array is wiped by the controller. */
    fun submitCredential(credential: CharArray) {
        if (_state.value != BackupUiState.NeedsCredential) {
            wipe(credential)
            return
        }
        val operation = synchronized(lock) {
            if (inFlight) null else pending?.takeIf { it.mode == BackupMode.IMPORT }
        }
        if (operation == null) {
            wipe(credential)
            return
        }
        beginImport(
            PendingOperation(
                mode = BackupMode.IMPORT,
                uri = operation.uri,
                password = credential,
                attempts = operation.attempts,
            ),
        )
    }

    /** Phrase form of [submitCredential]; words are joined with single spaces as the contract requires. */
    fun submitPhrase(words: List<String>) {
        if (words.isEmpty()) return
        submitCredential(words.joinToString(" "))
    }

    /**
     * Called when the host observes the repository becoming UNLOCKED. Resumes a pending
     * export/import that was interrupted by the idle lock. Idempotent and safe to call on
     * every unlock.
     */
    fun onVaultUnlocked() {
        if (repository.status.value != VaultStatus.UNLOCKED) return
        val operation = synchronized(lock) {
            if (inFlight) null else pending
        } ?: return
        when (operation.mode) {
            BackupMode.IMPORT -> beginImport(
                PendingOperation(BackupMode.IMPORT, operation.uri, password = null, operation.attempts),
            )
            else -> beginExport(operation)
        }
    }

    /** Abandons the current flow (success/error acknowledged, or the user left the screen). */
    fun reset() {
        if (inProgress()) return
        discardPending()
        _state.value = BackupUiState.Idle
    }

    /** Releases the external-activity bracket and wipes any held credential. Idempotent. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        discardPending()
        if (ownsScope) scope.cancel()
    }

    // =========================================================================
    // Operation runners
    // =========================================================================

    private fun beginExport(operation: PendingOperation<U>) {
        synchronized(lock) {
            pending = operation
            inFlight = true
        }
        _state.value = BackupUiState.Running(operation.mode)
        scope.launch(ioDispatcher) {
            var written = false
            var opened = false
            try {
                val out = openOut(operation.uri)
                if (out != null) {
                    opened = true
                    try {
                        written = repository.exportBackup(out, operation.password)
                    } finally {
                        runCatching { out.close() }
                    }
                }
            } catch (t: Throwable) {
                written = false
            }
            completeExport(operation, written && opened)
        }
    }

    private fun completeExport(operation: PendingOperation<U>, success: Boolean) {
        synchronized(lock) { inFlight = false }
        if (success) {
            synchronized(lock) { pending = null }
            wipe(operation.password)
            _state.value = BackupUiState.Success(operation.mode, report = null)
            return
        }
        if (repository.status.value != VaultStatus.UNLOCKED) {
            // Locked mid-export: keep the uri + password so the unlock resumes the write.
            _state.value = BackupUiState.Error(BackupErrorKind.LOCKED)
            return
        }
        synchronized(lock) { pending = null }
        wipe(operation.password)
        _state.value = BackupUiState.Error(BackupErrorKind.IO)
    }

    private fun beginImport(operation: PendingOperation<U>) {
        synchronized(lock) {
            pending = operation
            inFlight = true
        }
        _state.value = BackupUiState.Running(BackupMode.IMPORT)
        scope.launch(ioDispatcher) {
            var report: BackupImportReport? = null
            var ioFailure = false
            val input = try {
                openIn(operation.uri)
            } catch (t: Throwable) {
                null
            }
            if (input == null) {
                ioFailure = true
            } else {
                try {
                    report = repository.importBackup(input, operation.password)
                } catch (t: Throwable) {
                    report = null
                } finally {
                    runCatching { input.close() }
                }
            }
            completeImport(operation, report, ioFailure)
        }
    }

    private fun completeImport(
        operation: PendingOperation<U>,
        report: BackupImportReport?,
        ioFailure: Boolean,
    ) {
        synchronized(lock) { inFlight = false }
        if (report != null) {
            synchronized(lock) { pending = null }
            wipe(operation.password)
            _state.value = BackupUiState.Success(BackupMode.IMPORT, report)
            return
        }
        if (repository.status.value != VaultStatus.UNLOCKED) {
            // Locked mid-import: keep the uri so the unlock resumes (nothing was written —
            // importBackup authenticates the whole file before the first row).
            _state.value = BackupUiState.Error(BackupErrorKind.LOCKED)
            return
        }
        if (ioFailure) {
            synchronized(lock) { pending = null }
            wipe(operation.password)
            _state.value = BackupUiState.Error(BackupErrorKind.IO)
            return
        }
        if (operation.password == null) {
            // No credential and the import was rejected: either the file is not a backup or
            // it belongs to another vault. Validate the envelope structurally before asking
            // for a credential, so garbage never triggers a credential prompt.
            if (looksLikeBackup(operation.uri)) {
                _state.value = BackupUiState.NeedsCredential
            } else {
                synchronized(lock) { pending = null }
                _state.value = BackupUiState.Error(BackupErrorKind.INVALID_FILE)
            }
            return
        }
        // A credential was supplied and rejected.
        wipe(operation.password)
        val attempts = operation.attempts + 1
        if (attempts >= MAX_CREDENTIAL_ATTEMPTS) {
            synchronized(lock) { pending = null }
            _state.value = BackupUiState.Error(BackupErrorKind.WRONG_CREDENTIAL)
        } else {
            synchronized(lock) {
                pending = PendingOperation(BackupMode.IMPORT, operation.uri, password = null, attempts = attempts)
            }
            _state.value = BackupUiState.NeedsCredential
        }
    }

    /**
     * Structural probe (pure JVM, no decryption): true when the stream parses as a v1
     * backup envelope. Used only to separate "not a backup file" from "other vault".
     */
    private fun looksLikeBackup(uri: U): Boolean {
        val input = try {
            openIn(uri)
        } catch (t: Throwable) {
            return true // cannot re-read: don't mislabel a readable file as corrupt
        } ?: return true
        return try {
            BackupFormat.read(input) != null
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { input.close() }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun inProgress(): Boolean = synchronized(lock) { inFlight }

    private fun discardPending() {
        var launchToSettle: PendingLaunch? = null
        var operation: PendingOperation<U>? = null
        synchronized(lock) {
            launchToSettle = launch
            operation = pending
            launch = null
            pending = null
        }
        if (launchToSettle != null) repository.endExternalActivity()
        wipe(launchToSettle?.password)
        wipe(operation?.password)
    }

    private fun wipe(password: CharArray?) {
        if (password == null) return
        try {
            password.fill('\u0000')
        } catch (t: Throwable) {
            // CharArray.fill cannot fail; defensive only.
        }
    }

    /** The pre-uri phase of a flow: what to do once SAF returns a uri. */
    private class PendingLaunch(val mode: BackupMode, val password: CharArray?)

    /** A chosen file and the work still to do with it; survives a lock/unlock cycle. */
    private class PendingOperation<U>(
        val mode: BackupMode,
        val uri: U,
        val password: CharArray?,
        val attempts: Int,
    )

    private companion object {
        const val MAX_CREDENTIAL_ATTEMPTS = 3
        const val FILE_NAME_PATTERN = "yyyyMMdd-HHmm"
    }
}
