package com.cipherwell.notezero.ui.pdf

import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream

/**
 * Stores a page-tools copy as a **new encrypted PDF item** in the vault.
 *
 * Returns the new item's id, or null on any failure. The bytes never leave memory: the default
 * implementation hands them to [NoteRepository.importAttachment], which encrypts them into the
 * vault exactly like any other PDF attachment. No plaintext file, URI or cache is created by
 * this saver, nothing is logged, and a failed import never leaves an orphan row/blob behind
 * (the freshly created item is trashed before returning null).
 *
 * Injectable so screen tests can assert the save wiring without real repository encryption.
 */
fun interface PdfPageCopySaver {
    /**
     * Creates the copy of [source] (same folder) titled [displayName] from the flattened
     * [bytes]. Returns the new item id, or null when nothing was stored.
     */
    suspend fun save(
        repository: NoteRepository,
        source: Item,
        displayName: String,
        bytes: ByteArray,
    ): String?
}

/**
 * Default [PdfPageCopySaver]: create a [ItemKind.PDF_FILE] item in the source item's folder,
 * title it with the (already localized/suffixed and bounded) copy name, then import the bytes as
 * the item's `application/pdf` attachment through the normal encrypted pipeline.
 *
 * The copy name is built by the caller with [pageCopyFileName] so the localized
 * `pdf_pages_copy_suffix` string stays in the Compose layer (this class has no `Context`); here
 * the name is only re-sanitised and re-bounded so a hostile caller cannot smuggle a path or an
 * unbounded name into the item envelope.
 */
class DefaultPdfPageCopySaver : PdfPageCopySaver {

    override suspend fun save(
        repository: NoteRepository,
        source: Item,
        displayName: String,
        bytes: ByteArray,
    ): String? {
        if (bytes.isEmpty()) return null
        if (repository.status.value != VaultStatus.UNLOCKED) return null
        val name = boundedPageCopyName(displayName) ?: return null
        var createdId: String? = null
        return try {
            val created = repository.createItem(ItemKind.PDF_FILE, source.folderId)
            createdId = created.id
            repository.saveItem(created.copy(title = name))
            val imported = repository.importAttachment(
                noteId = created.id,
                displayName = name,
                mimeType = BlankPdf.MIME_TYPE,
                source = ByteArrayInputStream(bytes),
            )
            when (imported) {
                is AttachmentImport.Success -> created.id
                is AttachmentImport.Failure -> {
                    trashQuietly(repository, created.id)
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Fail closed: no row may survive without its encrypted attachment blob.
            createdId?.let { trashQuietly(repository, it) }
            null
        }
    }

    /** Trashing the half-created row is best effort; the save result is already a failure. */
    private suspend fun trashQuietly(repository: NoteRepository, id: String) {
        runCatching { repository.trashItem(id) }
    }
}

/** Display-name character ceiling for one page-tools copy (well inside the codec's name bounds). */
internal const val MAX_PAGE_COPY_NAME_LENGTH: Int = 96

/**
 * Builds the copy's display name: the source label's base ([pdfDisplayBaseName] strips/adjusts
 * the `.pdf` extension) + the localized `pdf_pages_copy_suffix` + `.pdf`, bounded to
 * [MAX_PAGE_COPY_NAME_LENGTH] characters, e.g. `report (pages).pdf`.
 *
 * The name is display data only (the attachment pipeline stores the bytes, never a file path),
 * so no path-separator sanitisation is needed beyond the base-name trim; [boundedPageCopyName]
 * still re-sanitises defensively for callers that pass their own string.
 */
internal fun pageCopyFileName(sourceLabel: String, suffix: String): String {
    // The localized `pdf_pages_copy_suffix` resource loses its leading space in the resource
    // table (aapt trims leading/trailing whitespace), so the separator is built here: one space
    // between the base and the suffix, never a run of them.
    val trimmedSuffix = suffix.replace('/', ' ').replace('\\', ' ').trim()
    val suffixPart = if (trimmedSuffix.isEmpty()) "" else " $trimmedSuffix"
    val maxBase = (MAX_PAGE_COPY_NAME_LENGTH - suffixPart.length - PDF_EXTENSION.length)
        .coerceAtLeast(1)
    return pdfDisplayBaseName(sourceLabel, maxBase) + suffixPart + PDF_EXTENSION
}

/**
 * Re-sanitises a caller-provided copy name: trims, replaces control characters and path
 * separators with spaces, appends `.pdf` when missing and bounds the result. Returns null for a
 * blank name.
 */
internal fun boundedPageCopyName(candidate: String): String? {
    val sanitised = candidate
        .map { character ->
            when {
                character == '/' || character == '\\' -> ' '
                character.code < 0x20 -> ' '
                else -> character
            }
        }
        .joinToString("")
        .trim()
    if (sanitised.isEmpty()) return null
    val capped = sanitised.take(MAX_PAGE_COPY_NAME_LENGTH)
    val withExtension = if (capped.endsWith(PDF_EXTENSION, ignoreCase = true)) {
        capped
    } else {
        val maxBase = (MAX_PAGE_COPY_NAME_LENGTH - PDF_EXTENSION.length).coerceAtLeast(1)
        capped.take(maxBase) + PDF_EXTENSION
    }
    return withExtension.ifBlank { null }
}
