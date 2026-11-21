package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Hard ceiling on the number of page rows one viewer may allocate for a document.
 *
 * The page count comes from the document itself, so a hostile/corrupt PDF could claim a huge
 * number of pages. Rows are lazy (an `Int` count allocates nothing), but a list of millions of
 * rows is still a bad UI and a bookmark/thumbnail surface bound. Above this count the viewer
 * shows the ordinary "could not open" error instead of a pager; below it every page is reachable.
 */
const val MAX_VIEW_PAGES: Int = 2_000

/** Default (and hard maximum) long-edge size in pixels of one viewer page bitmap. */
const val PDF_PAGE_MAX_EDGE_PX: Int = 1_600

/**
 * One open, *lazy* rendering session over a decrypted PDF document.
 *
 * A session owns the platform document handle for as long as the viewer needs it and renders an
 * arbitrary page on demand; [PdfRenderer] cannot be reopened cheaply per page and is not
 * thread-safe, which is why the session serialises rendering internally. Only one session is ever
 * open per viewer; [close] releases the handle and the ephemeral file (idempotent).
 *
 * Implementations must fail closed: [render] returns `null` for an invalid index, a failed render
 * or a closed session (never throws except coroutine cancellation), and no bitmap it produced is
 * leaked. Pages are in-memory bitmaps only — a session never writes a page image or thumbnail to
 * disk, and the ephemeral decrypted PDF is unlinked as soon as it is opened.
 */
interface PdfPageSession : AutoCloseable {

    /** Number of pages in the open document. Always `>= 1` for a successfully opened session. */
    val pageCount: Int

    /**
     * Renders 0-based [pageIndex] into a fresh white ARGB_8888 bitmap whose long edge is at most
     * [maxEdgePx] (capped at [PDF_PAGE_MAX_EDGE_PX]) and never larger than the PDF's own page.
     * Returns `null` for an out-of-range index, a closed session or any failure (corrupt page,
     * OOM, missing native renderer); rethrows coroutine cancellation.
     */
    suspend fun render(pageIndex: Int, maxEdgePx: Int = PDF_PAGE_MAX_EDGE_PX): Bitmap?
}

/** Opens one [PdfPageSession] for a decrypted PDF, or `null` when the bytes are not usable. */
fun interface PdfPageSessionFactory {
    suspend fun open(context: Context, pdfBytes: ByteArray): PdfPageSession?
}

/**
 * Platform [PdfPageSessionFactory] (`android.graphics.pdf`).
 *
 * `PdfRenderer` needs a **seekable file descriptor**, so the decrypted bytes are written to one
 * random app-private cache file, opened read-only and then **unlinked immediately after the
 * descriptor is opened**. On Linux an open descriptor keeps the inode alive after its directory
 * entry is gone: the file has no path for the lifetime of the session, so there is no plaintext
 * PDF name for another app/process (or a backup tool) to find, and nothing survives even a crash.
 * If unlink is not possible on a device filesystem, the session falls back to deleting the file
 * on [PdfPageSession.close] (the documented one-file, short-lived trade-off).
 *
 * Failures never throw and are never logged: an empty/corrupt document, an unsupported renderer
 * or a hostile file open to `null` and the viewer shows its ordinary error. The session keeps the
 * renderer open and releases it (plus the descriptor and any leftover path) on close.
 */
class AndroidPdfPageSessionFactory : PdfPageSessionFactory {

    override suspend fun open(context: Context, pdfBytes: ByteArray): PdfPageSession? {
        if (pdfBytes.isEmpty()) return null
        return try {
            withContext(Dispatchers.IO) { openBlocking(context, pdfBytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // OOM while writing the bytes / missing native renderer / bad document: fail closed.
            null
        }
    }

    /** Writes the ephemeral file, opens the descriptor and hands both to the live session. */
    private fun openBlocking(context: Context, pdfBytes: ByteArray): PdfPageSession? {
        val temp = File(context.cacheDir, "pdf-session-${UUID.randomUUID()}.pdf")
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            temp.writeBytes(pdfBytes)
            descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                // A readable descriptor without a single page is not a viewable document.
                renderer.close()
                descriptor.close()
                temp.delete()
                return null
            }
            // No plaintext path may outlive the open call: unlink now that the descriptor owns the
            // inode. Only when the filesystem refuses (rare) is delete-on-close kept as fallback.
            val stillOnDisk = !temp.delete()
            return AndroidPdfPageSession(
                renderer = renderer,
                descriptor = descriptor,
                leftoverPath = if (stillOnDisk) temp else null,
                pageCount = pageCount,
            )
        } catch (_: Throwable) {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            runCatching { temp.delete() }
            return null
        }
    }
}

/**
 * Live [PdfPageSession] over one open [PdfRenderer].
 *
 * Rendering is serialised through [Mutex] because `PdfRenderer` is explicitly not thread-safe, and
 * runs on [Dispatchers.IO] so the UI thread never rasterises. Any failure (destroyed page, OOM,
 * renderer torn down) returns `null`; the bitmap under construction is recycled so a failed render
 * cannot leak. [close] is idempotent and only destroys the native handle when no render is in
 * flight (an in-flight render releases the handle on its exit path), so closing the viewer while a
 * page is rendering cannot race the native document. No logging, no plaintext path.
 */
private class AndroidPdfPageSession(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val leftoverPath: File?,
    override val pageCount: Int,
) : PdfPageSession {

    private val renderMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)

    override suspend fun render(pageIndex: Int, maxEdgePx: Int): Bitmap? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        val edge = maxEdgePx.coerceIn(1, PDF_PAGE_MAX_EDGE_PX)
        var rendered: Bitmap? = null
        try {
            rendered = renderMutex.withLock {
                if (closed.get()) return null
                renderOnIo(pageIndex, edge)
            }
            return rendered
        } catch (cancelled: CancellationException) {
            // A page that completed just as the caller was cancelled is owned here, not by the UI.
            rendered?.let { recycleQuietly(it) }
            throw cancelled
        } catch (_: Throwable) {
            rendered?.let { recycleQuietly(it) }
            return null
        } finally {
            // If close() raced an in-flight render it leaves the release to the render exit path.
            if (closed.get()) closeResources()
        }
    }

    /**
     * One page render on the IO dispatcher; a bitmap is only returned fully rendered. The result is
     * captured inside the IO block so a coroutine cancelled at the dispatcher boundary recycles the
     * just-produced bitmap instead of leaking it.
     */
    private suspend fun renderOnIo(pageIndex: Int, edgePx: Int): Bitmap? {
        var bitmap: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                if (closed.get()) return@withContext
                bitmap = renderBlocking(pageIndex, edgePx)
            }
            return bitmap
        } catch (cancelled: CancellationException) {
            bitmap?.let { recycleQuietly(it) }
            throw cancelled
        } catch (failure: Throwable) {
            bitmap?.let { recycleQuietly(it) }
            throw failure
        }
    }

    /**
     * Blocking render of one page: bounded white ARGB bitmap, never upscaled.
     * Returns `null` for a degenerate page; recycles its own bitmap if the native render fails.
     */
    private fun renderBlocking(pageIndex: Int, edgePx: Int): Bitmap? {
        renderer.openPage(pageIndex).use { page ->
            if (page.width <= 0 || page.height <= 0) return null
            val size = pageBitmapSize(page.width, page.height, edgePx)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } catch (failure: Throwable) {
                recycleQuietly(bitmap)
                throw failure
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Never destroy the native document while a page render holds the mutex (that is undefined
        // behaviour for PdfRenderer): take the lock when it is free. If a render is in flight its
        // exit path calls closeResources() — also idempotent — as soon as that render finishes.
        if (renderMutex.tryLock()) {
            try {
                closeResources()
            } finally {
                renderMutex.unlock()
            }
        }
    }

    private fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        leftoverPath?.let { path -> runCatching { path.delete() } }
    }

}

/**
 * Bitmap dimensions for one page: the long edge is at most [maxEdgePx] (itself clamped to
 * `1..PDF_PAGE_MAX_EDGE_PX`) and a small page is **never upscaled**. Always at least 1×1 so a
 * corrupt/deprecated MediaBox cannot produce a zero-sized allocation.
 *
 * Pure integer math (no Android types) so the bound is a plain JVM unit test.
 */
internal fun pageBitmapSize(pageWidth: Int, pageHeight: Int, maxEdgePx: Int): PageBitmapSize {
    val safeWidth = pageWidth.coerceAtLeast(1)
    val safeHeight = pageHeight.coerceAtLeast(1)
    val edge = maxEdgePx.coerceIn(1, PDF_PAGE_MAX_EDGE_PX)
    val longest = max(safeWidth, safeHeight)
    if (longest <= edge) return PageBitmapSize(safeWidth, safeHeight)
    val scale = edge.toFloat() / longest
    return PageBitmapSize(
        width = (safeWidth * scale).toInt().coerceAtLeast(1),
        height = (safeHeight * scale).toInt().coerceAtLeast(1),
    )
}

/** Width/height in pixels of one rendered page bitmap. */
internal data class PageBitmapSize(val width: Int, val height: Int)

private fun recycleQuietly(bitmap: Bitmap) {
    runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
}
