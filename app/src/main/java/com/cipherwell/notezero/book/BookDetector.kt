package com.cipherwell.notezero.book

/**
 * Content-based format detection for imported book files. Extensions and stored MIME types are
 * never trusted; the decrypted bytes are inspected in this order:
 *
 * 1. **EPUB** — a ZIP carrying an `application/epub+zip` `mimetype` entry and/or
 *    `META-INF/container.xml`;
 * 2. **CBZ** — a ZIP that is not an EPUB and contains at least one image entry;
 * 3. **FB2** — well-formed-looking XML whose root element is `FictionBook` (namespace
 *    tolerant); the parser does the full validation;
 * 4. **TXT** — decodable text with no NUL bytes or control-character spam (BOM-aware UTF-8 /
 *    UTF-16LE / UTF-16BE, windows-1252 fallback).
 *
 * Anything else (random data, truncated archives, empty input) returns null. This function
 * never throws and allocates only the bounded structures the individual probes use.
 */
object BookDetector {

    fun detect(bytes: ByteArray): BookFormat? {
        return try {
            detectInternal(bytes)
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectInternal(bytes: ByteArray): BookFormat? {
        if (bytes.isEmpty()) return null
        if (isZipSignature(bytes)) {
            val archive = ZipArchive.open(bytes) ?: return null
            if (CbzParser.isEpubArchive(archive)) return BookFormat.EPUB
            if (archive.names().any { CbzParser.isPageName(it) }) return BookFormat.CBZ
            // A ZIP without EPUB markers or images is not a book format the engine supports.
            return null
        }
        if (XmlRootProbe.rootLocalName(bytes) == "FictionBook") return BookFormat.FB2
        if (TxtDecoder.looksLikeText(bytes)) return BookFormat.TXT
        return null
    }

    private fun isZipSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0].toInt() != 'P'.code || bytes[1].toInt() != 'K'.code) return false
        val third = bytes[2].toInt() and 0xFF
        val fourth = bytes[3].toInt() and 0xFF
        return (third == 0x03 && fourth == 0x04) || // local file header (normal archive)
            (third == 0x05 && fourth == 0x06) || // empty archive
            (third == 0x07 && fourth == 0x08) // spanned marker; ZipArchive rejects it
    }
}
