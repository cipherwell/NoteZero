package com.cipherwell.notezero.book

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds EPUB fixtures in memory. No binary fixtures live in the repository: every archive
 * the tests parse is produced by the helpers below from readable XML/byte literals.
 *
 * [ZipBuilder] is a plain [ZipOutputStream] wrapper (deflated, first-entry order, `mimetype`
 * stored as EPUB requires). [RawZip] writes stored entries with full control over the header
 * fields, which is what the capability/limit tests need (declared sizes, bogus methods,
 * duplicate names, unsafe paths).
 */
internal class ZipBuilder {
    // A list; java.util.zip.ZipOutputStream refuses duplicate names, so tests that need
    // duplicates use [RawZip] instead. `replace` is the supported way to override a fixture
    // entry without triggering that ZipException.
    private val entries = ArrayList<Pair<String, ByteArray>>()

    fun add(name: String, content: String): ZipBuilder = addBytes(name, content.toByteArray(Charsets.UTF_8))

    fun addBytes(name: String, content: ByteArray): ZipBuilder {
        entries.add(name to content)
        return this
    }

    fun remove(name: String): ZipBuilder {
        entries.removeAll { it.first == name }
        return this
    }

    fun replace(name: String, content: String): ZipBuilder = remove(name).add(name, content)

    fun contains(name: String): Boolean = entries.any { it.first == name }

    fun build(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                if (name == MIMETYPE_PATH) zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                if (name == MIMETYPE_PATH) zip.setLevel(Deflater.BEST_SPEED)
            }
        }
        return output.toByteArray()
    }

    internal companion object {
        const val MIMETYPE_PATH = "mimetype"
    }
}

/** Minimal stored-entry ZIP writer with explicit, test-controlled header values. */
internal object RawZip {

    class Entry(
        val name: String,
        val data: ByteArray = ByteArray(0),
        val method: Int = METHOD_STORED,
        val compressedSize: Long = data.size.toLong(),
        val uncompressedSize: Long = data.size.toLong(),
        val flags: Int = 0,
    )

    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8

    fun build(entries: List<Entry>): ByteArray {
        val output = ByteArrayOutputStream()
        val localOffsets = ArrayList<Int>(entries.size)
        for (entry in entries) {
            localOffsets.add(output.size())
            writeLe32(output, 0x04034B50L)
            writeLe16(output, 20) // version needed
            writeLe16(output, entry.flags)
            writeLe16(output, entry.method)
            writeLe16(output, 0) // mod time
            writeLe16(output, 0) // mod date
            writeLe32(output, 0) // crc (the engine does not depend on it)
            writeLe32(output, entry.compressedSize)
            writeLe32(output, entry.uncompressedSize)
            writeLe16(output, entry.name.toByteArray(Charsets.UTF_8).size)
            writeLe16(output, 0) // extra length
            output.write(entry.name.toByteArray(Charsets.UTF_8))
            output.write(entry.data)
        }
        val centralOffset = output.size()
        for (index in entries.indices) {
            val entry = entries[index]
            writeLe32(output, 0x02014B50L)
            writeLe16(output, 20) // version made by
            writeLe16(output, 20) // version needed
            writeLe16(output, entry.flags)
            writeLe16(output, entry.method)
            writeLe16(output, 0)
            writeLe16(output, 0)
            writeLe32(output, 0)
            writeLe32(output, entry.compressedSize)
            writeLe32(output, entry.uncompressedSize)
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            writeLe16(output, nameBytes.size)
            writeLe16(output, 0)
            writeLe16(output, 0)
            writeLe16(output, 0) // disk start
            writeLe16(output, 0) // internal attrs
            writeLe32(output, 0) // external attrs
            writeLe32(output, localOffsets[index].toLong())
            output.write(nameBytes)
        }
        val centralSize = output.size() - centralOffset
        writeLe32(output, 0x06054B50L)
        writeLe16(output, 0) // disk number
        writeLe16(output, 0) // central directory disk
        writeLe16(output, entries.size)
        writeLe16(output, entries.size)
        writeLe32(output, centralSize.toLong())
        writeLe32(output, centralOffset.toLong())
        writeLe16(output, 0) // comment length
        return output.toByteArray()
    }

    private fun writeLe16(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    private fun writeLe32(output: ByteArrayOutputStream, value: Long) {
        output.write((value and 0xFF).toInt())
        output.write(((value shr 8) and 0xFF).toInt())
        output.write(((value shr 16) and 0xFF).toInt())
        output.write(((value shr 24) and 0xFF).toInt())
    }
}

internal object Fixtures {

    const val MIMETYPE = "application/epub+zip"
    const val CONTAINER_PATH = "META-INF/container.xml"
    const val OPF_PATH = "OEBPS/content.opf"
    const val NAV_PATH = "OEBPS/nav.xhtml"
    const val NCX_PATH = "OEBPS/toc.ncx"
    const val CH1 = "OEBPS/text/ch1.xhtml"
    const val CH2 = "OEBPS/text/ch2.xhtml"
    const val CH3 = "OEBPS/text/ch3.xhtml"
    const val PIC = "OEBPS/images/pic.png"
    const val COVER = "OEBPS/images/cover.jpg"

    const val TITLE = "The Fixture Book"
    const val AUTHOR = "Ada Lovelace"
    const val LANGUAGE = "en"

    val PIC_BYTES = byteArrayOf(0x50, 0x49, 0x43, 0x00, 0x01, 0x02, 0x7F, 0xFF.toByte())
    val COVER_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x0D, 0x0A, 0x1A, 0x0A, 0x42)

    fun container(
        rootfile: String = OPF_PATH,
        mediaType: String = "application/oebps-package+xml",
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$rootfile" media-type="$mediaType"/>
  </rootfiles>
</container>"""

    fun xhtml(body: String, headExtra: String = ""): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Fixture</title>$headExtra</head>
<body>$body</body>
</html>"""

    fun item(id: String, href: String, mediaType: String, properties: String? = null): String {
        val propertiesAttribute = if (properties == null) "" else """ properties="$properties""""
        return """<item id="$id" href="$href" media-type="$mediaType"$propertiesAttribute/>"""
    }

    fun itemref(idref: String): String = """<itemref idref="$idref"/>"""

    fun opf(
        version: String = "3.0",
        metadataInner: String = defaultMetadata(),
        manifestInner: String,
        spineInner: String,
        spineAttributes: String = "",
    ): String = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="$version" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    $metadataInner
  </metadata>
  <manifest>
    $manifestInner
  </manifest>
  <spine$spineAttributes>
    $spineInner
  </spine>
</package>"""

    fun defaultMetadata(): String = """<dc:identifier id="book-id">urn:uuid:fixture-0001</dc:identifier>
    <dc:title>$TITLE</dc:title>
    <dc:creator>$AUTHOR</dc:creator>
    <dc:language>$LANGUAGE</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>"""

    fun epub3Manifest(): String = listOf(
        item("nav", "nav.xhtml", "application/xhtml+xml", "nav"),
        item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
        item("ch2", "text/ch2.xhtml", "application/xhtml+xml"),
        item("ch3", "text/ch3.xhtml", "application/xhtml+xml"),
        item("cover-image", "images/cover.jpg", "image/jpeg", "cover-image"),
        item("pic", "images/pic.png", "image/png"),
    ).joinToString("\n    ")

    fun epub3Spine(): String = listOf(itemref("ch1"), itemref("ch2"), itemref("ch3"))
        .joinToString("\n    ")

    fun epub3Nav(): String = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Navigation</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Contents</h1>
    <ol>
      <li><a href="text/ch1.xhtml">Chapter One</a></li>
      <li><a href="text/ch2.xhtml">Part Two</a>
        <ol>
          <li><a href="text/ch3.xhtml#section">Chapter Three</a></li>
        </ol>
      </li>
    </ol>
  </nav>
  <nav epub:type="landmarks" id="landmarks">
    <ol><li><a href="text/ch1.xhtml">Start</a></li></ol>
  </nav>
</body>
</html>"""

    fun chapterOne(): String = xhtml(
        """  <h1>Chapter One</h1>
  <p>Intro with <strong>bold</strong>, <em>italic</em>, <code>inline code</code> and <a href="https://example.com/x">a link</a>.</p>
  <p><img src="../images/pic.png" alt="A picture"/></p>
  <hr/>
  <blockquote><p>Quoted wisdom.</p></blockquote>
  <pre>line one
line two</pre>
  <ul>
    <li>Bullet one</li>
    <li>Bullet two
      <ul><li>Nested bullet</li></ul>
    </li>
  </ul>
  <table>
    <tr><td>Alpha</td><td>Beta</td></tr>
    <tr><td>Gamma</td><td>Delta</td></tr>
  </table>
  <p>Tail&nbsp;text &amp; entities</p>""",
    )

    fun chapterTwo(): String = xhtml(
        """  <h1>Second Chapter</h1>
  <p>Second chapter body.</p>""",
    )

    fun chapterThree(): String = xhtml(
        """  <h2>Third Chapter</h2>
  <p>Third chapter body.</p>""",
    )

    fun epub3Builder(): ZipBuilder = ZipBuilder()
        .add("mimetype", MIMETYPE)
        .add(CONTAINER_PATH, container())
        .add(OPF_PATH, opf(manifestInner = epub3Manifest(), spineInner = epub3Spine()))
        .add(NAV_PATH, epub3Nav())
        .add(CH1, chapterOne())
        .add(CH2, chapterTwo())
        .add(CH3, chapterThree())
        .addBytes(PIC, PIC_BYTES)
        .addBytes(COVER, COVER_BYTES)

    fun epub3(): ByteArray = epub3Builder().build()

    /** Same book as [epub3] but materialised as raw stored entries (control over headers). */
    fun epub3RawEntries(): List<RawZip.Entry> = listOf(
        RawZip.Entry("mimetype", MIMETYPE.toByteArray(Charsets.UTF_8)),
        RawZip.Entry(CONTAINER_PATH, container().toByteArray(Charsets.UTF_8)),
        RawZip.Entry(OPF_PATH, opf(manifestInner = epub3Manifest(), spineInner = epub3Spine()).toByteArray(Charsets.UTF_8)),
        RawZip.Entry(NAV_PATH, epub3Nav().toByteArray(Charsets.UTF_8)),
        RawZip.Entry(CH1, chapterOne().toByteArray(Charsets.UTF_8)),
        RawZip.Entry(CH2, chapterTwo().toByteArray(Charsets.UTF_8)),
        RawZip.Entry(CH3, chapterThree().toByteArray(Charsets.UTF_8)),
        RawZip.Entry(PIC, PIC_BYTES),
        RawZip.Entry(COVER, COVER_BYTES),
    )

    // ---- EPUB2 -----------------------------------------------------------

    fun epub2Metadata(): String = """<dc:identifier id="book-id">urn:uuid:fixture-0002</dc:identifier>
    <dc:title>An EPUB Two Book</dc:title>
    <dc:creator>Grace Hopper</dc:creator>
    <dc:language>en-US</dc:language>
    <meta name="cover" content="cover-image"/>"""

    fun epub2Manifest(): String = listOf(
        item("ncx", "toc.ncx", "application/x-dtbncx+xml"),
        item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
        item("ch2", "text/ch2.xhtml", "application/xhtml+xml"),
        item("cover-image", "images/cover.jpg", "image/jpeg"),
    ).joinToString("\n    ")

    fun epub2Ncx(): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:fixture-0002"/></head>
  <docTitle><text>An EPUB Two Book</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1">
      <navLabel><text>Chapter One</text></navLabel>
      <content src="text/ch1.xhtml"/>
      <navPoint id="np1a" playOrder="2">
        <navLabel><text>Section One A</text></navLabel>
        <content src="text/ch1.xhtml#a"/>
      </navPoint>
    </navPoint>
    <navPoint id="np2" playOrder="3">
      <navLabel><text>Chapter Two</text></navLabel>
      <content src="text/ch2.xhtml"/>
    </navPoint>
  </navMap>
</ncx>"""

    fun epub2ChapterOne(): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter One</title></head>
<body>
  <h1>Chapter One</h1>
  <p>An EPUB2 paragraph with a non-breaking&nbsp;space and an accent: &eacute;.</p>
</body>
</html>"""

    fun epub2ChapterTwo(): String = xhtml(
        """  <h1>Chapter Two</h1>
  <p>Second EPUB2 chapter.</p>""",
    )

    fun epub2Builder(): ZipBuilder = ZipBuilder()
        .add("mimetype", MIMETYPE)
        .add(CONTAINER_PATH, container())
        .add(
            OPF_PATH,
            opf(
                version = "2.0",
                metadataInner = epub2Metadata(),
                manifestInner = epub2Manifest(),
                spineInner = listOf(itemref("ch1"), itemref("ch2")).joinToString("\n    "),
                spineAttributes = """ toc="ncx"""",
            ),
        )
        .add(NCX_PATH, epub2Ncx())
        .add(CH1, epub2ChapterOne())
        .add(CH2, epub2ChapterTwo())
        .addBytes(COVER, COVER_BYTES)

    fun epub2(): ByteArray = epub2Builder().build()

    // ---- generic assembly ------------------------------------------------

    fun book(
        opf: String,
        entries: Map<String, String>,
        extraBytes: Map<String, ByteArray> = emptyMap(),
        containerXml: String? = container(),
        includeMimetype: Boolean = true,
    ): ByteArray {
        val builder = ZipBuilder()
        if (includeMimetype) builder.add("mimetype", MIMETYPE)
        if (containerXml != null) builder.add(CONTAINER_PATH, containerXml)
        builder.add(OPF_PATH, opf)
        for ((path, content) in entries) builder.add(path, content)
        for ((path, content) in extraBytes) builder.remove(path).addBytes(path, content)
        return builder.build()
    }
}
