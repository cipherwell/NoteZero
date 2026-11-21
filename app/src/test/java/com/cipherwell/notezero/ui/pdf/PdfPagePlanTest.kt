package com.cipherwell.notezero.ui.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure-JVM tests for the immutable page plan: every operation, boundary, rotation
 * normalisation, the last-page delete refusal, the 100-page cap, reset, dirty tracking and
 * hostile arguments (the plan must never throw and never produce an invalid plan).
 */
class PdfPagePlanTest {

    private fun sources(plan: PdfPagePlan): List<Int?> = plan.entries.map { it.sourcePage }

    private fun rotations(plan: PdfPagePlan): List<Int> = plan.entries.map { it.quarterTurns }

    @Test
    fun `of builds one unrotated entry per source page`() {
        val plan = PdfPagePlan.of(4)

        assertEquals(4, plan.size)
        assertEquals(4, plan.sourcePageCount)
        assertEquals(listOf(0, 1, 2, 3), sources(plan))
        assertEquals(listOf(0, 0, 0, 0), rotations(plan))
        assertTrue(plan.isValid)
        assertFalse(plan.isTooLarge)
        assertFalse(plan.hasChanges())
    }

    @Test
    fun `of clamps hostile and empty totals instead of throwing`() {
        val zero = PdfPagePlan.of(0)
        assertEquals(0, zero.size)
        assertFalse(zero.isValid)
        assertFalse(zero.isTooLarge)

        val negative = PdfPagePlan.of(-7)
        assertEquals(0, negative.size)
        assertFalse(negative.isValid)
    }

    @Test
    fun `of above the cap is flagged too large and yields no exportable entries`() {
        val plan = PdfPagePlan.of(PdfPagePlan.MAX_PAGES + 1)

        assertEquals(0, plan.size)
        assertTrue(plan.isTooLarge)
        assertFalse(plan.isValid)
        assertFalse(plan.hasChanges())
    }

    @Test
    fun `rotate right cycles clockwise through zero 90 180 270 and wraps`() {
        var plan = PdfPagePlan.of(1)
        val expected = listOf(1, 2, 3, 0, 1)
        for (turn in expected) {
            plan = plan.rotateRight(0)
            assertEquals(turn, rotations(plan).single())
        }
        assertTrue(plan.isValid)
    }

    @Test
    fun `rotate left cycles counter-clockwise through zero 270 180 90 and wraps`() {
        var plan = PdfPagePlan.of(1)
        val expected = listOf(3, 2, 1, 0, 3)
        for (turn in expected) {
            plan = plan.rotateLeft(0)
            assertEquals(turn, rotations(plan).single())
        }
    }

    @Test
    fun `rotations are independent per position`() {
        val plan = PdfPagePlan.of(3)
            .rotateRight(0)
            .rotateRight(0)
            .rotateLeft(1)
            .rotateRight(2)

        assertEquals(listOf(2, 3, 1), rotations(plan))
        assertEquals(listOf(0, 1, 2), sources(plan))
    }

    @Test
    fun `rotate with hostile positions is a no-op`() {
        val plan = PdfPagePlan.of(2)

        assertEquals(plan, plan.rotateLeft(-1))
        assertEquals(plan, plan.rotateRight(2))
        assertEquals(plan, plan.rotateRight(Int.MIN_VALUE))
        assertEquals(plan, plan.rotateLeft(Int.MAX_VALUE))
        assertFalse(plan.rotateRight(9).hasChanges())
    }

    @Test
    fun `move up and down reorder entries and no-op at the bounds`() {
        val plan = PdfPagePlan.of(3)

        // Page 3 moved to the top: [2, 0, 1].
        val reordered = plan.moveUp(2).moveUp(1)
        assertEquals(listOf(2, 0, 1), sources(reordered))
        assertTrue(reordered.hasChanges())
        assertTrue(reordered.isValid)

        // Already at the top / bottom: unchanged.
        assertEquals(plan, plan.moveUp(0))
        assertEquals(plan, plan.moveDown(2))
        // Hostile positions.
        assertEquals(plan, plan.moveUp(-5))
        assertEquals(plan, plan.moveDown(-5))
        assertEquals(plan, plan.moveUp(99))
        assertEquals(plan, plan.moveDown(99))
    }

    @Test
    fun `move down swaps with the next page`() {
        val plan = PdfPagePlan.of(3).moveDown(0)

        assertEquals(listOf(1, 0, 2), sources(plan))
        assertEquals(plan, plan.moveDown(2))
    }

    @Test
    fun `delete removes the addressed page and reports success`() {
        val plan = PdfPagePlan.of(3)
        val deletion = plan.delete(1)

        assertTrue(deletion.deleted)
        assertFalse(deletion.refused)
        assertEquals(2, deletion.plan.size)
        assertEquals(listOf(0, 2), sources(deletion.plan))
        assertTrue(deletion.plan.hasChanges())
        assertTrue(deletion.plan.isValid)
        // Immutability: the original plan is unchanged.
        assertEquals(listOf(0, 1, 2), sources(plan))
    }

    @Test
    fun `delete refuses the last remaining page`() {
        var plan = PdfPagePlan.of(2)
        plan = plan.delete(0).plan
        assertEquals(1, plan.size)

        val refused = plan.delete(0)
        assertTrue(refused.refused)
        assertFalse(refused.deleted)
        assertEquals(1, refused.plan.size)
        assertEquals(plan, refused.plan)
    }

    @Test
    fun `delete with hostile positions is refused and unchanged`() {
        val plan = PdfPagePlan.of(3)

        assertFalse(plan.delete(-1).deleted)
        assertFalse(plan.delete(3).deleted)
        assertFalse(plan.delete(Int.MAX_VALUE).deleted)
        assertEquals(plan, plan.delete(-1).plan)
    }

    @Test
    fun `deleting every page but one keeps the plan valid`() {
        var plan = PdfPagePlan.of(4)
        for (position in 0..2) {
            plan = plan.delete(0).plan
        }

        assertEquals(1, plan.size)
        assertEquals(listOf(3), sources(plan))
        assertTrue(plan.isValid)
    }

    @Test
    fun `insert blank appends by default and caps at the maximum`() {
        val appended = PdfPagePlan.of(2).insertBlank()
        assertEquals(3, appended.size)
        assertNull(sources(appended).last())
        assertEquals(0, rotations(appended).last())
        assertTrue(appended.hasChanges())

        var full = PdfPagePlan.of(PdfPagePlan.MAX_PAGES)
        val capped = full.insertBlank()
        assertEquals(PdfPagePlan.MAX_PAGES, capped.size)
        assertEquals(full, capped)
        // Still valid at the cap, so the copy can be saved.
        assertTrue(capped.isValid)

        // The cap holds even when repeatedly used.
        repeat(5) { full = full.insertBlank() }
        assertEquals(PdfPagePlan.MAX_PAGES, full.size)
    }

    @Test
    fun `insert blank after a position inserts in place and hostile positions append`() {
        val afterFirst = PdfPagePlan.of(2).insertBlank(afterPosition = 0)
        assertEquals(3, afterFirst.size)
        assertNull(sources(afterFirst)[1])
        assertEquals(listOf(0, null, 1), sources(afterFirst))

        assertEquals(listOf(0, 1, null), sources(PdfPagePlan.of(2).insertBlank(9)))
        assertEquals(listOf(0, 1, null), sources(PdfPagePlan.of(2).insertBlank(-4)))
        // An empty plan refuses no insert (nothing to plan) yet appending a blank is harmless.
        assertEquals(1, PdfPagePlan.of(0).insertBlank().size)
    }

    @Test
    fun `insert blank on a too-large source is refused`() {
        val tooLarge = PdfPagePlan.of(PdfPagePlan.MAX_PAGES + 5)

        assertEquals(tooLarge, tooLarge.insertBlank())
        assertEquals(0, tooLarge.insertBlank().size)
    }

    @Test
    fun `reset restores the original order, rotations and deleted pages`() {
        val initial = PdfPagePlan.of(3)
        val edited = initial
            .rotateRight(0)
            .moveUp(2)
            .delete(1)
            .plan
            .insertBlank()

        assertTrue(edited.hasChanges())
        val reset = edited.reset()

        assertEquals(initial, reset)
        assertFalse(reset.hasChanges())
        assertEquals(listOf(0, 1, 2), sources(reset))
        assertEquals(listOf(0, 0, 0), rotations(reset))
    }

    @Test
    fun `reset of an empty plan stays empty and invalid`() {
        val plan = PdfPagePlan.of(0).reset()

        assertEquals(0, plan.size)
        assertFalse(plan.isValid)
    }

    @Test
    fun `plans compare by value and hash consistently`() {
        assertEquals(PdfPagePlan.of(3), PdfPagePlan.of(3))
        assertEquals(PdfPagePlan.of(3).hashCode(), PdfPagePlan.of(3).hashCode())
        assertNotEquals(PdfPagePlan.of(3), PdfPagePlan.of(2))
        assertNotEquals(PdfPagePlan.of(3), PdfPagePlan.of(3).rotateRight(0))
        assertNotEquals(PdfPagePlan.of(3).rotateRight(0), PdfPagePlan.of(3).rotateRight(1))
    }

    @Test
    fun `a plan can never contain duplicate or out-of-range source pages`() {
        // Every public operation is checked from a freshly built plan and from a heavily edited
        // one: the invariant is what lets the exporter trust the plan.
        var plan = PdfPagePlan.of(4)
        plan = plan.moveUp(3).rotateRight(1).insertBlank(0).delete(2).plan.moveDown(0)
        assertTrue(plan.isValid)

        val sourceIndices = plan.entries.mapNotNull { it.sourcePage }
        assertEquals(sourceIndices.size, sourceIndices.toSet().size)
        assertTrue(sourceIndices.all { it in 0 until plan.sourcePageCount })
        assertTrue(plan.entries.all { it.quarterTurns in 0..3 })
    }

    @Test
    fun `entries reject hostile values at construction`() {
        assertThrows(IllegalArgumentException::class.java) { PdfPageEntry(sourcePage = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            PdfPageEntry(sourcePage = 0, quarterTurns = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfPageEntry(sourcePage = 0, quarterTurns = -1)
        }
    }

    @Test
    fun `blank entries are explicitly described and rotation normalises on the entry`() {
        val blank = PdfPageEntry(sourcePage = null)
        assertTrue(blank.isBlank)
        assertEquals(1, blank.rotatedRight().quarterTurns)
        assertEquals(0, blank.rotatedLeft().rotatedLeft().rotatedLeft().rotatedLeft().quarterTurns)

        val onPage = PdfPageEntry(sourcePage = 2, quarterTurns = 3)
        assertFalse(onPage.isBlank)
        assertEquals(0, onPage.rotatedRight().quarterTurns)
        assertEquals(2, onPage.rotatedLeft().quarterTurns)
        assertEquals(2, onPage.sourcePage)
    }

    @Test
    fun `maximum-sized plan stays valid after every operation`() {
        var plan = PdfPagePlan.of(PdfPagePlan.MAX_PAGES)
        plan = plan
            .rotateLeft(0)
            .rotateRight(PdfPagePlan.MAX_PAGES - 1)
            .moveUp(PdfPagePlan.MAX_PAGES - 1)
            .moveDown(0)
            .delete(PdfPagePlan.MAX_PAGES - 1)
            .plan
            .insertBlank()

        assertEquals(PdfPagePlan.MAX_PAGES, plan.size)
        assertTrue(plan.isValid)
        assertTrue(plan.hasChanges())
    }
}
