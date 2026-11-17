package com.cipherwell.notezero.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import javax.crypto.Cipher

/**
 * End-to-end tests for locked notes & folders (decision #15) against real crypto and
 * in-memory Room. Covers: flag persistence across lock/unlock with a rewritten envelope and
 * a still-valid AAD, envelope-only storage (row/column scan + decrypted v3 payload), the
 * master-password re-authentication rate limiter driven by the injected clock, and every
 * clearing path of `verifiedLockedIds` — including the external-activity deferral.
 *
 * No sleeps: the rate limiter and the auto-lock read the injected [now] clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedItemsRepositoryTest {

    private lateinit var context: Context

    private val databases = mutableListOf<NoteZeroDatabase>()
    private val repositories = mutableListOf<EncryptedNoteRepository>()
    private val preferenceScopes = mutableListOf<CoroutineScope>()

    private var now: Long = 1_700_000_000_000L

    private lateinit var db: NoteZeroDatabase
    private lateinit var prefs: VaultPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = newDatabase()
        prefs = newPrefs()
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
        allowAutoLock: Boolean = true,
    ): EncryptedNoteRepository = EncryptedNoteRepository(
        context = context,
        database = database,
        preferences = preferences,
        clock = { now },
        kdfIterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        allowAutoLock = allowAutoLock,
    ).also { repositories.add(it) }

    private suspend fun unlockedRepository(): EncryptedNoteRepository {
        val repository = newRepository()
        repository.setupVault(PASSWORD.toCharArray())
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        return repository
    }

    private suspend fun EncryptedNoteRepository.awaitStatus(target: VaultStatus) {
        withTimeout(5_000) { status.first { it == target } }
    }

    /** Recovers the VMK from the password envelope, exactly as unlock() does. */
    private suspend fun unwrapVaultKey(): ByteArray {
        val meta = db.vaultMetaDao().get() ?: throw AssertionError("vault meta row exists")
        val envelope = KeyEnvelope.fromBytes(meta.passwordEnvelope)
            ?: throw AssertionError("password envelope decodes")
        return NoteCrypto.unwrapVmk(
            envelope.wrappedVmk,
            NoteCrypto.deriveKek(PASSWORD.toCharArray(), envelope.salt, envelope.iterations),
            CryptoAad.VMK_WRAP.toByteArray(Charsets.UTF_8),
        ) ?: throw AssertionError("password unwraps the VMK")
    }

    private fun columnNames(table: String): List<String> {
        val names = ArrayList<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names.add(cursor.getString(nameIndex))
        }
        return names
    }

    // ---- persistence ---------------------------------------------------------

    @Test
    fun lockedNoteFlagPersistsAcrossLockUnlockAndRewritesTheEnvelope() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.saveItem(
            repo.createItem(ItemKind.NOTE).copy(title = "diary", body = "private thoughts", tags = listOf("me")),
        )
        val before = db.itemDao().getById(note.id)!!.ciphertext

        assertTrue(repo.setItemLocked(note.id, true))
        assertTrue(repo.items.value.single().locked)
        val after = db.itemDao().getById(note.id)!!.ciphertext
        assertFalse("locking re-encrypts the envelope with the flag inside", before.contentEquals(after))
        assertEquals(0, repo.corruptRowCount)

        repo.lock()
        assertEquals(VaultStatus.LOCKED, repo.status.value)
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))

        val reopened = repo.items.value.single()
        assertTrue("lock flag survives lock/unlock", reopened.locked)
        assertEquals("diary", reopened.title)
        assertEquals("private thoughts", reopened.body)
        assertEquals("the AAD still authenticates after the flag flip", 0, repo.corruptRowCount)
    }

    @Test
    fun setItemLockedIsIdempotentAndDoesNotBumpTheEnvelope() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "steady"))
        assertTrue(repo.setItemLocked(note.id, true))
        val stored = repo.items.value.single()
        val ciphertext = db.itemDao().getById(note.id)!!.ciphertext

        assertTrue(repo.setItemLocked(note.id, true))

        assertEquals("a no-op lock keeps the item unchanged", stored, repo.items.value.single())
        assertTrue(ciphertext.contentEquals(db.itemDao().getById(note.id)!!.ciphertext))
    }

    @Test
    fun lockedFolderFlagPersistsAcrossLockUnlockAndCanBeRemoved() = runBlocking {
        val repo = unlockedRepository()
        val folder = repo.createFolder("Vault")
        assertTrue(repo.setFolderLocked(folder.id, true))
        assertTrue(repo.folders.value.single().locked)

        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertTrue(repo.folders.value.single().locked)
        assertEquals(0, repo.corruptRowCount)

        assertTrue(repo.setFolderLocked(folder.id, false))
        assertFalse(repo.folders.value.single().locked)
        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertFalse(repo.folders.value.single().locked)
    }

    @Test
    fun lockApiFailsClosedForUnknownIdsAndLockedVaults() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val folder = repo.createFolder("Work")
        assertFalse(repo.setItemLocked("missing", true))
        assertFalse(repo.setFolderLocked("missing", true))

        repo.lock()
        assertFalse(repo.setItemLocked(note.id, true))
        assertFalse(repo.setFolderLocked(folder.id, true))
        val password = PASSWORD.toCharArray()
        assertFalse(repo.verifyMasterPassword(password))
        assertTrue("char array is wiped even when the vault is locked", password.all { it == '\u0000' })
        assertTrue(repo.verifiedLockedIds.value.isEmpty())
    }

    // ---- re-authentication ---------------------------------------------------

    @Test
    fun verifyMasterPasswordAcceptsOnlyTheMasterPasswordAndWipesEveryInput() = runBlocking {
        val repo = unlockedRepository()
        repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "visible"))

        val correct = PASSWORD.toCharArray()
        assertTrue(repo.verifyMasterPassword(correct))
        assertTrue("correct input is wiped", correct.all { it == '\u0000' })
        assertEquals("session stays unlocked", VaultStatus.UNLOCKED, repo.status.value)
        assertEquals(1, repo.items.value.size)

        val blank = CharArray(0)
        assertFalse("blank input is never accepted", repo.verifyMasterPassword(blank))

        val whitespace = "   ".toCharArray()
        assertFalse(repo.verifyMasterPassword(whitespace))
        assertTrue(whitespace.all { it == '\u0000' })
        // Blank input is not an attempt: the next call is not rate-limited.
        assertTrue(repo.verifyMasterPassword(PASSWORD.toCharArray()))

        val wrong = "not the password".toCharArray()
        assertFalse(repo.verifyMasterPassword(wrong))
        assertTrue("wrong input is wiped", wrong.all { it == '\u0000' })
        assertEquals("a failed re-auth never locks or mutates the session", VaultStatus.UNLOCKED, repo.status.value)
        assertEquals(1, repo.items.value.size)
        assertTrue("a failed re-auth reveals nothing", repo.verifiedLockedIds.value.isEmpty())
    }

    @Test
    fun verifyMasterPasswordEnforcesGrowingDelaysWithTheInjectedClock() = runBlocking {
        val repo = unlockedRepository()

        assertFalse(repo.verifyMasterPassword("nope".toCharArray()))
        assertFalse(
            "failure 1 delays the next attempt by 1 s",
            repo.verifyMasterPassword(PASSWORD.toCharArray()),
        )
        now += LockVerificationPolicy.delayAfterFailure(1) - 1
        assertFalse("still inside the delay window", repo.verifyMasterPassword(PASSWORD.toCharArray()))
        now += 1
        assertTrue("the correct password works once the delay elapsed", repo.verifyMasterPassword(PASSWORD.toCharArray()))

        // Success reset the counter: the next failure starts the ladder again at 1 s.
        assertFalse(repo.verifyMasterPassword("nope".toCharArray()))
        now += LockVerificationPolicy.delayAfterFailure(1) - 1
        assertFalse(repo.verifyMasterPassword(PASSWORD.toCharArray()))
        now += 1
        assertTrue(repo.verifyMasterPassword(PASSWORD.toCharArray()))

        // The delay grows with consecutive failures.
        assertTrue(LockVerificationPolicy.delayAfterFailure(2) > LockVerificationPolicy.delayAfterFailure(1))
        assertTrue(LockVerificationPolicy.delayAfterFailure(4) > LockVerificationPolicy.delayAfterFailure(3))
    }

    @Test
    fun fiveConsecutiveFailuresOpenAThirtySecondCooldown() = runBlocking {
        val repo = unlockedRepository()

        repeat(LockVerificationPolicy.MAX_CONSECUTIVE_FAILURES - 1) { index ->
            assertFalse(repo.verifyMasterPassword("nope".toCharArray()))
            now += LockVerificationPolicy.delayAfterFailure(index + 1)
        }
        assertFalse("fifth failure", repo.verifyMasterPassword("nope".toCharArray()))

        assertEquals(LockVerificationPolicy.COOLDOWN_MS, LockVerificationPolicy.delayAfterFailure(5))
        assertFalse(
            "the cooldown blocks even the correct password",
            repo.verifyMasterPassword(PASSWORD.toCharArray()),
        )
        now += LockVerificationPolicy.COOLDOWN_MS - 1
        assertFalse("cooldown still running", repo.verifyMasterPassword(PASSWORD.toCharArray()))
        now += 1
        assertTrue("cooldown elapsed", repo.verifyMasterPassword(PASSWORD.toCharArray()))

        // A failed attempt after the cooldown re-opens the 30 s window.
        assertFalse(repo.verifyMasterPassword("nope".toCharArray()))
        assertFalse(repo.verifyMasterPassword(PASSWORD.toCharArray()))
        now += LockVerificationPolicy.COOLDOWN_MS
        assertTrue(repo.verifyMasterPassword(PASSWORD.toCharArray()))
    }

    // ---- verification lifetime ----------------------------------------------

    @Test
    fun verificationsClearOnLockUnlockAndCloseAndAreNeverPersisted() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked"))
        val folder = repo.createFolder("Locked folder")
        assertTrue(repo.setItemLocked(note.id, true))
        assertTrue(repo.setFolderLocked(folder.id, true))

        repo.markLockVerified(note.id)
        repo.markLockVerified(folder.id)
        assertEquals(setOf(note.id, folder.id), repo.verifiedLockedIds.value)

        repo.lock()
        assertTrue("lock() clears every verification", repo.verifiedLockedIds.value.isEmpty())
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertTrue("a new session starts unverified", repo.verifiedLockedIds.value.isEmpty())

        // Memory only: another repository over the same DB never sees a verification.
        val other = newRepository()
        assertEquals(UnlockResult.SUCCESS, other.unlock(PASSWORD.toCharArray()))
        assertTrue(other.verifiedLockedIds.value.isEmpty())

        // Marking while locked is ignored (no verification can outlive the session).
        other.lock()
        other.markLockVerified(note.id)
        assertTrue(other.verifiedLockedIds.value.isEmpty())

        repo.markLockVerified(note.id)
        assertTrue(repo.verifiedLockedIds.value.contains(note.id))
        repo.clearLockVerifications()
        assertTrue("clearLockVerifications re-masks everything", repo.verifiedLockedIds.value.isEmpty())

        repo.markLockVerified(note.id)
        repo.close()
        assertTrue("close() clears every verification", repo.verifiedLockedIds.value.isEmpty())
        // After close() the VMK is gone even though status was never flipped: a late
        // re-auth callback must not be able to re-add a verification to a dead session.
        repo.markLockVerified(note.id)
        assertTrue(repo.verifiedLockedIds.value.isEmpty())
    }

    @Test
    fun recoveryResetAndBiometricUnlockStartUnverifiedWithACleanRateLimit() = runBlocking {
        val repo = newRepository()
        val phrase = repo.setupVault(PASSWORD.toCharArray())
        val lockedNote = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked"))
        assertTrue(repo.setItemLocked(lockedNote.id, true))
        repo.markLockVerified(lockedNote.id)
        assertEquals(setOf(lockedNote.id), repo.verifiedLockedIds.value)

        // A failed re-auth arms the growing-delay limiter for this session.
        assertFalse(repo.verifyMasterPassword("nope".toCharArray()))

        // Recovery reset re-wraps the SAME phrase (decision #12) and is a session boundary:
        // verifications are re-masked and the limiter starts clean, like unlock()/setupVault().
        assertEquals(
            UnlockResult.SUCCESS,
            repo.resetWithRecoveryPhrase(phrase, NEW_PASSWORD.toCharArray()),
        )
        assertTrue("recovery reset clears every verification", repo.verifiedLockedIds.value.isEmpty())
        assertTrue(
            "recovery reset clears the re-auth limiter (no clock advance needed)",
            repo.verifyMasterPassword(NEW_PASSWORD.toCharArray()),
        )

        // completeBiometricUnlock tears a live session down before trying the cipher. With no
        // Keystore enrolment in a JVM test the attempt fails, but the teardown must re-mask
        // immediately: a stale verification can never outlive the VMK it belongs to.
        repo.markLockVerified(lockedNote.id)
        assertEquals(setOf(lockedNote.id), repo.verifiedLockedIds.value)
        val failed = repo.completeBiometricUnlock(
            BiometricDecrypt(Cipher.getInstance("AES/GCM/NoPadding")),
        )
        assertEquals(UnlockResult.BIOMETRIC_FAILED, failed)
        assertTrue("biometric teardown clears every verification", repo.verifiedLockedIds.value.isEmpty())
        repo.awaitStatus(VaultStatus.LOCKED)
    }

    @Test
    fun backgroundLockClearsVerificationsButPickerDeferralDoesNot() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked"))
        assertTrue(repo.setItemLocked(note.id, true))
        repo.markLockVerified(note.id)
        assertEquals(setOf(note.id), repo.verifiedLockedIds.value)

        // The SAF picker is a separate Activity: its background stop is deferred and the
        // verification must survive, otherwise the user loses the revealed note for nothing.
        repo.beginExternalActivity()
        repo.onAppBackgrounded()
        assertEquals(VaultStatus.UNLOCKED, repo.status.value)
        assertEquals("deferral keeps the verified set", setOf(note.id), repo.verifiedLockedIds.value)

        repo.onAppForegrounded()
        repo.endExternalActivity()
        assertEquals(VaultStatus.UNLOCKED, repo.status.value)
        assertEquals(setOf(note.id), repo.verifiedLockedIds.value)

        // A real background (screen-off/manual background) clears immediately and locks.
        repo.onAppBackgrounded()
        assertTrue("real background lock clears verifications", repo.verifiedLockedIds.value.isEmpty())
        repo.awaitStatus(VaultStatus.LOCKED)
    }

    @Test
    fun endExternalActivityWhileStillBackgroundedLocksAndClears() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked"))
        assertTrue(repo.setItemLocked(note.id, true))
        repo.markLockVerified(note.id)

        repo.beginExternalActivity()
        repo.onAppBackgrounded()
        assertEquals(setOf(note.id), repo.verifiedLockedIds.value)

        // The result arrives before the Activity returns: the deferred lock applies now.
        repo.endExternalActivity()

        assertTrue(repo.verifiedLockedIds.value.isEmpty())
        repo.awaitStatus(VaultStatus.LOCKED)
    }

    @Test
    fun changingALockStateDropsTheVerificationForThatIdOnly() = runBlocking {
        val repo = unlockedRepository()
        val lockedNote = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked"))
        val otherNote = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "other"))
        val folder = repo.createFolder("folder")
        assertTrue(repo.setItemLocked(lockedNote.id, true))
        assertTrue(repo.setFolderLocked(folder.id, true))
        repo.markLockVerified(lockedNote.id)
        repo.markLockVerified(otherNote.id)
        repo.markLockVerified(folder.id)

        // Removing a lock drops its verification; unrelated ids stay verified.
        assertTrue(repo.setItemLocked(lockedNote.id, false))
        assertEquals(setOf(otherNote.id, folder.id), repo.verifiedLockedIds.value)

        // Locking again re-masks (verification is never carried over a lock change).
        assertTrue(repo.setItemLocked(lockedNote.id, true))
        assertEquals(setOf(otherNote.id, folder.id), repo.verifiedLockedIds.value)

        // The same rule for folders.
        assertTrue(repo.setFolderLocked(folder.id, false))
        assertEquals(setOf(otherNote.id), repo.verifiedLockedIds.value)

        assertTrue(repo.setItemLocked(otherNote.id, true))
        assertEquals(emptySet<String>(), repo.verifiedLockedIds.value)
    }

    @Test
    fun deletingContentDropsItsVerification() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked"))
        val trashed = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "trashed"))
        val folder = repo.createFolder("folder")
        assertTrue(repo.setItemLocked(note.id, true))
        assertTrue(repo.setItemLocked(trashed.id, true))
        assertTrue(repo.setFolderLocked(folder.id, true))
        repo.markLockVerified(note.id)
        repo.markLockVerified(trashed.id)
        repo.markLockVerified(folder.id)

        repo.deleteItemForever(note.id)
        assertEquals(setOf(trashed.id, folder.id), repo.verifiedLockedIds.value)

        repo.trashItem(trashed.id)
        repo.emptyTrash()
        assertEquals(setOf(folder.id), repo.verifiedLockedIds.value)

        repo.deleteFolder(folder.id)
        assertTrue(repo.verifiedLockedIds.value.isEmpty())
    }

    // ---- storage redaction ---------------------------------------------------

    @Test
    fun lockFlagLivesOnlyInsideTheAuthenticatedEnvelope() = runBlocking {
        val repo = unlockedRepository()
        val marker = "LOCKMARK${UUID.randomUUID().toString().replace("-", "")}"
        val note = repo.saveItem(
            repo.createItem(ItemKind.NOTE).copy(title = marker, body = "$marker body", tags = listOf(marker)),
        )
        val folder = repo.createFolder(marker)
        assertTrue(repo.setItemLocked(note.id, true))
        assertTrue(repo.setFolderLocked(folder.id, true))

        // No plaintext lock column and no content column anywhere.
        assertEquals(listOf("id", "kind", "created_at", "updated_at", "ciphertext"), columnNames("items"))
        assertEquals(listOf("id", "created_at", "ciphertext"), columnNames("folders"))

        val itemRow = db.itemDao().getById(note.id)!!
        val folderRow = db.folderDao().getAll().single()
        assertFalse(itemRow.id.contains(marker))
        assertFalse(itemRow.kind.contains(marker))
        for (field in listOf(itemRow.id, itemRow.kind, itemRow.createdAt.toString(), itemRow.updatedAt.toString())) {
            assertFalse("plaintext columns must not carry content or lock metadata", field.contains(marker))
            assertFalse(field.contains("locked", ignoreCase = true))
        }
        for (row in listOf(itemRow.ciphertext, folderRow.ciphertext)) {
            val asText = String(row, Charsets.ISO_8859_1)
            assertFalse("ciphertext must not contain content", asText.contains(marker))
            assertFalse("ciphertext must not contain a readable lock marker", asText.contains("locked"))
        }

        // The flag travels inside the GCM envelope: decrypt with the recovered VMK and read
        // the trailing lock byte of the v3 / v2 payloads.
        val vmk = unwrapVaultKey()
        try {
            val key = NoteCrypto.secretKey(vmk)
            val itemPlaintext = NoteCrypto.decrypt(
                key,
                itemRow.ciphertext,
                CryptoAad.item(note.id, ItemKind.NOTE.name),
            ) ?: throw AssertionError("item ciphertext decrypts")
            try {
                assertEquals(ItemCodec.ENVELOPE_VERSION, itemPlaintext[4].toInt())
                assertEquals(6, itemPlaintext[4].toInt())
                // v6 layout: lock byte is followed by reading-state, annotation and sketch
                // presence bytes only.
                assertEquals(0, itemPlaintext.last().toInt())
                assertEquals(0, itemPlaintext[itemPlaintext.size - 2].toInt())
                assertEquals(0, itemPlaintext[itemPlaintext.size - 3].toInt())
                assertEquals(1, itemPlaintext[itemPlaintext.size - 4].toInt())
                val decoded = ItemCodec.decode(itemPlaintext)
                    ?: throw AssertionError("item payload decodes")
                assertTrue(decoded.locked)
                assertEquals(marker, decoded.title)
            } finally {
                NoteCrypto.wipe(itemPlaintext)
            }
            val folderPlaintext = NoteCrypto.decrypt(
                key,
                folderRow.ciphertext,
                CryptoAad.item(folder.id, "FOLDER"),
            ) ?: throw AssertionError("folder ciphertext decrypts")
            try {
                assertEquals(FolderCodec.ENVELOPE_VERSION, folderPlaintext[4].toInt())
                assertEquals(2, folderPlaintext[4].toInt())
                assertEquals(1, folderPlaintext.last().toInt())
                val decodedFolder = FolderCodec.decode(folderPlaintext)
                    ?: throw AssertionError("folder payload decodes")
                assertTrue(decodedFolder.locked)
            } finally {
                NoteCrypto.wipe(folderPlaintext)
            }
        } finally {
            NoteCrypto.wipe(vmk)
        }
    }

    // ---- saveItem must not be able to change the lock ------------------------

    @Test
    fun saveItemCannotChangeTheLockFlag() = runBlocking {
        val repo = unlockedRepository()
        val locked = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked"))
        val unlocked = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "open"))
        assertTrue(repo.setItemLocked(locked.id, true))

        // A stale editor copy (locked = false) must not unlock the note on autosave.
        val savedLocked = repo.saveItem(repo.items.value.first { it.id == locked.id }.copy(title = "edited", locked = false))
        assertTrue("autosave must preserve the stored lock", savedLocked.locked)
        assertEquals("edited", savedLocked.title)
        assertTrue(repo.items.value.first { it.id == locked.id }.locked)

        // And it must not lock an unlocked note either: only the lock API may.
        val savedUnlocked = repo.saveItem(repo.items.value.first { it.id == unlocked.id }.copy(locked = true))
        assertFalse(savedUnlocked.locked)
        assertFalse(repo.items.value.first { it.id == unlocked.id }.locked)

        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertTrue(repo.items.value.first { it.id == locked.id }.locked)
        assertFalse(repo.items.value.first { it.id == unlocked.id }.locked)
    }

    // ---- backup --------------------------------------------------------------

    @Test
    fun backupRoundTripPreservesLockFlagsWithoutVerifyingThem() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.saveItem(repo.createItem(ItemKind.NOTE).copy(title = "locked note", body = "content"))
        val folder = repo.createFolder("locked folder")
        assertTrue(repo.setItemLocked(note.id, true))
        assertTrue(repo.setFolderLocked(folder.id, true))
        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, null))

        val otherDb = newDatabase()
        val restored = newRepository(database = otherDb, preferences = newPrefs())
        restored.setupVault(PASSWORD.toCharArray())
        // A vault-key backup restores into another vault with that vault's master password.
        val report = restored.importBackup(ByteArrayInputStream(exported.toByteArray()), PASSWORD.toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(1, report.foldersImported)
        assertEquals(0, report.skipped)

        assertTrue("restored note keeps its lock", restored.items.value.single().locked)
        assertTrue("restored folder keeps its lock", restored.folders.value.single().locked)
        assertTrue("restored locked content is not revealed", restored.verifiedLockedIds.value.isEmpty())
        assertEquals(0, restored.corruptRowCount)
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val NEW_PASSWORD = "another correct horse battery staple"
    }
}
