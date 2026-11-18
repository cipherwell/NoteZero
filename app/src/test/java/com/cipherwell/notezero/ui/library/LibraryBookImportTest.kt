package com.cipherwell.notezero.ui.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.book.BookFormat
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.comic.ComicTestData
import com.cipherwell.notezero.ui.reader.BookFormatsTestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Library import detection tests (the testable [importBookFromBytes] core).
 *
 * No SAF picker is driven: the importer's content-dependent half is extracted into a function
 * taking the SAF display name plus the decrypted bytes, exactly what the picker would hand it.
 * The fake repository mirrors the real attachment/reading-state contract, so these tests assert
 * the detected MIME, the derived title/author/language, the encrypted totals and the
 * unsupported-file cleanup (no orphan row on the shelf).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryBookImportTest {

    private fun repository(): FakeNoteRepository = FakeNoteRepository(
        initialStatus = com.cipherwell.notezero.data.VaultStatus.UNLOCKED,
        initialPassword = FakeNoteRepository.STRONG_PASSWORD,
    )

    private fun import(
        repository: FakeNoteRepository,
        displayName: String,
        bytes: ByteArray,
    ): ImportOutcome = runBlocking {
        importBookFromBytes(
            repository = repository,
            displayName = displayName,
            bytes = bytes,
            untitledBookTitle = "Untitled book",
        )
    }

    private fun bookItems(repository: FakeNoteRepository) =
        repository.items.value.filter { it.kind == ItemKind.BOOK_FILE }

    @Test
    fun `txt import stores the detected mime and the cleaned file-name title`() {
        val repository = repository()

        val outcome = import(repository, "My Plain Book.txt", BookFormatsTestData.validTxt())

        assertEquals(ImportOutcome.SUCCESS, outcome)
        val item = bookItems(repository).single()
        assertFalse(item.trashed)
        // TXT has no metadata: the title is the cleaned SAF display name (extension dropped).
        assertEquals("My Plain Book", item.title)
        val attachment = item.attachments.single()
        assertEquals(BookFormat.TXT.mimeType, attachment.mimeType)
        assertEquals("My Plain Book.txt", attachment.displayName)
        assertEquals(2, item.readingState?.total)
        assertNull(item.readingState?.author)
        assertEquals(0, item.readingState?.position)
    }

    @Test
    fun `fb2 import prefers metadata title author and language over the file name`() {
        val repository = repository()

        val outcome = import(repository, "wrong-file-name.fb2", BookFormatsTestData.validFb2())

        assertEquals(ImportOutcome.SUCCESS, outcome)
        val item = bookItems(repository).single()
        assertFalse(item.trashed)
        assertEquals(BookFormatsTestData.FB2_ITEM_TITLE, item.title)
        assertEquals(BookFormat.FB2.mimeType, item.attachments.single().mimeType)
        val state = item.readingState
        assertEquals(2, state?.total)
        assertEquals(BookFormatsTestData.FB2_AUTHOR, state?.author)
        assertEquals(BookFormatsTestData.FB2_LANGUAGE, state?.language)
    }

    @Test
    fun `cbz import stores the comic mime and the page total`() {
        val repository = repository()

        val outcome = import(repository, "My Comic.cbz", ComicTestData.validCbz())

        assertEquals(ImportOutcome.SUCCESS, outcome)
        val item = bookItems(repository).single()
        assertFalse(item.trashed)
        // CBZ carries no title metadata: the cleaned display name is the title.
        assertEquals("My Comic", item.title)
        assertEquals(BookFormat.CBZ.mimeType, item.attachments.single().mimeType)
        assertEquals(ComicTestData.PAGE_COUNT, item.readingState?.total)
        assertNull(item.readingState?.author)
    }

    @Test
    fun `the item title still falls back to the display name when metadata is blank`() {
        val repository = repository()

        // A TXT title is the (fallback) display name; an empty display name has no usable
        // text, so the importer's last-resort title is used.
        val outcome = import(repository, "", BookFormatsTestData.validTxt())

        assertEquals(ImportOutcome.SUCCESS, outcome)
        assertEquals("Untitled book", bookItems(repository).single().title)
    }

    @Test
    fun `corrupt zip bytes are unsupported and the placeholder item is trashed`() {
        val repository = repository()

        val outcome = import(repository, "broken.cbz", ComicTestData.corruptCbz())

        assertEquals(ImportOutcome.UNSUPPORTED, outcome)
        val created = bookItems(repository)
        assertEquals(1, created.size)
        assertTrue("the just-created item must be trashed", created.single().trashed)
        // No attachment was written for an unsupported file and nothing is left on the shelf.
        assertTrue(created.single().attachments.isEmpty())
        assertTrue(repository.items.value.none { it.kind == ItemKind.BOOK_FILE && !it.trashed })
    }

    @Test
    fun `empty input is unsupported and leaves no orphan row`() {
        val repository = repository()

        val outcome = import(repository, "empty.txt", ByteArray(0))

        assertEquals(ImportOutcome.UNSUPPORTED, outcome)
        assertEquals(1, bookItems(repository).size)
        assertTrue(bookItems(repository).single().trashed)
        assertTrue(repository.items.value.none { it.kind == ItemKind.BOOK_FILE && !it.trashed })
    }

    @Test
    fun `corrupt fb2 xml is unsupported and leaves no orphan row`() {
        val repository = repository()
        val brokenXml = """<?xml version="1.0"?><FictionBook><body><section>""".toByteArray()

        val outcome = import(repository, "broken.fb2", brokenXml)

        assertEquals(ImportOutcome.UNSUPPORTED, outcome)
        assertTrue(bookItems(repository).single().trashed)
        assertTrue(repository.items.value.none { it.kind == ItemKind.BOOK_FILE && !it.trashed })
    }
}
