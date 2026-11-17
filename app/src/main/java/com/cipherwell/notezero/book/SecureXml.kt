package com.cipherwell.notezero.book

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.w3c.dom.Document
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader

/**
 * Hardened XML entry points for untrusted EPUB metadata and content documents.
 *
 * All parsers are configured to never load external DTDs, external entities or schemas and
 * never touch the network or filesystem (an [EntityResolver] short-circuits every external
 * resolution with empty content). Documents that declare an *internal* DTD subset are
 * rejected outright: no EPUB document needs one, and it is the only way to declare custom
 * entities, i.e. the only route to entity-expansion ("billion laughs") attacks. Well-known
 * XHTML named entities are instead rewritten to numeric character references before parsing
 * so that legitimate EPUB2 documents with `&nbsp;`/`&eacute;` keep working.
 */
internal object SecureXml {

    // JAXP 1.5 property names; kept as literals because some compile SDK levels do not
    // expose the XMLConstants fields (the Android XMLConstants class differs from the JDK).
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun parseDocument(bytes: ByteArray): Document? {
        if (bytes.isEmpty()) return null
        // UTF-16 would hide markup from the byte-level DTD guard; EPUB XML is UTF-8 in
        // practice, so an exotic-encoding document is treated as unsupported rather than
        // parsed with weaker guarantees.
        if (isUtf16(bytes)) return null
        if (containsInternalDtdSubset(bytes)) return null
        val payload = replaceNamedEntities(bytes)
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            try {
                factory.isXIncludeAware = false
            } catch (_: UnsupportedOperationException) {
                // Not supported by every Android JAXP implementation; not needed.
            }
            applyCommonFeatures(
                setFeature = { feature, value -> factory.setFeature(feature, value) },
            )
            try {
                factory.setAttribute(ACCESS_EXTERNAL_DTD, "")
                factory.setAttribute(ACCESS_EXTERNAL_SCHEMA, "")
            } catch (_: IllegalArgumentException) {
                // JAXP 1.5 attributes are unavailable on old Android; the resolver below
                // still guarantees no external fetch happens.
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver(EmptyEntityResolver)
            builder.setErrorHandler(StrictErrorHandler)
            builder.parse(ByteArrayInputStream(payload))
        } catch (_: Exception) {
            null
        }
    }

    fun newSaxReader(): XMLReader? {
        return try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            applyCommonFeatures(
                setFeature = { feature, value -> factory.setFeature(feature, value) },
            )
            val parser = factory.newSAXParser()
            val reader = parser.xmlReader
            applyCommonFeatures(
                setFeature = { feature, value -> reader.setFeature(feature, value) },
            )
            reader.entityResolver = EmptyEntityResolver
            reader.errorHandler = StrictErrorHandler
            reader
        } catch (_: Exception) {
            null
        }
    }

    private fun applyCommonFeatures(setFeature: (String, Boolean) -> Unit) {
        setFeatureQuietly(setFeature, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureQuietly(setFeature, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly(setFeature, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureQuietly(setFeature, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

    private fun setFeatureQuietly(setFeature: (String, Boolean) -> Unit, feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: Exception) {
            // Unsupported on this parser; the entity resolver is the load-bearing defence.
        }
    }

    private object EmptyEntityResolver : EntityResolver {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))
    }

    private object StrictErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException) {
            throw exception
        }

        override fun fatalError(exception: SAXParseException) {
            throw exception
        }
    }

    // ---- pre-parse hardening -------------------------------------------------

    /**
     * True when a DOCTYPE declaration carries an internal subset (`<!DOCTYPE x [ ... ]>`).
     * Such documents are rejected: they are the only way to declare entities without an
     * external DTD, and EPUB content/metadata never legitimately needs them.
     */
    fun containsInternalDtdSubset(bytes: ByteArray): Boolean {
        var index = indexOfAscii(bytes, "<!DOCTYPE", 0)
        while (index >= 0) {
            var probe = index + "<!DOCTYPE".length
            while (probe < bytes.size) {
                val value = bytes[probe].toInt() and 0xFF
                if (value == '['.code) return true
                if (value == '>'.code) break
                probe++
            }
            index = indexOfAscii(bytes, "<!DOCTYPE", index + 1)
        }
        return false
    }

    fun isUtf16(bytes: ByteArray): Boolean {
        if (bytes.size >= 2) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if ((first == 0xFE && second == 0xFF) || (first == 0xFF && second == 0xFE)) return true
        }
        if (bytes.size >= 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            val third = bytes[2].toInt() and 0xFF
            if (first == '<'.code && second == 0 && third == '?'.code) return true
            if (first == 0 && second == '<'.code && third == 0) return true
        }
        return false
    }

    /**
     * Rewrites the named XHTML entities that an external-DTD-less parser would otherwise
     * reject (EPUB2 content documents routinely rely on the XHTML DTD's entity set) into
     * numeric character references. Byte-oriented so it works for every ASCII-compatible
     * encoding and never re-encodes the document.
     */
    fun replaceNamedEntities(bytes: ByteArray): ByteArray {
        if (!hasNonPredefinedNamedEntity(bytes)) return bytes
        val output = ByteArrayOutputStream(bytes.size + 32)
        var index = 0
        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xFF
            if (value == '&'.code) {
                val nameEnd = scanEntityName(bytes, index + 1)
                if (nameEnd > 0 && nameEnd < bytes.size && bytes[nameEnd].toInt() == ';'.code) {
                    val name = String(bytes, index + 1, nameEnd - index - 1, Charsets.ISO_8859_1)
                    val codePoint = HTML_ENTITIES[name]
                    if (codePoint != null) {
                        output.write("&#$codePoint;".toByteArray(Charsets.US_ASCII))
                        index = nameEnd + 1
                        continue
                    }
                }
            }
            output.write(value)
            index++
        }
        return output.toByteArray()
    }

    private fun hasNonPredefinedNamedEntity(bytes: ByteArray): Boolean {
        var index = 0
        while (index < bytes.size) {
            if ((bytes[index].toInt() and 0xFF) == '&'.code) {
                val nameEnd = scanEntityName(bytes, index + 1)
                if (nameEnd > 0 && nameEnd < bytes.size && bytes[nameEnd].toInt() == ';'.code) {
                    val length = nameEnd - index - 1
                    if (!isPredefinedEntity(bytes, index + 1, length)) return true
                }
            }
            index++
        }
        return false
    }

    private fun scanEntityName(bytes: ByteArray, start: Int): Int {
        if (start >= bytes.size) return -1
        var index = start
        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xFF
            val isNameChar = (value in 'a'.code..'z'.code) ||
                (value in 'A'.code..'Z'.code) ||
                (value in '0'.code..'9'.code) ||
                value == '_'.code || value == '-'.code || value == '.'.code || value == ':'.code
            if (!isNameChar) break
            index++
            if (index - start > 32) return -1
        }
        return if (index == start) -1 else index
    }

    private fun isPredefinedEntity(bytes: ByteArray, offset: Int, length: Int): Boolean = when (length) {
        2 -> matchesAscii(bytes, offset, "lt") || matchesAscii(bytes, offset, "gt")
        3 -> matchesAscii(bytes, offset, "amp")
        4 -> matchesAscii(bytes, offset, "quot") || matchesAscii(bytes, offset, "apos")
        else -> false
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, value: String): Boolean {
        if (offset + value.length > bytes.size) return false
        for (index in value.indices) {
            if (bytes[offset + index].toInt() != value[index].code) return false
        }
        return true
    }

    private fun indexOfAscii(bytes: ByteArray, needle: String, from: Int): Int {
        if (needle.isEmpty()) return if (from <= bytes.size) from else -1
        val last = bytes.size - needle.length
        var index = maxOf(0, from)
        while (index <= last) {
            var matched = true
            for (offset in needle.indices) {
                if (bytes[index + offset].toInt() != needle[offset].code) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index++
        }
        return -1
    }

    private val LATIN1_ENTITY_NAMES = listOf(
        "nbsp", "iexcl", "cent", "pound", "curren", "yen", "brvbar", "sect", "uml", "copy",
        "ordf", "laquo", "not", "shy", "reg", "macr", "deg", "plusmn", "sup2", "sup3",
        "acute", "micro", "para", "middot", "cedil", "sup1", "ordm", "raquo", "frac14",
        "frac12", "frac34", "iquest", "Agrave", "Aacute", "Acirc", "Atilde", "Auml", "Aring",
        "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc", "Euml", "Igrave", "Iacute", "Icirc",
        "Iuml", "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", "times",
        "Oslash", "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN", "szlig", "agrave",
        "aacute", "acirc", "atilde", "auml", "aring", "aelig", "ccedil", "egrave", "eacute",
        "ecirc", "euml", "igrave", "iacute", "icirc", "iuml", "eth", "ntilde", "ograve",
        "oacute", "ocirc", "otilde", "ouml", "divide", "oslash", "ugrave", "uacute", "ucirc",
        "uuml", "yacute", "thorn", "yuml",
    )

    private val HTML_ENTITIES: Map<String, Int> = buildMap {
        LATIN1_ENTITY_NAMES.forEachIndexed { index, name -> put(name, 160 + index) }
        put("ensp", 8194)
        put("emsp", 8195)
        put("thinsp", 8201)
        put("zwnj", 8204)
        put("zwj", 8205)
        put("lrm", 8206)
        put("rlm", 8207)
        put("ndash", 8211)
        put("mdash", 8212)
        put("lsquo", 8216)
        put("rsquo", 8217)
        put("sbquo", 8218)
        put("ldquo", 8220)
        put("rdquo", 8221)
        put("bdquo", 8222)
        put("dagger", 8224)
        put("Dagger", 8225)
        put("bull", 8226)
        put("hellip", 8230)
        put("permil", 8240)
        put("lsaquo", 8249)
        put("rsaquo", 8250)
        put("euro", 8364)
        put("trade", 8482)
        put("OElig", 338)
        put("oelig", 339)
        put("Scaron", 352)
        put("scaron", 353)
        put("Yuml", 376)
        put("fnof", 402)
        put("circ", 710)
        put("tilde", 732)
    }
}
