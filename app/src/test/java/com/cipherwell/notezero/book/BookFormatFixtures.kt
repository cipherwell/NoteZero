package com.cipherwell.notezero.book

import java.nio.charset.Charset
import java.util.Base64

/**
 * Generated-in-memory fixtures for the TXT / FB2 / CBZ engine tests. No binary fixture is
 * committed: archives, XML and text payloads are produced from readable literals here.
 */
internal object BookFormatFixtures {

    const val FALLBACK_TITLE = "Imported Book"

    val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)
    val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46)
    val GIF_BYTES = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x3B)
    val WEBP_BYTES = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x04, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun utf8Bom(text: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8(text)

    fun utf16Le(text: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    fun utf16Be(text: String): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)

    fun windows1252(text: String): ByteArray = text.toByteArray(Charset.forName("windows-1252"))

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** A tiny hand-built CBZ: natural-sortable page names in the given order. */
    fun cbz(vararg pages: Pair<String, ByteArray>): ByteArray {
        val builder = ZipBuilder()
        for ((name, bytes) in pages) builder.addBytes(name, bytes)
        return builder.build()
    }

    fun cbzPages(count: Int): ByteArray {
        val builder = ZipBuilder()
        for (index in 1..count) builder.addBytes("page$index.png", byteArrayOf(index.toByte()))
        return builder.build()
    }
}

/** FB2 (FictionBook 2) fixture builders. */
internal object Fb2Fixtures {

    const val NS = "http://www.gribuser.ru/xml/fictionbook/2.0"
    const val XLINK = "http://www.w3.org/1999/xlink"

    fun author(
        first: String? = null,
        middle: String? = null,
        last: String? = null,
        nickname: String? = null,
    ): String = buildString {
        append("<author>")
        if (first != null) append("<first-name>$first</first-name>")
        if (middle != null) append("<middle-name>$middle</middle-name>")
        if (last != null) append("<last-name>$last</last-name>")
        if (nickname != null) append("<nickname>$nickname</nickname>")
        append("</author>")
    }

    fun titleInfo(
        bookTitle: String? = "Fixture FB2",
        authors: String = author(first = "Ada", middle = "Byron", last = "Lovelace"),
        lang: String? = "en",
        coverpage: String? = null,
    ): String = buildString {
        append("<title-info>")
        if (bookTitle != null) append("<book-title>$bookTitle</book-title>")
        append(authors)
        if (lang != null) append("<lang>$lang</lang>")
        if (coverpage != null) append("<coverpage><image l:href=\"#$coverpage\"/></coverpage>")
        append("</title-info>")
    }

    fun description(titleInfo: String = titleInfo()): String = "<description>$titleInfo</description>"

    fun heading(text: String): String = "<title><p>$text</p></title>"

    fun section(title: String?, content: String, id: String? = null): String = buildString {
        append("<section")
        if (id != null) append(" id=\"$id\"")
        append(">")
        if (title != null) append(heading(title))
        append(content)
        append("</section>")
    }

    fun body(sections: String, title: String? = null, name: String? = null): String = buildString {
        append("<body")
        if (name != null) append(" name=\"$name\"")
        append(">")
        if (title != null) append(heading(title))
        append(sections)
        append("</body>")
    }

    fun binary(
        id: String,
        bytes: ByteArray,
        contentType: String = "image/png",
    ): String = "<binary id=\"$id\" content-type=\"$contentType\">" +
        BookFormatFixtures.base64(bytes) + "</binary>"

    fun document(
        description: String = description(),
        bodies: String,
        binaries: String = "",
    ): String = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="$NS" xmlns:l="$XLINK">
$description
$bodies
$binaries
</FictionBook>"""

    fun simple(
        title: String = "Fixture FB2",
        chapters: List<Pair<String?, String>> = listOf("Chapter One" to "<p>First body paragraph.</p>"),
        binaries: String = "",
    ): ByteArray {
        val bodies = body(
            chapters.joinToString("") { (sectionTitle, content) -> section(sectionTitle, content) },
        )
        return BookFormatFixtures.utf8(document(Fb2Fixtures.description(), bodies, binaries))
    }
}
