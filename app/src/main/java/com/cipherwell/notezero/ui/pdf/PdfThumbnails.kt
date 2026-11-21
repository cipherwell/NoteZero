package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Bounded, in-memory page thumbnails of one PDF document.
 *
 * Owned by the caller (the viewer): the bitmaps live only for the viewer session and are
 * recycled with [recycle] when the viewer leaves. This is a **memory** cache only — there is no
 * thumbnail file, database row or other plaintext artifact anywhere (the render file inside
 * [AndroidPdfThumbnailRenderer] is ephemeral and deleted before the set is returned).
 *
 * The set is intentionally tiny and immutable in shape: one small bitmap per rendered page,
 * page indices 0-based exactly like [PdfPageSession]. A missing index (a page beyond the renderer
 * cap, or a failed render) is `null` rather than a broken image, so the UI can fall back to text.
 */
class PdfThumbnails(bitmaps: List<Bitmap>) {

    private val pages: MutableList<Bitmap> = bitmaps.toMutableList()

    /** Number of rendered thumbnails (valid indices are `0 until size`). */
    val size: Int get() = pages.size

    /** True when there is no usable thumbnail at all (failed, empty or corrupt document). */
    val isEmpty: Boolean get() = pages.isEmpty()

    /** The thumbnail of 0-based [page], or `null` when that page is not in the set. */
    fun bitmap(page: Int): Bitmap? = pages.getOrNull(page)

    /**
     * Recycles every bitmap and empties the set. Idempotent: after the first call the set is
     * empty and further calls are no-ops. The instance must not be displayed after this.
     */
    fun recycle() {
        if (pages.isEmpty()) return
        val owned = pages.toList()
        pages.clear()
        owned.forEach { bitmap -> runCatching { bitmap.recycle() } }
    }
}

/**
 * Renders small page thumbnails for a decrypted PDF, off the main thread. Injectable so the
 * viewer can be tested with fake bitmaps in Robolectric (the platform renderer has no native
 * implementation there) and so the real implementation stays replaceable.
 *
 * Contract: never throws for a bad document (returns an empty [PdfThumbnails]), rethrows
 * coroutine cancellation, and never logs or persists anything.
 */
fun interface PdfThumbnailRenderer {
    suspend fun render(context: Context, pdfBytes: ByteArray): PdfThumbnails
}

/**
 * Platform thumbnail renderer (`android.graphics.pdf.PdfRenderer`).
 *
 * `PdfRenderer` needs a **seekable file descriptor**, so the decrypted bytes are written to one
 * ephemeral app-private cache file for the duration of the render and deleted in the `finally`
 * block — exactly the documented trade-off of [AndroidPdfPageSessionFactory] and
 * [AndroidPdfPageRenderer] (DECISIONS #20 / THREAT-MODEL): one file, app-private cache,
 * short-lived, no plaintext PDF survives. The file
 * name is random per render so two concurrent renders can never share it.
 *
 * Resource bounds: at most [maxPages] pages are rasterised, each on a white ARGB_8888 bitmap with
 * a long edge of at most [longEdgePx] (never upscaled beyond the PDF's own page size), so a
 * hostile document cannot exhaust the heap (~5.8 MiB for the 100 × A4 default). Any failure —
 * corrupt/empty file, missing native renderer, OOM — fails **closed** with an empty set and every
 * already-allocated bitmap recycled, never a partial/corrupt grid.
 */
class AndroidPdfThumbnailRenderer(
    private val maxPages: Int = MAX_PAGES,
    private val longEdgePx: Int = LONG_EDGE_PX,
) : PdfThumbnailRenderer {

    override suspend fun render(context: Context, pdfBytes: ByteArray): PdfThumbnails {
        if (pdfBytes.isEmpty()) return PdfThumbnails(emptyList())
        val pageCap = maxPages.coerceAtLeast(0)
        val edge = longEdgePx.coerceAtLeast(1)
        if (pageCap == 0) return PdfThumbnails(emptyList())

        // The list is declared outside the dispatcher block so a coroutine cancelled at the
        // dispatcher boundary still recycles everything it produced (no orphaned bitmaps).
        val pages = ArrayList<Bitmap>(pageCap.coerceAtMost(MAX_PAGES))
        return try {
            val rendered = withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "pdf-thumbs-${UUID.randomUUID()}.pdf")
                try {
                    temp.writeBytes(pdfBytes)
                    ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val pageCount = renderer.pageCount
                            for (index in 0 until pageCount.coerceAtMost(pageCap)) {
                                renderer.openPage(index).use { page ->
                                    check(page.width > 0 && page.height > 0) {
                                        "PDF page has no drawable size"
                                    }
                                    // Only downscale: a huge long edge must not invent pixels.
                                    val scale = minOf(
                                        edge.toFloat() / page.width,
                                        edge.toFloat() / page.height,
                                    ).coerceAtMost(1f)
                                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                                    val bitmap = Bitmap.createBitmap(
                                        width,
                                        height,
                                        Bitmap.Config.ARGB_8888,
                                    )
                                    pages += bitmap
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                    )
                                }
                            }
                        }
                    }
                    pages.toList()
                } finally {
                    temp.delete()
                }
            }
            PdfThumbnails(rendered)
        } catch (cancelled: CancellationException) {
            pages.forEach { bitmap -> runCatching { bitmap.recycle() } }
            throw cancelled
        } catch (_: Throwable) {
            // Empty/corrupt document, missing renderer, OOM: clean failure, never a crash.
            pages.forEach { bitmap -> runCatching { bitmap.recycle() } }
            PdfThumbnails(emptyList())
        }
    }

    companion object {
        /** Hard cap on rendered thumbnails per document (heap safety). */
        const val MAX_PAGES: Int = 100

        /** Long-edge size of one thumbnail in pixels (144 px ≈ 48 dp at xxhdpi). */
        const val LONG_EDGE_PX: Int = 144
    }
}
