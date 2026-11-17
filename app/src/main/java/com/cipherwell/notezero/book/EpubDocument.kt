package com.cipherwell.notezero.book

/**
 * A parsed, in-memory EPUB book. Produced only by [EpubParser.parse]; UI code compiles
 * against this type and never sees the archive.
 *
 * Resource lifecycle: the document keeps the compressed archive bytes and the cover image
 * until [close]. [imageBytes] decompresses a referenced image on demand (bounded, no cache),
 * so opening a large illustrated book does not decode every image up front. After [close],
 * [cover] and [imageBytes] return null and the byte state is dropped; [chapters] / [toc] stay
 * readable because they are already parsed plain Kotlin values.
 *
 * Threading: [close] and the lazy image reads are safe to call from different threads; the
 * returned arrays are always fresh copies owned by the caller.
 */
class EpubDocument internal constructor(
    val title: String,
    val author: String?,
    val language: String?,
    val chapters: List<BookChapter>,
    val toc: List<BookTocEntry>,
    private val archive: ZipArchive?,
    coverBytes: ByteArray?,
    private val imageEntries: Set<String>,
) {

    @Volatile
    private var archiveState: ZipArchive? = archive

    @Volatile
    private var coverState: ByteArray? = coverBytes

    @Volatile
    private var closed = false

    /** Cover image bytes (a copy), or null when absent, over the cover cap, or after close. */
    val cover: ByteArray?
        get() {
            if (closed) return null
            return coverState?.copyOf()
        }

    /**
     * Image bytes for a [BookBlock.Image.entry] produced by the parser. Returns a caller-owned
     * copy, or null when the entry is unknown/not referenced by a parsed image block, missing,
     * over the 16 MiB entry cap, or after [close].
     */
    fun imageBytes(entry: String): ByteArray? {
        if (closed) return null
        val archive = archiveState ?: return null
        if (entry !in imageEntries) return null
        val bytes = archive.read(entry) ?: return null
        return if (closed) null else bytes
    }

    /** Drops the archive and cover byte state. Idempotent. */
    fun close() {
        closed = true
        archiveState = null
        coverState = null
    }
}
