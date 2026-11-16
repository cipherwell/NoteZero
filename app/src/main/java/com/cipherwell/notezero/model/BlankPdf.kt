package com.cipherwell.notezero.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates the bytes of a minimal, standards-valid, one-page blank PDF.
 *
 * Pure Kotlin (no `android.*`), so the wire format is a plain JVM unit test and the bytes can
 * be validated by any PDF consumer. The generated file has no text, images, metadata or
 * embedded fonts: a catalog, a one-page page tree, an empty content stream and an xref table
 * with a trailer. It is deliberately tiny (~400 bytes) because it is stored as an encrypted
 * attachment inside the vault, exactly like an image.
 *
 * A "PDF document" item is a [ItemKind.PDF_FILE] with this file attached (display name
 * [fileName]); the viewer renders it with the platform PDF renderer and the only plaintext
 * egress is the explicit "export decrypted copy" action.
 */
object BlankPdf {

    /** Attachment MIME type for PDF documents. */
    const val MIME_TYPE = "application/pdf"

    /** A4 page size in PDF points (1/72 inch). */
    const val PAGE_WIDTH_PT = 595
    const val PAGE_HEIGHT_PT = 842

    /** File-name prefix for a new document, e.g. `document-20261115-221600.pdf`. */
    private const val FILE_PREFIX = "document-"

    private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"

    /**
     * Deterministic display name for a new blank document. ASCII digits via [Locale.US] and
     * an explicit [timeZone] so tests do not depend on the host clock. Display data only —
     * never a path.
     */
    fun fileName(timestampMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
        format.timeZone = timeZone
        return "$FILE_PREFIX${format.format(Date(timestampMillis))}.pdf"
    }

    /**
     * Builds the one-page blank PDF. Object offsets in the xref table are computed from the
     * actual byte positions, so the result is a valid PDF 1.4 file and not a template that
     * only looks right.
     */
    fun create(): ByteArray {
        val out = StringBuilder(512)
        val offsets = IntArray(5) // object number -> byte offset (0 = unused/free)
        out.append("%PDF-1.4\n")
        offsets[1] = out.length
        out.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets[2] = out.length
        out.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets[3] = out.length
        out.append(
            "3 0 obj\n" +
                "<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 $PAGE_WIDTH_PT $PAGE_HEIGHT_PT] " +
                "/Resources << >> /Contents 4 0 R >>\n" +
                "endobj\n",
        )
        offsets[4] = out.length
        out.append("4 0 obj\n<< /Length 0 >>\nstream\n\nendstream\nendobj\n")
        val xrefOffset = out.length
        out.append("xref\n0 5\n")
        out.append("0000000000 65535 f \n")
        for (objectNumber in 1..4) {
            out.append(String.format(Locale.US, "%010d 00000 n \n", offsets[objectNumber]))
        }
        out.append("trailer\n<< /Size 5 /Root 1 0 R >>\n")
        out.append("startxref\n$xrefOffset\n%%EOF\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }
}
