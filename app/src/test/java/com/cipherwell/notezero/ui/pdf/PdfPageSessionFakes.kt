package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [PdfPageSession] fake for Robolectric viewer tests (the platform `PdfRenderer` has no
 * Robolectric implementation). It records which 0-based pages were requested — the heart of the
 * lazy-viewer assertions — and hands out tiny real Robolectric bitmaps per page.
 */
class FakePdfPageSession(
    override val pageCount: Int,
    private val failPages: Set<Int> = emptySet(),
    // Small landscape pages keep a page plus its annotation overlay inside the Robolectric
    // viewport, so gesture tests can touch page 0 without scrolling within the page.
    private val bitmapWidth: Int = 60,
    private val bitmapHeight: Int = 30,
) : PdfPageSession {

    /** 0-based page indices requested through [render], in call order. */
    val requestedPages: MutableList<Int> = mutableListOf()

    /** `maxEdgePx` argument of every [render] call (the viewer always uses the default). */
    val requestedEdges: MutableList<Int> = mutableListOf()

    /** Every bitmap handed to the viewer, keyed by page; used for the recycling assertions. */
    val bitmapsByPage: MutableMap<Int, Bitmap> = mutableMapOf()

    /** All bitmaps ever created, so a test can assert previous pages were recycled. */
    val createdBitmaps: MutableList<Bitmap> = mutableListOf()

    /** Optional gate: a test can hold renders to observe the loading placeholder. */
    var gate: CompletableDeferred<Unit>? = null

    var closeCalls: Int = 0
        private set

    /** Mirrors the real contract: close() is idempotent and only the first call counts. */
    val isClosed: Boolean get() = closeCalls > 0

    override suspend fun render(pageIndex: Int, maxEdgePx: Int): Bitmap? {
        requestedPages += pageIndex
        requestedEdges += maxEdgePx
        gate?.await()
        if (pageIndex < 0 || pageIndex >= pageCount || pageIndex in failPages) return null
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (pageIndex % 2 == 0) Color.WHITE else Color.LTGRAY)
        }
        bitmapsByPage[pageIndex] = bitmap
        createdBitmaps += bitmap
        return bitmap
    }

    override fun close() {
        if (isClosed) return
        closeCalls += 1
    }
}

/** In-memory [PdfPageSessionFactory] fake: counts opens and records the decrypted bytes. */
class FakePdfPageSessionFactory(
    private val session: PdfPageSession?,
) : PdfPageSessionFactory {

    var openCalls: Int = 0
        private set

    var lastBytes: ByteArray? = null
        private set

    var lastContext: Context? = null
        private set

    override suspend fun open(context: Context, pdfBytes: ByteArray): PdfPageSession? {
        openCalls += 1
        lastContext = context
        lastBytes = pdfBytes.copyOf()
        return session
    }
}
