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

    // ---- additional reflowable formats (TXT / FictionBook FB2) -------------------------

    /** Chapters parsed for TXT/FB2 (2,000; matches the phase-13 contract). */
    const val MAX_REFOWABLE_CHAPTERS: Int = 2_000

    /**
     * Total blocks across one TXT/FB2 book (200,000). The per-chapter cap alone would allow
     * 2,000 x 20,000 blocks, which is far beyond a phone reader's memory; this is the
     * whole-book bound behind that cap.
     */
    const val MAX_TOTAL_BLOCKS: Int = 200_000

    /** Total searchable characters across one TXT/FB2 book (8,000,000). */
    const val MAX_TOTAL_TEXT_CHARS: Int = 8_000_000

    /** TXT chapter part length: a new "Part N" starts at the next paragraph boundary (20,000). */
    const val TXT_PART_TARGET_CHARS: Int = 20_000

    /** Largest FB2 document handed to the parser (32 MiB; larger input is unsupported). */
    const val MAX_FB2_XML_BYTES: Int = 32 * 1024 * 1024

    /** FB2 `<binary>` images kept per book (64). */
    const val MAX_FB2_IMAGES: Int = 64

    /** Decoded bytes per FB2 image (4 MiB, same bound as an EPUB cover). */
    const val MAX_FB2_IMAGE_BYTES: Int = 4 * 1024 * 1024

    /** Total decoded FB2 image bytes kept per book (32 MiB). */
    const val MAX_FB2_IMAGE_TOTAL_BYTES: Int = 32 * 1024 * 1024

    /** Base64 characters collected from one `<binary>` element (6,000,000 ~= 4.5 MiB decoded). */
    const val MAX_FB2_BASE64_CHARS: Int = 6_000_000

    /** Pages exposed by a CBZ archive (500; extra image entries are ignored). */
    const val MAX_COMIC_PAGES: Int = 500

    /** Characters decoded from a TXT file; the remainder is not read (8,000,000). */
    const val MAX_TXT_DECODED_CHARS: Int = MAX_TOTAL_TEXT_CHARS
}
