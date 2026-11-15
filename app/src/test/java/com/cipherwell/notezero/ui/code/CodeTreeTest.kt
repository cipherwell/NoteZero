package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.code.CodeLanguage
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the code tree builder and name/extension helpers. No Android, no
 * crypto: the tree is a deterministic function of the decrypted index + expanded set.
 */
class CodeTreeTest {

    private fun folder(id: String, name: String, parentId: String? = null, sortOrder: Int = 0) =
        Folder(id = id, name = name, parentId = parentId, sortOrder = sortOrder, createdAt = 1L)

    private fun codeFile(
        id: String,
        title: String,
        folderId: String? = null,
        trashed: Boolean = false,
        kind: ItemKind = ItemKind.CODE_FILE,
    ) = Item(
        id = id,
        kind = kind,
        title = title,
        body = "",
        folderId = folderId,
        trashed = trashed,
        createdAt = 100L,
        updatedAt = 100L,
        version = 1L,
    )

    private fun fileNames(rows: List<CodeTreeNode>): List<String> =
        rows.filterIsInstance<CodeTreeNode.FileNode>().map { it.item.title }

    private fun folderNames(rows: List<CodeTreeNode>): List<String> =
        rows.filterIsInstance<CodeTreeNode.FolderNode>().map { it.folder.name }

    @Test
    fun `locked vault renders no rows`() {
        val rows = CodeTree.build(
            items = listOf(codeFile("f", "Main.kt")),
            folders = listOf(folder("d", "Docs")),
            expandedFolderIds = setOf("d"),
            isUnlocked = false,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `root folders and root files render folders first`() {
        val rows = CodeTree.build(
            items = listOf(
                codeFile("a", "Zeta.kt"),
                codeFile("b", "alpha.kt"),
                codeFile("n", "Note", kind = ItemKind.NOTE),
                codeFile("t", "Old.kt", trashed = true),
            ),
            folders = listOf(folder("d2", "Work"), folder("d1", "Docs")),
            expandedFolderIds = emptySet(),
            isUnlocked = true,
        )
        assertEquals(listOf("Docs", "Work"), folderNames(rows))
        assertEquals(listOf("alpha.kt", "Zeta.kt"), fileNames(rows))
        assertEquals(
            listOf("folder:d1", "folder:d2", "file:b", "file:a"),
            rows.map { it.key },
        )
        assertTrue(rows.all { it.depth == 0 })
    }

    @Test
    fun `nested folders only show children when expanded`() {
        val folders = listOf(
            folder("root", "Projects"),
            folder("child", "NoteZero", parentId = "root"),
        )
        val items = listOf(
            codeFile("a", "Root.kt", folderId = "root"),
            codeFile("b", "Child.kt", folderId = "child"),
        )

        val collapsed = CodeTree.build(items, folders, expandedFolderIds = emptySet(), isUnlocked = true)
        assertEquals(listOf("Projects"), folderNames(collapsed))
        assertTrue(fileNames(collapsed).isEmpty())

        val rootExpanded = CodeTree.build(items, folders, setOf("root"), isUnlocked = true)
        assertEquals(listOf("Projects", "NoteZero"), folderNames(rootExpanded))
        assertEquals(listOf("Root.kt"), fileNames(rootExpanded))
        val childRow = rootExpanded.filterIsInstance<CodeTreeNode.FolderNode>().single { it.folder.id == "child" }
        assertEquals(1, childRow.depth)

        val allExpanded = CodeTree.build(items, folders, setOf("root", "child"), isUnlocked = true)
        assertEquals(listOf("Projects", "NoteZero"), folderNames(allExpanded))
        // Pre-order: the expanded child folder's files render before the parent's own files.
        assertEquals(listOf("Child.kt", "Root.kt"), fileNames(allExpanded))
    }

    @Test
    fun `folder row counts its direct code files`() {
        val folders = listOf(folder("d", "Docs"))
        val items = listOf(
            codeFile("a", "one.kt", folderId = "d"),
            codeFile("b", "two.kt", folderId = "d"),
            codeFile("n", "note", folderId = "d", kind = ItemKind.NOTE),
            codeFile("t", "trashed.kt", folderId = "d", trashed = true),
        )
        val rows = CodeTree.build(items, folders, setOf("d"), isUnlocked = true)
        val docs = rows.filterIsInstance<CodeTreeNode.FolderNode>().single()
        assertEquals(2, docs.fileCount)
        assertTrue(docs.expanded)
    }

    @Test
    fun `orphaned folders surface at the root`() {
        val folders = listOf(
            folder("orphan", "Lost", parentId = "missing-parent"),
            folder("orphan-child", "Inside", parentId = "orphan"),
        )
        val rows = CodeTree.build(emptyList(), folders, setOf("orphan"), isUnlocked = true)
        assertEquals(listOf("Lost", "Inside"), folderNames(rows))
    }

    @Test
    fun `folder cycle cannot loop forever`() {
        val folders = listOf(
            folder("a", "A", parentId = "b"),
            folder("b", "B", parentId = "a"),
        )
        val rows = CodeTree.build(emptyList(), folders, setOf("a", "b"), isUnlocked = true)
        // Cycle members are unreachable from the root, then surfaced once as orphans.
        assertEquals(listOf("A", "B"), folderNames(rows))
        assertTrue(rows.size <= 2)
    }

    @Test
    fun `flattenFolders is depth annotated pre order`() {
        val folders = listOf(
            folder("b", "Beta", sortOrder = 2),
            folder("a", "Alpha", sortOrder = 1),
            folder("a1", "Alpha child", parentId = "a"),
        )
        val flat = CodeTree.flattenFolders(folders)
        assertEquals(listOf("a", "a1", "b"), flat.map { it.folder.id })
        assertEquals(listOf(0, 1, 0), flat.map { it.depth })
    }

    @Test
    fun `default new file name is the engine default kotlin name`() {
        assertEquals("untitled.kt", CodeTree.defaultNewFileName())
        assertEquals("untitled.kt", CodeTree.normalizeNewFileName("   "))
        assertEquals("hello.kt", CodeTree.normalizeNewFileName(" hello.kt "))
        assertEquals("untitled.kt", CodeTree.suggestedFileName(null))
    }

    @Test
    fun `name validation rejects path separators and blank names`() {
        assertNull(CodeTree.validateName("hello.kt"))
        assertNotNull(CodeTree.validateName("docs/readme"))
        assertNotNull(CodeTree.validateName("bad/name"))
        assertNotNull(CodeTree.validateName("bad\\name"))
        assertNotNull(CodeTree.validateName("   "))
        assertNotNull(CodeTree.validateName(".."))
        assertNotNull(CodeTree.validateName("x".repeat(CodeTree.MAX_NAME_LENGTH + 1)))
        assertFalse(CodeTree.validateName("x".repeat(CodeTree.MAX_NAME_LENGTH)) != null)
    }

    @Test
    fun `language detection follows the file extension`() {
        assertEquals(CodeLanguage.KOTLIN, CodeTree.languageFor("Main.kt"))
        assertEquals(CodeLanguage.KOTLIN, CodeTree.languageFor("build.KTS"))
        assertEquals(CodeLanguage.JAVASCRIPT, CodeTree.languageFor("index.js"))
        assertEquals(CodeLanguage.MARKDOWN, CodeTree.languageFor("README.md"))
        assertEquals(CodeLanguage.PLAIN, CodeTree.languageFor("Makefile"))
        assertEquals(CodeLanguage.PLAIN, CodeTree.languageFor(".gitignore"))
    }
}
