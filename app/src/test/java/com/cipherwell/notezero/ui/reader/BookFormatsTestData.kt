package com.cipherwell.notezero.ui.reader

import com.cipherwell.notezero.book.BookFormat
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * Generated-in-memory TXT / FB2 fixtures for the multi-format reader tests.
 *
 * Like [EpubTestData] nothing is committed as a binary fixture and nothing is written to disk:
 * the book bytes are readable literals, imported through the same encrypted-attachment path the
 * app uses (`createItem` → `importAttachment` → `saveReadingState`). The engine tests own the
 * parsing details; these fixtures are deliberately small but exercise the shapes the reader
 * renders (auto-chaptered headings, sections, paragraphs, nested TOC entries).
 */
object BookFormatsTestData {

    // ---- TXT ----------------------------------------------------------------

    const val TXT_ITEM_TITLE = "Plain Text Book"
    const val TXT_FILE_NAME = "Plain Text Book.txt"
    const val TXT_PHRASE = "distinctive txtsearchphrase"
    const val TXT_CHAPTER_ONE = "Chapter One"
    const val TXT_CHAPTER_TWO = "Chapter Two"
    const val TXT_BODY_ONE = "First chapter body with a $TXT_PHRASE for search."
    const val TXT_BODY_TWO = "Second chapter body in the plain text file."

    /** Two auto-detected chapters ("Chapter One"/"Chapter Two") with one paragraph each. */
    fun validTxt(): ByteArray = """
        |$TXT_CHAPTER_ONE
        |
        |$TXT_BODY_ONE
        |
        |$TXT_CHAPTER_TWO
        |
        |$TXT_BODY_TWO
    """.trimMargin().toByteArray(Charsets.UTF_8)

    // ---- FB2 ----------------------------------------------------------------

    const val FB2_ITEM_TITLE = "Fixture FB2 Book"
    const val FB2_AUTHOR = "Jane Doe"
    const val FB2_LANGUAGE = "en"
    const val FB2_SECTION_ONE = "Part One"
    const val FB2_SECTION_TWO = "Part Two"
    const val FB2_NESTED_SECTION = "Nested Idea"
    const val FB2_BODY_ONE = "FB2 first section body with a distinctive fb2phrase for search."
    const val FB2_NESTED_BODY = "FB2 nested paragraph."
    const val FB2_BODY_TWO = "FB2 second section body."

    /**
     * A valid FictionBook 2 document: metadata (title/author/lang), two top-level sections
     * (chapters) and one nested section (a depth-1 TOC entry inside chapter one).
     */
    fun validFb2(): ByteArray = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description>
    <title-info>
      <book-title>$FB2_ITEM_TITLE</book-title>
      <author>
        <first-name>Jane</first-name>
        <last-name>Doe</last-name>
      </author>
      <lang>$FB2_LANGUAGE</lang>
    </title-info>
  </description>
  <body>
    <section>
      <title><p>$FB2_SECTION_ONE</p></title>
      <p>$FB2_BODY_ONE</p>
      <section>
        <title><p>$FB2_NESTED_SECTION</p></title>
        <p>$FB2_NESTED_BODY</p>
      </section>
    </section>
    <section>
      <title><p>$FB2_SECTION_TWO</p></title>
      <p>$FB2_BODY_TWO</p>
    </section>
  </body>
</FictionBook>""".toByteArray(Charsets.UTF_8)

    // ---- seeding ------------------------------------------------------------

    /**
     * Seeds a [FakeNoteRepository] with a BOOK_FILE item holding [bytes] exactly like the
     * importer does: encrypted-attachment path plus optional encrypted reading state.
     */
    fun seedBook(
        repository: FakeNoteRepository,
        bytes: ByteArray,
        title: String,
        displayName: String,
        mimeType: String,
        readingState: ReadingState? = null,
    ): String = runBlocking {
        val created = repository.createItem(ItemKind.BOOK_FILE)
        val renamed = repository.saveItem(created.copy(title = title))
        val imported = repository.importAttachment(
            noteId = renamed.id,
            displayName = displayName,
            mimeType = mimeType,
            source = ByteArrayInputStream(bytes),
        )
        check(imported is AttachmentImport.Success) { "test fixture import failed" }
        if (readingState != null) {
            repository.saveReadingState(renamed.id, readingState)
        }
        renamed.id
    }

    fun seedTxt(
        repository: FakeNoteRepository,
        readingState: ReadingState? = null,
    ): String = seedBook(
        repository = repository,
        bytes = validTxt(),
        title = TXT_ITEM_TITLE,
        displayName = TXT_FILE_NAME,
        mimeType = BookFormat.TXT.mimeType,
        readingState = readingState,
    )

    fun seedFb2(
        repository: FakeNoteRepository,
        readingState: ReadingState? = null,
    ): String = seedBook(
        repository = repository,
        bytes = validFb2(),
        title = FB2_ITEM_TITLE,
        displayName = "fixture-fb2.fb2",
        mimeType = BookFormat.FB2.mimeType,
        readingState = readingState,
    )
}
