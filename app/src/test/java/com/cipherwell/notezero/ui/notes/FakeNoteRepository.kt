package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.BiometricEnroll
import com.cipherwell.notezero.data.BackupImportReport
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.UnlockResult
import com.cipherwell.notezero.data.VaultInfo
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.io.OutputStream

/**
 * Test double for [NoteRepository]. Mirrors the real repository's observable contract
 * (StateFlow index, version bump on save, decrypted-only in-memory items) without any
 * crypto or Room, so UI/ViewModel tests run as plain JVM tests.
 *
 * Test-only file: lives under src/test, never shipped in the app.
 */
class FakeNoteRepository : NoteRepository {

    private val _status = MutableStateFlow(VaultStatus.UNLOCKED)
    override val status: StateFlow<VaultStatus> = _status.asStateFlow()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    override val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    override val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _vaultInfo = MutableStateFlow<VaultInfo?>(null)
    override val vaultInfo: StateFlow<VaultInfo?> = _vaultInfo.asStateFlow()

    private val _autoLockMinutes = MutableStateFlow(5)
    override val autoLockMinutes: StateFlow<Int> = _autoLockMinutes.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Monotonic fake clock used for createdAt/updatedAt. */
    var clock: Long = 1_000_000L

    var saveCount: Int = 0
        private set

    var trashCalls: Int = 0
        private set

    val savedItems: MutableList<Item> = mutableListOf()

    /** Test-only master password used by [verifyMasterPassword]; null = no vault password. */
    var masterPassword: String? = null

    /**
     * When true the next [saveItem] simulates losing the auto-lock race: the vault locks
     * (index cleared, LOCKED published) and the call throws `IllegalStateException` exactly
     * like the real repository's `requireUnlocked()` would.
     */
    var failNextSaveAfterLock: Boolean = false

    /** Same race simulation for [setPinned] (notes-list mutation path, audit F10). */
    var lockRaceOnNextPin: Boolean = false

    var verifyMasterPasswordCalls: Int = 0
        private set

    private val _verifiedLockedIds = MutableStateFlow<Set<String>>(emptySet())
    override val verifiedLockedIds: StateFlow<Set<String>> = _verifiedLockedIds.asStateFlow()

    private var idCounter = 0

    // ---- Test helpers --------------------------------------------------------

    fun setItems(items: List<Item>) {
        _items.value = items
    }

    fun setFolders(folders: List<Folder>) {
        _folders.value = folders
    }

    fun setStatus(status: VaultStatus) {
        _status.value = status
    }

    fun itemById(id: String): Item? = _items.value.firstOrNull { it.id == id }

    fun folderById(id: String): Folder? = _folders.value.firstOrNull { it.id == id }

    fun nextClock(): Long {
        clock += 1
        return clock
    }

    /** Test helper: reveal a locked id without driving a prompt (as the repository does). */
    fun revealForTest(id: String) = markLockVerified(id)

    // ---- Vault lifecycle (no crypto in tests) --------------------------------

    override suspend fun setupVault(password: CharArray): List<String> = List(12) { "word$it" }

    override suspend fun unlock(password: CharArray): UnlockResult {
        _status.value = VaultStatus.UNLOCKED
        return UnlockResult.SUCCESS
    }

    override fun prepareBiometricEnroll(): BiometricEnroll? = null

    override suspend fun completeBiometricEnroll(enroll: BiometricEnroll): Boolean = false

    override suspend fun disableBiometricUnlock() = Unit

    override fun prepareBiometricDecrypt(): BiometricDecrypt? = null

    override suspend fun completeBiometricUnlock(decrypt: BiometricDecrypt): UnlockResult =
        UnlockResult.BIOMETRIC_FAILED

    override suspend fun lock() {
        _status.value = VaultStatus.LOCKED
        _items.value = emptyList()
        _folders.value = emptyList()
        clearLockVerifications()
    }

    override suspend fun changePassword(current: CharArray, next: CharArray): Boolean = true

    override suspend fun resetWithRecoveryPhrase(
        phrase: List<String>,
        newPassword: CharArray,
    ): UnlockResult = UnlockResult.SUCCESS

    override suspend fun rotateRecoveryPhrase(currentPassword: CharArray): List<String>? = null

    override suspend fun validateRecoveryPhrase(phrase: List<String>): Boolean = phrase.size == 12

    override fun registerInteraction() = Unit

    override fun onAppBackgrounded() = Unit

    override fun onAppForegrounded() = Unit

    override suspend fun setAutoLockMinutes(minutes: Int) {
        _autoLockMinutes.value = minutes
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    // ---- Items ---------------------------------------------------------------

    override suspend fun createItem(kind: ItemKind, folderId: String?): Item {
        idCounter += 1
        val timestamp = nextClock()
        val item = Item(
            id = "item-$idCounter",
            kind = kind,
            title = "",
            body = "",
            folderId = folderId,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        _items.value = _items.value + item
        return item
    }

    /** In-memory plaintext for the test-only attachment import/read path (never on disk). */
    private val attachmentPlaintext = mutableMapOf<String, ByteArray>()

    override suspend fun importAttachment(
        noteId: String,
        displayName: String,
        mimeType: String,
        source: InputStream,
    ): AttachmentImport {
        if (_status.value != VaultStatus.UNLOCKED) {
            return AttachmentImport.Failure(com.cipherwell.notezero.data.AttachmentError.LOCKED)
        }
        val existing = itemById(noteId)
            ?: return AttachmentImport.Failure(com.cipherwell.notezero.data.AttachmentError.LOCKED)
        val bytes = source.readBytes()
        val ref = AttachmentRef(
            id = "att-${idCounter++}",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = nextClock(),
            sha256 = "0".repeat(64),
        )
        attachmentPlaintext[ref.id] = bytes
        _items.value = _items.value.map {
            if (it.id == existing.id) it.copy(attachments = it.attachments + ref) else it
        }
        return AttachmentImport.Success(ref)
    }

    override suspend fun attachmentBytes(noteId: String, attachmentId: String): ByteArray? {
        val item = itemById(noteId) ?: return null
        if (item.attachments.none { it.id == attachmentId }) return null
        return attachmentPlaintext[attachmentId]?.copyOf()
    }

    override suspend fun saveItem(item: Item): Item {
        if (failNextSaveAfterLock) {
            failNextSaveAfterLock = false
            lockSessionLikeTheRealRepository()
            throw IllegalStateException("Vault is locked")
        }
        saveCount += 1
        val existing = itemById(item.id)
        val saved = item.copy(
            // Mirrors the real repository: only setItemLocked changes the flag, so a stale
            // editor autosave can never unlock a locked note.
            locked = existing?.locked ?: item.locked,
            readingState = existing?.readingState ?: item.readingState,
            pdfAnnotations = existing?.pdfAnnotations ?: item.pdfAnnotations,
            sketch = existing?.sketch ?: item.sketch,
            updatedAt = nextClock(),
            version = maxOf(item.version, existing?.version ?: 0L) + 1,
        )
        _items.value = _items.value.map { if (it.id == saved.id) saved else it }
        savedItems += saved
        return saved
    }

    override suspend fun saveSketch(itemId: String, sketch: SketchDocument?): Boolean {
        if (_status.value != VaultStatus.UNLOCKED) return false
        val existing = itemById(itemId) ?: return false
        _items.value = _items.value.map {
            if (it.id == existing.id) {
                it.copy(
                    sketch = sketch?.takeUnless { doc -> doc.isEmpty },
                    updatedAt = nextClock(),
                )
            } else {
                it
            }
        }
        return true
    }

    override suspend fun savePdfAnnotations(itemId: String, annotations: PdfAnnotations?): Boolean {
        if (_status.value != VaultStatus.UNLOCKED) return false
        val existing = itemById(itemId) ?: return false
        _items.value = _items.value.map {
            if (it.id == existing.id) {
                it.copy(
                    pdfAnnotations = annotations?.takeUnless { layer -> layer.isEmpty },
                    updatedAt = nextClock(),
                )
            } else {
                it
            }
        }
        return true
    }

    override suspend fun saveReadingState(itemId: String, state: ReadingState): Boolean {
        if (_status.value != VaultStatus.UNLOCKED) return false
        val existing = itemById(itemId) ?: return false
        _items.value = _items.value.map {
            if (it.id == existing.id) it.copy(readingState = state, updatedAt = nextClock()) else it
        }
        return true
    }

    override suspend fun trashItem(id: String) {
        trashCalls += 1
        mutate(id) { it.copy(trashed = true, updatedAt = nextClock()) }
    }

    override suspend fun restoreItem(id: String) {
        mutate(id) { it.copy(trashed = false, updatedAt = nextClock()) }
    }

    override suspend fun deleteItemForever(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
    }

    override suspend fun emptyTrash() {
        _items.value = _items.value.filterNot { it.trashed }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        if (lockRaceOnNextPin) {
            lockRaceOnNextPin = false
            lockSessionLikeTheRealRepository()
            throw IllegalStateException("Vault is locked")
        }
        mutate(id) { it.copy(pinned = pinned, updatedAt = nextClock(), version = it.version + 1) }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        mutate(id) { it.copy(favorite = favorite, updatedAt = nextClock(), version = it.version + 1) }
    }

    override suspend fun moveItem(id: String, folderId: String?) {
        mutate(id) { it.copy(folderId = folderId, updatedAt = nextClock(), version = it.version + 1) }
    }

    // ---- Folders -------------------------------------------------------------

    override suspend fun createFolder(name: String, parentId: String?): Folder {
        idCounter += 1
        val folder = Folder(
            id = "folder-$idCounter",
            name = name,
            parentId = parentId,
            sortOrder = _folders.value.size,
            createdAt = nextClock(),
        )
        _folders.value = _folders.value + folder
        return folder
    }

    override suspend fun renameFolder(id: String, name: String) {
        _folders.value = _folders.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun deleteFolder(id: String) {
        val target = folderById(id) ?: return
        _folders.value = _folders.value
            .filterNot { it.id == id }
            .map { if (it.parentId == id) it.copy(parentId = target.parentId) else it }
        _items.value = _items.value.map {
            if (it.folderId == id) it.copy(folderId = target.parentId, updatedAt = nextClock()) else it
        }
    }

    // ---- Locked notes & folders (visibility gate; decision #15) --------------

    override suspend fun setItemLocked(id: String, locked: Boolean): Boolean {
        if (_status.value != VaultStatus.UNLOCKED) return false
        if (itemById(id) == null) return false
        mutate(id) {
            it.copy(locked = locked, updatedAt = nextClock(), version = it.version + 1)
        }
        _verifiedLockedIds.update { it - id }
        return true
    }

    override suspend fun setFolderLocked(id: String, locked: Boolean): Boolean {
        if (_status.value != VaultStatus.UNLOCKED) return false
        if (folderById(id) == null) return false
        _folders.value = _folders.value.map {
            if (it.id == id) it.copy(locked = locked) else it
        }
        _verifiedLockedIds.update { it - id }
        return true
    }

    override suspend fun verifyMasterPassword(password: CharArray): Boolean {
        verifyMasterPasswordCalls += 1
        val stored = masterPassword
        val matches = stored != null && String(password) == stored
        // Mirrors the real repository: the caller's array is wiped on every path.
        password.fill('\u0000')
        return matches
    }

    override fun markLockVerified(id: String) {
        if (id.isBlank()) return
        if (_status.value != VaultStatus.UNLOCKED) return
        _verifiedLockedIds.update { it + id }
    }

    override fun clearLockVerifications() {
        _verifiedLockedIds.value = emptySet()
    }

    // ---- Encrypted backup ----------------------------------------------------

    override suspend fun exportBackup(out: OutputStream, password: CharArray?): Boolean = true

    override suspend fun importBackup(
        input: InputStream,
        password: CharArray?,
    ): BackupImportReport? = BackupImportReport(0, 0, 0, 0)

    // ---- Internals -----------------------------------------------------------

    /** Mirrors `requireUnlocked()` on a vault that locked mid-call: nothing is mutable after. */
    private fun lockSessionLikeTheRealRepository() {
        _status.value = VaultStatus.LOCKED
        _items.value = emptyList()
        _folders.value = emptyList()
        _verifiedLockedIds.value = emptySet()
    }

    private fun mutate(id: String, transform: (Item) -> Item) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }
}
