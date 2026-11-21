package com.cipherwell.notezero.ui.pdf

/**
 * One page of a planned page-tools copy.
 *
 * [sourcePage] is the 0-based index of the page in the **original** PDF, or `null` for a blank
 * page inserted by the user. [quarterTurns] is the clockwise rotation applied to that source
 * page while flattening, normalised to `0..3` (0/90/180/270 degrees).
 *
 * Entries are immutable descriptions only: they carry no bitmap, annotation or item reference,
 * so a plan is a tiny, copyable value that can never leak decrypted content.
 */
data class PdfPageEntry(
    val sourcePage: Int?,
    val quarterTurns: Int = 0,
) {
    init {
        require(sourcePage == null || sourcePage >= 0) { "source page must be non-negative" }
        require(quarterTurns in 0..3) { "quarter turns must be 0..3" }
    }

    /** True for a page the user inserted (it has no original page to draw). */
    val isBlank: Boolean get() = sourcePage == null

    /** Same entry rotated 90 degrees counter-clockwise, normalised to `0..3`. */
    fun rotatedLeft(): PdfPageEntry = copy(quarterTurns = (quarterTurns + 3) % 4)

    /** Same entry rotated 90 degrees clockwise, normalised to `0..3`. */
    fun rotatedRight(): PdfPageEntry = copy(quarterTurns = (quarterTurns + 1) % 4)
}

/** Outcome of [PdfPagePlan.delete]: the resulting plan plus whether an entry was removed. */
data class PdfPageDeletion(
    val plan: PdfPagePlan,
    val deleted: Boolean,
) {
    /** True when nothing changed — the plan's only page cannot be deleted. */
    val refused: Boolean get() = !deleted
}

/**
 * Immutable page plan for the PDF page-tools copy: an ordered list of source pages (with a
 * clockwise quarter-turn each) plus any number of inserted blank pages.
 *
 * The plan is **pure Kotlin** (no Android types) so the whole page-tools state machine is a
 * plain JVM unit test. Every operation returns a new plan and never throws on hostile
 * positions: an out-of-range index is a no-op, and the last remaining page cannot be deleted.
 * A plan is bounded by [MAX_PAGES] output pages and never contains duplicate or out-of-range
 * source indices, so the exporter can trust it (and re-validates it anyway).
 *
 * The original document is never represented here — the plan only describes the flattened copy
 * the user is about to save; the source PDF and its annotation layer stay untouched.
 */
class PdfPagePlan private constructor(
    /** Number of pages the source document had when the plan was created (for [reset]). */
    val sourcePageCount: Int,
    private val entryList: List<PdfPageEntry>,
) {

    /** The planned output pages, in order. Never empty for a valid plan; never > [MAX_PAGES]. */
    val entries: List<PdfPageEntry> get() = entryList

    /** Number of output pages (equals `entries.size`). */
    val size: Int get() = entryList.size

    /** The source document has more pages than page tools can process; no copy can be saved. */
    val isTooLarge: Boolean get() = sourcePageCount > MAX_PAGES

    /**
     * True when the plan can be exported: at least one output page, at most [MAX_PAGES], a
     * source small enough to plan, and only in-range, non-duplicated source indices.
     */
    val isValid: Boolean
        get() = !isTooLarge && entryList.isNotEmpty() && entryList.size <= MAX_PAGES &&
            isValidEntries(entryList)

    /** True when the plan no longer matches the original page order/rotation. */
    fun hasChanges(): Boolean = entryList != of(sourcePageCount).entryList

    /** Same plan with page [position] rotated 90 degrees counter-clockwise. No-op if invalid. */
    fun rotateLeft(position: Int): PdfPagePlan =
        rotate(position) { entry -> entry.rotatedLeft() }

    /** Same plan with page [position] rotated 90 degrees clockwise. No-op if invalid. */
    fun rotateRight(position: Int): PdfPagePlan =
        rotate(position) { entry -> entry.rotatedRight() }

    /** Same plan with page [position] swapped one place up. No-op at the top / for bad positions. */
    fun moveUp(position: Int): PdfPagePlan {
        if (position <= 0 || position >= entryList.size) return this
        return swap(position, position - 1)
    }

    /** Same plan with page [position] swapped one place down. No-op at the bottom / bad positions. */
    fun moveDown(position: Int): PdfPagePlan {
        if (position < 0 || position >= entryList.size - 1) return this
        return swap(position, position + 1)
    }

    /**
     * Removes page [position]. The plan's **last remaining page is never deleted** and hostile
     * positions are refused, so [PdfPageDeletion.deleted] tells the UI when to show
     * `pdf_pages_last_page` instead of silently doing nothing.
     */
    fun delete(position: Int): PdfPageDeletion {
        if (entryList.size <= 1 || position !in entryList.indices) {
            return PdfPageDeletion(plan = this, deleted = false)
        }
        val next = entryList.toMutableList().apply { removeAt(position) }
        return PdfPageDeletion(plan = PdfPagePlan(sourcePageCount, next), deleted = true)
    }

    /**
     * Inserts a blank page after [afterPosition] (`null` appends at the end — the UI action's
     * only mode). Hostile positions fall back to appending; a plan at [MAX_PAGES] is unchanged.
     */
    fun insertBlank(afterPosition: Int? = null): PdfPagePlan {
        if (isTooLarge || entryList.size >= MAX_PAGES) return this
        val insertAt = if (afterPosition == null || afterPosition !in entryList.indices) {
            entryList.size
        } else {
            afterPosition + 1
        }
        val next = entryList.toMutableList().apply { add(insertAt, PdfPageEntry(sourcePage = null)) }
        return PdfPagePlan(sourcePageCount, next)
    }

    /** Initial order and rotation for [sourcePageCount] pages (all deleted pages come back). */
    fun reset(): PdfPagePlan = of(sourcePageCount)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfPagePlan) return false
        return sourcePageCount == other.sourcePageCount && entryList == other.entryList
    }

    override fun hashCode(): Int = 31 * sourcePageCount + entryList.hashCode()

    override fun toString(): String = "PdfPagePlan($sourcePageCount pages -> $entryList)"

    private fun rotate(position: Int, transform: (PdfPageEntry) -> PdfPageEntry): PdfPagePlan {
        if (position !in entryList.indices) return this
        val next = entryList.toMutableList()
        next[position] = transform(next[position])
        return PdfPagePlan(sourcePageCount, next)
    }

    private fun swap(first: Int, second: Int): PdfPagePlan {
        val next = entryList.toMutableList()
        val temp = next[first]
        next[first] = next[second]
        next[second] = temp
        return PdfPagePlan(sourcePageCount, next)
    }

    private fun isValidEntries(candidate: List<PdfPageEntry>): Boolean {
        if (candidate.size > MAX_PAGES) return false
        val seen = HashSet<Int>(candidate.size)
        for (entry in candidate) {
            val source = entry.sourcePage ?: continue
            if (source !in 0 until sourcePageCount) return false
            if (!seen.add(source)) return false
        }
        return true
    }

    companion object {
        /** Hard ceiling for one page-tools copy: bounded so a hostile PDF cannot bloat export. */
        const val MAX_PAGES: Int = 100

        /**
         * Initial plan for a source document with [totalPages] pages: one entry per source page
         * in order, no rotation. A source above [MAX_PAGES] produces an **empty, invalid** plan
         * flagged by [isTooLarge] — building thousands of entries would be pointless work and a
         * truncated plan would silently drop pages, which is worse than an explicit error.
         * Hostile/negative totals are clamped to an empty plan.
         */
        fun of(totalPages: Int): PdfPagePlan {
            val safeTotal = totalPages.coerceAtLeast(0)
            if (safeTotal > MAX_PAGES) return PdfPagePlan(safeTotal, emptyList())
            return PdfPagePlan(safeTotal, List(safeTotal) { index -> PdfPageEntry(index) })
        }
    }
}
