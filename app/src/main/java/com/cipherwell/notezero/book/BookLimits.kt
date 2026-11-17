package com.cipherwell.notezero.book

/**
 * Hard bounds for the EPUB engine. Every bound is enforced before or while bytes are
 * produced, so an untrusted EPUB cannot exhaust memory/CPU or smuggle oversized payloads
 * into the UI. The values are intentionally conservative for an offline phone reader.
 */
internal object BookLimits {

    /** Total declared uncompressed size of every entry in the archive (128 MiB). */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 128L * 1024L * 1024L

    /** Single decompressed entry (16 MiB). Chapters/images above this are unavailable. */
    const val MAX_ENTRY_BYTES: Int = 16 * 1024 * 1024

    /** Entries in the ZIP central directory (10,000). */
    const val MAX_ENTRIES: Int = 10_000

    /** Cover image bytes kept by the document (4 MiB). */
    const val MAX_COVER_BYTES: Int = 4 * 1024 * 1024

    /** Parsed blocks per chapter (20,000). */
    const val MAX_BLOCKS_PER_CHAPTER: Int = 20_000

    /** Characters of searchable text per chapter (2,000,000). */
    const val MAX_TEXT_PER_CHAPTER: Int = 2_000_000

    /** Deepest list nesting that maps to [BookListItem.depth]. */
    const val MAX_LIST_DEPTH: Int = 8

    /** Maximum element nesting the XHTML walker maintains frames for (hardening). */
    const val MAX_ELEMENT_DEPTH: Int = 256

    /** Chapter title derived from a heading, truncated to this many characters. */
    const val MAX_DERIVED_TITLE_CHARS: Int = 120

    /** Metadata/container/OPF/NCX/nav XML documents are small; reject anything larger. */
    const val MAX_XML_BYTES: Int = 4 * 1024 * 1024

    /** `META-INF/container.xml` specific bound (256 KiB). */
    const val MAX_CONTAINER_XML_BYTES: Int = 256 * 1024

    /** Number of spine items parsed into chapters (defensive; central directory is capped too). */
    const val MAX_CHAPTERS: Int = 5_000
}
