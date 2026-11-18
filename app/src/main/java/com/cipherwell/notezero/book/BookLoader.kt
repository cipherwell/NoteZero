package com.cipherwell.notezero.book

/**
 * One entry point for opening any supported book file.
 *
 * [load] detects the format with [BookDetector] and dispatches to the matching parser:
 * [EpubParser] (EPUB 2/3), [Fb2BookParser] (FictionBook 2), [TxtBookParser] (plain text,
 * reflowable) or [CbzParser] (comic, [LoadedBook.Comic]). A null result means
 * unsupported/corrupt/DRM-protected input and is never an exception. `fallbackTitle` (the
 * item/file display name) is used by the formats that have no reliable title metadata.
 */
object BookLoader {

    fun load(bytes: ByteArray, fallbackTitle: String): LoadedBook? {
        return try {
            when (BookDetector.detect(bytes)) {
                BookFormat.EPUB ->
                    EpubParser.parse(bytes)?.let { LoadedBook.Reflowable(it, BookFormat.EPUB) }
                BookFormat.FB2 ->
                    Fb2BookParser.parse(bytes, fallbackTitle)?.let { LoadedBook.Reflowable(it, BookFormat.FB2) }
                BookFormat.TXT ->
                    TxtBookParser.parse(bytes, fallbackTitle)?.let { LoadedBook.Reflowable(it, BookFormat.TXT) }
                BookFormat.CBZ ->
                    CbzParser.parse(bytes, fallbackTitle)?.let { LoadedBook.Comic(it, BookFormat.CBZ) }
                null -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
