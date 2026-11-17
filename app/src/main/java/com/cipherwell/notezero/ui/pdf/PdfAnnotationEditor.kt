package com.cipherwell.notezero.ui.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfHighlightRect
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Tools of the annotate-mode palette. */
enum class PdfAnnotationTool {
    INK,
    HIGHLIGHTER,
    RECT,
    ERASER,
    NOTE,
}

/** Ink kind created by [tool]; `null` for tools that do not draw freehand strokes. */
internal fun PdfAnnotationTool.inkKind(): PdfInkKind? = when (this) {
    PdfAnnotationTool.INK -> PdfInkKind.INK
    PdfAnnotationTool.HIGHLIGHTER -> PdfInkKind.HIGHLIGHTER
    PdfAnnotationTool.RECT,
    PdfAnnotationTool.ERASER,
    PdfAnnotationTool.NOTE,
    -> null
}

/** Axis-aligned rectangle in normalised page coordinates, already ordered left/top/right/bottom. */
internal data class NormalisedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Editor state of the annotation layer: the current immutable [PdfAnnotations] snapshot plus a
 * bounded undo/redo history of snapshots.
 *
 * Every mutation goes through a single [commit] so **all** of them (ink, highlight, note
 * create/edit/delete, eraser, clear) are undoable and a failed/no-op mutation does not pollute
 * the history. The model itself validates its bounds; this class clamps user input first and
 * fails closed (`false`) instead of throwing when a limit would be exceeded, so a long editing
 * session cannot crash the viewer.
 *
 * Persistence is the screen's job: it calls `repository.savePdfAnnotations` after a `true`
 * return. This class holds no repository, no Android types and no content logging.
 */
class PdfAnnotationEditor(initial: PdfAnnotations? = null) {

    /** Current layer; read by the canvas and persisted by the screen. */
    var value: PdfAnnotations by mutableStateOf(initial ?: PdfAnnotations())
        private set

    /** True when [undo] has at least one step. */
    var canUndo: Boolean by mutableStateOf(false)
        private set

    /** True when [redo] has at least one step. */
    var canRedo: Boolean by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<PdfAnnotations>()
    private val redoStack = ArrayDeque<PdfAnnotations>()

    /** Replaces the layer without creating a history entry (used when the document opens). */
    fun load(layer: PdfAnnotations?) {
        value = layer ?: PdfAnnotations()
        undoStack.clear()
        redoStack.clear()
        syncHistoryFlags()
    }

    /** Appends a freehand stroke; false when the points or bounds are invalid/at the limit. */
    fun addStroke(
        page: Int,
        kind: PdfInkKind,
        colorIndex: Int,
        widthIndex: Int,
        points: List<PdfPoint>,
    ): Boolean {
        if (page !in 0..PdfAnnotations.MAX_PAGE) return false
        val bounded = points
            .asSequence()
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map { PdfPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .take(PdfAnnotations.MAX_POINTS_PER_STROKE)
            .toList()
        if (bounded.size < PdfAnnotations.MIN_POINTS_PER_STROKE) return false
        if (value.strokes.size >= PdfAnnotations.MAX_STROKES) return false
        val totalPoints = value.strokes.sumOf { it.points.size } + bounded.size
        if (totalPoints > PdfAnnotations.MAX_TOTAL_POINTS) return false
        val stroke = PdfInkStroke(
            page = page,
            kind = kind,
            colorIndex = colorIndex.coerceIn(0, PdfAnnotations.PALETTE_SIZE - 1),
            widthIndex = widthIndex.coerceIn(0, PdfAnnotations.WIDTH_LEVELS - 1),
            points = bounded,
        )
        return commit(value.copy(strokes = value.strokes + stroke))
    }

    /**
     * Appends a rectangle highlight from a raw (possibly inverted) drag. Tiny drags and
     * zero-area rectangles are ignored; the rectangle is clamped to the page.
     */
    fun addHighlight(
        page: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        colorIndex: Int,
    ): Boolean {
        if (page !in 0..PdfAnnotations.MAX_PAGE) return false
        if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) {
            return false
        }
        val left = min(startX, endX).coerceIn(0f, 1f)
        val right = max(startX, endX).coerceIn(0f, 1f)
        val top = min(startY, endY).coerceIn(0f, 1f)
        val bottom = max(startY, endY).coerceIn(0f, 1f)
        if (right - left < MIN_HIGHLIGHT_SIZE || bottom - top < MIN_HIGHLIGHT_SIZE) return false
        if (value.highlights.size >= PdfAnnotations.MAX_HIGHLIGHTS) return false
        val highlight = PdfHighlightRect(
            page = page,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            colorIndex = colorIndex.coerceIn(0, PdfAnnotations.PALETTE_SIZE - 1),
        )
        return commit(value.copy(highlights = value.highlights + highlight))
    }

    /** Adds a sticky note at a normalised position; returns its index or `null` when invalid. */
    fun addNote(page: Int, x: Float, y: Float, text: String): Int? {
        if (page !in 0..PdfAnnotations.MAX_PAGE) return null
        if (!x.isFinite() || !y.isFinite()) return null
        if (value.notes.size >= PdfAnnotations.MAX_NOTES) return null
        val bounded = clampNoteText(text)
        if (bounded.isBlank()) return null
        val note = PdfNote(
            page = page,
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            text = bounded,
        )
        return if (commit(value.copy(notes = value.notes + note))) value.notes.lastIndex else null
    }

    /** Replaces the text of the note at [index]; false when the index/text is invalid. */
    fun updateNote(index: Int, text: String): Boolean {
        if (index !in value.notes.indices) return false
        val bounded = clampNoteText(text)
        if (bounded.isBlank()) return false
        if (bounded == value.notes[index].text) return false
        val notes = value.notes.toMutableList()
        notes[index] = notes[index].copy(text = bounded)
        return commit(value.copy(notes = notes))
    }

    /** Removes the note at [index]; false when the index is invalid. */
    fun deleteNote(index: Int): Boolean {
        if (index !in value.notes.indices) return false
        return commit(value.copy(notes = value.notes.filterIndexed { i, _ -> i != index }))
    }

    /**
     * Erases whatever lies within [tolerance] (normalised distance) of ([x], [y]) on [page]:
     * whole strokes that pass near the point, highlights containing/near the point and sticky
     * notes whose marker is near the point. Returns true when something was removed.
     */
    fun eraseAt(page: Int, x: Float, y: Float, tolerance: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val safeTolerance = tolerance.coerceIn(MIN_ERASE_TOLERANCE, MAX_ERASE_TOLERANCE)
        val strokes = value.strokes.filterNot { stroke ->
            stroke.page == page && strokeNear(stroke, x, y, safeTolerance)
        }
        val highlights = value.highlights.filterNot { highlight ->
            highlight.page == page && highlightNear(highlight, x, y, safeTolerance)
        }
        val notes = value.notes.filterNot { note ->
            note.page == page && hypot(note.x - x, note.y - y) <= safeTolerance
        }
        if (strokes.size == value.strokes.size &&
            highlights.size == value.highlights.size &&
            notes.size == value.notes.size
        ) {
            return false
        }
        return commit(value.copy(strokes = strokes, highlights = highlights, notes = notes))
    }

    /** Removes everything on every page; not exposed as a toolbar action but undoable. */
    fun clear(): Boolean {
        if (value.isEmpty) return false
        return commit(PdfAnnotations())
    }

    /** Steps one snapshot back in the bounded history. */
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(value)
        while (redoStack.size > MAX_HISTORY) redoStack.removeFirst()
        value = previous
        syncHistoryFlags()
        return true
    }

    /** Steps one snapshot forward after an [undo]. */
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(value)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        value = next
        syncHistoryFlags()
        return true
    }

    private fun commit(next: PdfAnnotations): Boolean {
        if (next == value) return false
        undoStack.addLast(value)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        value = next
        syncHistoryFlags()
        return true
    }

    private fun syncHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun strokeNear(stroke: PdfInkStroke, x: Float, y: Float, tolerance: Float): Boolean {
        val points = stroke.points
        if (points.isEmpty()) return false
        if (points.size == 1) {
            return hypot(points[0].x - x, points[0].y - y) <= tolerance
        }
        for (i in 0 until points.size - 1) {
            if (distanceToSegment(x, y, points[i].x, points[i].y, points[i + 1].x, points[i + 1].y) <=
                tolerance
            ) {
                return true
            }
        }
        return false
    }

    private fun highlightNear(
        highlight: PdfHighlightRect,
        x: Float,
        y: Float,
        tolerance: Float,
    ): Boolean =
        x >= highlight.left - tolerance &&
            x <= highlight.right + tolerance &&
            y >= highlight.top - tolerance &&
            y <= highlight.bottom + tolerance

    companion object {
        /** At least 50 undo steps are required; snapshots are cheap so keep some headroom. */
        const val MAX_HISTORY: Int = 64

        /** Smallest rectangle-highlight edge (normalised) that counts as a real drag. */
        const val MIN_HIGHLIGHT_SIZE: Float = 0.004f

        /** Bounds of the eraser tolerance so a hostile gesture cannot erase the whole document. */
        const val MIN_ERASE_TOLERANCE: Float = 0.004f
        const val MAX_ERASE_TOLERANCE: Float = 0.2f

        /**
         * Caps sticky-note text to the model's UTF-8 byte budget by deleting whole characters
         * (never splitting a surrogate pair), without trimming — safe to run on every keystroke.
         */
        fun boundedNoteText(text: String): String {
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val codePoint = Character.codePointAt(text, end)
                val charCount = Character.charCount(codePoint)
                val byteCount = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + byteCount > PdfAnnotations.MAX_NOTE_BYTES) break
                bytes += byteCount
                end += charCount
            }
            return if (end == text.length) text else text.substring(0, end)
        }

        /** Caps and trims sticky-note text for storage; blank text is rejected by the editor. */
        fun clampNoteText(text: String): String = boundedNoteText(text).trim()
    }
}

/** Euclidean distance from a point to a line segment; 0 when the point is on the segment. */
internal fun distanceToSegment(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/** Builds an ordered, clamped normalised rectangle from a drag, or null for a degenerate drag. */
internal fun normalisedRect(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
): NormalisedRect? {
    if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) {
        return null
    }
    val left = min(startX, endX).coerceIn(0f, 1f)
    val right = max(startX, endX).coerceIn(0f, 1f)
    val top = min(startY, endY).coerceIn(0f, 1f)
    val bottom = max(startY, endY).coerceIn(0f, 1f)
    if (right - left < PdfAnnotationEditor.MIN_HIGHLIGHT_SIZE ||
        bottom - top < PdfAnnotationEditor.MIN_HIGHLIGHT_SIZE
    ) {
        return null
    }
    return NormalisedRect(left = left, top = top, right = right, bottom = bottom)
}
