package com.cipherwell.notezero.ui.workspace

import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.notes.FolderRowModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the pure workspace query rules: fail-closed visibility, search,
 * filtering, sorting, storage totals, selection algebra and the move-to-folder flattening.
 *
 * No Android, no Compose, no Robolectric: these run in milliseconds and pin the contract that
 * masked/hidden content can never reach the UI model.
 */
class WorkspaceQueryTest {

    // ---- Fixtures ------------------------------------------------------------

    private fun attachment(
        id: String,
        name: String,
        mime: String = "application/octet-stream",
        size: Long = 1_024L,
    ) = AttachmentRef(
        id = id,
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        createdAt = 1L,
        sha256 = "0".repeat(64),
    )

    private fun item(
        id: String,
        kind: ItemKind = ItemKind.NOTE,
        title: String = "Title $id",
        body: String = "",
        tags: List<String> = emptyList(),
        folderId: String? = null,
        trashed: Boolean = false,
        locked: Boolean = false,
        updatedAt: Long = 1_000L,
        attachments: List<AttachmentRef> = emptyList(),
        readingState: ReadingState? = null,
    ) = Item(
        id = id,
        kind = kind,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        trashed = trashed,
        locked = locked,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        version = 1,
        attachments = attachments,
        readingState = readingState,
    )

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
        locked: Boolean = false,
        sortOrder: Int = 0,
    ) = Folder(
        id = id,
        name = name,
        parentId = parentId,
        sortOrder = sortOrder,
        createdAt = 1L,
        locked = locked,
    )

    private fun rowsOf(
        items: List<Item>,
        folders: List<Folder> = emptyList(),
        verified: Set<String> = emptySet(),
    ) = WorkspaceQuery.visibleRows(items, folders, verified)

    // ---- Visibility gate -----------------------------------------------------

    @Test
    fun `visible rows exclude trashed masked and hidden folder items`() {
        val folders = listOf(
            folder("f-open", "Projects"),
            folder("f-locked", "Secret", locked = true),
            folder("f-child", "Secret child", parentId = "f-locked"),
        )
        val items = listOf(
            item("plain", folderId = "f-open"),
            item("trashed", trashed = true),
            item("locked-unverified", locked = true),
            item("inside-locked-folder", folderId = "f-locked"),
            item("inside-hidden-child", folderId = "f-child"),
        )

        val rows = rowsOf(items, folders)

        assertEquals(listOf("plain"), rows.map { it.id })
        assertEquals("Projects", rows.single().folderName)
    }

    @Test
    fun `verified locked items and folders become visible with their real names`() {
        val folders = listOf(folder("f-locked", "Secret", locked = true))
        val items = listOf(
            item("locked", locked = true, folderId = "f-locked", title = "Revealed note"),
            item("child", folderId = "f-locked", title = "Folder note"),
        )

        val rows = rowsOf(items, folders, verified = setOf("locked", "f-locked"))

        assertEquals(setOf("locked", "child"), rows.map { it.id }.toSet())
        assertEquals("Secret", rows.first { it.id == "locked" }.folderName)
        assertEquals("Secret", rows.first { it.id == "child" }.folderName)
    }

    @Test
    fun `hidden folder names never enter the visible folder map`() {
        val folders = listOf(
            folder("f-locked", "Top secret folder", locked = true),
            folder("f-child", "Child secret", parentId = "f-locked"),
            folder("f-open", "Open"),
        )

        val names = WorkspaceQuery.visibleFolderNames(folders, emptySet())

        assertEquals(mapOf("f-open" to "Open"), names)
        assertFalse(names.values.any { it.contains("secret", ignoreCase = true) })
    }

    // ---- Filtering -----------------------------------------------------------

    @Test
    fun `type filter chips select exactly one kind and all keeps every row`() {
        val items = listOf(
            item("note", ItemKind.NOTE),
            item("code", ItemKind.CODE_FILE),
            item("pdf", ItemKind.PDF_FILE),
            item("book", ItemKind.BOOK_FILE),
            item("sketch", ItemKind.SKETCH_FILE),
        )
        val rows = rowsOf(items)
        assertEquals(5, rows.size)

        fun filtered(filter: WorkspaceFilter) =
            WorkspaceQuery.build(items, emptyList(), emptySet(), filter, "", WorkspaceSort.UPDATED)
                .rows
                .map { it.id }

        assertEquals(listOf("note"), filtered(WorkspaceFilter.NOTES))
        assertEquals(listOf("code"), filtered(WorkspaceFilter.CODE))
        assertEquals(listOf("pdf"), filtered(WorkspaceFilter.PDFS))
        assertEquals(listOf("book"), filtered(WorkspaceFilter.BOOKS))
        assertEquals(listOf("sketch"), filtered(WorkspaceFilter.DRAWINGS))
        assertEquals(setOf("note", "code", "pdf", "book", "sketch"), filtered(WorkspaceFilter.ALL).toSet())
    }

    // ---- Search --------------------------------------------------------------

    @Test
    fun `search is case insensitive over title body tags file name and author`() {
        val rows = rowsOf(
            listOf(
                item("title", title = "Meeting Notes"),
                item("body", body = "The quarterly budget is attached"),
                item("tag", tags = listOf("Errand")),
                item(
                    "file",
                    ItemKind.PDF_FILE,
                    attachments = listOf(attachment("a1", "Passport Scan.pdf")),
                ),
                item(
                    "book",
                    ItemKind.BOOK_FILE,
                    readingState = ReadingState(author = "Ursula Le Guin"),
                ),
                item("other", title = "Unrelated"),
            ),
        )

        fun found(query: String) = WorkspaceQuery.search(rows, query).map { it.id }

        assertEquals(listOf("title"), found("MEETING"))
        assertEquals(listOf("body"), found("quarterly"))
        assertEquals(listOf("tag"), found("errand"))
        assertEquals(listOf("file"), found("passport"))
        assertEquals(listOf("book"), found("le guin"))
        assertEquals(listOf("file"), found(".PDF"))
        assertTrue(found("no-such-phrase").isEmpty())
    }

    @Test
    fun `search can never match masked content`() {
        val items = listOf(
            item("secret", title = "Passport scan", body = "number 12345", tags = listOf("identity"), locked = true),
            item("open", title = "Kotlin", body = "coroutines"),
        )

        val index = WorkspaceQuery.build(items, emptyList(), emptySet(), WorkspaceFilter.ALL, "Passport", WorkspaceSort.UPDATED)

        assertTrue(index.rows.isEmpty())
        assertTrue(index.allRows.none { it.id == "secret" })
        assertTrue(index.rows.none { it.title.contains("Passport") || it.item.body.contains("12345") })
    }

    @Test
    fun `body matches attach a bounded snippet while title-only matches do not`() {
        val longBody = "intro " + "filler ".repeat(30) + "NEEDLE in the middle " + "tail ".repeat(30)
        val rows = rowsOf(
            listOf(
                item("body-match", title = "Body", body = longBody),
                item("title-match", title = "Needle title", body = "nothing here"),
            ),
        )

        val byBody = WorkspaceQuery.search(rows, "needle").first { it.id == "body-match" }
        val snippet = byBody.snippet
        assertNotNull(snippet)
        assertTrue(snippet!!.contains("NEEDLE"))
        assertTrue("snippet stays short: ${snippet.length}", snippet.length <= WorkspaceQuery.MAX_SNIPPET_CHARS + 2)

        val byTitle = WorkspaceQuery.search(rows, "needle").first { it.id == "title-match" }
        assertNull(byTitle.snippet)
    }

    @Test
    fun `snippet helper collapses whitespace and handles edge cases`() {
        assertNull(WorkspaceQuery.searchSnippet("", "x"))
        assertNull(WorkspaceQuery.searchSnippet("some text", " "))
        assertNull(WorkspaceQuery.searchSnippet("some text", "missing"))
        assertEquals("alpha beta", WorkspaceQuery.searchSnippet("alpha   beta", "beta"))
        val excerpt = WorkspaceQuery.searchSnippet("a".repeat(500) + " needle", "needle", maxLength = 40)
        assertNotNull(excerpt)
        assertTrue(excerpt!!.contains("needle"))
        assertTrue(excerpt.startsWith("…"))
        assertTrue(excerpt.length <= 42)
    }

    // ---- Sorting -------------------------------------------------------------

    @Test
    fun `sort defaults to last updated desc and name sorts case insensitively`() {
        val items = listOf(
            item("old", title = "Zebra", updatedAt = 1_000L),
            item("new", title = "alpha", updatedAt = 9_000L),
            item("mid", title = "Beta", updatedAt = 5_000L),
        )
        val rows = rowsOf(items)

        assertEquals(
            listOf("new", "mid", "old"),
            WorkspaceQuery.sort(rows, WorkspaceSort.UPDATED).map { it.id },
        )
        assertEquals(
            listOf("new", "mid", "old"),
            WorkspaceQuery.sort(rows, WorkspaceSort.NAME).map { it.id },
        )
        val alphabetical = rowsOf(
            listOf(
                item("b", title = "beta"),
                item("A", title = "Alpha"),
                item("c", title = "charlie"),
            ),
        )
        assertEquals(
            listOf("A", "b", "c"),
            WorkspaceQuery.sort(alphabetical, WorkspaceSort.NAME).map { it.id },
        )
    }

    @Test
    fun `build applies filter and search on top of the sorted visible set`() {
        val items = listOf(
            item("note-a", ItemKind.NOTE, title = "Plan", updatedAt = 2_000L),
            item("note-b", ItemKind.NOTE, title = "Plan B", updatedAt = 1_000L),
            item("pdf-a", ItemKind.PDF_FILE, title = "Plan pdf", updatedAt = 3_000L),
        )

        val index = WorkspaceQuery.build(
            items = items,
            folders = emptyList(),
            verifiedLockedIds = emptySet(),
            filter = WorkspaceFilter.NOTES,
            query = "plan",
            sort = WorkspaceSort.UPDATED,
        )

        assertEquals(listOf("note-a", "note-b"), index.rows.map { it.id })
        // allRows is the visible set before the filter/search: storage/summary decisions use it.
        assertEquals(setOf("note-a", "note-b", "pdf-a"), index.allRows.map { it.id }.toSet())
    }

    @Test
    fun `empty vault and no-match states are distinguishable`() {
        val empty = WorkspaceQuery.build(emptyList(), emptyList(), emptySet(), WorkspaceFilter.ALL, "", WorkspaceSort.UPDATED)
        assertTrue(empty.isEmpty)
        assertFalse(empty.hasNoMatches)

        val noMatches = WorkspaceQuery.build(
            listOf(item("note", ItemKind.NOTE)),
            emptyList(),
            emptySet(),
            WorkspaceFilter.PDFS,
            "",
            WorkspaceSort.UPDATED,
        )
        assertFalse(noMatches.isEmpty)
        assertTrue(noMatches.hasNoMatches)

        val emptySearch = WorkspaceQuery.build(
            listOf(item("note", ItemKind.NOTE, title = "Alpha")),
            emptyList(),
            emptySet(),
            WorkspaceFilter.ALL,
            "zzz",
            WorkspaceSort.UPDATED,
        )
        assertTrue(emptySearch.hasNoMatches)
    }

    // ---- Storage summary -----------------------------------------------------

    @Test
    fun `storage summary counts only visible items and sums visible attachment sizes`() {
        val folders = listOf(folder("f-locked", "Secret", locked = true))
        val visible = item(
            "visible",
            kind = ItemKind.PDF_FILE,
            attachments = listOf(
                attachment("a1", "doc.pdf", size = 100L),
                attachment("a2", "other.pdf", size = 200L),
            ),
        )
        val items = listOf(
            visible,
            item("trashed", trashed = true, attachments = listOf(attachment("t", "t.pdf", size = 9_999L))),
            item("locked", locked = true, attachments = listOf(attachment("l", "l.pdf", size = 8_888L))),
            item("hidden", folderId = "f-locked", attachments = listOf(attachment("h", "h.pdf", size = 7_777L))),
        )

        val index = WorkspaceQuery.build(items, folders, emptySet(), WorkspaceFilter.ALL, "", WorkspaceSort.UPDATED)

        assertEquals(1, index.storage.items)
        assertEquals(2, index.storage.attachments)
        assertEquals(300L, index.storage.attachmentBytes)
        assertEquals(
            "storage summary is independent of the active filter",
            index.storage,
            WorkspaceQuery.build(items, folders, emptySet(), WorkspaceFilter.BOOKS, "zzz", WorkspaceSort.NAME).storage,
        )
    }

    // ---- Selection algebra ---------------------------------------------------

    @Test
    fun `selection toggle select-all and visible retention`() {
        val rows = rowsOf(
            listOf(
                item("a", updatedAt = 3_000L),
                item("b", updatedAt = 2_000L),
                item("c", updatedAt = 1_000L),
            ),
        )

        var selection: Set<String> = emptySet()
        selection = WorkspaceQuery.toggle(selection, "a")
        selection = WorkspaceQuery.toggle(selection, "c")
        assertEquals(setOf("a", "c"), selection)
        selection = WorkspaceQuery.toggle(selection, "a")
        assertEquals(setOf("c"), selection)

        assertEquals(setOf("a", "b", "c"), WorkspaceQuery.selectAll(rows))

        assertEquals(
            listOf("a", "c"),
            WorkspaceQuery.selectedVisibleIds(setOf("a", "c", "gone"), rows),
        )
        assertEquals(
            setOf("a"),
            WorkspaceQuery.retainVisible(setOf("a", "gone"), rows),
        )
        assertTrue(WorkspaceQuery.selectedVisibleIds(emptySet(), rows).isEmpty())
        assertTrue(WorkspaceQuery.retainVisible(emptySet(), rows).isEmpty())
    }

    // ---- Move-to-folder flattening ------------------------------------------

    @Test
    fun `folder choices flatten depth first exclude hidden folders and count items`() {
        val folders = listOf(
            folder("b", "Beta", sortOrder = 1),
            folder("a", "Alpha", sortOrder = 0),
            folder("a1", "Alpha child", parentId = "a"),
            folder("secret", "Hidden vault", locked = true),
            folder("secret-child", "Hidden child", parentId = "secret"),
        )
        val items = listOf(
            item("n1", folderId = "a"),
            item("n2", folderId = "a"),
            item("n3", folderId = "a1"),
            item("trashed", folderId = "a", trashed = true),
            item("hidden", folderId = "secret"),
        )

        val choices: List<FolderRowModel> = WorkspaceQuery.folderChoices(folders, emptySet(), items)

        assertEquals(listOf("Alpha", "Alpha child", "Beta"), choices.map { it.folder.name })
        assertEquals(listOf(0, 1, 0), choices.map { it.depth })
        assertEquals(2, choices.first { it.folder.id == "a" }.itemCount)
        assertEquals(1, choices.first { it.folder.id == "a1" }.itemCount)
        assertEquals(1, choices.first { it.folder.id == "a" }.childFolderCount)
        assertTrue(choices.none { it.folder.id == "secret" || it.folder.id == "secret-child" })
        assertTrue(choices.none { it.folder.name.contains("Hidden") })
        assertTrue(choices.all { !it.masked })
    }

    @Test
    fun `folder choices tolerate orphan folders and cycles`() {
        val folders = listOf(
            folder("orphan", "Orphan", parentId = "missing-parent"),
            folder("x", "X", parentId = "y"),
            folder("y", "Y", parentId = "x"),
        )

        val choices = WorkspaceQuery.folderChoices(folders, emptySet())

        assertEquals(setOf("orphan", "x", "y"), choices.map { it.folder.id }.toSet())
        assertEquals(3, choices.size)
    }

    @Test
    fun `typeOf maps every frozen item kind`() {
        assertEquals(WorkspaceType.NOTE, WorkspaceQuery.typeOf(ItemKind.NOTE))
        assertEquals(WorkspaceType.CODE, WorkspaceQuery.typeOf(ItemKind.CODE_FILE))
        assertEquals(WorkspaceType.PDF, WorkspaceQuery.typeOf(ItemKind.PDF_FILE))
        assertEquals(WorkspaceType.BOOK, WorkspaceQuery.typeOf(ItemKind.BOOK_FILE))
        assertEquals(WorkspaceType.SKETCH, WorkspaceQuery.typeOf(ItemKind.SKETCH_FILE))
    }
}
