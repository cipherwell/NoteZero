package com.cipherwell.notezero.ui.attachments

import com.cipherwell.notezero.data.AttachmentError
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.AttachmentLimits
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Attachment-aware test double: delegates the whole non-attachment surface to
 * [FakeNoteRepository] and emulates the encrypted attachment engine in memory, including
 * the observable contract the UI depends on (refs live on `Item.attachments`, bytes come
 * back only while unlocked, external-activity bracketing is recorded in order).
 *
 * Test-only file: lives under src/test, never shipped.
 */
class FakeAttachmentRepository(
    private val base: FakeNoteRepository = FakeNoteRepository(),
) : NoteRepository by base {

    // ---- Bracket bookkeeping (ordered, so tests can assert begin/end exactly once) ----

    val externalActivityEvents: MutableList<String> = mutableListOf()
    var beginExternalActivityCount: Int = 0
        private set
    var endExternalActivityCount: Int = 0
        private set

    // ---- Call counters ----

    var importCalls: Int = 0
        private set
    var attachmentBytesCalls: Int = 0
        private set
    var exportCalls: Int = 0
        private set
    var deleteCalls: Int = 0
        private set

    /** Forced engine failures (simulate the data layer's own limit checks). */
    var forcedImportError: AttachmentError? = null

    /** When true, [attachmentBytes] returns null without touching the blob map. */
    var forceMissingBytes: Boolean = false

    var exportResult: Boolean = true
    var deleteResult: Boolean = true

    private val blobs = mutableMapOf<Pair<String, String>, ByteArray>()
    private var attachmentSequence = 0

    // ---- Test helpers -------------------------------------------------------

    fun setItems(items: List<Item>) = base.setItems(items)

    fun item(id: String): Item? = base.itemById(id)

    fun setStatus(status: VaultStatus) = base.setStatus(status)

    /** Seeds a note with an optional body so the editor renders it. */
    fun seedNote(
        id: String = "n1",
        attachments: List<AttachmentRef> = emptyList(),
        body: String = "body",
        title: String = "Document",
    ): Item {
        val item = Item(
            id = id,
            kind = ItemKind.NOTE,
            title = title,
            body = body,
            attachments = attachments,
            createdAt = base.nextClock(),
            updatedAt = base.nextClock(),
        )
        base.setItems(base.items.value.filterNot { it.id == id } + item)
        return item
    }

    /** Registers an attachment ref + its plaintext bytes in one step. */
    fun seedAttachment(
        noteId: String = "n1",
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef {
        attachmentSequence += 1
        val ref = AttachmentRef(
            id = "att-$attachmentSequence",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = base.nextClock(),
            sha256 = sha256Hex(bytes),
        )
        if (base.itemById(noteId) == null) seedNote(id = noteId)
        base.setItems(
            base.items.value.map {
                if (it.id == noteId) it.copy(attachments = it.attachments + ref) else it
            },
        )
        blobs[noteId to ref.id] = bytes
        return ref
    }

    fun blob(noteId: String, attachmentId: String): ByteArray? = blobs[noteId to attachmentId]

    // ---- Attachment engine --------------------------------------------------

    override fun beginExternalActivity() {
        beginExternalActivityCount += 1
        externalActivityEvents += "begin"
    }

    override fun endExternalActivity() {
        endExternalActivityCount += 1
        externalActivityEvents += "end"
    }

    override suspend fun importAttachment(
        noteId: String,
        displayName: String,
        mimeType: String,
        source: InputStream,
    ): AttachmentImport {
        importCalls += 1
        if (status.value != VaultStatus.UNLOCKED) {
            return AttachmentImport.Failure(AttachmentError.LOCKED)
        }
        forcedImportError?.let { return AttachmentImport.Failure(it) }
        val item = base.itemById(noteId)
            ?: return AttachmentImport.Failure(AttachmentError.NOTE_NOT_FOUND)
        if (item.attachments.size >= AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE) {
            return AttachmentImport.Failure(AttachmentError.QUOTA_EXCEEDED)
        }
        val bytes = source.use { it.readBytes() }
        if (bytes.size > AttachmentLimits.MAX_ATTACHMENT_BYTES) {
            return AttachmentImport.Failure(AttachmentError.TOO_LARGE)
        }
        attachmentSequence += 1
        val ref = AttachmentRef(
            id = "att-$attachmentSequence",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = base.nextClock(),
            sha256 = sha256Hex(bytes),
        )
        blobs[noteId to ref.id] = bytes
        base.setItems(
            base.items.value.map {
                if (it.id == noteId) it.copy(attachments = it.attachments + ref) else it
            },
        )
        return AttachmentImport.Success(ref)
    }

    override suspend fun attachmentBytes(noteId: String, attachmentId: String): ByteArray? {
        attachmentBytesCalls += 1
        if (status.value != VaultStatus.UNLOCKED) return null
        if (forceMissingBytes) return null
        return blobs[noteId to attachmentId]
    }

    override suspend fun exportAttachment(
        noteId: String,
        attachmentId: String,
        out: OutputStream,
    ): Boolean {
        exportCalls += 1
        if (status.value != VaultStatus.UNLOCKED) return false
        if (!exportResult) return false
        val bytes = blobs[noteId to attachmentId] ?: return false
        out.write(bytes)
        out.flush()
        return true
    }

    override suspend fun deleteAttachment(noteId: String, attachmentId: String): Boolean {
        deleteCalls += 1
        if (status.value != VaultStatus.UNLOCKED) return false
        if (!deleteResult) return false
        val existed = blobs.remove(noteId to attachmentId) != null
        base.setItems(
            base.items.value.map {
                if (it.id == noteId) {
                    it.copy(attachments = it.attachments.filterNot { ref -> ref.id == attachmentId })
                } else {
                    it
                }
            },
        )
        return existed
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Builds an [AttachmentSource] while counting how often its stream is opened. */
class CountingAttachmentSource(
    private val bytes: ByteArray,
    overrideDisplayName: String = "photo.png",
    mimeType: String = "image/png",
    declaredSizeBytes: Long? = bytes.size.toLong(),
) {
    var openCount: Int = 0
        private set

    /** When set, [open] returns null (simulating an expired content grant). */
    var failToOpen: Boolean = false

    /** When set, [open] throws (simulating a provider/IO error). */
    var throwOnOpen: Boolean = false

    val source: AttachmentSource = AttachmentSource(
        displayName = overrideDisplayName,
        mimeType = mimeType,
        declaredSizeBytes = declaredSizeBytes,
        open = {
            openCount += 1
            when {
                throwOnOpen -> throw java.io.IOException("provider failure")
                failToOpen -> null
                else -> bytes.inputStream()
            }
        },
    )
}
