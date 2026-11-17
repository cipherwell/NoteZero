package com.cipherwell.notezero.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * Fail-closed behaviour for hostile and broken input: DRM, missing/broken documents, XXE,
 * truncated/corrupt archives and arbitrary fuzz bytes. Every case must return null (or a
 * safe parse) without ever throwing.
 */
class EpubSecurityTest {

    private val encryptionXml = """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
            xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptedData>
    <enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/>
    <enc:CipherData><enc:CipherReference URI="OEBPS/text/ch1.xhtml"/></enc:CipherData>
  </enc:EncryptedData>
</encryption>"""

    @Test
    fun `encryption xml with EncryptedData is treated as DRM and rejected`() {
        val bytes = Fixtures.epub3Builder().add("META-INF/encryption.xml", encryptionXml).build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `rights xml presence is treated as DRM and rejected`() {
        val bytes = Fixtures.epub3Builder().add("META-INF/rights.xml", "<rights/>").build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `malformed encryption xml fails closed`() {
        val bytes = Fixtures.epub3Builder().add("META-INF/encryption.xml", "<encryption>").build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `encryption xml without EncryptedData does not block the book`() {
        val noEncryption = """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"/>"""
        val bytes = Fixtures.epub3Builder().add("META-INF/encryption.xml", noEncryption).build()
        assertNotNull(EpubParser.parse(bytes))
    }

    @Test
    fun `missing container returns null`() {
        val bytes = Fixtures.epub3Builder().remove(Fixtures.CONTAINER_PATH).build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `missing package document returns null`() {
        val bytes = Fixtures.epub3Builder().remove(Fixtures.OPF_PATH).build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `broken container xml returns null`() {
        val bytes = Fixtures.epub3Builder()
            .replace(Fixtures.CONTAINER_PATH, "<container><rootfiles>")
            .build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `broken package document xml returns null`() {
        val bytes = Fixtures.epub3Builder()
            .replace(Fixtures.OPF_PATH, "<package><metadata>")
            .build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `broken chapter xml fails the whole book instead of returning a partial book`() {
        val bytes = Fixtures.epub3Builder()
            .replace(Fixtures.CH1, "<html><body><p>unclosed")
            .build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(EpubParser.parse(ByteArray(0)))
    }

    @Test
    fun `non-zip input returns null`() {
        assertNull(EpubParser.parse("this is not an epub file".toByteArray(Charsets.UTF_8)))
        assertNull(EpubParser.parse("<html><body>hi</body></html>".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `truncated archives return null`() {
        val valid = Fixtures.epub3()
        val cuts = listOf(0, 1, 10, 100, 1024, valid.size / 2, valid.size - 1, valid.size - 10)
        for (cut in cuts) {
            val truncated = valid.copyOf(cut.coerceIn(0, valid.size))
            assertNull("cut at $cut must not parse", EpubParser.parse(truncated))
        }
    }

    @Test
    fun `corrupted end of central directory returns null`() {
        val valid = Fixtures.epub3()
        val corrupted = valid.copyOf()
        corrupted[corrupted.size - 22] = 0x00
        assertNull(EpubParser.parse(corrupted))
    }

    @Test
    fun `internal dtd subset with external entity is rejected before parsing`() {
        val containerXml = """<?xml version="1.0"?>
<!DOCTYPE container [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
        val bytes = Fixtures.epub3Builder().replace(Fixtures.CONTAINER_PATH, containerXml).build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `entity expansion bomb is rejected without expanding`() {
        val bomb = StringBuilder("""<?xml version="1.0"?>
<!DOCTYPE lolz [ <!ENTITY lol "lol">""")
        repeat(9) { level ->
            val name = if (level == 0) "lol" else "lol${level}"
            val previous = if (level == 0) "lol" else "lol${level - 1}"
            bomb.append("<!ENTITY ").append(name).append(" \"")
            repeat(10) { bomb.append("&").append(previous).append(";") }
            bomb.append("\">")
        }
        bomb.append(""">
<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata></metadata><manifest/><spine/></package>""")
        val bytes = Fixtures.epub3Builder().replace(Fixtures.OPF_PATH, bomb.toString()).build()
        assertNull(EpubParser.parse(bytes))
    }

    @Test
    fun `external doctype is never resolved and cannot leak file contents`() {
        val secretFile = kotlin.io.path.createTempFile("epub-secret", ".txt").toFile()
        secretFile.writeText("TOP-SECRET-FILE-CONTENT")
        try {
            val chapter = """<?xml version="1.0"?>
<!DOCTYPE html SYSTEM "file://${secretFile.absolutePath}">
<html xmlns="http://www.w3.org/1999/xhtml"><body><p>&secret;</p></body></html>"""
            val bytes = Fixtures.epub3Builder().replace(Fixtures.CH1, chapter).build()
            val document = EpubParser.parse(bytes)
            if (document != null) {
                val text = document.chapters.joinToString("\n") { it.plainText() }
                assertFalse("external entity must not be resolved", text.contains("secret"))
                assertFalse("external entity must not leak file contents", text.contains("TOP-SECRET-FILE-CONTENT"))
            }
        } finally {
            secretFile.delete()
        }
    }

    @Test
    fun `random bytes never throw and never parse`() {
        val random = Random(20261117)
        repeat(1000) { iteration ->
            val length = random.nextInt(0, 2048)
            val payload = ByteArray(length) { random.nextInt(0, 256).toByte() }
            assertNull("fuzz iteration $iteration must not parse", EpubParser.parse(payload))
        }
    }

    @Test
    fun `fuzz mutations of a valid epub never throw`() {
        val valid = Fixtures.epub3()
        val random = Random(424242)
        repeat(300) { iteration ->
            val mutated = valid.copyOf()
            val mutations = 1 + random.nextInt(4)
            repeat(mutations) {
                val index = random.nextInt(mutated.size)
                mutated[index] = random.nextInt(0, 256).toByte()
            }
            // Result may be null or a still-valid book; the invariant under test is no throw.
            EpubParser.parse(mutated)
        }
    }

    @Test
    fun `random chapter payload fails the book`() {
        val random = Random(7)
        val chapter = ByteArray(2048).also { random.nextBytes(it) }
        val bytes = Fixtures.book(
            opf = Fixtures.opf(
                manifestInner = Fixtures.item("ch1", "text/ch1.xhtml", "application/xhtml+xml"),
                spineInner = Fixtures.itemref("ch1"),
            ),
            entries = mapOf(Fixtures.CH1 to "placeholder"),
            extraBytes = mapOf(Fixtures.CH1 to chapter),
        )
        assertNull(ChapterBlocks.parse(chapter, Fixtures.CH1))
        assertNull("binary chapter document must fail the whole book", EpubParser.parse(bytes))
    }
}
