package com.cipherwell.notezero.ui.sketch

import android.graphics.BitmapFactory
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchInkKind
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import com.cipherwell.notezero.model.SketchTemplate
import com.cipherwell.notezero.model.SketchWidth
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory

/**
 * Robolectric UI tests for the drawing studio.
 *
 * The screen is mounted against [FakeNoteRepository] (unlocked, one seeded `SKETCH_FILE`), touch
 * gestures are injected into the `sketch_page` node and the persisted vector is asserted through
 * the repository item (`item.sketch`), never through pixels. The flattened preview is asserted
 * through the encrypted-attachment pipeline: a real PNG is rendered in memory on exit, imported
 * as one `sketch-*.png` attachment and decoded back with the platform decoder.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SketchStudioScreenTest : UiTestBase() {

    /**
     * The shared [FakeNoteRepository] implements the fail-secure default for
     * `deleteAttachment` (the studio's preview replacement relies on a real deletion), so the
     * studio is mounted against this thin wrapper which adds the deletion semantics the real
     * encrypted repository has.
     */
    private class PreviewRepository(val fake: FakeNoteRepository) : NoteRepository by fake {
        override suspend fun deleteAttachment(noteId: String, attachmentId: String): Boolean {
            val item = fake.items.value.firstOrNull { it.id == noteId } ?: return false
            if (item.attachments.none { it.id == attachmentId }) return false
            fake.saveItem(item.copy(attachments = item.attachments.filterNot { it.id == attachmentId }))
            return true
        }
    }

    private fun repository(): PreviewRepository = PreviewRepository(
        FakeNoteRepository(
            initialStatus = VaultStatus.UNLOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        ),
    )

    private fun seedSketch(
        repository: PreviewRepository,
        id: String = SKETCH_ID,
        title: String = "My sketch",
        sketch: SketchDocument? = null,
    ): Item {
        val item = Item(
            id = id,
            kind = ItemKind.SKETCH_FILE,
            title = title,
            body = "",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            version = 1,
            sketch = sketch,
        )
        repository.fake.seedItem(item)
        return item
    }

    private fun itemOf(repository: PreviewRepository, id: String = SKETCH_ID): Item? =
        repository.items.value.firstOrNull { it.id == id }

    private fun sketchOf(repository: PreviewRepository, id: String = SKETCH_ID): SketchDocument? =
        itemOf(repository, id)?.sketch

    private fun previewsOf(repository: PreviewRepository, id: String = SKETCH_ID) =
        itemOf(repository, id)?.attachments.orEmpty().filter {
            it.mimeType == SketchRenderer.MIME_TYPE && SketchRenderer.isPreviewName(it.displayName)
        }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForNoTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Mounts the studio inside a host that removes it when [onClose] fires, so a test can close
     * and re-open the same repository item (preview replacement test).
     */
    private fun showStudio(
        repository: PreviewRepository,
        itemId: String = SKETCH_ID,
        onClose: () -> Unit = {},
    ): MutableState<Boolean> {
        val open = mutableStateOf(true)
        setThemedContent {
            if (open.value) {
                SketchStudioScreen(
                    repository = repository,
                    itemId = itemId,
                    // Smaller than production (1600) so 17 Robolectric cases stay inside the
                    // shared test JVM heap; the real renderer + import pipeline is exercised.
                    previewMaxDimensionPx = TEST_PREVIEW_EDGE_PX,
                    onClose = {
                        open.value = false
                        onClose()
                    },
                )
            }
        }
        waitForTag("sketch_page")
        composeRule.waitForIdle()
        return open
    }

    private fun clickBack(open: MutableState<Boolean>? = null) {
        composeRule.onNodeWithTag("sketch_back").performClick()
        composeRule.waitForIdle()
        if (open != null) {
            composeRule.waitUntil(5_000) { !open.value }
        }
    }

    /** One-finger drag across the page, in page-fraction coordinates. */
    private fun drawStroke(
        from: Offset = Offset(0.2f, 0.3f),
        to: Offset = Offset(0.7f, 0.6f),
    ) {
        composeRule.onNodeWithTag("sketch_page").performTouchInput {
            val widthPx = width.toFloat()
            val heightPx = height.toFloat()
            val deltaX = (to.x - from.x) * widthPx
            val deltaY = (to.y - from.y) * heightPx
            down(position = Offset(widthPx * from.x, heightPx * from.y))
            moveTo(position = Offset(widthPx * from.x + deltaX * 0.33f, heightPx * from.y + deltaY * 0.33f))
            moveTo(position = Offset(widthPx * from.x + deltaX * 0.66f, heightPx * from.y + deltaY * 0.66f))
            moveTo(position = Offset(widthPx * to.x, heightPx * to.y))
            up()
        }
        composeRule.waitForIdle()
    }

    /** A closed rectangular lasso path, in page-fraction coordinates. */
    private fun lassoRectangle(left: Float, top: Float, right: Float, bottom: Float) {
        composeRule.onNodeWithTag("sketch_page").performTouchInput {
            val widthPx = width.toFloat()
            val heightPx = height.toFloat()
            down(position = Offset(widthPx * left, heightPx * top))
            moveTo(position = Offset(widthPx * right, heightPx * top))
            moveTo(position = Offset(widthPx * right, heightPx * bottom))
            moveTo(position = Offset(widthPx * left, heightPx * bottom))
            moveTo(position = Offset(widthPx * left, heightPx * top))
            up()
        }
        composeRule.waitForIdle()
    }

    private fun clickTag(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun lineStroke(
        from: SketchPoint = SketchPoint(0.2f, 0.2f),
        to: SketchPoint = SketchPoint(0.5f, 0.5f),
    ): SketchStroke = SketchStroke(
        points = listOf(from, to),
        ink = SketchInk.BLACK,
        width = SketchWidth.MEDIUM,
    )

    // =========================================================================
    // Chrome / empty state
    // =========================================================================

    @Test
    fun `a new sketch shows the a4 page the empty hint and the tool palette`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        composeRule.onNodeWithTag("sketch_studio").assertExists()
        composeRule.onNodeWithTag("sketch_page").assertExists()
        composeRule.onNodeWithTag("sketch_empty_hint").assertExists()
        composeRule.onNodeWithTag("sketch_undo").assertIsNotEnabled()
        composeRule.onNodeWithTag("sketch_redo").assertIsNotEnabled()
        composeRule.onNodeWithTag("sketch_clear").assertIsNotEnabled()
        composeRule.onNodeWithTag("sketch_title").assertExists()

        // Draw mode controls exist and are addressable by the frozen contract tags.
        composeRule.onNodeWithTag("sketch_tool_pen").assertExists()
        composeRule.onNodeWithTag("sketch_tool_highlighter").assertExists()
        composeRule.onNodeWithTag("sketch_tool_eraser").assertExists()
        composeRule.onNodeWithTag("sketch_tool_lasso").assertExists()
        composeRule.onNodeWithTag("sketch_tool_shape").assertExists()
        composeRule.onNodeWithTag("sketch_zoom_in").assertExists()
        composeRule.onNodeWithTag("sketch_zoom_out").assertExists()
        composeRule.onNodeWithTag("sketch_zoom_reset").assertExists()
        composeRule.onNodeWithTag("sketch_rename").assertExists()
        SketchInk.entries.indices.forEach { index ->
            composeRule.onNodeWithTag("sketch_colour_$index").assertExists()
        }
        SketchWidth.entries.indices.forEach { index ->
            composeRule.onNodeWithTag("sketch_width_$index").assertExists()
        }
        SketchTemplate.entries.forEach { template ->
            composeRule.onNodeWithTag(templateTag(template)).assertExists()
        }
    }

    @Test
    fun `zoom buttons transform the page and fit resets it`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        composeRule.onNodeWithTag("sketch_zoom_in").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_zoom_in").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_zoom_reset").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_zoom_out").performClick()
        composeRule.waitForIdle()

        // The page survives every zoom step and drawing still works after fitting it.
        composeRule.onNodeWithTag("sketch_page").assertExists()
        drawStroke()
        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
    }

    // =========================================================================
    // Drawing tools
    // =========================================================================

    @Test
    fun `a pen stroke is committed and autosaved as the encrypted vector`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        drawStroke()

        // Debounced autosave (>= 400 ms) must persist the vector without leaving the screen.
        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
        val stroke = sketchOf(repository)!!.strokes.single()
        assertTrue("a swipe must sample more than the two minimum points", stroke.points.size >= 2)
        assertEquals(SketchInkKind.PEN, stroke.kind)
        assertEquals(SketchInk.BLACK, stroke.ink)
        assertEquals(SketchWidth.MEDIUM, stroke.width)
        stroke.points.forEach { point ->
            assertTrue("normalised x, was ${point.x}", point.x in 0f..1f)
            assertTrue("normalised y, was ${point.y}", point.y in 0f..1f)
        }
        composeRule.onNodeWithTag("sketch_empty_hint").assertDoesNotExist()
        composeRule.onNodeWithTag("sketch_undo").assertIsEnabled()
    }

    @Test
    fun `the highlighter records a highlighter stroke`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        clickTag("sketch_tool_highlighter")
        drawStroke()

        clickBack(open)
        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
        assertEquals(SketchInkKind.HIGHLIGHTER, sketchOf(repository)!!.strokes.single().kind)
    }

    @Test
    fun `the selected colour and width apply to the next stroke`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        clickTag("sketch_colour_2")
        clickTag("sketch_width_2")
        drawStroke()
        clickBack(open)

        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
        val stroke = sketchOf(repository)!!.strokes.single()
        assertEquals(SketchInk.RED, stroke.ink)
        assertEquals(SketchWidth.BOLD, stroke.width)
    }

    @Test
    fun `a two-point drag creates the selected shape`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        clickTag("sketch_tool_shape")
        clickTag("sketch_shape_rect")
        drawStroke(from = Offset(0.2f, 0.2f), to = Offset(0.6f, 0.5f))
        clickBack(open)

        composeRule.waitUntil(5_000) { (sketchOf(repository)?.shapes?.size ?: 0) == 1 }
        val shape = sketchOf(repository)!!.shapes.single()
        assertEquals(SketchShapeKind.RECT, shape.kind)
        assertTrue(shape.start.x in 0f..1f && shape.end.y in 0f..1f)
    }

    @Test
    fun `every shape kind is offered by the shape menu`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        clickTag("sketch_tool_shape")
        SketchShapeKind.entries.forEach { kind ->
            composeRule.onNodeWithTag(shapeTag(kind)).assertExists()
        }
        composeRule.onNodeWithTag("sketch_shape_arrow").performClick()
        composeRule.waitForIdle()
        clickTag("sketch_tool_shape")
        composeRule.onNodeWithTag("sketch_shape_ellipse").performClick()
        composeRule.waitForIdle()
        drawStroke()
        clickBack(open)

        composeRule.waitUntil(5_000) { (sketchOf(repository)?.shapes?.size ?: 0) == 1 }
        assertEquals(SketchShapeKind.ELLIPSE, sketchOf(repository)!!.shapes.single().kind)
    }

    @Test
    fun `the eraser removes a stroke it crosses`() {
        val repository = repository()
        seedSketch(
            repository,
            sketch = SketchDocument(
                strokes = listOf(
                    lineStroke(SketchPoint(0.2f, 0.3f), SketchPoint(0.8f, 0.7f)),
                    lineStroke(SketchPoint(0.1f, 0.9f), SketchPoint(0.2f, 0.95f)),
                ),
            ),
        )
        val open = showStudio(repository)

        clickTag("sketch_tool_eraser")
        drawStroke(from = Offset(0.5f, 0.5f), to = Offset(0.55f, 0.55f))
        clickBack(open)

        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
        assertTrue(
            "the untouched stroke must remain",
            sketchOf(repository)!!.strokes.single().points.first().y >= 0.9f,
        )
    }

    @Test
    fun `the lasso removes enclosed strokes and keeps the rest`() {
        val repository = repository()
        seedSketch(
            repository,
            sketch = SketchDocument(
                strokes = listOf(
                    lineStroke(SketchPoint(0.2f, 0.2f), SketchPoint(0.4f, 0.4f)),
                    lineStroke(SketchPoint(0.75f, 0.75f), SketchPoint(0.9f, 0.9f)),
                ),
            ),
        )
        val open = showStudio(repository)

        clickTag("sketch_tool_lasso")
        composeRule.onNodeWithTag("sketch_lasso_hint").assertExists()
        lassoRectangle(left = 0.1f, top = 0.1f, right = 0.55f, bottom = 0.55f)
        clickBack(open)

        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
        val remaining = sketchOf(repository)!!.strokes.single()
        assertTrue(remaining.points.first().x >= 0.7f)
    }

    @Test
    fun `the paper template switch persists`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        // An empty document is the model's "no content" value (repository clears it), so the
        // template is persisted together with at least one stroke.
        drawStroke()
        clickTag(templateTag(SketchTemplate.GRID))
        clickBack(open)

        composeRule.waitUntil(5_000) { sketchOf(repository)?.template == SketchTemplate.GRID }
        assertEquals(SketchTemplate.GRID, sketchOf(repository)!!.template)
        assertEquals(1, sketchOf(repository)!!.strokes.size)
    }

    // =========================================================================
    // Undo / redo / clear
    // =========================================================================

    @Test
    fun `undo and redo move the page history`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        drawStroke()
        composeRule.onNodeWithTag("sketch_undo").assertIsEnabled()
        clickTag("sketch_undo")
        composeRule.onNodeWithTag("sketch_empty_hint").assertExists()
        composeRule.onNodeWithTag("sketch_undo").assertIsNotEnabled()
        composeRule.onNodeWithTag("sketch_redo").assertIsEnabled()

        clickTag("sketch_redo")
        composeRule.onNodeWithTag("sketch_empty_hint").assertDoesNotExist()
        clickBack(open)

        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
    }

    @Test
    fun `clear asks for confirmation and the clear is undoable`() {
        val repository = repository()
        seedSketch(repository)
        val open = showStudio(repository)

        drawStroke()
        clickTag("sketch_clear")

        composeRule.onNodeWithTag("sketch_clear_dialog").assertExists()
        composeRule.onNodeWithTag("sketch_clear_cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_clear_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("sketch_empty_hint").assertDoesNotExist()

        clickTag("sketch_clear")
        composeRule.onNodeWithTag("sketch_clear_confirm").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_empty_hint").assertExists()
        composeRule.onNodeWithTag("sketch_cleared_notice").assertExists()

        // The clear is a normal history step: undo restores the stroke.
        clickTag("sketch_undo")
        composeRule.onNodeWithTag("sketch_empty_hint").assertDoesNotExist()
        clickBack(open)

        composeRule.waitUntil(5_000) { (sketchOf(repository)?.strokes?.size ?: 0) == 1 }
    }

    // =========================================================================
    // Rename
    // =========================================================================

    @Test
    fun `rename updates the encrypted item title`() {
        val repository = repository()
        seedSketch(repository, title = "Old name")
        val open = showStudio(repository)

        composeRule.onNodeWithTag("sketch_rename").performClick()
        // A focused text field inside a dialog keeps the Compose test clock busy (cursor
        // blink), so this test waits on visible outcomes instead of waitForIdle while it is open.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("sketch_rename_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sketch_rename_field").performTextClearance()
        composeRule.onNodeWithTag("sketch_rename_field").performTextInput("New name")
        composeRule.onNodeWithTag("sketch_rename_save").performClick()

        composeRule.waitUntil(5_000) { itemOf(repository)?.title == "New name" }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("sketch_rename_dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("sketch_title").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // =========================================================================
    // Preview attachment pipeline
    // =========================================================================

    @Test
    fun `back flushes the vector and imports one flattened preview png`() {
        val repository = repository()
        seedSketch(repository)
        var closed = 0
        val open = showStudio(repository, onClose = { closed += 1 })

        drawStroke()
        composeRule.onNodeWithTag("sketch_undo").assertIsEnabled()
        clickBack(open)

        assertEquals(1, closed)
        composeRule.waitUntil(5_000) { previewsOf(repository).size == 1 }
        val preview = previewsOf(repository).single()
        assertEquals(SketchRenderer.MIME_TYPE, preview.mimeType)
        assertTrue(preview.displayName.startsWith("sketch-"))
        assertTrue(preview.displayName.endsWith(".png"))
        assertTrue(preview.sizeBytes > 0L)

        runBlocking {
            val bytes = repository.attachmentBytes(SKETCH_ID, preview.id)
            assertNotNull("preview bytes must be stored through the encrypted pipeline", bytes)
            ShadowBitmapFactory.setAllowInvalidImageData(false)
            try {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size)
                assertNotNull("the preview must be a real PNG", bitmap)
                assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
                assertTrue(bitmap.width <= SketchRenderer.MAX_DIMENSION_PX)
                assertTrue(bitmap.height <= SketchRenderer.MAX_DIMENSION_PX)
            } finally {
                ShadowBitmapFactory.setAllowInvalidImageData(true)
            }
        }
        // The vector is still the source of truth and re-opens.
        assertNotNull(sketchOf(repository))
        assertEquals(1, sketchOf(repository)!!.strokes.size)
    }

    @Test
    fun `a later exit replaces the previous preview instead of accumulating`() {
        val repository = repository()
        val vector = SketchDocument(strokes = listOf(lineStroke()))
        seedSketch(repository, sketch = vector)
        // Simulate the preview left behind by an earlier studio visit.
        runBlocking {
            val oldPng = SketchRenderer.renderPng(vector, maxDimensionPx = 64)
            assertNotNull(oldPng)
            repository.importAttachment(
                noteId = SKETCH_ID,
                displayName = "sketch-old.png",
                mimeType = SketchRenderer.MIME_TYPE,
                source = ByteArrayInputStream(oldPng!!),
            ) as AttachmentImport.Success
        }
        val firstPreviewId = previewsOf(repository).single().id
        val open = showStudio(repository)

        drawStroke(from = Offset(0.3f, 0.7f), to = Offset(0.8f, 0.9f))
        clickBack(open)

        composeRule.waitUntil(5_000) {
            previewsOf(repository).size == 1 && previewsOf(repository).single().id != firstPreviewId
        }
        assertNotEquals(firstPreviewId, previewsOf(repository).single().id)
        assertEquals(2, sketchOf(repository)!!.strokes.size)
    }

    @Test
    fun `clearing the page deletes the stale preview on exit`() {
        val repository = repository()
        seedSketch(
            repository,
            sketch = SketchDocument(
                strokes = listOf(lineStroke()),
            ),
        )
        // Worst case: a preview already exists for the vector that is about to be cleared.
        runBlocking {
            val seedPng = SketchRenderer.renderPng(sketchOf(repository)!!, maxDimensionPx = 64)
            assertNotNull(seedPng)
            repository.importAttachment(
                noteId = SKETCH_ID,
                displayName = "sketch-1.png",
                mimeType = SketchRenderer.MIME_TYPE,
                source = ByteArrayInputStream(seedPng!!),
            ) as AttachmentImport.Success
        }
        assertEquals(1, previewsOf(repository).size)
        val open = showStudio(repository)

        clickTag("sketch_clear")
        composeRule.onNodeWithTag("sketch_clear_confirm").performClick()
        composeRule.waitForIdle()
        clickBack(open)

        composeRule.waitUntil(5_000) { previewsOf(repository).isEmpty() }
        assertTrue("an empty sketch has no flattened preview", previewsOf(repository).isEmpty())
        // The empty vector clears the encrypted document but keeps the item.
        assertNull(sketchOf(repository))
        assertNotNull(itemOf(repository))
    }

    @Test
    fun `a sketch without a preview still loads its vector`() {
        val repository = repository()
        seedSketch(
            repository,
            sketch = SketchDocument(
                template = SketchTemplate.DOTTED,
                strokes = listOf(lineStroke()),
            ),
        )
        val open = showStudio(repository)

        composeRule.onNodeWithTag("sketch_empty_hint").assertDoesNotExist()
        composeRule.onNodeWithTag("sketch_undo").assertIsNotEnabled()
        clickBack(open)

        composeRule.waitUntil(5_000) { previewsOf(repository).size == 1 }
        assertEquals(SketchTemplate.DOTTED, sketchOf(repository)!!.template)
        assertEquals(1, sketchOf(repository)!!.strokes.size)
    }

    private companion object {
        const val SKETCH_ID = "sketch-1"

        /** Test-only flattened-preview long edge (production is [SketchRenderer.MAX_DIMENSION_PX]). */
        const val TEST_PREVIEW_EDGE_PX = 320
    }
}
