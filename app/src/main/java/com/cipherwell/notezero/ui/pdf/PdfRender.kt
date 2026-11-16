package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Rendered pages of one PDF: the bitmaps that could be produced and the document page count. */
data class PdfPages(
    val bitmaps: List<Bitmap>,
    val totalPages: Int,
) {
    val renderedPages: Int get() = bitmaps.size
    val isTruncated: Boolean get() = totalPages > bitmaps.size
}

/**
 * Renders a decrypted PDF to bitmaps for the viewer. Injectable so the screen is testable in
 * Robolectric, where the platform PDF renderer has no native implementation.
 */
fun interface PdfPageRenderer {
    suspend fun render(context: Context, bytes: ByteArray): PdfPages
}

/**
 * Platform renderer (`android.graphics.pdf.PdfRenderer`).
 *
 * `PdfRenderer` needs a **seekable file descriptor**, so the decrypted bytes are written to an
 * ephemeral app-private cache file for the duration of the render and deleted in the `finally`
 * block — no plaintext PDF survives viewing. The trade-off is documented in DECISIONS #20 /
 * THREAT-MODEL: it is bounded (one file, app-private cache, excluded from backup), short-lived
 * (deleted before the first frame is shown) and only exists because the platform API offers no
 * in-memory renderer.
 *
 * Resource bounds: at most [maxRenderedPages] pages are rasterised, each capped at
 * [maxEdgePx] on the long edge, so a hostile/corrupt file cannot exhaust the heap. Any failure
 * (corrupt PDF, OOM, missing native renderer) returns an empty [PdfPages] and the screen shows
 * a clean error; no partial pages are surfaced.
 */
class AndroidPdfPageRenderer(
    private val maxRenderedPages: Int = MAX_RENDERED_PAGES,
    private val maxEdgePx: Int = MAX_EDGE_PX,
) : PdfPageRenderer {

    override suspend fun render(context: Context, bytes: ByteArray): PdfPages =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext PdfPages(emptyList(), 0)
            val temp = File(context.cacheDir, "pdf-render-${UUID.randomUUID()}.pdf")
            try {
                temp.writeBytes(bytes)
                ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val total = renderer.pageCount
                        val pages = ArrayList<Bitmap>(total.coerceAtMost(maxRenderedPages))
                        for (index in 0 until total.coerceAtMost(maxRenderedPages)) {
                            renderer.openPage(index).use { page ->
                                val scale = minOf(
                                    maxEdgePx.toFloat() / page.width,
                                    maxEdgePx.toFloat() / page.height,
                                ).coerceAtLeast(MIN_SCALE)
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pages += bitmap
                            }
                        }
                        PdfPages(pages, total)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Corrupt file / missing renderer / OOM: clean failure, never a crash.
                PdfPages(emptyList(), 0)
            } finally {
                temp.delete()
            }
        }

    private companion object {
        const val MAX_RENDERED_PAGES = 8
        const val MAX_EDGE_PX = 1_600
        const val MIN_SCALE = 0.1f
    }
}
