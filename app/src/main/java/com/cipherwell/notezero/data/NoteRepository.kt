package com.cipherwell.notezero.data

import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.BiometricEnroll
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

/** Shared empty verification set: the fail-secure default of [NoteRepository.verifiedLockedIds]. */
private val NO_LOCK_VERIFICATIONS: StateFlow<Set<String>> = MutableStateFlow(emptySet())

/** High-level vault lifecycle state. The UI routes on this. */
enum class VaultStatus {
    /** No vault exists on this device yet — show setup. */
    SETUP_REQUIRED,

    /** Vault exists but the VMK is not in memory — show unlock. */
    LOCKED,

    /** VMK is in memory and the decrypted index is loaded — show notes. */
    UNLOCKED,
}

/** Result of an unlock attempt. No exception message ever carries secret material. */
enum class UnlockResult { SUCCESS, WRONG_PASSWORD, WRONG_RECOVERY_PHRASE, BIOMETRIC_FAILED, CORRUPT_VAULT }

/** Non-secret vault metadata for the settings screen. */
data class VaultInfo(
    val createdAt: Long,
    val itemCount: Int,
    val folderCount: Int,
    val kdfIterations: Int,
    val biometricUnlockEnabled: Boolean,
    val schemaVersion: Int,
)

/**
 * Outcome of restoring an encrypted backup envelope through SAF.
 *
 * [attachmentsImported]/[attachmentsSkipped] are the v2 attachment-blob counts (zero for
 * v1 backups). A skipped attachment is never an error that fails the import: e.g. the
 * backup carried a ref but no blob, the blob failed its SHA-256/integrity check, or the
 * target note does not reference that attachment id. The note itself is still restored.
 */
data class BackupImportReport(
    val imported: Int,
    val duplicated: Int,
    val skipped: Int,
    val foldersImported: Int,
    val attachmentsImported: Int = 0,
    val attachmentsSkipped: Int = 0,
)

/** Hard limits for encrypted attachments. Values are part of the storage/backup contract. */
object AttachmentLimits {
    /** Maximum plaintext size of one attachment (10 MiB). */
    const val MAX_ATTACHMENT_BYTES: Long = 10L * 1024 * 1024

    /** Maximum number of attachments in one item. */
    const val MAX_ATTACHMENTS_PER_NOTE: Int = 100

    /** Maximum total plaintext size of all referenced attachments in the vault (512 MiB). */
    const val MAX_TOTAL_BYTES: Long = 512L * 1024 * 1024
}

/**
 * Non-secret attachment failure kinds. The UI maps these to friendly messages; no path,
 * file name or content is ever included.
 */
enum class AttachmentError {
    /** The source exceeded [AttachmentLimits.MAX_ATTACHMENT_BYTES]. */
    TOO_LARGE,

    /** The per-item count or the vault-wide byte quota would be exceeded. */
    QUOTA_EXCEEDED,

    /** The target note does not exist (or is not in the decrypted index). */
    NOTE_NOT_FOUND,

    /** The vault is locked / locked-out; attachments fail closed. */
    LOCKED,

    /** Reading the source, writing the blob or persisting the item failed. */
    IO,

    /** The stored blob failed its SHA-256 / AEAD / size integrity check. */
    INTEGRITY,
}

/** Result of [NoteRepository.importAttachment]. */
sealed interface AttachmentImport {
    data class Success(val ref: AttachmentRef) : AttachmentImport

    data class Failure(val error: AttachmentError) : AttachmentImport
}

/**
 * The single contract between the encrypted data layer and the Compose UI.
 *
 * Implementation rules (data-layer):
 *  - Every suspend function is main-safe (dispatch to Dispatchers.IO internally).
 *  - [items]/[folders] expose the DECRYPTED in-memory index; nothing decrypted is
 *    ever persisted by this class.
 *  - After [lock] the flows must emit empty lists; after [unlock] they repopulate.
 *  - Unknown/corrupt ciphertext is skipped, never surfaced as plaintext.
 *
 * Session/auto-lock: the implementation owns an idle timer; UI calls [registerInteraction]
 * on real user input and the Application/Activity calls [onAppBackgrounded]/[onAppForegrounded].
 */
interface NoteRepository {
    val status: StateFlow<VaultStatus>
    val items: StateFlow<List<Item>>
    val folders: StateFlow<List<Folder>>
    val vaultInfo: StateFlow<VaultInfo?>
    val autoLockMinutes: StateFlow<Int>
    val themeMode: StateFlow<ThemeMode>

    // ---- Vault lifecycle ----------------------------------------------------

    /** First-run setup: creates the VMK, wraps it under the password, returns the recovery phrase ONCE. */
    suspend fun setupVault(password: CharArray): List<String>

    suspend fun unlock(password: CharArray): UnlockResult

    /**
     * Enabling biometric unlock is two-phase (Keystore keys with per-use auth cannot
     * encrypt without a prompt): first [prepareBiometricEnroll], drive a BiometricPrompt
     * with the returned cipher, then call [completeBiometricEnroll]. Requires an unlocked
     * vault; returns null/false otherwise.
     */
    fun prepareBiometricEnroll(): BiometricEnroll?

    suspend fun completeBiometricEnroll(enroll: BiometricEnroll): Boolean

    suspend fun disableBiometricUnlock()

    /**
     * Prepares the Keystore cipher for a BiometricPrompt; UI wraps it in a
     * BiometricPrompt.CryptoObject, then calls [completeBiometricUnlock].
     * Returns null when biometric unlock is not enrolled/available.
     */
    fun prepareBiometricDecrypt(): BiometricDecrypt?

    suspend fun completeBiometricUnlock(decrypt: BiometricDecrypt): UnlockResult

    suspend fun lock()

    suspend fun changePassword(current: CharArray, next: CharArray): Boolean

    /** Reset by recovery phrase: unwraps the VMK, re-wraps under [newPassword], rotates the kit. */
    suspend fun resetWithRecoveryPhrase(phrase: List<String>, newPassword: CharArray): UnlockResult

    /** Rotates the recovery kit (requires the current password); returns the new phrase ONCE. */
    suspend fun rotateRecoveryPhrase(currentPassword: CharArray): List<String>?

    /** Validate a phrase for the recovery screen without changing anything. */
    suspend fun validateRecoveryPhrase(phrase: List<String>): Boolean

    // ---- Session ------------------------------------------------------------

    /** Called on real user input; resets the idle auto-lock timer. */
    fun registerInteraction()

    /** App moved to background/screen off — arms the background lock. */
    fun onAppBackgrounded()

    fun onAppForegrounded()

    /**
     * Marks the start of a system-owned external activity (the SAF picker) launched from
     * the unlocked vault. While one is active the [onAppBackgrounded] caused by that
     * activity must NOT lock the vault (the user did not leave the app on purpose); the
     * pending background state is recorded and [endExternalActivity] decides once the
     * result arrives. The idle auto-lock timer keeps running, so a picker left open past
     * the timeout still locks — there is no permanent suppression.
     *
     * Call immediately before launching the activity, from the main thread.
     */
    fun beginExternalActivity() {
        // Fail-secure default (test doubles that never launch system activities): no
        // deferral is recorded, so onAppBackgrounded keeps its immediate lock.
    }

    /**
     * Marks the end of that external activity (result success, cancel or error). If the
     * app has not returned to the foreground yet, this locks the vault immediately.
     *
     * Defaults are fail-secure no-ops for test fakes that never launch system activities;
     * [EncryptedNoteRepository] overrides both methods with the real deferral.
     */
    fun endExternalActivity() {
        // Fail-secure default: nothing was deferred, so nothing to settle.
    }

    suspend fun setAutoLockMinutes(minutes: Int)

    suspend fun setThemeMode(mode: ThemeMode)

    // ---- Items --------------------------------------------------------------

    suspend fun createItem(kind: ItemKind, folderId: String? = null): Item

    /** Persists the decrypted item; bumps [Item.updatedAt]/[Item.version]. Returns saved item. */
    suspend fun saveItem(item: Item): Item

    suspend fun trashItem(id: String)

    suspend fun restoreItem(id: String)

    suspend fun deleteItemForever(id: String)

    suspend fun emptyTrash()

    suspend fun setPinned(id: String, pinned: Boolean)

    suspend fun setFavorite(id: String, favorite: Boolean)

    suspend fun moveItem(id: String, folderId: String?)

    // ---- Folders ------------------------------------------------------------

    suspend fun createFolder(name: String, parentId: String? = null): Folder

    suspend fun renameFolder(id: String, name: String)

    /** Deletes a folder; its children move to the folder's parent (never deleted implicitly). */
    suspend fun deleteFolder(id: String)

    // ---- Locked notes & folders (visibility gate; decision #15) --------------

    /**
     * Locks/unlocks one note and persists the flag **inside** the item envelope
     * (`ItemCodec` payload v3). Locking never changes the VMK and never changes the item
     * ciphertext AAD: the content stays sealed exactly as before, this flag only tells the
     * UI to mask it until the session re-authenticates for [id].
     *
     * The repository owns the flag like attachment metadata: `saveItem` preserves the
     * stored value, so an editor autosave with a stale copy can never unlock a locked note.
     * Changing the flag drops any verification for [id] (locking re-masks; unlocking makes
     * the verification moot).
     *
     * Returns false when the vault is locked or [id] is unknown; true when the flag is
     * saved or already in the requested state. Callers are expected to have re-authenticated
     * before unlocking (the repository does not gate removal on verification).
     *
     * Default is fail-secure (`false`) for test doubles / alternative implementations.
     */
    suspend fun setItemLocked(id: String, locked: Boolean): Boolean = false

    /**
     * Locks/unlocks one folder and persists the flag inside the folder envelope
     * (`FolderCodec` payload v2). Descendants are not modified: hiding them is the UI's
     * job while the folder (or an ancestor) is locked and unverified. Same ownership and
     * fail-secure rules as [setItemLocked].
     */
    suspend fun setFolderLocked(id: String, locked: Boolean): Boolean = false

    /**
     * Re-authentication for locked content: verifies [password] against the stored master
     * (password) `KeyEnvelope` **without changing the session** — the vault stays unlocked,
     * the VMK is untouched. The unwrapped VMK is compared to the live one in constant time.
     *
     * Rate limiting (injected clock, deterministic): after a wrong non-blank password the
     * next attempt is delayed by a growing delay (1 s, 2 s, 3 s, 4 s for failures 1–4);
     * the 5th consecutive failure opens a 30 s cooldown. While an attempt is blocked the
     * method returns false immediately, before the KDF runs, and changes no state. A
     * successful verification resets the counter. The state is memory-only and is reset
     * when a session starts/ends.
     *
     * Wrong and blank passwords both return false with no state change (the char array is
     * wiped in every case, including exceptions) and neither reveals whether the vault is
     * corrupt. The result must never be logged.
     *
     * Default is fail-secure (`false`) for test doubles / alternative implementations.
     */
    suspend fun verifyMasterPassword(password: CharArray): Boolean = false

    /**
     * Ids (notes AND folders) whose locked content is revealed for this session. Memory-only,
     * never persisted. Cleared by [lock], [close] and by a background lock that actually
     * applies — but **not** while an external activity (the SAF picker, see
     * [beginExternalActivity]) defers that background lock.
     */
    val verifiedLockedIds: StateFlow<Set<String>>
        get() = NO_LOCK_VERIFICATIONS

    /**
     * Call after a successful biometric/master-password re-authentication for [id]. Ignored
     * while the vault is locked (a verification can never be created outside an unlocked
     * session) and ignored for blank ids.
     *
     * Default is a no-op for test doubles / alternative implementations.
     */
    fun markLockVerified(id: String) {
        // Fail-secure default: no verification state to record.
    }

    /**
     * Clears all lock verifications, re-masking every locked note/folder. The UI calls this
     * for "re-arm locked notes now"; the repository already calls it on lock/background.
     *
     * Default is a no-op for test doubles / alternative implementations.
     */
    fun clearLockVerifications() {
        // Fail-secure default: nothing is verified.
    }

    // ---- Encrypted backup (SAF; UI resolves Uri -> streams) -----------------

    /**
     * Writes a self-contained encrypted backup envelope to [out]. When [password] is
     * non-null the backup is additionally protected by it; when null the envelope is
     * decryptable with the vault password / recovery phrase (phrase-only mode).
     */
    suspend fun exportBackup(out: OutputStream, password: CharArray?): Boolean

    /**
     * Restores an envelope from [input]. Duplicate items (same id + same updatedAt)
     * are counted, never overwritten. Returns null when the envelope is invalid or
     * the password is wrong.
     */
    suspend fun importBackup(input: InputStream, password: CharArray?): BackupImportReport?

    // ---- Encrypted attachments (v2; streams resolved by the UI) --------------

    /**
     * Encrypts [source] into the vault as a new attachment of the note [noteId].
     *
     * The blob is written to app-private storage only after it is AES-256-GCM encrypted
     * (magic `NZAT` + version + ciphertext); the per-file size limit is enforced **while
     * reading** (a hostile [source] cannot make us allocate past
     * [AttachmentLimits.MAX_ATTACHMENT_BYTES]); the vault-wide byte quota and the
     * per-note count are enforced before anything is written. Import is atomic: on any
     * failure there is no orphan blob and no attachment ref. Never logs the name/content.
     *
     * Default is fail-secure (`LOCKED`) for test fakes / alternative implementations.
     */
    suspend fun importAttachment(
        noteId: String,
        displayName: String,
        mimeType: String,
        source: InputStream,
    ): AttachmentImport = AttachmentImport.Failure(AttachmentError.LOCKED)

    /**
     * Decrypts one attachment and returns its plaintext bytes, or null when the vault is
     * locked, the note/ref is unknown, the blob is missing, or its SHA-256 / AEAD / size
     * check fails (counted, never surfaced). The result is caller-owned; it must never be
     * cached to disk or logged.
     *
     * Default is fail-secure (`null`).
     */
    suspend fun attachmentBytes(noteId: String, attachmentId: String): ByteArray? = null

    /**
     * Explicit user-initiated decrypted export (SAF target): decrypts the attachment and
     * writes the plaintext to [out]. Returns false on any failure (including locked vault
     * or integrity failure). This is the ONLY path that hands attachment plaintext to
     * external storage, and only because the user explicitly asked for it.
     *
     * Default is fail-secure (`false`).
     */
    suspend fun exportAttachment(noteId: String, attachmentId: String, out: OutputStream): Boolean =
        false

    /**
     * Removes the attachment ref from the note and deletes its encrypted blob. Returns
     * false when locked or when the note/ref does not exist. Trashing a note never deletes
     * blobs; deleting it forever / emptying the trash does.
     *
     * Default is fail-secure (`false`).
     */
    suspend fun deleteAttachment(noteId: String, attachmentId: String): Boolean = false
}


/**
 * Rate-limit policy for [NoteRepository.verifyMasterPassword], kept here (not in the UI)
 * so the numbers are part of the data-layer contract and testable with the injected clock.
 *
 * Failures 1–4 delay the next attempt by 1 s, 2 s, 3 s and 4 s (strictly growing); the 5th
 * consecutive failure opens a [COOLDOWN_MS] cooldown. Nothing sleeps inside the repository:
 * an attempt before `now + delay` is rejected immediately.
 */
internal object LockVerificationPolicy {
    /** Consecutive failures that trigger the long cooldown. */
    const val MAX_CONSECUTIVE_FAILURES: Int = 5

    /** Delay after the first failure; multiplied by the failure count (growing delay). */
    const val BASE_DELAY_MS: Long = 1_000L

    /** Blocking window after [MAX_CONSECUTIVE_FAILURES] consecutive failures. */
    const val COOLDOWN_MS: Long = 30_000L

    /** Milliseconds the next verification attempt must wait after [consecutiveFailures]. */
    fun delayAfterFailure(consecutiveFailures: Int): Long =
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            COOLDOWN_MS
        } else {
            BASE_DELAY_MS * consecutiveFailures.coerceAtLeast(1)
        }
}
