package com.cipherwell.notezero.book

/**
 * [BookContent] backed only by parsed Kotlin values and bounded in-memory byte arrays.
 *
 * Used by the TXT and FB2 parsers: unlike EPUB there is no archive to keep, but both formats
 * may carry images (FB2 base64 `<binary>`), so the same lifecycle contract is implemented
 * here: [cover] and [imageBytes] return fresh copies and become null after [close]; [close] is
 * idempotent and safe concurrently with lazy reads.
 */
internal class InMemoryDocument(
    override val title: String,
    override val author: String?,
    override val language: String?,
    override val chapters: List<BookChapter>,
    override val toc: List<BookTocEntry>,
    coverBytes: ByteArray?,
    images: Map<String, ByteArray>,
) : BookContent {

    @Volatile
    private var imagesState: Map<String, ByteArray>? = if (images.isEmpty()) null else images

    @Volatile
    private var coverState: ByteArray? = coverBytes

    @Volatile
    private var closed = false

    override val cover: ByteArray?
        get() {
            if (closed) return null
            return coverState?.copyOf()
        }

    override fun imageBytes(entry: String): ByteArray? {
        if (closed) return null
        val images = imagesState ?: return null
        val direct = images[entry]
        if (direct != null) return direct.copyOf()
        // FB2 references carry their id with a leading '#' in `l:href`; tolerate both spellings.
        if (entry.startsWith("#")) {
            return images[entry.substring(1)]?.copyOf()
        }
        return null
    }

    override fun close() {
        closed = true
        imagesState = null
        coverState = null
    }
}
