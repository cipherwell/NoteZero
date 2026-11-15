package com.cipherwell.notezero.data

import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.BiometricEnroll
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

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

/** Outcome of restoring an encrypted backup envelope through SAF. */
data class BackupImportReport(
    val imported: Int,
    val duplicated: Int,
    val skipped: Int,
    val foldersImported: Int,
)

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
}

