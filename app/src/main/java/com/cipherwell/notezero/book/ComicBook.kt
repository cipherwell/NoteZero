package com.cipherwell.notezero.book

/**
 * A fixed-page comic (CBZ): page images are decoded lazily, one bounded entry at a time, so
 * opening a large archive does not decode every page up front.
 *
 * [pageBytes] returns a fresh caller-owned copy (or null for a missing/corrupt/oversized page,
 * an out-of-range index, or after [close]). [close] is idempotent; [title] and [pageCount] stay
 * readable afterwards so the UI can keep its progress state, but no page bytes are served.
 */
interface ComicBook {
    val title: String
    val pageCount: Int
    fun pageBytes(index: Int): ByteArray?
    fun close()
}

/** What [BookLoader] returns: reflowable text content or a fixed-page comic. */
sealed interface LoadedBook {
    data class Reflowable(val content: BookContent, val format: BookFormat) : LoadedBook
    data class Comic(val comic: ComicBook, val format: BookFormat) : LoadedBook
}
