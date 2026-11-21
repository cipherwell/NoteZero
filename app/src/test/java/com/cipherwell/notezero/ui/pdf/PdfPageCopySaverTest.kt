package com.cipherwell.notezero.ui.pdf

import com.cipherwell.notezero.data.AttachmentError
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.FakeNoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * Tests for the page-tools copy saver and its display-name helpers. No crypto is involved:
 * [FakeNoteRepository] keeps the attachment bytes in memory exactly like the real repository's
 * sealed-attachment pipeline, so the assertions are about *wiring* (new item in the source
 * folder, title, encrypted import, trash-on-failure), not about ciphertext.
 */
class PdfPageCopySaverTest {

    private fun sourceItem(folderId: String? = null): Pair<FakeNoteRepository, Item> {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.UNLOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val item = runBlocking {
            val created = repository.createItem(ItemKind.PDF_FILE, folderId)
            repository.saveItem(created.copy(title = "report"))
        }
        return repository to item
    }

    /**
     * Delegating repository whose attachment import always fails — the only way to reach the
     * saver's trash-on-failure path with the in-memory fake.
     */
    private class FailingImportRepository(
        private val delegate: FakeNoteRepository,
        private val error: AttachmentError = AttachmentError.IO,
    ) : NoteRepository by delegate {
        var importCalls: Int = 0
            private set

        override suspend fun importAttachment(
            noteId: String,
            displayName: String,
            mimeType: String,
            source: InputStream,
        ): AttachmentImport {
            importCalls++
            source.readBytes()
            return AttachmentImport.Failure(error)
        }
    }

    @Test
    fun `saves a new pdf item in the source folder with the copy name and encrypted bytes`() =
        runBlocking {
            val (repository, source) = sourceItem(folderId = "folder-42")
            val bytes = "%PDF-1.4 page-tools copy".toByteArray()

            val savedId = DefaultPdfPageCopySaver().save(
                repository = repository,
                source = source,
                displayName = "report (pages).pdf",
                bytes = bytes,
            )

            assertNotNull(savedId)
            val copy = repository.items.value.first { it.id == savedId }
            assertEquals(ItemKind.PDF_FILE, copy.kind)
            assertEquals("folder-42", copy.folderId)
            assertEquals("report (pages).pdf", copy.title)
            val attachment = copy.attachments.single()
            assertEquals("report (pages).pdf", attachment.displayName)
            assertEquals(BlankPdf.MIME_TYPE, attachment.mimeType)
            assertEquals(bytes.size.toLong(), attachment.sizeBytes)
            assertTrue(attachment.id.isNotBlank())
            // The bytes came back through the encrypted attachment read path unchanged.
            assertTrue(repository.attachmentBytes(copy.id, attachment.id)!!.contentEquals(bytes))
            // The source item is untouched: no attachment was added to it.
            assertTrue(repository.items.value.first { it.id == source.id }.attachments.isEmpty())
        }

    @Test
    fun `a failed import leaves no orphan row and returns null`() = runBlocking {
        val (delegate, source) = sourceItem(folderId = "folder-7")
        val repository = FailingImportRepository(delegate)

        val savedId = DefaultPdfPageCopySaver().save(
            repository = repository,
            source = source,
            displayName = "report (pages).pdf",
            bytes = "%PDF-1.4".toByteArray(),
        )

        assertNull(savedId)
        assertEquals(1, repository.importCalls)
        val created = delegate.items.value.filter { it.id != source.id }
        assertEquals("exactly one rolled-back row expected", 1, created.size)
        assertTrue("the half-created item must be trashed", created.single().trashed)
        assertTrue(created.single().attachments.isEmpty())
    }

    @Test
    fun `locked vault saves nothing and never touches the item index`() = runBlocking {
        val (repository, source) = sourceItem(folderId = "folder-8")
        repository.forceStatus(VaultStatus.LOCKED)

        val savedId = DefaultPdfPageCopySaver().save(
            repository = repository,
            source = source,
            displayName = "report (pages).pdf",
            bytes = "%PDF-1.4".toByteArray(),
        )

        assertNull(savedId)
        // A locked repository emits no items, and nothing was created while locked either.
        assertTrue(repository.items.value.isEmpty())
    }

    @Test
    fun `empty bytes are refused before anything is created`() = runBlocking {
        val (repository, source) = sourceItem()

        val savedId = DefaultPdfPageCopySaver().save(
            repository = repository,
            source = source,
            displayName = "report (pages).pdf",
            bytes = ByteArray(0),
        )

        assertNull(savedId)
        assertEquals(1, repository.items.value.size)
        assertTrue(repository.items.value.first().attachments.isEmpty())
    }

    @Test
    fun `blank copy names are refused`() = runBlocking {
        val (repository, source) = sourceItem()

        assertNull(DefaultPdfPageCopySaver().save(repository, source, "   ", "%PDF-1.4".toByteArray()))
        assertNull(boundedPageCopyName(""))
        assertNull(boundedPageCopyName("  \u0000  "))
        assertEquals(1, repository.items.value.size)
    }

    @Test
    fun `copy name is the source base plus the localized suffix and the pdf extension`() {
        assertEquals("report (pages).pdf", pageCopyFileName("report.pdf", " (pages)"))
        // The resource table trims the leading space off `pdf_pages_copy_suffix`, so the helper
        // must rebuild exactly one separator space from either form.
        assertEquals("report (pages).pdf", pageCopyFileName("report", " (pages)"))
        assertEquals("report (pages).pdf", pageCopyFileName("report", "(pages)"))
        assertEquals("report.pdf", pageCopyFileName("report", "  "))
        assertEquals("document (pages).pdf", pageCopyFileName("   ", " (pages)"))
        assertEquals("2026-11-20 (pages).pdf", pageCopyFileName("2026-11-20.PDF", " (pages)"))
        assertEquals("documento (copia de páginas).pdf", pageCopyFileName("documento", " (copia de páginas)"))
    }

    @Test
    fun `copy names stay bounded and path separators are never stored`() {
        val name = pageCopyFileName("a".repeat(500) + ".pdf", " (pages)")

        assertTrue("name must stay bounded, was ${name.length}", name.length <= MAX_PAGE_COPY_NAME_LENGTH)
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.startsWith("a"))

        assertEquals("report pages.pdf", boundedPageCopyName("report/pages.pdf"))
        assertEquals("report pages.pdf", boundedPageCopyName("report\\pages.pdf"))
        assertEquals("note.pdf", boundedPageCopyName("note"))
        assertEquals("note.pdf", boundedPageCopyName("  note.pdf  "))
        val hostile = boundedPageCopyName("x".repeat(500))
        assertNotNull(hostile)
        assertTrue(hostile!!.length <= MAX_PAGE_COPY_NAME_LENGTH)
        assertTrue(hostile.endsWith(".pdf"))
    }

    @Test
    fun `two consecutive saves produce two distinct encrypted items`() = runBlocking {
        val (repository, source) = sourceItem()
        val saver = DefaultPdfPageCopySaver()

        val first = saver.save(repository, source, "report (pages).pdf", "%PDF-1.4 one".toByteArray())
        val second = saver.save(repository, source, "report (pages).pdf", "%PDF-1.4 two".toByteArray())

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first != second)
        assertEquals(3, repository.items.value.size)
        val firstCopy = repository.items.value.first { it.id == first }
        val firstBytes = repository.attachmentBytes(firstCopy.id, firstCopy.attachments.single().id)
        assertTrue(firstBytes!!.contentEquals("%PDF-1.4 one".toByteArray()))
    }
}
