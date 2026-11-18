package com.cipherwell.notezero.book

/**
 * CBZ (comic archive) parser: a ZIP of page images, read lazily from memory.
 *
 * Pages are the archive entries with a `jpg`/`jpeg`/`png`/`webp`/`gif` extension, ordered by
 * [NaturalOrder] (numeric page names sort correctly, `page2` before `page10`). The archive
 * itself is opened by the bounded [ZipArchive] reader, so the EPUB limits apply on top of the
 * comic caps: at most [BookLimits.MAX_ENTRIES] central-directory entries, no ZIP64/multi-disk/
 * encrypted entries, at most [BookLimits.MAX_ENTRY_BYTES] per decompressed entry and
 * [BookLimits.MAX_TOTAL_UNCOMPRESSED_BYTES] declared in total. Pages over the entry cap are
 * absent from the page list; the first [BookLimits.MAX_COMIC_PAGES] pages are exposed.
 *
 * `pageBytes` inflates that one entry on demand and returns a fresh copy, so opening a 500-page
 * comic does not decode every page up front. No metadata (`ComicInfo.xml`) is parsed: the title
 * is the app-provided fallback. Never throws; a corrupt/empty/non-CBZ archive returns null.
 */
object CbzParser {

    private val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun parse(bytes: ByteArray, fallbackTitle: String): ComicBook? {
        return try {
            if (bytes.isEmpty()) return null
            val archive = ZipArchive.open(bytes) ?: return null
            // An EPUB that also happens to contain images stays an EPUB.
            if (isEpubArchive(archive)) return null
            val pages = archive.names()
                .filter { isPageName(it) }
                .sortedWith(NaturalOrder)
                .take(BookLimits.MAX_COMIC_PAGES)
            if (pages.isEmpty()) return null
            CbzComic(
                title = BookText.fallbackTitle(fallbackTitle),
                pages = pages,
                archive = archive,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** True for archive entry names that look like page images (case-insensitive extension). */
    internal fun isPageName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in PAGE_EXTENSIONS
    }

    /** True when the archive carries the EPUB markers (`mimetype` and/or OCF container). */
    internal fun isEpubArchive(archive: ZipArchive): Boolean {
        if (archive.contains("META-INF/container.xml")) return true
        if (!archive.contains("mimetype")) return false
        val mimetype = archive.read("mimetype", 256) ?: return false
        return String(mimetype, Charsets.ISO_8859_1).trim() == EpubParser.MIME
    }
}
