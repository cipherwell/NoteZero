package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.BiometricEnroll
import com.cipherwell.notezero.data.BackupImportReport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.UnlockResult
import com.cipherwell.notezero.data.VaultInfo
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory [NoteRepository] for the code workspace tests. No crypto, no Room: the fake
 * mirrors the observable contract (StateFlow index, version bump on save, decrypted-only
 * in-memory items) so the tests assert real state transitions, not mocks of themselves.
 *
 * Test-only file: lives under src/test, never shipped in the app.
 */
class FakeCodeRepository : NoteRepository {

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

    private val _verifiedLockedIds = MutableStateFlow<Set<String>>(emptySet())
    override val verifiedLockedIds: StateFlow<Set<String>> = _verifiedLockedIds.asStateFlow()

    // ---- Test knobs ---------------------------------------------------------

    /** Monotonic fake clock for createdAt/updatedAt. */
    var clock: Long = 1_000L

    var saveCount: Int = 0
        private set

    /** When true the next [saveItem] throws once (save-error handling test). */
    var failNextSave: Boolean = false

    val savedItems: MutableList<Item> = mutableListOf()

    private var idCounter = 0

    // ---- Test helpers -------------------------------------------------------

    fun setItems(items: List<Item>) {
        _items.value = items
    }

    fun setFolders(folders: List<Folder>) {
        _folders.value = folders
    }

    /** Simulates a successful re-auth for locked content ids (notes/folders). */
    fun setVerifiedLockedIds(ids: Set<String>) {
        _verifiedLockedIds.value = ids
    }

    fun setStatus(status: VaultStatus) {
        _status.value = status
        if (status != VaultStatus.UNLOCKED) {
            _items.value = emptyList()
            _folders.value = emptyList()
        }
    }

    fun itemById(id: String): Item? = _items.value.firstOrNull { it.id == id }

    fun folderById(id: String): Folder? = _folders.value.firstOrNull { it.id == id }

    /** Seeds a code file item; returns it so tests can assert on the exact instance. */
    fun seedCodeFile(
        id: String = "file-1",
        title: String = "Main.kt",
        body: String = "",
        folderId: String? = null,
        trashed: Boolean = false,
        version: Long = 1L,
        locked: Boolean = false,
    ): Item {
        val item = Item(
            id = id,
            kind = ItemKind.CODE_FILE,
            title = title,
            body = body,
            folderId = folderId,
            trashed = trashed,
            createdAt = clock,
            updatedAt = clock,
            version = version,
            locked = locked,
        )
        _items.value = _items.value + item
        return item
    }

    /** Seeds a note (must never appear in the code tree). */
    fun seedNote(
        id: String = "note-1",
        title: String = "Shopping",
        folderId: String? = null,
    ): Item {
        val item = Item(
            id = id,
            kind = ItemKind.NOTE,
            title = title,
            body = "milk",
            folderId = folderId,
            createdAt = clock,
            updatedAt = clock,
            version = 1L,
        )
        _items.value = _items.value + item
        return item
    }

    fun seedFolder(
        id: String = "folder-1",
        name: String = "Projects",
        parentId: String? = null,
        sortOrder: Int = 0,
        locked: Boolean = false,
    ): Folder {
        val folder = Folder(
            id = id,
            name = name,
            parentId = parentId,
            sortOrder = sortOrder,
            createdAt = clock,
            locked = locked,
        )
        _folders.value = _folders.value + folder
        return folder
    }

    /** Simulates another surface writing the item (external content change → conflict). */
    fun replaceStored(item: Item) {
        _items.value = _items.value.map { if (it.id == item.id) item else it }
    }

    fun nextClock(): Long {
        clock += 1
        return clock
    }

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
            version = 1L,
        )
        _items.value = _items.value + item
        return item
    }

    override suspend fun saveItem(item: Item): Item {
        if (failNextSave) {
            failNextSave = false
            throw IllegalStateException("fake save failure")
        }
        saveCount += 1
        val existing = itemById(item.id)
        val saved = item.copy(
            updatedAt = nextClock(),
            version = maxOf(item.version, existing?.version ?: 0L) + 1,
        )
        _items.value = _items.value.map { if (it.id == saved.id) saved else it }
        savedItems += saved
        return saved
    }

    override suspend fun trashItem(id: String) {
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
        mutate(id) { it.copy(pinned = pinned, updatedAt = nextClock(), version = it.version + 1) }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        mutate(id) { it.copy(favorite = favorite, updatedAt = nextClock(), version = it.version + 1) }
    }

    override suspend fun moveItem(id: String, folderId: String?) {
        mutate(id) { it.copy(folderId = folderId, updatedAt = nextClock(), version = it.version + 1) }
    }

    private fun mutate(id: String, transform: (Item) -> Item) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
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

    // ---- Encrypted backup ----------------------------------------------------

    override suspend fun exportBackup(out: OutputStream, password: CharArray?): Boolean = true

    override suspend fun importBackup(
        input: InputStream,
        password: CharArray?,
    ): BackupImportReport? = BackupImportReport(0, 0, 0, 0)
}
