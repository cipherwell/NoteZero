package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the locked notes/folders masking in [NotesIndex] (decision #15):
 * a locked record that the session has not re-authenticated must not contribute its title,
 * body, tags, folder path or preview to rows, search results, tag chips or counts.
 */
class NotesIndexLockTest {

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
        locked: Boolean = false,
        sortOrder: Int = 0,
    ) = Folder(id = id, name = name, parentId = parentId, sortOrder = sortOrder, locked = locked)

    private fun item(
        id: String,
        title: String = "Title",
        body: String = "",
        folderId: String? = null,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        favorite: Boolean = false,
        trashed: Boolean = false,
        locked: Boolean = false,
        updatedAt: Long = 1_000L,
    ) = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        pinned = pinned,
        favorite = favorite,
        trashed = trashed,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        locked = locked,
    )

    @Test
    fun `locked unverified note renders as a content-free mask`() {
        val state = NotesIndex.build(
            items = listOf(
                item(
                    "s",
                    title = "Secret title",
                    body = "secret body",
                    tags = listOf("private"),
                    folderId = "f",
                    locked = true,
                ),
            ),
            folders = listOf(folder("f", "Personal")),
            controls = NotesControls(),
            isUnlocked = true,
        )

        val row = state.recent.single()
        assertTrue(row.masked)
        assertEquals("Locked note", row.item.title)
        assertEquals("", row.item.body)
        assertTrue(row.item.tags.isEmpty())
        assertNull(row.item.folderId)
        assertNull(row.folderPath)
    }

    @Test
    fun `verified locked note renders normally`() {
        val state = NotesIndex.build(
            items = listOf(
                item("s", title = "Secret title", body = "secret body", locked = true),
            ),
            folders = emptyList(),
            controls = NotesControls(),
            isUnlocked = true,
            verifiedLockedIds = setOf("s"),
        )

        val row = state.recent.single()
        assertFalse(row.masked)
        assertEquals("Secret title", row.item.title)
        assertEquals("secret body", row.item.body)
    }

    @Test
    fun `masked notes never appear in search results`() {
        val locked = item(
            "s",
            title = "Passport scan",
            body = "number 12345",
            tags = listOf("identity"),
            folderId = "f",
            locked = true,
        )
        val plain = item("p", title = "Groceries", body = "milk")
        val folders = listOf(folder("f", "Papers"))
        listOf("Passport", "12345", "identity", "Papers", "Locked note").forEach { query ->
            val state = NotesIndex.build(
                items = listOf(locked, plain),
                folders = folders,
                controls = NotesControls(query = query),
                isUnlocked = true,
            )
            assertTrue(
                "query '$query' must not match a masked note",
                state.searchResults.none { it.item.id == "s" },
            )
        }

        val plainState = NotesIndex.build(
            items = listOf(locked, plain),
            folders = folders,
            controls = NotesControls(query = "milk"),
            isUnlocked = true,
        )
        assertEquals(listOf("p"), plainState.searchResults.map { it.item.id })
    }

    @Test
    fun `revealed locked note becomes searchable`() {
        val state = NotesIndex.build(
            items = listOf(item("s", title = "Passport scan", locked = true)),
            folders = emptyList(),
            controls = NotesControls(query = "passport"),
            isUnlocked = true,
            verifiedLockedIds = setOf("s"),
        )
        assertEquals(listOf("s"), state.searchResults.map { it.item.id })
        assertFalse(state.searchResults.single().masked)
    }

    @Test
    fun `masked tags are excluded from the tag chips`() {
        val state = NotesIndex.build(
            items = listOf(
                item("s", tags = listOf("identity", "private"), locked = true),
                item("p", tags = listOf("errand")),
            ),
            folders = emptyList(),
            controls = NotesControls(),
            isUnlocked = true,
        )
        assertEquals(listOf("errand"), state.tags)
    }

    @Test
    fun `locked folder hides its name counts and descendants`() {
        val state = NotesIndex.build(
            items = listOf(
                item("direct", title = "Direct note", folderId = "locked"),
                item("nested", title = "Nested note", folderId = "child"),
                item("root", title = "Root note"),
            ),
            folders = listOf(
                folder("locked", "Secret folder", locked = true),
                folder("child", "Secret subfolder", parentId = "locked"),
                folder("other", "Other"),
            ),
            controls = NotesControls(section = NotesSection.FOLDERS),
            isUnlocked = true,
        )

        val lockedRow = state.folders.single { it.folder.id == "locked" }
        assertTrue(lockedRow.masked)
        assertEquals("Locked folder", lockedRow.folder.name)
        assertEquals(0, lockedRow.itemCount)
        assertEquals(0, lockedRow.childFolderCount)

        // The child folder and every note inside the locked subtree are hidden entirely.
        assertTrue(state.folders.none { it.folder.id == "child" })
        assertEquals(listOf("root"), state.recent.map { it.item.id })
        assertEquals(listOf("other"), state.allFolders.map { it.folder.id })
        assertTrue(state.trash.isEmpty())
    }

    @Test
    fun `locked folder contents reappear when verified`() {
        val state = NotesIndex.build(
            items = listOf(
                item("direct", title = "Direct note", folderId = "locked"),
                item("nested", title = "Nested note", folderId = "child"),
            ),
            folders = listOf(
                folder("locked", "Secret folder", locked = true),
                folder("child", "Secret subfolder", parentId = "locked"),
            ),
            controls = NotesControls(section = NotesSection.FOLDERS),
            isUnlocked = true,
            verifiedLockedIds = setOf("locked"),
        )

        val lockedRow = state.folders.single { it.folder.id == "locked" }
        assertFalse(lockedRow.masked)
        assertEquals("Secret folder", lockedRow.folder.name)
        assertEquals(1, lockedRow.itemCount)
        assertEquals(1, lockedRow.childFolderCount)
        assertEquals(setOf("direct", "nested"), state.recent.map { it.item.id }.toSet())
    }

    @Test
    fun `clearing verification re-masks immediately`() {
        val locked = item("s", title = "Secret title", locked = true)
        val revealed = NotesIndex.build(
            items = listOf(locked),
            folders = emptyList(),
            controls = NotesControls(),
            isUnlocked = true,
            verifiedLockedIds = setOf("s"),
        )
        assertEquals("Secret title", revealed.recent.single().item.title)

        val reMasked = NotesIndex.build(
            items = listOf(locked),
            folders = emptyList(),
            controls = NotesControls(),
            isUnlocked = true,
            verifiedLockedIds = emptySet(),
        )
        assertEquals("Locked note", reMasked.recent.single().item.title)
        assertTrue(reMasked.recent.single().masked)
    }

    @Test
    fun `trashed locked note is masked in the trash`() {
        val state = NotesIndex.build(
            items = listOf(item("s", title = "Deleted secret", trashed = true, locked = true)),
            folders = emptyList(),
            controls = NotesControls(section = NotesSection.TRASH),
            isUnlocked = true,
        )
        assertEquals("Locked note", state.trash.single().item.title)
        assertTrue(state.trash.single().masked)
    }

    @Test
    fun `re-masking while inside a locked folder falls back to the root list`() {
        val state = NotesIndex.build(
            items = listOf(item("inside", title = "Inside", folderId = "locked")),
            folders = listOf(
                folder("locked", "Secret folder", locked = true),
                folder("sibling", "Sibling"),
            ),
            controls = NotesControls(section = NotesSection.FOLDERS, currentFolderId = "locked"),
            isUnlocked = true,
        )

        assertNull(state.currentFolderId)
        assertEquals(listOf("Locked folder", "Sibling"), state.folders.map { it.folder.name })
        assertTrue(state.folderItems.isEmpty())
        assertTrue(state.breadcrumb.isEmpty())
    }
}
