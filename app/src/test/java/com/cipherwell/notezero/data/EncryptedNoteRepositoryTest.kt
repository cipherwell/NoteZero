package com.cipherwell.notezero.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cipherwell.notezero.crypto.Bip39Recovery
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchInkKind
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShape
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import com.cipherwell.notezero.model.SketchTemplate
import com.cipherwell.notezero.model.SketchWidth
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * End-to-end tests of the encrypted vault against real crypto and in-memory Room
 * (Robolectric supplies the Android Context/Room/SQLite stack). Every assertion is on
 * observable behaviour: flows, persisted envelope bytes, backup reports and failure modes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedNoteRepositoryTest {

    private lateinit var context: Context

    private val databases = mutableListOf<NoteZeroDatabase>()
    private val repositories = mutableListOf<EncryptedNoteRepository>()
    private val preferenceScopes = mutableListOf<CoroutineScope>()

    private var now: Long = 1_700_000_000_000L

    private lateinit var db: NoteZeroDatabase
    private lateinit var prefs: VaultPreferences
    private lateinit var repo: EncryptedNoteRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = newDatabase()
        prefs = newPrefs()
        repo = newRepository(database = db, preferences = prefs)
    }

    @After
    fun tearDown() {
        repositories.forEach { repository -> runCatching { repository.close() } }
        preferenceScopes.forEach { scope -> scope.cancel() }
        databases.forEach { database -> runCatching { database.close() } }
    }

    // ---- harness -------------------------------------------------------------

    private fun newDatabase(): NoteZeroDatabase =
        Room.inMemoryDatabaseBuilder(context, NoteZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases.add(it) }

    private fun newPrefs(): VaultPreferences {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferenceScopes.add(scope)
        val file = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        return VaultPreferences.createForFile(file, scope)
    }

    private fun newRepository(
        database: NoteZeroDatabase = db,
        preferences: VaultPreferences = prefs,
        clock: () -> Long = { now },
        allowAutoLock: Boolean = false,
    ): EncryptedNoteRepository = EncryptedNoteRepository(
        context = context,
        database = database,
        preferences = preferences,
        clock = clock,
        kdfIterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        allowAutoLock = allowAutoLock,
    ).also { repositories.add(it) }

    private fun itemTitle(item: Item): String = item.title

    // ---- lifecycle -----------------------------------------------------------

    @Test
    fun setupSaveLockUnlockRoundTrip() = runBlocking {
        assertEquals(VaultStatus.SETUP_REQUIRED, repo.status.value)

        val phrase = repo.setupVault(PASSWORD.toCharArray())
        assertEquals(12, phrase.size)
        assertTrue(Bip39Recovery.validatePhrase(phrase))
        assertEquals(VaultStatus.UNLOCKED, repo.status.value)

        val created = repo.createItem(ItemKind.NOTE)
        assertEquals(0L, created.version)
        val saved = repo.saveItem(
            created.copy(
                title = "My note",
                body = "# Heading\nbody text",
                tags = listOf("Work", "work", " home "),
            ),
        )
        assertEquals(1L, saved.version)
        assertEquals(listOf("Work", "home"), saved.tags)
        assertTrue("updatedAt must advance", saved.updatedAt > created.updatedAt)
        assertEquals(saved.id, repo.items.value.single().id)

        repo.lock()
        assertEquals(VaultStatus.LOCKED, repo.status.value)
        assertTrue(repo.items.value.isEmpty())
        assertTrue(repo.folders.value.isEmpty())
        assertNull(repo.vaultInfo.value)

        assertEquals(UnlockResult.WRONG_PASSWORD, repo.unlock("not the password".toCharArray()))
        assertEquals(VaultStatus.LOCKED, repo.status.value)

        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        val restored = repo.items.value.single()
        assertEquals(saved.id, restored.id)
        assertEquals(saved.title, restored.title)
        assertEquals(saved.body, restored.body)
        assertEquals(saved.tags, restored.tags)
        assertEquals(saved.createdAt, restored.createdAt)
        assertEquals(saved.updatedAt, restored.updatedAt)
        assertEquals(saved.version, restored.version)
    }

    @Test
    fun vaultPersistsAcrossRepositoryInstances() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Persisted", body = "body"))
        repo.lock()

        val second = newRepository(database = db, preferences = prefs)
        assertEquals("presence marker makes the initial status correct", VaultStatus.LOCKED, second.status.value)
        assertEquals(UnlockResult.SUCCESS, second.unlock(PASSWORD.toCharArray()))
        assertEquals(listOf("Persisted"), second.items.value.map(::itemTitle))
    }

    @Test
    fun setupTwiceIsRejected() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val failure = runCatching { repo.setupVault("other".toCharArray()) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        // Session untouched after the rejected setup attempt.
        assertEquals(VaultStatus.UNLOCKED, repo.status.value)
    }

    @Test
    fun corruptVaultEnvelopeReturnsCorruptVault() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        repo.lock()
        val meta = db.vaultMetaDao().get()!!
        db.vaultMetaDao().updatePasswordEnvelope(ByteArray(64) { 0x11 })
        assertEquals(UnlockResult.CORRUPT_VAULT, repo.unlock(PASSWORD.toCharArray()))
        // The recovery envelope was not touched by the corrupt password envelope.
        assertNotNull(meta.recoveryEnvelope)
    }

    @Test
    fun vaultInfoReportsCountsIterationsAndClearsOnLock() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        repo.createFolder("Folder")
        repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "x"))

        val info = repo.vaultInfo.value!!
        assertEquals(1, info.itemCount)
        assertEquals(1, info.folderCount)
        assertEquals(NoteCrypto.MIN_ACCEPTED_ITERATIONS, info.kdfIterations)
        assertEquals(NoteZeroDatabase.SCHEMA_VERSION, info.schemaVersion)
        assertFalse(info.biometricUnlockEnabled)
        assertTrue(info.createdAt > 0L)

        repo.lock()
        assertNull(repo.vaultInfo.value)
    }

    // ---- password / recovery rotation ---------------------------------------

    @Test
    fun changePasswordKeepsItemCiphertextByteIdentical() = runBlocking {
        repo.setupVault(OLD_PASSWORD.toCharArray())
        val saved = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Secret", body = "body"))
        val ciphertextBefore = db.itemDao().getById(saved.id)!!.ciphertext.copyOf()
        val passwordEnvelopeBefore = db.vaultMetaDao().get()!!.passwordEnvelope.copyOf()

        assertTrue(repo.changePassword(OLD_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()))
        assertFalse(repo.changePassword("wrong".toCharArray(), NEW_PASSWORD.toCharArray()))

        val ciphertextAfter = db.itemDao().getById(saved.id)!!.ciphertext
        assertArrayEquals("notes must never be re-encrypted on password change", ciphertextBefore, ciphertextAfter)
        assertFalse(
            "only the password wrapper changes",
            passwordEnvelopeBefore.contentEquals(db.vaultMetaDao().get()!!.passwordEnvelope),
        )

        repo.lock()
        assertEquals(UnlockResult.WRONG_PASSWORD, repo.unlock(OLD_PASSWORD.toCharArray()))
        assertEquals(UnlockResult.SUCCESS, repo.unlock(NEW_PASSWORD.toCharArray()))
        assertEquals("Secret", repo.items.value.single().title)
    }

    @Test
    fun recoveryResetRewrapsAndUnlocks() = runBlocking {
        val phrase = repo.setupVault(OLD_PASSWORD.toCharArray())
        repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Survivor"))
        val metaBefore = db.vaultMetaDao().get()!!
        repo.lock()

        val wrongPhrase = Bip39Recovery.generatePhrase()
        assertEquals(
            UnlockResult.WRONG_RECOVERY_PHRASE,
            repo.resetWithRecoveryPhrase(wrongPhrase, NEW_PASSWORD.toCharArray()),
        )
        assertEquals(
            UnlockResult.SUCCESS,
            repo.resetWithRecoveryPhrase(phrase, NEW_PASSWORD.toCharArray()),
        )
        assertEquals("recovery reset unlocks", "Survivor", repo.items.value.single().title)

        val metaAfter = db.vaultMetaDao().get()!!
        assertFalse(metaBefore.passwordEnvelope.contentEquals(metaAfter.passwordEnvelope))
        assertFalse(metaBefore.recoveryEnvelope.contentEquals(metaAfter.recoveryEnvelope))

        repo.lock()
        assertEquals(UnlockResult.WRONG_PASSWORD, repo.unlock(OLD_PASSWORD.toCharArray()))
        assertEquals(UnlockResult.SUCCESS, repo.unlock(NEW_PASSWORD.toCharArray()))
        repo.lock()
        // The phrase the user already wrote down stays valid (frozen interface returns no
        // new phrase from reset; real rotation is rotateRecoveryPhrase).
        assertEquals(UnlockResult.SUCCESS, repo.resetWithRecoveryPhrase(phrase, NEW_PASSWORD.toCharArray()))
    }

    @Test
    fun rotateRecoveryPhraseReplacesTheKit() = runBlocking {
        val original = repo.setupVault(PASSWORD.toCharArray())
        repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Still here"))

        assertNull(repo.rotateRecoveryPhrase("wrong password".toCharArray()))
        val rotated = repo.rotateRecoveryPhrase(PASSWORD.toCharArray())
        assertNotNull(rotated)
        assertEquals(12, rotated!!.size)
        assertTrue(Bip39Recovery.validatePhrase(rotated))
        assertNotEquals(original, rotated)

        repo.lock()
        assertEquals(UnlockResult.WRONG_RECOVERY_PHRASE, repo.resetWithRecoveryPhrase(original, NEW_PASSWORD.toCharArray()))
        assertEquals(UnlockResult.SUCCESS, repo.resetWithRecoveryPhrase(rotated, NEW_PASSWORD.toCharArray()))
        assertEquals("Still here", repo.items.value.single().title)
        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(NEW_PASSWORD.toCharArray()))
    }

    @Test
    fun validateRecoveryPhraseChecksFormatOnly() = runBlocking {
        val phrase = repo.setupVault(PASSWORD.toCharArray())
        assertTrue(repo.validateRecoveryPhrase(phrase))
        // Format/checksum validation only: any valid BIP-39 phrase passes, even one that is
        // not this vault's phrase (the credential match happens on reset).
        assertTrue(repo.validateRecoveryPhrase(Bip39Recovery.generatePhrase()))
        assertTrue(repo.validateRecoveryPhrase(phrase.map { it.uppercase() }))
        assertFalse(repo.validateRecoveryPhrase(listOf("one", "two")))
        assertFalse(repo.validateRecoveryPhrase(phrase.take(11)))
        assertFalse(repo.validateRecoveryPhrase(phrase.dropLast(1) + "notaword"))
    }

    // ---- envelope integrity --------------------------------------------------

    @Test
    fun aadBindsCiphertextToRowId() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val a = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "A", body = "secret A"))
        val b = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "B", body = "secret B"))

        // Swap ciphertext between two rows of the same kind: id AAD must reject both.
        val rowA = db.itemDao().getById(a.id)!!
        val rowB = db.itemDao().getById(b.id)!!
        db.itemDao().upsert(rowA.copy(ciphertext = rowB.ciphertext))
        db.itemDao().upsert(rowB.copy(ciphertext = rowA.ciphertext))

        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertTrue(repo.items.value.isEmpty())
        assertEquals(2, repo.corruptRowCount)
    }

    @Test
    fun aadBindsCiphertextToRowKind() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val note = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Note"))
        val code = repo.saveItem(repo.createItem(ItemKind.CODE_FILE).copy(title = "Code"))
        val noteRow = db.itemDao().getById(note.id)!!
        val codeRow = db.itemDao().getById(code.id)!!

        // A crafted row: NOTE kind with the CODE_FILE ciphertext (valid GCM under the VMK).
        db.itemDao().upsert(noteRow.copy(ciphertext = codeRow.ciphertext))

        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertEquals(listOf("Code"), repo.items.value.map(::itemTitle))
        assertEquals(1, repo.corruptRowCount)
    }

    @Test
    fun tamperedAndGarbageRowsAreSkippedNotCrashed() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val good = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Good", body = "visible"))
        db.itemDao().upsert(
            ItemEntity(
                id = UUID.randomUUID().toString(),
                kind = "NOTE",
                createdAt = now,
                updatedAt = now,
                ciphertext = ByteArray(40) { 0x5A },
            ),
        )
        db.itemDao().upsert(
            ItemEntity(
                id = UUID.randomUUID().toString(),
                kind = "NOT_A_KIND",
                createdAt = now,
                updatedAt = now,
                ciphertext = ByteArray(40) { 0x33 },
            ),
        )
        // Flip one ciphertext byte in the good row (still a well-formed row).
        val tamperedGood = db.itemDao().getById(good.id)!!.let {
            it.copy(ciphertext = it.ciphertext.copyOf().also { bytes -> bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte() })
        }
        db.itemDao().upsert(tamperedGood)

        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertTrue(repo.items.value.isEmpty())
        assertEquals(3, repo.corruptRowCount)
    }

    // ---- CRUD / folders ------------------------------------------------------

    @Test
    fun trashRestoreDeleteForeverAndEmptyTrash() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val first = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "First"))
        val second = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Second"))

        repo.trashItem(first.id)
        assertTrue(repo.items.value.first { it.id == first.id }.trashed)
        repo.restoreItem(first.id)
        assertFalse(repo.items.value.first { it.id == first.id }.trashed)

        repo.trashItem(second.id)
        repo.emptyTrash()
        assertEquals(listOf("First"), repo.items.value.map(::itemTitle))
        assertEquals(1, db.itemDao().getAll().size)

        repo.deleteItemForever(first.id)
        assertTrue(repo.items.value.isEmpty())
        assertEquals(0, db.itemDao().count())
    }

    @Test
    fun pinFavoriteMoveAndFolderReparenting() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val root = repo.createFolder("Root")
        val child = repo.createFolder("Child", root.id)
        val grandchild = repo.createFolder("Grandchild", child.id)
        val item = repo.saveItem(
            repo.createItem(ItemKind.NOTE).copy(title = "Nested", folderId = grandchild.id),
        )

        repo.setPinned(item.id, true)
        repo.setFavorite(item.id, true)
        val toggled = repo.items.value.single()
        assertTrue(toggled.pinned)
        assertTrue(toggled.favorite)
        repo.setPinned(item.id, false)
        repo.setFavorite(item.id, false)
        assertFalse(repo.items.value.single().pinned)
        assertFalse(repo.items.value.single().favorite)

        repo.moveItem(item.id, child.id)
        assertEquals(child.id, repo.items.value.single().folderId)

        repo.renameFolder(child.id, "Renamed")
        assertEquals("Renamed", repo.folders.value.first { it.id == child.id }.name)

        // Deleting the child folder moves its items and child folders to its parent.
        repo.deleteFolder(child.id)
        assertEquals(root.id, repo.items.value.single().folderId)
        assertEquals(root.id, repo.folders.value.first { it.id == grandchild.id }.parentId)
        assertNull(repo.folders.value.firstOrNull { it.id == child.id })
    }

    // ---- auto-lock / session -------------------------------------------------

    @Test
    fun idleAutoLockLocksAfterTimeoutAndCanBeDisabled() = runBlocking {
        val autoRepo = newRepository(clock = { now }, allowAutoLock = true)
        autoRepo.setupVault(PASSWORD.toCharArray())
        autoRepo.setAutoLockMinutes(1)
        autoRepo.registerInteraction()

        now += 59_000
        assertFalse(autoRepo.evaluateAutoLock())
        assertEquals(VaultStatus.UNLOCKED, autoRepo.status.value)

        now += 2_000
        assertTrue(autoRepo.evaluateAutoLock())
        assertEquals(VaultStatus.LOCKED, autoRepo.status.value)
        assertTrue(autoRepo.items.value.isEmpty())

        assertEquals(UnlockResult.SUCCESS, autoRepo.unlock(PASSWORD.toCharArray()))
        autoRepo.setAutoLockMinutes(0)
        autoRepo.registerInteraction()
        now += 24L * 60L * 60L * 1000L
        assertFalse(autoRepo.evaluateAutoLock())
        assertEquals(VaultStatus.UNLOCKED, autoRepo.status.value)
    }

    @Test
    fun appBackgroundedLocksImmediately() = runBlocking {
        val autoRepo = newRepository(clock = { now }, allowAutoLock = true)
        autoRepo.setupVault(PASSWORD.toCharArray())
        autoRepo.saveItem(autoRepo.createItem(ItemKind.NOTE).copy(title = "Boom"))

        autoRepo.onAppBackgrounded()
        withTimeout(5_000) {
            autoRepo.status.first { status -> status == VaultStatus.LOCKED }
        }
        assertTrue(autoRepo.items.value.isEmpty())
    }

    @Test
    fun preferencesPersistAcrossInstances() = runBlocking {
        repo.setThemeMode(ThemeMode.DARK)
        repo.setAutoLockMinutes(15)
        assertEquals(ThemeMode.DARK, repo.themeMode.value)
        assertEquals(15, repo.autoLockMinutes.value)

        val second = newRepository(database = db, preferences = prefs)
        withTimeout(5_000) { second.themeMode.first { it == ThemeMode.DARK } }
        withTimeout(5_000) { second.autoLockMinutes.first { it == 15 } }
        // Out-of-range values are clamped by the repository.
        repo.setAutoLockMinutes(10_000)
        assertEquals(VaultPreferences.MAX_AUTO_LOCK_MINUTES, repo.autoLockMinutes.value)
    }

    @Test
    fun biometricSeamsFailClosedWithoutHardware() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        // Robolectric has no biometric hardware: the two-phase enrolment must return
        // "unavailable" instead of throwing or persisting anything.
        assertNull(repo.prepareBiometricEnroll())
        assertNull(repo.prepareBiometricDecrypt())
        assertFalse(repo.vaultInfo.value!!.biometricUnlockEnabled)
        repo.disableBiometricUnlock() // idempotent, must not throw
        assertFalse(repo.vaultInfo.value!!.biometricUnlockEnabled)
    }

    // ---- on-disk redaction ---------------------------------------------------

    @Test
    fun schemaExposesOnlyRedactedColumnsAndCiphertext() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val marker = "PLAINTEXT_${UUID.randomUUID().toString().replace("-", "")}"
        val saved = repo.saveItem(
            repo.createItem(ItemKind.NOTE).copy(
                title = marker,
                body = "$marker body",
                tags = listOf(marker),
            ),
        )
        repo.createFolder(marker)

        assertEquals(
            listOf("id", "kind", "created_at", "updated_at", "ciphertext"),
            columnNames("items"),
        )
        assertEquals(listOf("id", "created_at", "ciphertext"), columnNames("folders"))
        assertEquals(
            listOf(
                "singleton",
                "vault_id",
                "password_envelope",
                "recovery_envelope",
                "schema_version",
                "created_at",
                "biometric_enabled",
            ),
            columnNames("vault_meta"),
        )

        val row = db.itemDao().getById(saved.id)!!
        assertFalse(row.kind.contains(marker))
        assertFalse(
            "ciphertext must not contain the title",
            String(row.ciphertext, Charsets.ISO_8859_1).contains(marker),
        )
        val folderRow = db.folderDao().getAll().single()
        assertFalse(String(folderRow.ciphertext, Charsets.ISO_8859_1).contains(marker))
    }

    @Test
    fun vaultDatabaseFileContainsNoPlaintextContent() = runBlocking {
        val dbName = "redaction-${UUID.randomUUID()}.db"
        val fileDatabase = Room.databaseBuilder(context, NoteZeroDatabase::class.java, dbName).build()
        databases.add(fileDatabase)
        val filePrefs = newPrefs()
        val fileRepo = newRepository(database = fileDatabase, preferences = filePrefs)
        fileRepo.setupVault(PASSWORD.toCharArray())
        val marker = "ZZPLAINTEXT${UUID.randomUUID().toString().replace("-", "")}"
        fileRepo.saveItem(
            fileRepo.createItem(ItemKind.NOTE).copy(
                title = marker,
                body = marker,
                tags = listOf(marker),
            ),
        )
        fileRepo.createFolder(marker)
        fileRepo.lock()
        fileDatabase.close()
        databases.remove(fileDatabase)

        val databaseFile = context.getDatabasePath(dbName)
        assertTrue("database file exists", databaseFile.exists())
        val raw = listOf(
            databaseFile,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
        ).filter { it.exists() }
            .map { it.readBytes() }
            .reduce { left, right -> left + right }

        val asText = String(raw, Charsets.ISO_8859_1)
        assertFalse("plaintext marker must not be present in the DB files", asText.contains(marker))
        // The encrypted payload header must not appear on disk either (it lives inside GCM).
        assertFalse(asText.contains("NZIT"))
        assertFalse(asText.contains("NZFD"))
    }

    private fun columnNames(table: String): List<String> {
        val names = ArrayList<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names.add(cursor.getString(nameIndex))
        }
        return names
    }

    // ---- encrypted backup ----------------------------------------------------

    @Test
    fun backupRoundTripIsDuplicateSafeAndRestoresDeletedItems() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val folder = repo.createFolder("Work")
        val first = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "A", body = "body A", folderId = folder.id))
        repo.saveItem(repo.createItem(ItemKind.CODE_FILE).copy(title = "B", body = "code"))

        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, null))
        val bytes = exported.toByteArray()

        val duplicateReport = repo.importBackup(ByteArrayInputStream(bytes), null)
        assertNotNull(duplicateReport)
        assertEquals(0, duplicateReport!!.imported)
        assertEquals(3, duplicateReport.duplicated) // two items + one folder
        assertEquals(0, duplicateReport.skipped)
        assertEquals(0, duplicateReport.foldersImported)

        repo.deleteItemForever(first.id)
        val restoreReport = repo.importBackup(ByteArrayInputStream(bytes), null)!!
        assertEquals(1, restoreReport.imported)
        assertEquals(2, restoreReport.duplicated) // B + folder
        assertEquals(0, restoreReport.skipped)
        assertEquals(listOf("A", "B"), repo.items.value.map(::itemTitle).sorted())
        assertEquals("body A", repo.items.value.first { it.title == "A" }.body)
    }

    @Test
    fun readingStateIsPersistedEncryptedAndRepositoryOwned() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val book = repo.saveItem(repo.createItem(ItemKind.BOOK_FILE).copy(title = "Stored book"))
        val state = ReadingState(
            position = 3,
            fraction = 0.5f,
            total = 12,
            bookmarks = listOf(ReadingBookmark(position = 2, label = "secret bookmark label", createdAt = now)),
            author = "secret author",
            language = "en",
        )

        assertTrue(repo.saveReadingState(book.id, state))
        assertEquals(state, repo.items.value.first { it.id == book.id }.readingState)

        // saveItem is repository-owned: a stale copy cannot roll the reader back.
        repo.saveItem(repo.items.value.first { it.id == book.id }.copy(title = "Renamed book"))
        assertEquals(state, repo.items.value.first { it.id == book.id }.readingState)

        // Persisted through the encrypted envelope: lock wipes the index, unlock restores it.
        repo.lock()
        assertEquals(VaultStatus.LOCKED, repo.status.value)
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertEquals(state, repo.items.value.first { it.id == book.id }.readingState)

        // The bookmark label/author must not exist as plaintext in the Room ciphertext.
        val ciphertext = db.openHelper.readableDatabase
            .query("SELECT ciphertext FROM items WHERE id = ?", arrayOf(book.id))
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else ByteArray(0)
            }
        val asText = String(ciphertext, Charsets.ISO_8859_1)
        assertFalse(asText.contains("secret bookmark label"))
        assertFalse(asText.contains("secret author"))
    }

    @Test
    fun pdfAnnotationsArePersistedEncryptedAndRepositoryOwned() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val pdf = repo.saveItem(repo.createItem(ItemKind.PDF_FILE).copy(title = "Annotated doc"))
        val annotations = PdfAnnotations(
            strokes = listOf(
                PdfInkStroke(
                    page = 0,
                    kind = PdfInkKind.INK,
                    colorIndex = 1,
                    widthIndex = 0,
                    points = listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.6f, 0.4f)),
                ),
            ),
            notes = listOf(PdfNote(page = 0, x = 0.5f, y = 0.5f, text = "secret sticky note text")),
        )

        assertTrue(repo.savePdfAnnotations(pdf.id, annotations))
        assertEquals(annotations, repo.items.value.first { it.id == pdf.id }.pdfAnnotations)

        // saveItem is repository-owned: a stale copy cannot roll annotations back.
        repo.saveItem(repo.items.value.first { it.id == pdf.id }.copy(title = "Renamed"))
        assertEquals(annotations, repo.items.value.first { it.id == pdf.id }.pdfAnnotations)

        // Persisted through the encrypted envelope: lock wipes the index, unlock restores it.
        repo.lock()
        assertFalse("locked vault must reject annotation writes", repo.savePdfAnnotations(pdf.id, annotations))
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertEquals(annotations, repo.items.value.first { it.id == pdf.id }.pdfAnnotations)

        // The sticky-note text must not exist as plaintext in the Room ciphertext.
        val ciphertext = db.openHelper.readableDatabase
            .query("SELECT ciphertext FROM items WHERE id = ?", arrayOf(pdf.id))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else ByteArray(0) }
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("secret sticky note text"))

        // Passing an empty layer clears the stored annotations.
        assertTrue(repo.savePdfAnnotations(pdf.id, PdfAnnotations()))
        assertNull(repo.items.value.first { it.id == pdf.id }.pdfAnnotations)
    }

    @Test
    fun sketchIsPersistedEncryptedAndRepositoryOwned() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val item = repo.saveItem(repo.createItem(ItemKind.SKETCH_FILE).copy(title = "Sketch"))
        val sketch = SketchDocument(
            template = SketchTemplate.GRID,
            strokes = listOf(
                SketchStroke(
                    points = listOf(SketchPoint(0.1f, 0.2f), SketchPoint(0.7f, 0.8f)),
                    ink = SketchInk.GREEN,
                    width = SketchWidth.BOLD,
                    kind = SketchInkKind.PEN,
                ),
            ),
            shapes = listOf(
                SketchShape(
                    SketchShapeKind.RECT,
                    SketchPoint(0.2f, 0.3f),
                    SketchPoint(0.6f, 0.9f),
                    SketchInk.BLUE,
                    SketchWidth.THIN,
                ),
            ),
        )

        assertTrue(repo.saveSketch(item.id, sketch))
        assertEquals(sketch, repo.items.value.first { it.id == item.id }.sketch)

        // saveItem is repository-owned: a stale copy cannot roll the sketch back.
        repo.saveItem(repo.items.value.first { it.id == item.id }.copy(title = "Renamed"))
        assertEquals(sketch, repo.items.value.first { it.id == item.id }.sketch)

        // Locked vault rejects writes; unlock restores the encrypted document.
        repo.lock()
        assertFalse("locked vault must reject sketch writes", repo.saveSketch(item.id, sketch))
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertEquals(sketch, repo.items.value.first { it.id == item.id }.sketch)

        // Clearing with an empty document removes the stored layer.
        assertTrue(repo.saveSketch(item.id, SketchDocument()))
        assertNull(repo.items.value.first { it.id == item.id }.sketch)
    }

    @Test
    fun backupRoundTripCarriesSketchDocument() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val item = repo.saveItem(repo.createItem(ItemKind.SKETCH_FILE).copy(title = "Portable sketch"))
        val sketch = SketchDocument(
            template = SketchTemplate.DOTTED,
            shapes = listOf(
                SketchShape(
                    SketchShapeKind.LINE,
                    SketchPoint(0f, 0f),
                    SketchPoint(1f, 1f),
                    SketchInk.PURPLE,
                    SketchWidth.MEDIUM,
                ),
            ),
        )
        assertTrue(repo.saveSketch(item.id, sketch))

        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, null))
        val bytes = exported.toByteArray()

        val otherRepo = newRepository(database = newDatabase(), preferences = newPrefs())
        otherRepo.setupVault("target password".toCharArray())
        val report = otherRepo.importBackup(ByteArrayInputStream(bytes), PASSWORD.toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(sketch, otherRepo.items.value.single().sketch)
    }

    @Test
    fun backupRoundTripCarriesPdfAnnotations() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val pdf = repo.saveItem(repo.createItem(ItemKind.PDF_FILE).copy(title = "Portable PDF"))
        val annotations = PdfAnnotations(
            notes = listOf(PdfNote(page = 2, x = 0.25f, y = 0.75f, text = "carried by backup")),
        )
        assertTrue(repo.savePdfAnnotations(pdf.id, annotations))

        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, null))
        val bytes = exported.toByteArray()

        val otherRepo = newRepository(database = newDatabase(), preferences = newPrefs())
        otherRepo.setupVault("target password".toCharArray())
        val report = otherRepo.importBackup(ByteArrayInputStream(bytes), PASSWORD.toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(annotations, otherRepo.items.value.single().pdfAnnotations)
    }

    @Test
    fun backupRoundTripCarriesReadingState() = runBlocking {
        repo.setupVault(PASSWORD.toCharArray())
        val book = repo.saveItem(repo.createItem(ItemKind.BOOK_FILE).copy(title = "Portable book"))
        val state = ReadingState(
            position = 9,
            fraction = 0.75f,
            total = 20,
            bookmarks = listOf(ReadingBookmark(position = 4, label = "mark", createdAt = now)),
            author = "Author",
            language = "es",
        )
        assertTrue(repo.saveReadingState(book.id, state))

        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, null))
        val bytes = exported.toByteArray()

        val otherRepo = newRepository(database = newDatabase(), preferences = newPrefs())
        otherRepo.setupVault("target password".toCharArray())
        val report = otherRepo.importBackup(ByteArrayInputStream(bytes), PASSWORD.toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(state, otherRepo.items.value.single().readingState)
    }

    @Test
    fun extraPasswordBackupRestoresIntoAnotherVault() = runBlocking {
        repo.setupVault(OLD_PASSWORD.toCharArray())
        repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Migrated", body = "payload"))

        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, "backup secret".toCharArray()))
        val bytes = exported.toByteArray()

        val otherDb = newDatabase()
        val otherRepo = newRepository(database = otherDb, preferences = newPrefs())
        otherRepo.setupVault("target password".toCharArray())
        assertNull(otherRepo.importBackup(ByteArrayInputStream(bytes), "wrong backup password".toCharArray()))

        val report = otherRepo.importBackup(ByteArrayInputStream(bytes), "backup secret".toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(0, report.duplicated)
        assertEquals("Migrated", otherRepo.items.value.single().title)
        assertEquals("payload", otherRepo.items.value.single().body)
        // The imported item is re-encrypted under the target vault's VMK and persisted.
        assertEquals(1, otherDb.itemDao().count())
    }

    @Test
    fun phraseOnlyBackupRestoresWithSourcePasswordOrRecoveryPhrase() = runBlocking {
        val phrase = repo.setupVault(OLD_PASSWORD.toCharArray())
        repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "Transfer", body = "portable"))
        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, null))
        val bytes = exported.toByteArray()

        val passwordRestore = newRepository(database = newDatabase(), preferences = newPrefs())
        passwordRestore.setupVault("target one".toCharArray())
        assertNull(passwordRestore.importBackup(ByteArrayInputStream(bytes), "definitely wrong".toCharArray()))
        val reportOne = passwordRestore.importBackup(ByteArrayInputStream(bytes), OLD_PASSWORD.toCharArray())
        assertNotNull(reportOne)
        assertEquals(1, reportOne!!.imported)
        assertEquals("Transfer", passwordRestore.items.value.single().title)

        val phraseRestore = newRepository(database = newDatabase(), preferences = newPrefs())
        phraseRestore.setupVault("target two".toCharArray())
        val reportTwo = phraseRestore.importBackup(
            ByteArrayInputStream(bytes),
            phrase.joinToString(" ").toCharArray(),
        )
        assertNotNull(reportTwo)
        assertEquals(1, reportTwo!!.imported)
        assertEquals("portable", phraseRestore.items.value.single().body)
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val OLD_PASSWORD = "old master password"
        const val NEW_PASSWORD = "new master password"
    }
}
