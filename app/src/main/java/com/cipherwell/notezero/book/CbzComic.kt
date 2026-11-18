package com.cipherwell.notezero.book

/**
 * A parsed CBZ comic. Produced only by [CbzParser.parse].
 *
 * The retained state is the compressed archive plus the natural-sorted page-name list; page
 * images are inflated one at a time by [pageBytes] and returned as caller-owned copies. After
 * [close] the archive is dropped and [pageBytes] returns null; [title] and [pageCount] keep
 * working (the UI can restore its reading position). [close] is idempotent and safe to call
 * from another thread while pages are being read.
 */
internal class CbzComic(
    override val title: String,
    private val pages: List<String>,
    archive: ZipArchive,
) : ComicBook {

    @Volatile
    private var archiveState: ZipArchive? = archive

    override val pageCount: Int get() = pages.size

    override fun pageBytes(index: Int): ByteArray? {
        if (index < 0 || index >= pages.size) return null
        val current = archiveState ?: return null
        return current.read(pages[index])
    }

    override fun close() {
        archiveState = null
    }
}
