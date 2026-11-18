package com.cipherwell.notezero.book

/**
 * Read-only content of a reflowable book (EPUB, FB2 or TXT).
 *
 * Implementations are produced only by [EpubParser], [Fb2BookParser] and [TxtBookParser] (or
 * through [BookLoader]). The same lifecycle rules apply to every format: parsed values
 * ([title], [author], [language], [chapters], [toc]) stay readable forever; byte state
 * ([cover], [imageBytes]) is dropped by [close], after which those reads return null.
 * [imageBytes] always returns a fresh caller-owned array; [close] is idempotent and calls are
 * safe from different threads.
 */
interface BookContent {
    val title: String
    val author: String?
    val language: String?
    val chapters: List<BookChapter>
    val toc: List<BookTocEntry>
    val cover: ByteArray?
    fun imageBytes(entry: String): ByteArray?
    fun close()
}

/**
 * Formats the engine can detect and open. [mimeType] is the value the app stores on the
 * encrypted attachment at import time; detection never trusts the extension.
 */
enum class BookFormat(val mimeType: String) {
    EPUB("application/epub+zip"),
    FB2("application/x-fictionbook+xml"),
    TXT("text/plain"),
    CBZ("application/vnd.comicbook+zip"),
}
