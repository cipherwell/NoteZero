package com.cipherwell.notezero.ui.backup

import com.cipherwell.notezero.data.BackupImportReport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.FakeNoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Repository double for the backup-flow controller tests: full real interface (delegated to
 * the shared UI fake) with the backup and session seams overridden so tests can drive
 * success/failure, lock transitions and credential outcomes deterministically.
 *
 * Captured credentials are converted to strings *before* the controller wipes the CharArray
 * — the tests then assert both the attempt text and the zeroization.
 */
class FakeBackupRepository(
    withContent: Boolean = true,
) : NoteRepository by FakeNoteRepository(
    initialStatus = VaultStatus.UNLOCKED,
    initialPassword = FakeNoteRepository.STRONG_PASSWORD,
) {

    private val mutableStatus = MutableStateFlow(VaultStatus.UNLOCKED)
    override val status: StateFlow<VaultStatus> = mutableStatus

    private val mutableItems = MutableStateFlow(if (withContent) listOf(SAMPLE_ITEM) else emptyList())
    override val items: StateFlow<List<Item>> = mutableItems

    private val mutableFolders = MutableStateFlow(emptyList<Folder>())
    override val folders: StateFlow<List<Folder>> = mutableFolders

    val beginExternalCalls = AtomicInteger(0)
    val endExternalCalls = AtomicInteger(0)
    val exportCalls = AtomicInteger(0)
    val importCalls = AtomicInteger(0)

    @Volatile
    var exportResult: Boolean = true

    /** Return value for a non-null credential attempt; null means "rejected". */
    @Volatile
    var importReport: BackupImportReport? = BackupImportReport(1, 0, 0, 0)

    /** Overrides the null-credential attempt result (the same-vault fast path). */
    @Volatile
    var importWithoutCredentialReport: BackupImportReport? = null

    @Volatile
    var lastExportPasswordText: String? = null

    @Volatile
    var lastImportCredentialText: String? = null

    @Volatile
    var throwOnExport: Boolean = false

    fun lockVault() {
        mutableStatus.value = VaultStatus.LOCKED
    }

    fun unlockVault() {
        mutableStatus.value = VaultStatus.UNLOCKED
    }

    override fun beginExternalActivity() {
        beginExternalCalls.incrementAndGet()
    }

    override fun endExternalActivity() {
        endExternalCalls.incrementAndGet()
    }

    override suspend fun exportBackup(out: OutputStream, password: CharArray?): Boolean {
        exportCalls.incrementAndGet()
        if (throwOnExport) throw java.io.IOException("simulated write failure")
        lastExportPasswordText = password?.concatToString()
        out.write(BACKUP_BYTES)
        return exportResult
    }

    override suspend fun importBackup(input: InputStream, password: CharArray?): BackupImportReport? {
        importCalls.incrementAndGet()
        lastImportCredentialText = password?.concatToString()
        input.readBytes()
        return if (password == null) importWithoutCredentialReport else importReport
    }

    companion object {
        /** Non-empty "envelope" written by the fake export; never decrypted in these tests. */
        val BACKUP_BYTES: ByteArray = "nzbk-test-envelope-bytes".toByteArray(Charsets.UTF_8)

        val SAMPLE_ITEM: Item = Item(
            id = "item-1",
            kind = ItemKind.NOTE,
            title = "Test note",
            body = "Body",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_001_000L,
            version = 1L,
        )
    }
}
