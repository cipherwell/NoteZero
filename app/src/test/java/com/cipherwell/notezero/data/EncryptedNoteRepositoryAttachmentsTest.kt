package com.cipherwell.notezero.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cipherwell.notezero.backup.BackupFormat
import com.cipherwell.notezero.crypto.CryptoAad
import com.cipherwell.notezero.crypto.EnvelopeType
import com.cipherwell.notezero.crypto.KeyEnvelope
import com.cipherwell.notezero.crypto.NoteCrypto
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * End-to-end attachment storage/backup tests against real AES-256-GCM, real Room (in-memory)
 * and a real (isolated) app-private blob directory under Robolectric.
 *
 * Every assertion is on observable behaviour: imported bytes, blob file bytes (ciphertext
 * only), SHA-256/AAD failure modes, trash-vs-delete lifecycle, orphan sweep, quota limits,
 * locked-vault fail-closed and v2/v1 backup restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedNoteRepositoryAttachmentsTest {

    private lateinit var context: Context

    private val databases = mutableListOf<NoteZeroDatabase>()
    private val repositories = mutableListOf<EncryptedNoteRepository>()
    private val preferenceScopes = mutableListOf<CoroutineScope>()
    private val attachmentDirs = mutableListOf<File>()

    private var now: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        repositories.forEach { repository -> runCatching { repository.close() } }
        preferenceScopes.forEach { scope -> scope.cancel() }
        databases.forEach { database -> runCatching { database.close() } }
        attachmentDirs.forEach { dir -> dir.deleteRecursively() }
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

    private fun newAttachmentsDir(): File =
        File(context.cacheDir, "attachments-${UUID.randomUUID()}").also { attachmentDirs.add(it) }

    private fun newRepository(
        database: NoteZeroDatabase = newDatabase(),
        preferences: VaultPreferences = newPrefs(),
        attachmentsDir: File = newAttachmentsDir(),
    ): EncryptedNoteRepository = EncryptedNoteRepository(
        context = context,
        database = database,
        preferences = preferences,
        clock = { now },
        kdfIterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        allowAutoLock = false,
        attachmentsDir = attachmentsDir,
    ).also { repositories.add(it) }

    private suspend fun unlockedRepository(): EncryptedNoteRepository {
        val repository = newRepository()
        repository.setupVault(PASSWORD.toCharArray())
        return repository
    }

    private suspend fun EncryptedNoteRepository.addAttachment(
        noteId: String,
        bytes: ByteArray,
        displayName: String = "file.bin",
        mimeType: String = "application/octet-stream",
    ): AttachmentRef {
        val result = importAttachment(noteId, displayName, mimeType, ByteArrayInputStream(bytes))
        assertTrue("import failed: $result", result is AttachmentImport.Success)
        return (result as AttachmentImport.Success).ref
    }

    private fun EncryptedNoteRepository.blobFile(attachmentId: String): File =
        File(attachmentsDirectory, "$attachmentId.nzb")

    /** Reflects the live VMK so tests can craft alternative valid ciphertexts (never logged). */
    private fun vmkOf(repository: EncryptedNoteRepository): ByteArray {
        val field = EncryptedNoteRepository::class.java.getDeclaredField("vmk")
        field.isAccessible = true
        return (field.get(repository) as ByteArray).copyOf()
    }

    private fun marker(): String = "ZZATTACH${UUID.randomUUID().toString().replace("-", "")}"

    /** Scans the given roots for a plaintext marker (byte-wise, ISO-8859-1 view). */
    private fun containsMarker(roots: List<File>, marker: String): Boolean {
        for (root in roots) {
            if (!root.exists()) continue
            val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList() else listOf(root)
            for (file in files) {
                if (String(file.readBytes(), Charsets.ISO_8859_1).contains(marker)) return true
            }
        }
        return false
    }

    /** Builds a v2 extra-password backup under a synthetic source VMK (for cross-vault tests). */
    private fun extraPasswordBackup(
        password: CharArray,
        items: List<Item> = emptyList(),
        attachments: List<BackupFormat.AttachmentBlobRecord> = emptyList(),
    ): ByteArray {
        val sourceVmk = NoteCrypto.randomVmk()
        try {
            val salt = NoteCrypto.randomBytes(NoteCrypto.SALT_LENGTH_BYTES)
            val envelope = KeyEnvelope(
                type = EnvelopeType.PASSWORD,
                iterations = NoteCrypto.MIN_ACCEPTED_ITERATIONS,
                salt = salt,
                wrappedVmk = NoteCrypto.wrapVmk(
                    sourceVmk,
                    NoteCrypto.deriveKek(password, salt, NoteCrypto.MIN_ACCEPTED_ITERATIONS),
                    CryptoAad.BACKUP.toByteArray(Charsets.UTF_8),
                ),
            )
            val out = ByteArrayOutputStream()
            BackupFormat.write(
                out,
                BackupFormat.Contents(exportedAt = now, items = items, folders = emptyList(), attachments = attachments),
                sourceVmk,
                BackupFormat.MODE_EXTRA_PASSWORD,
                listOf(envelope),
            )
            return out.toByteArray()
        } finally {
            NoteCrypto.wipe(sourceVmk)
        }
    }

    // ---- import / read round trip -------------------------------------------

    @Test
    fun importAndReadRoundTripEncryptsAtRestAndNeverCachesPlaintext() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val sql = note.id
        val markerText = marker()
        val plaintext = "$markerText \u0000\u0001 binary \u00ff".toByteArray(Charsets.ISO_8859_1)

        val ref = repo.addAttachment(sql, plaintext, displayName = "photo.jpg", mimeType = "image/jpeg")

        // Ref metadata is exact: random id, plaintext size + SHA-256, timestamp.
        assertTrue(ref.id.isNotEmpty())
        assertEquals("photo.jpg", ref.displayName)
        assertEquals("image/jpeg", ref.mimeType)
        assertEquals(plaintext.size.toLong(), ref.sizeBytes)
        assertEquals(AttachmentBlob.sha256Hex(plaintext), ref.sha256)
        assertTrue(ref.sha256.length == 64 && ref.sha256 == ref.sha256.lowercase())

        // The ref is part of the encrypted item payload and surfaces in the index.
        assertEquals(listOf(ref), repo.items.value.single().attachments)

        // The blob file contains only NZAT + version + ciphertext: no plaintext bytes/name.
        val blob = repo.blobFile(ref.id)
        assertTrue("blob file exists", blob.isFile)
        val raw = blob.readBytes()
        assertArrayEquals(AttachmentBlob.MAGIC, raw.copyOfRange(0, AttachmentBlob.MAGIC.size))
        assertEquals(AttachmentBlob.VERSION, raw[AttachmentBlob.MAGIC.size].toInt())
        assertFalse("plaintext marker must never be written to disk", containsMarker(listOf(blob), markerText))
        assertEquals(
            AttachmentBlob.HEADER_BYTES + NoteCrypto.GCM_IV_LENGTH_BYTES + plaintext.size + NoteCrypto.GCM_TAG_LENGTH_BYTES,
            raw.size,
        )

        // Read path returns the exact original bytes.
        assertArrayEquals(plaintext, repo.attachmentBytes(sql, ref.id))

        // Nothing plaintext anywhere in app-private storage used by this test.
        assertEquals(0, repo.attachmentFailureCount)
    }

    @Test
    fun lockedVaultFailsClosedForEveryAttachmentOperation() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val ref = repo.addAttachment(note.id, "secret bytes".toByteArray())
        val exported = ByteArrayOutputStream()

        repo.lock()

        val imported = repo.importAttachment(note.id, "x", "text/plain", ByteArrayInputStream(ByteArray(3)))
        assertEquals(AttachmentImport.Failure(AttachmentError.LOCKED), imported)
        assertNull(repo.attachmentBytes(note.id, ref.id))
        assertFalse(repo.exportAttachment(note.id, ref.id, exported))
        assertFalse(repo.deleteAttachment(note.id, ref.id))
        assertEquals("locked reads must not write", 0, exported.size())

        // Fails closed every time, not just once.
        assertNull(repo.attachmentBytes(note.id, ref.id))

        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertArrayEquals("secret bytes".toByteArray(), repo.attachmentBytes(note.id, ref.id))
    }

    @Test
    fun sizeLimitIsEnforcedWhileStreamingWithoutAllocatingOrOrphaning() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)

        // A hostile source that can produce gigabytes: it must be cut off at the limit.
        val hostile = object : InputStream() {
            var served = 0L
            override fun read(): Int {
                served += 1
                return 0x41
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                served += length
                return length
            }
        }
        val result = repo.importAttachment(note.id, "huge.bin", "application/octet-stream", hostile)
        assertEquals(AttachmentImport.Failure(AttachmentError.TOO_LARGE), result)
        assertTrue(
            "must stop near the limit, served=${hostile.served}",
            hostile.served <= AttachmentLimits.MAX_ATTACHMENT_BYTES + 64L * 1024L,
        )
        assertTrue("no ref was added", repo.items.value.single().attachments.isEmpty())
        assertEquals("no orphan blob", 0, repo.attachmentsDirectory.listFiles()?.size ?: 0)

        // Exactly at the limit is accepted (the boundary is not off by one).
        val atLimit = ByteArray(AttachmentLimits.MAX_ATTACHMENT_BYTES.toInt())
        val ref = repo.addAttachment(note.id, atLimit)
        assertEquals(AttachmentLimits.MAX_ATTACHMENT_BYTES, ref.sizeBytes)
        assertArrayEquals(atLimit, repo.attachmentBytes(note.id, ref.id))
    }

    @Test
    fun perNoteCountLimitRejectsThe101stAttachmentWithoutOrphan() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        repeat(AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE) { index ->
            repo.addAttachment(note.id, byteArrayOf(index.toByte()), displayName = "f$index.bin")
        }
        val blobCountBefore = repo.attachmentsDirectory.listFiles()!!.count { it.name.endsWith(".nzb") }
        assertEquals(AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE, repo.items.value.single().attachments.size)

        val result = repo.importAttachment(note.id, "one-too-many", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        assertEquals(AttachmentImport.Failure(AttachmentError.QUOTA_EXCEEDED), result)
        assertEquals(AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE, repo.items.value.single().attachments.size)
        assertEquals(blobCountBefore, repo.attachmentsDirectory.listFiles()!!.count { it.name.endsWith(".nzb") })
    }

    @Test
    fun vaultByteQuotaRejectsImportWhenReferencedAttachmentsExceed512MiB() = runBlocking {
        val repo = unlockedRepository()
        // A backup with refs claiming 52 x 10 MiB (520 MiB > 512 MiB quota) but no blob records:
        // restoring it exercises the quota accounting without writing 512 MiB to disk.
        val sha = AttachmentBlob.sha256Hex(byteArrayOf(0))
        val refs = List(52) { index ->
            AttachmentRef(
                id = "quota-$index",
                displayName = "quota-$index.bin",
                mimeType = "application/octet-stream",
                sizeBytes = AttachmentLimits.MAX_ATTACHMENT_BYTES,
                createdAt = now,
                sha256 = sha,
            )
        }
        val item = Item(
            id = "quota-note",
            kind = ItemKind.NOTE,
            title = "quota",
            body = "",
            createdAt = now,
            updatedAt = now,
            attachments = refs,
        )
        val backup = extraPasswordBackup("quota backup".toCharArray(), items = listOf(item))
        val report = repo.importBackup(ByteArrayInputStream(backup), "quota backup".toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(52, report.attachmentsSkipped)

        val result = repo.importAttachment("quota-note", "one.bin", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        assertEquals(AttachmentImport.Failure(AttachmentError.QUOTA_EXCEEDED), result)
        assertTrue(repo.items.value.first { it.id == "quota-note" }.attachments.size == 52)
        assertEquals(0, repo.attachmentsDirectory.listFiles()?.size ?: 0)
    }

    // ---- integrity -----------------------------------------------------------

    @Test
    fun aadBindsEachBlobToItsNoteAndAttachmentId() = runBlocking {
        val repo = unlockedRepository()
        val noteA = repo.createItem(ItemKind.NOTE)
        val noteB = repo.createItem(ItemKind.NOTE)
        val refA = repo.addAttachment(noteA.id, "bytes of A".toByteArray())
        val refB1 = repo.addAttachment(noteB.id, "bytes of B1".toByteArray())
        val refB2 = repo.addAttachment(noteB.id, "bytes of B2".toByteArray())

        // 1) Cross-note swap: A's ciphertext at B's attachment path must fail the noteId AAD.
        val blobA = repo.blobFile(refA.id).readBytes()
        repo.blobFile(refB1.id).writeBytes(blobA)
        assertNull("cross-note blob must not authenticate", repo.attachmentBytes(noteB.id, refB1.id))
        assertEquals(1, repo.attachmentFailureCount)

        // 2) Same-note id swap: B1's (now A's) bytes are not valid for B2's id.
        repo.blobFile(refB2.id).writeBytes(repo.blobFile(refB1.id).readBytes())
        assertNull("cross-id blob must not authenticate", repo.attachmentBytes(noteB.id, refB2.id))
        assertEquals(2, repo.attachmentFailureCount)
        assertArrayEquals("A's own blob is untouched", "bytes of A".toByteArray(), repo.attachmentBytes(noteA.id, refA.id))
    }

    @Test
    fun tamperedBlobReturnsNullCountsAndNeverSurfacesBytes() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val ref = repo.addAttachment(note.id, "tamper me".toByteArray())
        val file = repo.blobFile(ref.id)
        val tampered = file.readBytes()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(tampered)

        assertNull(repo.attachmentBytes(note.id, ref.id))
        assertEquals(1, repo.attachmentFailureCount)
        assertNull(repo.attachmentBytes(note.id, ref.id))
        assertEquals(2, repo.attachmentFailureCount)

        // Deleting the file is an integrity failure too: nothing is fabricated.
        file.delete()
        assertNull(repo.attachmentBytes(note.id, ref.id))
        assertEquals(3, repo.attachmentFailureCount)
    }

    @Test
    fun sha256AndSizeAreVerifiedEvenWhenTheAeadIsValid() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val original = "12345678".toByteArray()
        val ref = repo.addAttachment(note.id, original)
        val vmk = vmkOf(repo)
        val key = NoteCrypto.secretKey(vmk)
        val aad = CryptoAad.attachment(note.id, ref.id)

        try {
            // Same length, valid AEAD under the right key/AAD, different content -> SHA mismatch.
            val sameSizeOther = "87654321".toByteArray()
            repo.blobFile(ref.id).writeBytes(AttachmentBlob.encode(sameSizeOther, key, aad))
            assertNull("SHA-256 mismatch must not be surfaced", repo.attachmentBytes(note.id, ref.id))
            assertEquals(1, repo.attachmentFailureCount)

            // Different length, valid AEAD -> size mismatch.
            repo.blobFile(ref.id).writeBytes(AttachmentBlob.encode("different length".toByteArray(), key, aad))
            assertNull("size mismatch must not be surfaced", repo.attachmentBytes(note.id, ref.id))
            assertEquals(2, repo.attachmentFailureCount)

            // Restoring the valid blob makes reads work again (no cached failure state).
            repo.blobFile(ref.id).writeBytes(AttachmentBlob.encode(original, key, aad))
            assertArrayEquals(original, repo.attachmentBytes(note.id, ref.id))
        } finally {
            NoteCrypto.wipe(vmk)
        }
    }

    // ---- lifecycle: delete / trash / orphan sweep ----------------------------

    @Test
    fun trashKeepsBlobsAndDeleteForeverRemovesThem() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val ref = repo.addAttachment(note.id, "trash lifecycle".toByteArray())

        repo.trashItem(note.id)
        assertTrue("trash alone must keep the blob", repo.blobFile(ref.id).isFile)
        assertArrayEquals("trashed notes can still decrypt their attachments", "trash lifecycle".toByteArray(), repo.attachmentBytes(note.id, ref.id))

        repo.deleteItemForever(note.id)
        assertFalse("delete-forever must remove the blob", repo.blobFile(ref.id).exists())
        assertNull(repo.attachmentBytes(note.id, ref.id))
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun emptyTrashRemovesAllTrashedBlobsAndKeepsLiveOnes() = runBlocking {
        val repo = unlockedRepository()
        val trashed = repo.createItem(ItemKind.NOTE)
        val live = repo.createItem(ItemKind.NOTE)
        val trashedRef = repo.addAttachment(trashed.id, "trashed".toByteArray())
        val liveRef = repo.addAttachment(live.id, "live".toByteArray())
        repo.trashItem(trashed.id)

        repo.emptyTrash()
        assertFalse(repo.blobFile(trashedRef.id).exists())
        assertTrue(repo.blobFile(liveRef.id).isFile)
        assertArrayEquals("live".toByteArray(), repo.attachmentBytes(live.id, liveRef.id))
        assertEquals(listOf(live.id), repo.items.value.map { it.id })
    }

    @Test
    fun deleteAttachmentRemovesRefAndBlobAndIsIdempotent() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val keep = repo.addAttachment(note.id, "keep".toByteArray(), displayName = "keep.txt")
        val remove = repo.addAttachment(note.id, "remove".toByteArray(), displayName = "remove.txt")

        assertTrue(repo.deleteAttachment(note.id, remove.id))
        assertFalse(repo.blobFile(remove.id).exists())
        assertNull(repo.attachmentBytes(note.id, remove.id))
        assertEquals(listOf(keep), repo.items.value.single().attachments)
        assertArrayEquals("keep".toByteArray(), repo.attachmentBytes(note.id, keep.id))

        assertFalse("second delete is a no-op", repo.deleteAttachment(note.id, remove.id))
        assertFalse("unknown note", repo.deleteAttachment("nope", keep.id))
        assertFalse("unknown attachment", repo.deleteAttachment(note.id, "nope"))
    }

    @Test
    fun saveItemPreservesRepositoryOwnedAttachmentRefs() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val ref = repo.addAttachment(note.id, "metadata".toByteArray())
        // Autosave with a stale copy (empty attachment list) must not drop the ref.
        val saved = repo.saveItem(repo.items.value.single().copy(title = "edited", attachments = emptyList()))
        assertEquals(listOf(ref), saved.attachments)
        assertEquals(listOf(ref), repo.items.value.single().attachments)
        assertArrayEquals("metadata".toByteArray(), repo.attachmentBytes(note.id, ref.id))
    }

    @Test
    fun orphanBlobsAndInterruptedTempFilesAreSweptOnUnlock() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val ref = repo.addAttachment(note.id, "referenced".toByteArray())

        val orphan = File(repo.attachmentsDirectory, "orphan-${UUID.randomUUID()}.nzb")
        orphan.writeBytes(ByteArray(64) { 0x11 })
        val temp = File(repo.attachmentsDirectory, ".tmp-${UUID.randomUUID()}")
        temp.writeBytes(ByteArray(64) { 0x22 })

        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))

        assertFalse("unreferenced blob must be swept", orphan.exists())
        assertFalse("interrupted import temp file must be swept", temp.exists())
        assertTrue("referenced blob must be kept", repo.blobFile(ref.id).isFile)
        assertArrayEquals("referenced".toByteArray(), repo.attachmentBytes(note.id, ref.id))
    }

    @Test
    fun orphanSweepDoesNotDeleteBlobsWhenAnyRowIsCorrupt() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val ref = repo.addAttachment(note.id, "must survive".toByteArray())
        val orphan = File(repo.attachmentsDirectory, "orphan-${UUID.randomUUID()}.nzb")
        orphan.writeBytes(ByteArray(32) { 0x33 })

        // Corrupt the item row: its refs are no longer readable, so their blobs would look
        // orphaned. The sweep must be skipped entirely (fail-safe: never destroy user data).
        val db = databases.last()
        val row = db.itemDao().getById(note.id)!!
        db.itemDao().upsert(row.copy(ciphertext = row.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }))

        repo.lock()
        assertEquals(UnlockResult.SUCCESS, repo.unlock(PASSWORD.toCharArray()))
        assertEquals(1, repo.corruptRowCount)
        assertTrue("blob of the corrupt row must not be deleted", repo.blobFile(ref.id).isFile)
        assertTrue("orphan sweep must be skipped when rows are corrupt", orphan.exists())
    }

    // ---- explicit export -----------------------------------------------------

    @Test
    fun exportAttachmentWritesExactPlaintextAndFailingSinkReturnsFalse() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val markerText = marker()
        val plaintext = "$markerText export me".toByteArray(Charsets.ISO_8859_1)
        val ref = repo.addAttachment(note.id, plaintext)

        val out = ByteArrayOutputStream()
        assertTrue(repo.exportAttachment(note.id, ref.id, out))
        assertArrayEquals(plaintext, out.toByteArray())

        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("target failed")
        }
        assertFalse(repo.exportAttachment(note.id, ref.id, failing))
        // No plaintext cached anywhere by the failed export.
        assertFalse(containsMarker(listOf(context.filesDir, context.cacheDir), markerText))
        // Unknown ids fail closed.
        assertFalse(repo.exportAttachment(note.id, "unknown", ByteArrayOutputStream()))
        assertFalse(repo.exportAttachment("unknown", ref.id, ByteArrayOutputStream()))
    }

    // ---- backup v2 -----------------------------------------------------------

    @Test
    fun backupV2RestoresAttachmentBytesExactlyAndIsDuplicateSafe() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        repo.saveItem(note.copy(title = "with attachment", body = "body"))
        val markerText = marker()
        val first = ("$markerText A").toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 1, 2, 0x7F)
        val second = ByteArray(4096) { (it * 31).toByte() }
        val refFirst = repo.addAttachment(note.id, first, displayName = "first.bin")
        val refSecond = repo.addAttachment(note.id, second, displayName = "second.bin")

        val exported = ByteArrayOutputStream()
        assertTrue(repo.exportBackup(exported, null))
        val backup = exported.toByteArray()
        // The backup is encrypted: no attachment plaintext/name leaks into the file.
        assertFalse(String(backup, Charsets.ISO_8859_1).contains(markerText))
        assertFalse(String(backup, Charsets.ISO_8859_1).contains("first.bin"))

        // Lose the item (and therefore its refs + blobs), then restore.
        repo.deleteItemForever(note.id)
        assertTrue(repo.items.value.isEmpty())
        assertFalse(repo.blobFile(refFirst.id).exists())

        val report = repo.importBackup(ByteArrayInputStream(backup), null)
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(0, report.skipped)
        assertEquals(2, report.attachmentsImported)
        assertEquals(0, report.attachmentsSkipped)

        val restored = repo.items.value.single()
        assertEquals(listOf(refFirst, refSecond), restored.attachments)
        assertArrayEquals("attachment bytes must be reproduced exactly", first, repo.attachmentBytes(note.id, refFirst.id))
        assertArrayEquals("attachment bytes must be reproduced exactly", second, repo.attachmentBytes(note.id, refSecond.id))

        // Re-importing the same file is a no-op (items and blobs dedupe).
        val duplicate = repo.importBackup(ByteArrayInputStream(backup), null)!!
        assertEquals(0, duplicate.imported)
        assertEquals(0, duplicate.attachmentsImported)
        assertTrue(duplicate.attachmentsSkipped == 0)
        assertArrayEquals(first, repo.attachmentBytes(note.id, refFirst.id))
    }

    @Test
    fun crossVaultBackupRestoresAttachmentsUnderTheLocalVmk() = runBlocking {
        val source = unlockedRepository()
        val sourceNote = source.createItem(ItemKind.NOTE)
        source.saveItem(sourceNote.copy(title = "portable"))
        val markerText = marker()
        val plaintext = "$markerText cross vault".toByteArray(Charsets.ISO_8859_1)
        val sourceRef = source.addAttachment(sourceNote.id, plaintext, displayName = "portable.bin")

        val exported = ByteArrayOutputStream()
        assertTrue(source.exportBackup(exported, "backup password".toCharArray()))
        val backup = exported.toByteArray()

        val target = unlockedRepository()
        assertNull(target.importBackup(ByteArrayInputStream(backup), "wrong password".toCharArray()))
        val report = target.importBackup(ByteArrayInputStream(backup), "backup password".toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(1, report.attachmentsImported)

        val imported = target.items.value.single()
        assertEquals("portable", imported.title)
        val importedRef = imported.attachments.single()
        assertEquals(sourceRef.id, importedRef.id)
        assertEquals(sourceRef.sha256, importedRef.sha256)
        assertArrayEquals(plaintext, target.attachmentBytes(imported.id, importedRef.id))
        // The restored blob was re-encrypted under the target VMK: the source ciphertext is not
        // copied verbatim (AAD/VMK differ), but the plaintext is exact.
        assertTrue(target.blobFile(importedRef.id).isFile)
        assertFalse(String(target.blobFile(importedRef.id).readBytes(), Charsets.ISO_8859_1).contains(markerText))
    }

    @Test
    fun v1BackupFixtureStillImportsWithoutAttachments() = runBlocking {
        val sourceVmk = NoteCrypto.randomVmk()
        try {
            val item = Item(
                id = "legacy-item",
                kind = ItemKind.NOTE,
                title = "legacy title",
                body = "legacy body",
                tags = listOf("old"),
                createdAt = now,
                updatedAt = now,
                version = 5L,
            )
            val v1 = LegacyBackupFixture.writeV1(sourceVmk, "v1 password".toCharArray(), listOf(item))

            val repo = unlockedRepository()
            assertNull("wrong password", repo.importBackup(ByteArrayInputStream(v1), "wrong".toCharArray()))
            val report = repo.importBackup(ByteArrayInputStream(v1), "v1 password".toCharArray())
            assertNotNull("v1 backup must import", report)
            assertEquals(1, report!!.imported)
            assertEquals(0, report.attachmentsImported)
            assertEquals(0, report.attachmentsSkipped)
            val restored = repo.items.value.single()
            assertEquals("legacy title", restored.title)
            assertEquals("legacy body", restored.body)
            assertEquals(listOf("old"), restored.tags)
            assertTrue(restored.attachments.isEmpty())
        } finally {
            NoteCrypto.wipe(sourceVmk)
        }
    }

    @Test
    fun missingBlobRecordSkipsThatAttachmentButKeepsTheNote() = runBlocking {
        val source = unlockedRepository()
        val note = source.createItem(ItemKind.NOTE)
        source.saveItem(note.copy(title = "partial"))
        val ref = source.addAttachment(note.id, "will be missing".toByteArray())
        // Simulate a blob that cannot be read at export time (e.g. storage corruption).
        source.blobFile(ref.id).delete()

        val exported = ByteArrayOutputStream()
        assertTrue("export still succeeds with the ref travelling alone", source.exportBackup(exported, "backup pw".toCharArray()))
        val backup = exported.toByteArray()

        val target = unlockedRepository()
        val report = target.importBackup(ByteArrayInputStream(backup), "backup pw".toCharArray())
        assertNotNull(report)
        assertEquals(1, report!!.imported)
        assertEquals(0, report.attachmentsImported)
        assertEquals(1, report.attachmentsSkipped)

        val restored = target.items.value.single()
        assertEquals("partial", restored.title)
        assertEquals(1, restored.attachments.size)
        assertNull("missing blob is never fabricated", target.attachmentBytes(restored.id, restored.attachments.single().id))
        assertEquals(0, target.attachmentsDirectory.listFiles()?.size ?: 0)
    }

    @Test
    fun exportFailsCleanlyWhenAttachmentBytesExceedTheBackupSizeCap() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        // 7 x 10 MiB = 70 MiB of attachment bytes > 64 MiB MAX_BACKUP_BYTES. The export must
        // fail with no partial output instead of silently dropping attachments to fit.
        val tenMiB = ByteArray(AttachmentLimits.MAX_ATTACHMENT_BYTES.toInt()) { (it % 251).toByte() }
        repeat(7) { index ->
            repo.addAttachment(note.id, tenMiB, displayName = "big-$index.bin")
        }
        val exported = ByteArrayOutputStream()
        assertFalse("over-cap export must fail, never drop attachments", repo.exportBackup(exported, null))
        assertEquals("no partial backup may be written", 0, exported.size())
        // The vault is untouched: every ref and blob still present.
        assertEquals(7, repo.items.value.single().attachments.size)
        assertEquals(7, repo.attachmentsDirectory.listFiles()!!.count { it.name.endsWith(".nzb") })
        assertEquals(7 * AttachmentLimits.MAX_ATTACHMENT_BYTES, repo.items.value.single().attachments.sumOf { it.sizeBytes })
    }

    @Test
    fun attachmentPlaintextNeverAppearsInAnyStorageFile() = runBlocking {
        val repo = unlockedRepository()
        val note = repo.createItem(ItemKind.NOTE)
        val markerText = marker()
        val ref = repo.addAttachment(note.id, "$markerText only in memory".toByteArray(Charsets.ISO_8859_1))
        val exported = ByteArrayOutputStream()
        repo.exportBackup(exported, null)
        repo.lock()

        val roots = listOf(repo.attachmentsDirectory, context.filesDir, context.cacheDir)
        assertFalse("plaintext marker found in app storage", containsMarker(roots, markerText))
        // The attachment file is ciphertext + header only.
        val raw = repo.blobFile(ref.id).readBytes()
        assertEquals(AttachmentBlob.MAGIC.size + 1 + NoteCrypto.GCM_IV_LENGTH_BYTES + "$markerText only in memory".length + NoteCrypto.GCM_TAG_LENGTH_BYTES, raw.size)
    }

    private companion object {
        const val PASSWORD = "attachment vault password"
    }
}
