package com.cipherwell.notezero.model

/**
 * The kind of item stored in the vault. A single item model carries both notes and
 * code files (decision 2026-11-15): the storage envelope and folder tree are shared,
 * and the UI decides how to render/edit based on [ItemKind].
 */
enum class ItemKind {
    /** Markdown note rendered with the block preview. */
    NOTE,

    /** Plain source file opened in the code editor. */
    CODE_FILE,

    /**
     * PDF document. Created as a blank, valid PDF stored as an encrypted
     * `application/pdf` attachment on the item (same NZAT pipeline as images); opened in the
     * PDF viewer and exportable through the explicit decrypted-copy path.
     */
    PDF_FILE,

    /**
     * E-book (EPUB 2/3) stored as an encrypted `application/epub+zip` attachment. The EPUB is
     * parsed in memory when opened; [Item.readingState] keeps the encrypted position/bookmarks.
     * Enumeration order is frozen (payload ordinals), append new kinds only.
     */
    BOOK_FILE,

    /**
     * Standalone hand-drawn sketch (`ItemCodec` payload v6). [Item.sketch] stores the editable
     * encrypted vector document; a flattened PNG preview is an encrypted image attachment
     * rendered in memory. Opened in the drawing studio from the Library's Drawings shelf.
     * Enumeration order is frozen (payload ordinals), append new kinds only.
     */
    SKETCH_FILE,
}

/**
 * Reference to one encrypted attachment of an item.
 *
 * The bytes themselves live outside Room in `filesDir/attachments/<id>.nzb` (see
 * docs/ATTACHMENTS.md); this ref is content and therefore encrypted **inside** the item
 * envelope (`ItemCodec` payload v2). [displayName]/[mimeType]/[sizeBytes] are user data:
 * never log them, never use [displayName] as a file name.
 *
 * [id] is a random UUID generated at import time and never derived from the content.
 * [sha256] is the lowercase hex SHA-256 of the *plaintext* bytes, verified on every read;
 * a mismatch means the blob was corrupted/tampered with and is never surfaced.
 */
data class AttachmentRef(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val sha256: String,
)

/**
 * A decrypted vault item. This type must never be written to disk in plaintext:
 * persistence happens through the encrypted item envelope (see data/ItemCodec).
 *
 * [id] is a random UUID generated at creation and never derived from content.
 * [createdAt]/[updatedAt] are epoch millis.
 *
 * [attachments] is repository-owned metadata: it is added by `importAttachment` and
 * removed by `deleteAttachment`; `saveItem` preserves the stored list, so an editor
 * autosave with a stale copy can never add or drop an attachment ref.
 *
 * [locked] is the per-note lock flag (decision #15, SPEC "lock note"). It is **content**:
 * it lives inside the AES-GCM item envelope (`ItemCodec` payload v3) and never in a
 * plaintext Room column. Locking is a *visibility gate*, not a second encryption layer:
 * the ciphertext stays sealed under the same VMK and the same AAD, so locking/unlocking
 * never re-encrypts or rotates anything. The repository owns the flag exactly like
 * [attachments]: only `setItemLocked` changes it, `saveItem` preserves the stored value.
 * The UI masks the content while the id is absent from `verifiedLockedIds`.
 */
data class Item(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val folderId: String? = null,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val trashed: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Incremented on every successful save; used for autosave conflict detection. */
    val version: Long = 0L,
    val attachments: List<AttachmentRef> = emptyList(),
    /** Locked note: text/tags/preview stay hidden until the session re-authenticates. */
    val locked: Boolean = false,
    /**
     * Encrypted reading position/bookmarks for documents and books (`ItemCodec` payload v4,
     * repository-owned like [attachments]). `null` for items that have never been read. Only
     * `saveReadingState` changes it; `saveItem` preserves the stored value so a stale UI copy
     * cannot roll the reader back.
     */
    val readingState: ReadingState? = null,
    /**
     * Encrypted PDF annotation layer (`ItemCodec` payload v5, repository-owned like
     * [readingState]). `null` when the document has no annotations. Only
     * `savePdfAnnotations` changes it; `saveItem` preserves the stored value.
     */
    val pdfAnnotations: PdfAnnotations? = null,
    /**
     * Encrypted standalone sketch document (`ItemCodec` payload v6, repository-owned like
     * [pdfAnnotations]). `null` for non-sketch items / unread sketches; only `saveSketch`
     * changes it and `saveItem` preserves the stored value.
     */
    val sketch: SketchDocument? = null,
) {
    val isBlank: Boolean get() = title.isBlank() && body.isBlank()
}

/**
 * A decrypted folder in the single hierarchical tree shared by notes and code files.
 *
 * [locked] is the per-folder lock flag, stored inside the encrypted folder envelope
 * (`FolderCodec` payload v2) — never in a plaintext column. A locked folder is hidden
 * (with its descendants) until the session re-verifies; like the note flag it is a
 * visibility gate over content that stays VMK-encrypted.
 */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val locked: Boolean = false,
)

/** User-selectable theme mode; persisted in vault preferences (non-secret). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
