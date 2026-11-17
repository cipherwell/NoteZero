package com.cipherwell.notezero.ui

import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.BiometricEnroll
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.BackupImportReport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.UnlockResult
import com.cipherwell.notezero.data.VaultInfo
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher

/**
 * In-memory [NoteRepository] for UI/JVM tests. NO real crypto: the "password" is kept
 * as an obvious dummy test string, never on disk, never logged. Flows mirror the real
 * contract (empty item index while locked, repopulated after unlock).
 */
class FakeNoteRepository(
    initialStatus: VaultStatus = VaultStatus.SETUP_REQUIRED,
    initialPassword: String? = null,
) : NoteRepository {

    companion object {
        /** 12 distinct all-lowercase words, BIP-39-shaped but NOT used for crypto. */
        val DEFAULT_PHRASE: List<String> = listOf(
            "abandon", "ability", "able", "about", "above", "absent",
            "absorb", "abstract", "absurd", "abuse", "access", "accident",
        )
        val ROTATED_PHRASE: List<String> = listOf(
            "zebra", "zero", "zone", "zoo", "yacht", "yard",
            "year", "yellow", "you", "young", "youth", "zest",
        )

        /** Test-only dummy credential (fails the policy only where a test wants that). */
        const val STRONG_PASSWORD = "Correct-Horse-9!"
        const val OTHER_STRONG_PASSWORD = "Another-Passw0rd!"
        const val FIXED_CREATED_AT = 1_700_000_000_000L
        const val KDF_ITERATIONS = 600_000
    }

    private val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<VaultStatus> = _status

    private var allItems: List<Item> = emptyList()
    private var allFolders: List<Folder> = emptyList()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    override val items: StateFlow<List<Item>> = _items
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    override val folders: StateFlow<List<Folder>> = _folders

    private val _vaultInfo = MutableStateFlow<VaultInfo?>(null)
    override val vaultInfo: StateFlow<VaultInfo?> = _vaultInfo

    private val _autoLockMinutes = MutableStateFlow(VaultViewModel.DEFAULT_AUTO_LOCK_MINUTES)
    override val autoLockMinutes: StateFlow<Int> = _autoLockMinutes

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: StateFlow<ThemeMode> = _themeMode

    // ---- Test knobs ---------------------------------------------------------

    var password: String? = initialPassword
        private set
    var recoveryPhrase: List<String> = DEFAULT_PHRASE
        private set
    var biometricEnrolled: Boolean = false
        private set

    /** When set, [unlock] returns it once instead of checking the password. */
    var nextUnlockResult: UnlockResult? = null
    var nextRecoveryResult: UnlockResult? = null
    var failSetup: Boolean = false
    var failChangePassword: Boolean = false

    /** Recorded non-secret operation log for assertions (no payloads, no secrets). */
    val calls: MutableList<String> = mutableListOf()
    var interactionCount: Int = 0
        private set
    var backgroundCount: Int = 0
        private set
    var foregroundCount: Int = 0
        private set

    private var idCounter = 1
    private var clock = FIXED_CREATED_AT

    init {
        if (initialPassword != null) {
            _vaultInfo.value = buildVaultInfo()
        }
        refreshIndex()
    }

    // ---- Vault lifecycle ----------------------------------------------------

    override suspend fun setupVault(password: CharArray): List<String> {
        calls += "setupVault"
        if (failSetup) throw IllegalStateException("fake setup failure")
        this.password = String(password)
        recoveryPhrase = DEFAULT_PHRASE
        _vaultInfo.value = buildVaultInfo()
        _status.value = VaultStatus.UNLOCKED
        refreshIndex()
        return recoveryPhrase
    }

    override suspend fun unlock(password: CharArray): UnlockResult {
        calls += "unlock"
        nextUnlockResult?.let { result ->
            nextUnlockResult = null
            if (result == UnlockResult.SUCCESS) setUnlocked()
            return result
        }
        val stored = this.password
        return if (stored != null && String(password) == stored) {
            setUnlocked()
            UnlockResult.SUCCESS
        } else {
            UnlockResult.WRONG_PASSWORD
        }
    }

    override fun prepareBiometricEnroll(): BiometricEnroll? =
        if (_status.value == VaultStatus.UNLOCKED) BiometricEnroll(dummyCipher()) else null

    override suspend fun completeBiometricEnroll(enroll: BiometricEnroll): Boolean {
        calls += "completeBiometricEnroll"
        biometricEnrolled = true
        _vaultInfo.value = buildVaultInfo(biometricEnabled = true)
        return true
    }

    override suspend fun disableBiometricUnlock() {
        calls += "disableBiometricUnlock"
        biometricEnrolled = false
        _vaultInfo.value = buildVaultInfo(biometricEnabled = false)
    }

    override fun isBiometricUnlockEnrolled(): Boolean = biometricEnrolled

    override fun prepareBiometricDecrypt(): BiometricDecrypt? =
        if (biometricEnrolled) BiometricDecrypt(dummyCipher()) else null

    override suspend fun completeBiometricUnlock(decrypt: BiometricDecrypt): UnlockResult {
        calls += "completeBiometricUnlock"
        if (!biometricEnrolled) return UnlockResult.BIOMETRIC_FAILED
        setUnlocked()
        return UnlockResult.SUCCESS
    }

    override suspend fun lock() {
        calls += "lock"
        _status.value = VaultStatus.LOCKED
        refreshIndex()
    }

    override suspend fun changePassword(current: CharArray, next: CharArray): Boolean {
        calls += "changePassword"
        if (failChangePassword) return false
        if (password == null || String(current) != password) return false
        password = String(next)
        return true
    }

    override suspend fun resetWithRecoveryPhrase(phrase: List<String>, newPassword: CharArray): UnlockResult {
        calls += "resetWithRecoveryPhrase"
        nextRecoveryResult?.let { result ->
            nextRecoveryResult = null
            return result
        }
        if (phrase != recoveryPhrase) return UnlockResult.WRONG_RECOVERY_PHRASE
        password = String(newPassword)
        // Mirrors the real repository (decision #12): a reset re-wraps the SAME phrase under
        // a fresh salt; it does not rotate it. Only rotateRecoveryPhrase replaces the phrase.
        setUnlocked()
        return UnlockResult.SUCCESS
    }

    override suspend fun rotateRecoveryPhrase(currentPassword: CharArray): List<String>? {
        calls += "rotateRecoveryPhrase"
        if (password == null || String(currentPassword) != password) return null
        recoveryPhrase = ROTATED_PHRASE
        return recoveryPhrase
    }

    override suspend fun validateRecoveryPhrase(phrase: List<String>): Boolean {
        calls += "validateRecoveryPhrase"
        return phrase == recoveryPhrase
    }

    // ---- Session ------------------------------------------------------------

    override fun registerInteraction() {
        interactionCount += 1
    }

    override fun onAppBackgrounded() {
        backgroundCount += 1
        if (_status.value == VaultStatus.UNLOCKED) {
            _status.value = VaultStatus.LOCKED
            refreshIndex()
        }
    }

    override fun onAppForegrounded() {
        foregroundCount += 1
    }

    override suspend fun setAutoLockMinutes(minutes: Int) {
        calls += "setAutoLockMinutes:$minutes"
        _autoLockMinutes.value = minutes
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        calls += "setThemeMode:$mode"
        _themeMode.value = mode
    }

    // ---- Items --------------------------------------------------------------

    override suspend fun createItem(kind: ItemKind, folderId: String?): Item {
        val item = Item(
            id = "item-${idCounter++}",
            kind = kind,
            title = "",
            body = "",
            folderId = folderId,
            createdAt = clock,
            updatedAt = clock,
            version = 1,
        )
        allItems = allItems + item
        refreshIndex()
        return item
    }

    override suspend fun saveItem(item: Item): Item {
        val existing = allItems.firstOrNull { it.id == item.id }
        val saved = item.copy(
            updatedAt = ++clock,
            version = item.version + 1,
            // Repository-owned like the real implementation: a stale editor copy cannot roll
            // the encrypted reading position/bookmarks back.
            readingState = existing?.readingState ?: item.readingState,
            pdfAnnotations = existing?.pdfAnnotations ?: item.pdfAnnotations,
        )
        allItems = allItems.map { if (it.id == saved.id) saved else it }
        refreshIndex()
        return saved
    }

    override suspend fun savePdfAnnotations(itemId: String, annotations: PdfAnnotations?): Boolean {
        if (_status.value != VaultStatus.UNLOCKED) return false
        if (allItems.none { it.id == itemId }) return false
        updateItem(itemId) { it.copy(pdfAnnotations = annotations?.takeUnless { layer -> layer.isEmpty }) }
        return true
    }

    override suspend fun saveReadingState(itemId: String, state: ReadingState): Boolean {
        if (_status.value != VaultStatus.UNLOCKED) return false
        if (allItems.none { it.id == itemId }) return false
        updateItem(itemId) { it.copy(readingState = state) }
        return true
    }

    override suspend fun trashItem(id: String) {
        updateItem(id) { it.copy(trashed = true) }
    }

    override suspend fun restoreItem(id: String) {
        updateItem(id) { it.copy(trashed = false) }
    }

    override suspend fun deleteItemForever(id: String) {
        allItems = allItems.filterNot { it.id == id }
        refreshIndex()
    }

    override suspend fun emptyTrash() {
        allItems = allItems.filterNot { it.trashed }
        refreshIndex()
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        updateItem(id) { it.copy(pinned = pinned) }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        updateItem(id) { it.copy(favorite = favorite) }
    }

    override suspend fun moveItem(id: String, folderId: String?) {
        updateItem(id) { it.copy(folderId = folderId) }
    }

    /** In-memory plaintext for the test-only attachment import/read path (never on disk). */
    private val attachmentBytesById = mutableMapOf<String, ByteArray>()

    override suspend fun importAttachment(
        noteId: String,
        displayName: String,
        mimeType: String,
        source: InputStream,
    ): AttachmentImport {
        val bytes = source.readBytes()
        val ref = AttachmentRef(
            id = "att-${idCounter++}",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = clock,
            sha256 = "0".repeat(64),
        )
        attachmentBytesById[ref.id] = bytes
        updateItem(noteId) { it.copy(attachments = it.attachments + ref) }
        return AttachmentImport.Success(ref)
    }

    override suspend fun attachmentBytes(noteId: String, attachmentId: String): ByteArray? {
        val item = items.value.firstOrNull { it.id == noteId } ?: return null
        if (item.attachments.none { it.id == attachmentId }) return null
        return attachmentBytesById[attachmentId]?.copyOf()
    }

    private fun updateItem(id: String, transform: (Item) -> Item) {
        allItems = allItems.map { if (it.id == id) transform(it) else it }
        refreshIndex()
    }

    // ---- Folders ------------------------------------------------------------

    override suspend fun createFolder(name: String, parentId: String?): Folder {
        val folder = Folder(
            id = "folder-${idCounter++}",
            name = name,
            parentId = parentId,
            sortOrder = allFolders.size,
            createdAt = clock,
        )
        allFolders = allFolders + folder
        refreshIndex()
        return folder
    }

    override suspend fun renameFolder(id: String, name: String) {
        allFolders = allFolders.map { if (it.id == id) it.copy(name = name) else it }
        refreshIndex()
    }

    override suspend fun deleteFolder(id: String) {
        val parent = allFolders.firstOrNull { it.id == id }?.parentId
        allFolders = allFolders.filterNot { it.id == id }
            .map { if (it.parentId == id) it.copy(parentId = parent) else it }
        allItems = allItems.map { if (it.folderId == id) it.copy(folderId = parent) else it }
        refreshIndex()
    }

    // ---- Backup -------------------------------------------------------------

    override suspend fun exportBackup(out: OutputStream, password: CharArray?): Boolean {
        calls += "exportBackup"
        out.write("fake-encrypted-backup".toByteArray())
        return true
    }

    override suspend fun importBackup(input: InputStream, password: CharArray?): BackupImportReport? {
        calls += "importBackup"
        input.readBytes()
        return BackupImportReport(imported = 0, duplicated = 0, skipped = 0, foldersImported = 0)
    }

    // ---- Test helpers -------------------------------------------------------

    /** Test-only: jump to a given session state (e.g. LOCKED with an existing vault). */
    fun forceStatus(status: VaultStatus) {
        _status.value = status
        refreshIndex()
    }

    /** Test-only: mark biometrics enrolled without driving a prompt. */
    fun forceBiometricEnrolled(enrolled: Boolean = true) {
        biometricEnrolled = enrolled
        _vaultInfo.value = buildVaultInfo(biometricEnabled = enrolled)
    }

    fun seedItem(item: Item) {
        allItems = allItems + item
        refreshIndex()
    }

    private fun setUnlocked() {
        _status.value = VaultStatus.UNLOCKED
        if (_vaultInfo.value == null) _vaultInfo.value = buildVaultInfo()
        refreshIndex()
    }

    private fun refreshIndex() {
        val unlocked = _status.value == VaultStatus.UNLOCKED
        _items.value = if (unlocked) allItems else emptyList()
        _folders.value = if (unlocked) allFolders else emptyList()
        _vaultInfo.value = _vaultInfo.value?.copy(
            itemCount = allItems.size,
            folderCount = allFolders.size,
        ) ?: if (password != null) buildVaultInfo() else null
    }

    private fun buildVaultInfo(biometricEnabled: Boolean = biometricEnrolled): VaultInfo = VaultInfo(
        createdAt = FIXED_CREATED_AT,
        itemCount = allItems.size,
        folderCount = allFolders.size,
        kdfIterations = KDF_ITERATIONS,
        biometricUnlockEnabled = biometricEnabled,
        schemaVersion = 1,
    )

    private fun dummyCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
}
