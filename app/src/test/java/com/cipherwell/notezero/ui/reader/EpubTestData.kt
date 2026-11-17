package com.cipherwell.notezero.ui.reader

import com.cipherwell.notezero.book.EpubParser
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds minimal but valid EPUB 2/3-style fixtures in memory for UI tests.
 *
 * No binary fixture lives in the repository and nothing is written to disk: the archive is
 * assembled from readable XHTML/XML literals with [ZipOutputStream] (the `mimetype` entry is
 * stored uncompressed first, as the EPUB container requires), then imported through the
 * same encrypted-attachment path the app uses.
 */
object EpubTestData {

    const val BOOK_TITLE = "Test Shelf Book"
    const val BOOK_AUTHOR = "Test Author"
    const val BOOK_LANGUAGE = "en"
    const val SEARCH_PHRASE = "distinctive searchphrase"
    const val CHAPTER_ONE_BODY = "First chapter body with a $SEARCH_PHRASE for search."
    const val CHAPTER_TWO_BODY = "Second chapter body."

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val OPF_PATH = "OEBPS/content.opf"
    private const val NAV_PATH = "OEBPS/nav.xhtml"
    private const val CHAPTER_ONE_PATH = "OEBPS/text/ch1.xhtml"
    private const val CHAPTER_TWO_PATH = "OEBPS/text/ch2.xhtml"

    /** A valid two-chapter EPUB with an EPUB3 nav TOC ("One" -> ch1, "Two" -> ch2). */
    fun validEpub(
        title: String = BOOK_TITLE,
        author: String = BOOK_AUTHOR,
    ): ByteArray {
        val entries = linkedMapOf(
            "mimetype" to EpubParser.MIME,
            CONTAINER_PATH to containerXml(),
            OPF_PATH to opf(title = title, author = author),
            NAV_PATH to navXml(),
            CHAPTER_ONE_PATH to chapterOneXml(),
            CHAPTER_TWO_PATH to chapterTwoXml(),
        )
        return zip(entries)
    }

    /** Bytes that are not a ZIP/EPUB at all: the reader must fail closed. */
    fun corruptEpub(): ByteArray = "this is not an epub archive".toByteArray(Charsets.UTF_8)

    /**
     * Seeds a [FakeNoteRepository] with an encrypted-looking BOOK_FILE item holding [bytes]
     * and optional reading state, exactly through the repository API the real app uses.
     */
    fun seedBook(
        repository: FakeNoteRepository,
        bytes: ByteArray = validEpub(),
        title: String = BOOK_TITLE,
        readingState: ReadingState? = null,
    ): String = runBlocking {
        val created = repository.createItem(ItemKind.BOOK_FILE)
        val renamed = repository.saveItem(created.copy(title = title))
        val imported = repository.importAttachment(
            noteId = renamed.id,
            displayName = "book.epub",
            mimeType = EpubParser.MIME,
            source = ByteArrayInputStream(bytes),
        )
        check(imported is AttachmentImport.Success) { "test fixture import failed" }
        if (readingState != null) {
            repository.saveReadingState(renamed.id, readingState)
        }
        renamed.id
    }

    /** An unlocked fake vault; tests seed it directly (no crypto involved). */
    fun unlockedRepository(initialItems: List<com.cipherwell.notezero.model.Item> = emptyList()): FakeNoteRepository {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.UNLOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        initialItems.forEach { repository.seedItem(it) }
        return repository
    }

    private fun containerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$OPF_PATH" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun opf(title: String, author: String): String = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:uuid:notezero-ui-test</dc:identifier>
    <dc:title>$title</dc:title>
    <dc:creator>$author</dc:creator>
    <dc:language>$BOOK_LANGUAGE</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>"""

    private fun navXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <ol>
      <li><a href="text/ch1.xhtml">One</a></li>
      <li><a href="text/ch2.xhtml">Two</a></li>
    </ol>
  </nav>
</body>
</html>"""

    private fun chapterOneXml(): String = xhtml(
        """
        <h1>Chapter One</h1>
        <p>$CHAPTER_ONE_BODY It has <strong>bold</strong>, <em>italic</em> and <code>code</code>.</p>
        <hr/>
        <blockquote><p>Quoted wisdom.</p></blockquote>
        <pre>val answer = 42</pre>
        <ul>
          <li>Bullet one</li>
          <li>Bullet two</li>
        </ul>
        ${(1..40).joinToString("\n") { "<p>Filler paragraph $it keeps the first chapter taller than the test viewport.</p>" }}
        """.trimIndent(),
    )

    private fun chapterTwoXml(): String = xhtml(
        """
        <h1>Chapter Two</h1>
        <p>$CHAPTER_TWO_BODY</p>
        ${(1..40).joinToString("\n") { "<p>Tail paragraph $it keeps the second chapter easily scrollable.</p>" }}
        """.trimIndent(),
    )

    private fun xhtml(body: String): String = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Fixture</title></head>
<body>$body</body>
</html>"""

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, content) in entries) {
                if (name == "mimetype") zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (name == "mimetype") zip.setLevel(Deflater.BEST_SPEED)
            }
        }
        return output.toByteArray()
    }
}
