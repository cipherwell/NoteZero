package com.cipherwell.notezero.ui.locks

import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [LockVisibility] masking and folder-subtree hiding (decision #15). */
class LockVisibilityTest {

    private fun item(
        id: String = "n1",
        locked: Boolean = true,
        folderId: String? = "f1",
    ) = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = "Secret title",
        body = "secret body",
        tags = listOf("private"),
        folderId = folderId,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        attachments = listOf(
            AttachmentRef(
                id = "att-1",
                displayName = "scan.pdf",
                mimeType = "application/pdf",
                sizeBytes = 10L,
                createdAt = 1_000L,
                sha256 = "00",
            ),
        ),
        locked = locked,
    )

    @Test
    fun `unverified note is masked`() {
        assertTrue(LockVisibility.isItemMasked(item(), emptySet()))
        assertFalse(LockVisibility.isItemMasked(item(), setOf("n1")))
        assertFalse(LockVisibility.isItemMasked(item(locked = false), emptySet()))
    }

    @Test
    fun `masked copy carries no content`() {
        val masked = LockVisibility.maskItem(item(), emptySet())

        assertEquals("Locked note", masked.title)
        assertEquals("", masked.body)
        assertTrue(masked.tags.isEmpty())
        assertNull(masked.folderId)
        assertTrue(masked.attachments.isEmpty())
        assertEquals("n1", masked.id)
    }

    @Test
    fun `verified note is returned unchanged`() {
        val original = item()
        val visible = LockVisibility.maskItem(original, setOf("n1"))

        assertEquals("Secret title", visible.title)
        assertEquals("secret body", visible.body)
        assertEquals(listOf("private"), visible.tags)
        assertEquals("f1", visible.folderId)
    }

    @Test
    fun `locked folder hides itself and every descendant`() {
        val folders = listOf(
            Folder(id = "locked", name = "Secret", locked = true),
            Folder(id = "child", name = "Child", parentId = "locked"),
            Folder(id = "grandchild", name = "Grandchild", parentId = "child"),
            Folder(id = "sibling", name = "Sibling"),
        )

        assertEquals(
            setOf("locked", "child", "grandchild"),
            LockVisibility.hiddenFolderIds(folders, emptySet()),
        )
        assertTrue(LockVisibility.hiddenFolderIds(folders, setOf("locked")).isEmpty())
    }

    @Test
    fun `hidden folder walk is cycle safe`() {
        val folders = listOf(
            Folder(id = "a", name = "A", parentId = "b", locked = true),
            Folder(id = "b", name = "B", parentId = "a"),
        )
        assertEquals(setOf("a", "b"), LockVisibility.hiddenFolderIds(folders, emptySet()))
    }

    @Test
    fun `locked folder name is masked`() {
        val folder = Folder(id = "f", name = "Secret folder", locked = true)

        assertEquals("Locked folder", LockVisibility.maskFolder(folder, emptySet()).name)
        assertEquals("Secret folder", LockVisibility.maskFolder(folder, setOf("f")).name)
        assertTrue(LockVisibility.isFolderMasked(folder, emptySet()))
        assertFalse(LockVisibility.isFolderMasked(folder, setOf("f")))
    }
}
