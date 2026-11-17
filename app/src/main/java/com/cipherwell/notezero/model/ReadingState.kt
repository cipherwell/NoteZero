package com.cipherwell.notezero.model

/**
 * One bookmark inside a document or book.
 *
 * [position] is the chapter index for e-books and the page index (0-based) for PDFs. [label]
 * is user content (encrypted, never logged, never written to a plaintext column).
 */
data class ReadingBookmark(
    val position: Int,
    val label: String,
    val createdAt: Long,
)

/**
 * Encrypted per-document reading state (payload v4 of `ItemCodec`).
 *
 * Kept inside the item envelope, so progress, bookmarks and book metadata are encrypted with
 * the same AES-256-GCM key/AAD as the content and travel with encrypted backups. It is
 * repository-owned: `saveItem` preserves the stored value; only `saveReadingState` changes it.
 *
 * Bounds are enforced on encode and decode so a corrupt/hostile payload cannot allocate or
 * surface unbounded data: bookmarks ≤ [MAX_BOOKMARKS], labels ≤ [MAX_LABEL_BYTES] UTF-8 bytes,
 * author/language ≤ [MAX_METADATA_BYTES] UTF-8 bytes each, [position] ≥ 0, [fraction] in
 * 0.0..1.0 (NaN/infinity rejected).
 */
data class ReadingState(
    /** Chapter (book) or page (PDF) index, 0-based. */
    val position: Int = 0,
    /** Fraction read within the current chapter/page, 0.0..1.0 (used for progress %). */
    val fraction: Float = 0f,
    /** Total chapters (books) or pages (PDFs); 0 when not known yet (imported, not opened). */
    val total: Int = 0,
    val bookmarks: List<ReadingBookmark> = emptyList(),
    /** Book author from EPUB metadata (null for PDFs / unknown). */
    val author: String? = null,
    /** Book language tag from EPUB metadata (null for PDFs / unknown). */
    val language: String? = null,
) {
    init {
        require(position >= 0) { "position must be >= 0" }
        require(fraction.isFinite() && fraction in 0f..1f) { "fraction must be 0..1" }
        require(total in 0..MAX_POSITION) { "total out of range" }
        require(bookmarks.size <= MAX_BOOKMARKS) { "too many bookmarks" }
        bookmarks.forEach { bookmark ->
            require(bookmark.position >= 0) { "bookmark position must be >= 0" }
            require(bookmark.label.toByteArray(Charsets.UTF_8).size <= MAX_LABEL_BYTES) {
                "bookmark label exceeds limit"
            }
            require(bookmark.createdAt >= 0L) { "bookmark createdAt must be >= 0" }
        }
        requireUtf8Bounded(author)
        requireUtf8Bounded(language)
    }

    private fun requireUtf8Bounded(value: String?) {
        if (value == null) return
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) {
            "metadata exceeds limit"
        }
    }

    companion object {
        const val MAX_BOOKMARKS: Int = 500
        const val MAX_LABEL_BYTES: Int = 1024
        const val MAX_METADATA_BYTES: Int = 1024
        const val MAX_POSITION: Int = 1_000_000
    }
}
