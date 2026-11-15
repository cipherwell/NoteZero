package com.cipherwell.notezero.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure read-side helpers the notes UI consumes. */
class ItemQueriesTest {

    private fun item(
        id: String = "id-1",
        title: String = "",
        body: String = "",
        tags: List<String> = emptyList(),
        folderId: String? = null,
        pinned: Boolean = false,
        favorite: Boolean = false,
        trashed: Boolean = false,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        version: Long = 0L,
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
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
    )

    // ---- title / preview -----------------------------------------------------

    @Test
    fun `deriveTitle prefers explicit title`() {
        assertEquals("Hello", ItemQueries.deriveTitle(item(title = "Hello", body = "# Body title")))
    }

    @Test
    fun `deriveTitle falls back to first meaningful body line without block markers`() {
        assertEquals("First line", ItemQueries.deriveTitle(item(body = "\n\n# First line\nmore")))
        assertEquals("bullet", ItemQueries.deriveTitle(item(body = "- bullet")))
        assertEquals("quote", ItemQueries.deriveTitle(item(body = "> quote")))
        assertEquals("task", ItemQueries.deriveTitle(item(body = "- [ ] task")))
        assertEquals("num", ItemQueries.deriveTitle(item(body = "12. num")))
    }

    @Test
    fun `deriveTitle truncates long text with ellipsis`() {
        val title = ItemQueries.deriveTitle(item(title = "x".repeat(200)), maxLength = 20)
        assertEquals("x".repeat(20) + "\u2026", title)
    }

    @Test
    fun `derivePreview strips inline markers and skips title line`() {
        val note = item(
            title = "Title",
            body = "Title\n**bold** text with `code` and _italic_",
        )
        assertEquals("bold text with code and italic", ItemQueries.derivePreview(note))
    }

    @Test
    fun `blank item has empty title preview and word count zero`() {
        val blank = item()
        assertEquals("", ItemQueries.deriveTitle(blank))
        assertEquals("", ItemQueries.derivePreview(blank))
        assertEquals(0, ItemQueries.wordCount(blank))
        assertTrue(blank.isBlank)
    }

    @Test
    fun `word count counts title and body`() {
        assertEquals(4, ItemQueries.countWords("one two   three\nfour"))
        assertEquals(5, ItemQueries.wordCount(item(title = "a b", body = "c d e")))
        assertEquals(0, ItemQueries.countWords("   \n\t "))
    }

    // ---- search / filter -----------------------------------------------------

    @Test
    fun `matchesQuery is case insensitive across title body and tags`() {
        val note = item(title = "Shopping", body = "Milk and eggs", tags = listOf("Errands"))
        assertTrue(ItemQueries.matchesQuery(note, "shop"))
        assertTrue(ItemQueries.matchesQuery(note, "MILK"))
        assertTrue(ItemQueries.matchesQuery(note, "errand"))
        assertFalse(ItemQueries.matchesQuery(note, "bread"))
        assertTrue(ItemQueries.matchesQuery(note, "   "))
    }

    @Test
    fun `filter combines predicates and separates trash view`() {
        val a = item(id = "a", title = "Apple", tags = listOf("fruit"), pinned = true)
        val b = item(id = "b", title = "Banana", tags = listOf("fruit"), favorite = true)
        val c = item(id = "c", title = "Apple pie", tags = listOf("dessert"))
        val t = item(id = "t", title = "Old apple", trashed = true)
        val all = listOf(a, b, c, t)

        assertEquals(listOf("a", "c"), ItemQueries.filter(all, query = "apple").map { it.id })
        assertEquals(listOf("a", "b"), ItemQueries.filter(all, tag = "fruit").map { it.id })
        assertEquals(listOf("a"), ItemQueries.filter(all, pinnedOnly = true).map { it.id })
        assertEquals(listOf("b"), ItemQueries.filter(all, favoritesOnly = true).map { it.id })
        assertEquals(listOf("t"), ItemQueries.filter(all, trashView = true).map { it.id })
        assertEquals(emptyList<Item>(), ItemQueries.filter(all, query = "zzz"))
    }

    @Test
    fun `filter by folder id`() {
        val root = item(id = "root")
        val nested = item(id = "nested", folderId = "f1")
        assertEquals(listOf("nested"), ItemQueries.filter(listOf(root, nested), folderId = "f1").map { it.id })
    }

    // ---- sorting -------------------------------------------------------------

    @Test
    fun `sort recent newest first with deterministic id tiebreak`() {
        val older = item(id = "b", updatedAt = 100)
        val newer = item(id = "a", updatedAt = 200)
        val same = item(id = "c", updatedAt = 200)
        assertEquals(
            listOf("a", "c", "b"),
            ItemQueries.sort(listOf(older, newer, same)).map { it.id },
        )
    }

    @Test
    fun `sort puts pinned then favourites first when requested`() {
        val plain = item(id = "plain", updatedAt = 300)
        val pinned = item(id = "pinned", pinned = true, updatedAt = 100)
        val favorite = item(id = "fav", favorite = true, updatedAt = 200)
        assertEquals(
            listOf("pinned", "fav", "plain"),
            ItemQueries.sort(
                listOf(plain, pinned, favorite),
                pinnedFirst = true,
                favoritesFirst = true,
            ).map { it.id },
        )
    }

    @Test
    fun `sort by title uses lowercase and created order`() {
        val a = item(id = "a", title = "banana")
        val b = item(id = "b", title = "Apple")
        assertEquals(listOf("b", "a"), ItemQueries.sort(listOf(a, b), sort = ItemQueries.ItemSort.TITLE).map { it.id })
    }

    // ---- tags ----------------------------------------------------------------

    @Test
    fun `tagCounts aggregates case-insensitively and skips trashed items`() {
        val items = listOf(
            item(id = "1", tags = listOf("Work", "home")),
            item(id = "2", tags = listOf("work")),
            item(id = "3", tags = listOf("trashed"), trashed = true),
            item(id = "4", tags = listOf("  ", "home ")),
        )
        val counts = ItemQueries.tagCounts(items)
        assertEquals(listOf("home", "Work"), counts.map { it.tag })
        assertEquals(listOf(2, 2), counts.map { it.count })
    }

    @Test
    fun `normaliseTags trims dedupes case-insensitively and keeps order`() {
        assertEquals(
            listOf("Work", "home"),
            ItemQueries.normaliseTags(listOf(" Work ", "work", "", "home", "HOME")),
        )
    }

    // ---- trash retention -----------------------------------------------------

    @Test
    fun `trash retention window and expired ids`() {
        val now = 1_000_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val none = item(id = "n", updatedAt = now)
        val fresh = item(id = "f", trashed = true, updatedAt = now - 10 * day)
        val old = item(id = "o", trashed = true, updatedAt = now - 31 * day)
        assertEquals(now + 30 * day, ItemQueries.trashExpiry(now, 30))
        assertFalse(ItemQueries.isTrashExpired(none, now, 30))
        assertFalse(ItemQueries.isTrashExpired(fresh, now, 30))
        assertTrue(ItemQueries.isTrashExpired(old, now, 30))
        assertEquals(listOf("o"), ItemQueries.expiredTrashIds(listOf(none, fresh, old), now, 30))
    }

    // ---- folders -------------------------------------------------------------

    private val root = Folder(id = "root", name = "Root", parentId = null, sortOrder = 0)
    private val work = Folder(id = "work", name = "Work", parentId = "root", sortOrder = 0)
    private val client = Folder(id = "client", name = "Client", parentId = "work", sortOrder = 0)
    private val folders = listOf(root, work, client)

    @Test
    fun `folderPath returns root to leaf chain and label`() {
        assertEquals(listOf("root", "work", "client"), ItemQueries.folderPath(folders, "client").map { it.id })
        assertEquals("Root / Work / Client", ItemQueries.folderPathLabel(folders, "client"))
        assertEquals(emptyList<Folder>(), ItemQueries.folderPath(folders, null))
        assertEquals(emptyList<Folder>(), ItemQueries.folderPath(folders, "missing"))
    }

    @Test
    fun `folderPath is cycle safe`() {
        val a = Folder(id = "a", name = "A", parentId = "b")
        val b = Folder(id = "b", name = "B", parentId = "a")
        assertEquals(2, ItemQueries.folderPath(listOf(a, b), "a").size)
    }

    @Test
    fun `folderTree nests children with deterministic order and tolerates orphans`() {
        val zeta = Folder(id = "zeta", name = "Zeta", parentId = null, sortOrder = 1)
        val alpha = Folder(id = "alpha", name = "Alpha", parentId = null, sortOrder = 0)
        val orphan = Folder(id = "orphan", name = "Orphan", parentId = "gone", sortOrder = 0)
        val tree = ItemQueries.folderTree(listOf(client, work, zeta, root, alpha, orphan))
        // Roots sorted by sortOrder then name: alpha(0), orphan(0), root(0), zeta(1).
        assertEquals(listOf("alpha", "orphan", "root", "zeta"), tree.map { it.folder.id })
        val rootNode = tree.first { it.folder.id == "root" }
        assertEquals(listOf("work"), rootNode.children.map { it.folder.id })
        assertEquals(listOf("client"), rootNode.children.single().children.map { it.folder.id })
    }

    @Test
    fun `stripBlockMarkers removes nested prefixes`() {
        assertEquals("text", ItemQueries.stripBlockMarkers("> - ### text"))
        assertEquals("plain", ItemQueries.stripBlockMarkers("plain"))
    }
}
