package com.cipherwell.notezero.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Archive-path normalisation/resolution: the single choke point for every ZIP lookup. */
class EpubPathsTest {

    @Test
    fun `normalizes harmless paths`() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.normalize("OEBPS/text/ch1.xhtml"))
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.normalize("OEBPS/./text//ch1.xhtml"))
        assertEquals("OEBPS/ch1.xhtml", EpubPaths.normalize("OEBPS/text/../ch1.xhtml"))
        assertEquals("a b.xhtml", EpubPaths.normalize("a b.xhtml"))
    }

    @Test
    fun `rejects unsafe paths`() {
        assertNull(EpubPaths.normalize(""))
        assertNull(EpubPaths.normalize("../secret"))
        assertNull(EpubPaths.normalize("OEBPS/../../secret"))
        assertNull(EpubPaths.normalize("/absolute"))
        assertNull(EpubPaths.normalize("back\\slash"))
        assertNull(EpubPaths.normalize("scheme:thing"))
        assertNull(EpubPaths.normalize("nul\u0000byte"))
        assertNull(EpubPaths.normalize("."))
    }

    @Test
    fun `resolves hrefs relative to the referring document`() {
        assertEquals("OEBPS/text/images/pic.png", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "images/pic.png"))
        assertEquals("OEBPS/images/pic.png", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "../images/pic.png"))
        assertEquals("OEBPS/nav.xhtml", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "../nav.xhtml"))
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.resolve("OEBPS/content.opf", "text/ch1.xhtml"))
    }

    @Test
    fun `strips fragments and queries and decodes percent escapes`() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.resolve("OEBPS/nav.xhtml", "text/ch1.xhtml#section-2"))
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.resolve("OEBPS/nav.xhtml", "text/ch1.xhtml?x=1"))
        assertEquals("OEBPS/text/my file.xhtml", EpubPaths.resolve("OEBPS/nav.xhtml", "text/my%20file.xhtml"))
        assertEquals("OEBPS/text/a\u00e9.xhtml", EpubPaths.resolve("OEBPS/nav.xhtml", "text/a%C3%A9.xhtml"))
    }

    @Test
    fun `rejects remote absolute and escaping hrefs`() {
        assertNull(EpubPaths.resolve("OEBPS/nav.xhtml", "http://example.com/x.xhtml"))
        assertNull(EpubPaths.resolve("OEBPS/nav.xhtml", "https://example.com/x.xhtml"))
        assertNull(EpubPaths.resolve("OEBPS/nav.xhtml", "data:text/html,hi"))
        assertNull(EpubPaths.resolve("OEBPS/nav.xhtml", "/OEBPS/x.xhtml"))
        assertNull(EpubPaths.resolve("OEBPS/nav.xhtml", "../../../etc/passwd"))
        // A ".." chain that stays inside the archive resolves normally; only escaping is rejected.
        assertEquals("evil.xhtml", EpubPaths.resolve("OEBPS/nav.xhtml", "text/../../evil.xhtml"))
        assertNull(EpubPaths.resolve("OEBPS/nav.xhtml", "bad\\path.xhtml"))
        assertNull(EpubPaths.resolve("OEBPS/nav.xhtml", ""))
    }

    @Test
    fun `percent decoding is strict`() {
        assertEquals("plain", EpubPaths.percentDecode("plain"))
        assertEquals("a b", EpubPaths.percentDecode("a%20b"))
        assertNull(EpubPaths.percentDecode("bad%2"))
        assertNull(EpubPaths.percentDecode("bad%zz"))
        assertNull(EpubPaths.percentDecode("bad%FF"))
    }

    @Test
    fun `directory and fragment helpers`() {
        assertEquals("OEBPS/text", EpubPaths.directoryOf("OEBPS/text/ch1.xhtml"))
        assertEquals("", EpubPaths.directoryOf("content.opf"))
        assertEquals("section", EpubPaths.fragmentOf("ch1.xhtml#section"))
        assertNull(EpubPaths.fragmentOf("ch1.xhtml"))
        assertNull(EpubPaths.fragmentOf("ch1.xhtml#"))
    }
}
