package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesIndexTest {

    private fun folder(id: String, name: String, parentId: String? = null, sortOrder: Int = 0) =
        Folder(id = id, name = name, parentId = parentId, sortOrder = sortOrder)

    private fun item(
        id: String,
        title: String = "Title",
        body: String = "",
        folderId: String? = null,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        favorite: Boolean = false,
        trashed: Boolean = false,
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
    )

    @Test
    fun `folder path walks root to leaf`() {
        val folders = listOf(
            folder("root", "Work"),
            folder("child", "Projects", parentId = "root"),
            folder("grandchild", "NoteZero", parentId = "child"),
        )
        assertEquals(
            listOf("Work", "Projects", "NoteZero"),
            NotesIndex.folderPath(folders, "grandchild").map { it.name },
        )
        assertNull(NotesIndex.folderPathLabel(folders, null))
        assertEquals("Work / Projects", NotesIndex.folderPathLabel(folders, "child"))
        assertTrue(NotesIndex.folderPath(folders, "unknown").isEmpty())
    }

    @Test
    fun `folder path is cycle safe`() {
        val folders = listOf(
            folder("a", "A", parentId = "b"),
            folder("b", "B", parentId = "a"),
        )
        val path = NotesIndex.folderPath(folders, "a")
        assertTrue(path.isNotEmpty())
        // The guard must bound the walk instead of looping forever on a corrupt tree.
        assertTrue(path.size < 100)
    }

    @Test
    fun `flatten folders is pre-order and keeps orphans visible`() {
        val folders = listOf(
            folder("a", "Alpha"),
            folder("b", "Beta", parentId = "a"),
            folder("c", "Gamma", parentId = "a", sortOrder = 1),
            folder("orphan", "Lost", parentId = "gone"),
        )
        val flat = NotesIndex.flattenFolders(folders)
        assertEquals(listOf("a", "b", "c", "orphan"), flat.map { it.folder.id })
        assertEquals(0, flat.first { it.folder.id == "a" }.depth)
        assertEquals(1, flat.first { it.folder.id == "b" }.depth)
        assertEquals(1, flat.first { it.folder.id == "c" }.depth)
        // Orphan is surfaced at root so it can still be moved out of.
        assertEquals(0, flat.first { it.folder.id == "orphan" }.depth)
    }

    @Test
    fun `collect tags deduplicates case-insensitively and sorts`() {
        val tags = NotesIndex.collectTags(
            listOf(
                item("1", tags = listOf("Work", "home")),
                item("2", tags = listOf("work", "  errand  ", "")),
            ),
        )
        assertEquals(listOf("errand", "home", "Work"), tags)
    }

    @Test
    fun `matches query searches title body tags and folder`() {
        val note = item("1", title = "Groceries", body = "milk and eggs", folderId = "f1", tags = listOf("errand"))
        assertTrue(NotesIndex.matchesQuery(note, "Home / Kitchen", "grocer"))
        assertTrue(NotesIndex.matchesQuery(note, "Home / Kitchen", "EGGS"))
        assertTrue(NotesIndex.matchesQuery(note, "Home / Kitchen", "kitchen"))
        assertTrue(NotesIndex.matchesQuery(note, "Home / Kitchen", "errand"))
        assertFalse(NotesIndex.matchesQuery(note, "Home / Kitchen", "car"))
        assertTrue(NotesIndex.matchesQuery(note, null, "  "))
    }

    @Test
    fun `locked build exposes no decrypted content`() {
        val state = NotesIndex.build(
            items = listOf(item("1", title = "Secret", body = "secret body")),
            folders = listOf(folder("f1", "Secret folder")),
            controls = NotesControls(query = "secret"),
            isUnlocked = false,
        )
        assertFalse(state.isUnlocked)
        assertTrue(state.pinned.isEmpty())
        assertTrue(state.recent.isEmpty())
        assertTrue(state.searchResults.isEmpty())
        assertTrue(state.folders.isEmpty())
        assertTrue(state.allFolders.isEmpty())
        assertEquals(0, state.totalNotes)
    }

    @Test
    fun `favourites only never leaks non favourite notes`() {
        val state = NotesIndex.build(
            items = listOf(
                item("fav", favorite = true, updatedAt = 2),
                item("plain", updatedAt = 3),
                item("pinned", pinned = true, updatedAt = 4),
            ),
            folders = emptyList(),
            controls = NotesControls(favoritesOnly = true),
            isUnlocked = true,
        )
        assertEquals(listOf("fav"), state.favorites.map { it.item.id })
        assertTrue(state.recent.isEmpty())
        assertTrue(state.pinned.isEmpty())
    }
}
