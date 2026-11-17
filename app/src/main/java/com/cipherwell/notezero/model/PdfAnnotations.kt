package com.cipherwell.notezero.model

/** Kind of freehand PDF annotation stroke. */
enum class PdfInkKind {
    /** Solid pen stroke. */
    INK,

    /** Translucent highlighter stroke. */
    HIGHLIGHTER,
}

/** One normalised point on a PDF page ([x]/[y] in 0.0..1.0, origin at the top-left). */
data class PdfPoint(val x: Float, val y: Float)

/**
 * One freehand stroke on a PDF page.
 *
 * Coordinates and width are normalised so annotations are resolution-independent: the same
 * state renders correctly over a phone-sized bitmap, a tablet bitmap or the flattened export.
 * [colorIndex] indexes the shared annotation palette and [widthIndex] the shared width ladder
 * (both defined by the UI layer; the model only stores bounded indices so it stays codec/UI
 * agnostic and can be validated fail-closed).
 */
data class PdfInkStroke(
    val page: Int,
    val kind: PdfInkKind,
    val colorIndex: Int,
    val widthIndex: Int,
    val points: List<PdfPoint>,
)

/** One rectangular highlight region on a PDF page (normalised bounds, 0.0..1.0). */
data class PdfHighlightRect(
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val colorIndex: Int,
)

/** One sticky-note annotation pinned at a normalised page position. */
data class PdfNote(
    val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
)

/**
 * Encrypted PDF annotation layer of one document (`ItemCodec` payload v5).
 *
 * This is **content**, not UI state: it is sealed inside the same AES-256-GCM item envelope as
 * the document and travels with encrypted backups. It is repository-owned like
 * [ReadingState]/attachments — `saveItem` preserves the stored value and only
 * `savePdfAnnotations` changes it.
 *
 * All geometry is normalised to the page (0.0..1.0) so it survives different render sizes,
 * orientation changes and the flattened export. The container validates its own bounds so a
 * corrupt/hostile payload cannot allocate or surface unbounded data; the binary codec enforces
 * the same limits again on decode.
 */
data class PdfAnnotations(
    val strokes: List<PdfInkStroke> = emptyList(),
    val highlights: List<PdfHighlightRect> = emptyList(),
    val notes: List<PdfNote> = emptyList(),
) {
    init {
        require(strokes.size <= MAX_STROKES) { "too many strokes" }
        var totalPoints = 0
        for (stroke in strokes) {
            require(stroke.page in 0..MAX_PAGE) { "stroke page out of range" }
            require(stroke.colorIndex in 0 until PALETTE_SIZE) { "stroke colour out of range" }
            require(stroke.widthIndex in 0 until WIDTH_LEVELS) { "stroke width out of range" }
            require(stroke.points.size in MIN_POINTS_PER_STROKE..MAX_POINTS_PER_STROKE) {
                "stroke point count out of range"
            }
            totalPoints += stroke.points.size
            if (totalPoints > MAX_TOTAL_POINTS) throw IllegalArgumentException("too many points")
            for (point in stroke.points) requireNormalised(point.x, "x")
            for (point in stroke.points) requireNormalised(point.y, "y")
        }
        require(highlights.size <= MAX_HIGHLIGHTS) { "too many highlights" }
        for (highlight in highlights) {
            require(highlight.page in 0..MAX_PAGE) { "highlight page out of range" }
            require(highlight.colorIndex in 0 until PALETTE_SIZE) { "highlight colour out of range" }
            requireNormalised(highlight.left, "highlight left")
            requireNormalised(highlight.top, "highlight top")
            requireNormalised(highlight.right, "highlight right")
            requireNormalised(highlight.bottom, "highlight bottom")
            require(highlight.left <= highlight.right) { "highlight bounds inverted horizontally" }
            require(highlight.top <= highlight.bottom) { "highlight bounds inverted vertically" }
        }
        require(notes.size <= MAX_NOTES) { "too many notes" }
        for (note in notes) {
            require(note.page in 0..MAX_PAGE) { "note page out of range" }
            requireNormalised(note.x, "note x")
            requireNormalised(note.y, "note y")
            require(note.text.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) {
                "note text exceeds limit"
            }
        }
    }

    /** True when there is nothing to draw/persist (used to clear the stored layer). */
    val isEmpty: Boolean get() = strokes.isEmpty() && highlights.isEmpty() && notes.isEmpty()

    private fun requireNormalised(value: Float, name: String) {
        require(value.isFinite() && value in 0f..1f) { "$name must be normalised 0..1" }
    }

    companion object {
        const val PALETTE_SIZE: Int = 6
        const val WIDTH_LEVELS: Int = 3
        const val MAX_STROKES: Int = 2_000
        const val MIN_POINTS_PER_STROKE: Int = 2
        const val MAX_POINTS_PER_STROKE: Int = 2_000
        const val MAX_TOTAL_POINTS: Int = 100_000
        const val MAX_HIGHLIGHTS: Int = 1_000
        const val MAX_NOTES: Int = 500
        const val MAX_NOTE_BYTES: Int = 4_000
        const val MAX_PAGE: Int = 100_000
        const val MAX_ENCODED_BYTES: Int = 4 * 1024 * 1024
    }
}
