package com.cipherwell.notezero.book

import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Dependency-free EPUB 2 / EPUB 3 parser.
 *
 * Input is a complete `application/epub+zip` file in memory. The result is a plain Kotlin
 * [EpubDocument] for the UI; no XML/ZIP types escape this package and no Android API is used,
 * so the engine is unit-testable on a plain JVM.
 *
 * Pipeline: ZIP central directory (bounded) -> DRM check -> `META-INF/container.xml` ->
 * OPF (metadata, manifest, spine, cover) -> cover bytes -> XHTML chapters (SAX) -> TOC
 * (EPUB3 nav, else EPUB2 NCX, else derived per-chapter titles).
 *
 * Failure policy: any malformed/unsupported/DRM-protected/oversized document fails closed
 * with null. [parse] never throws, even for arbitrary bytes (see fuzz tests).
 */
object EpubParser {

    /** Media type every valid EPUB file declares in `mimetype`. */
    const val MIME: String = "application/epub+zip"

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val RIGHTS_PATH = "META-INF/rights.xml"
    private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
    private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
    private const val EPUB_OPS_NS = "http://www.idpf.org/2007/ops"
    private const val MAX_TOC_ENTRIES = 10_000
    private const val MAX_TOC_DEPTH = 16

    private class ManifestItem(
        val href: String,
        val mediaType: String?,
        val properties: Set<String>,
    )

    private class SpineItem(
        val href: String,
        val mediaType: String?,
    )

    /** Parses an EPUB file. Returns null for invalid/unsupported/DRM-protected input; never throws. */
    fun parse(bytes: ByteArray): EpubDocument? {
        if (bytes.isEmpty()) return null
        return try {
            parseArchive(bytes)
        } catch (_: Throwable) {
            // Fail closed for every input, including pathological XML/ZIP structures that can
            // escape more targeted guards (e.g. stack depth inside the XML parser itself).
            null
        }
    }

    private fun parseArchive(bytes: ByteArray): EpubDocument? {
        val archive = ZipArchive.open(bytes) ?: return null
        if (!passesDrmCheck(archive)) return null
        val packagePath = readPackagePath(archive) ?: return null
        val packageBytes = archive.read(packagePath, BookLimits.MAX_XML_BYTES) ?: return null
        val packageDocument = SecureXml.parseDocument(packageBytes) ?: return null
        val packageRoot = packageDocument.documentElement ?: return null

        val metadata = firstDescendant(packageRoot, "metadata")
        val title = firstNonBlankText(metadata ?: packageRoot, "title") ?: firstNonBlankText(packageRoot, "title") ?: "Untitled"
        val author = firstNonBlankText(metadata ?: packageRoot, "creator") ?: firstNonBlankText(packageRoot, "creator")
        val language = firstNonBlankText(metadata ?: packageRoot, "language") ?: firstNonBlankText(packageRoot, "language")

        val manifest = readManifest(packageRoot, packagePath)
        val (spine, spineTocId) = readSpine(packageRoot, manifest)
        val chapters = readChapters(archive, spine) ?: return null

        var toc = readNavToc(archive, manifest, chapters)
        if (toc.isEmpty()) toc = readNcxToc(archive, manifest, spineTocId, chapters)
        if (toc.isEmpty()) toc = deriveToc(chapters)

        val cover = readCover(archive, packageRoot, manifest)
        val imageEntries = collectImageEntries(chapters)
        val retainedArchive = if (imageEntries.isEmpty()) null else archive
        return EpubDocument(
            title = title,
            author = author,
            language = language,
            chapters = chapters,
            toc = toc,
            archive = retainedArchive,
            coverBytes = cover,
            imageEntries = imageEntries,
        )
    }

    // ---- DRM -------------------------------------------------------------

    /**
     * Refuses any book that declares encrypted resources. `EncryptedData` in
     * `META-INF/encryption.xml` (DRM or font obfuscation) is unsupported by design: the
     * engine never attempts key recovery or content de-obfuscation.
     */
    private fun passesDrmCheck(archive: ZipArchive): Boolean {
        if (archive.contains(RIGHTS_PATH)) return false
        if (!archive.contains(ENCRYPTION_PATH)) return true
        val bytes = archive.read(ENCRYPTION_PATH, BookLimits.MAX_XML_BYTES) ?: return false
        val document = SecureXml.parseDocument(bytes) ?: return false
        val root = document.documentElement ?: return false
        return !containsElement(root, "EncryptedData")
    }

    // ---- container / OPF -------------------------------------------------

    private fun readPackagePath(archive: ZipArchive): String? {
        val bytes = archive.read(CONTAINER_PATH, BookLimits.MAX_CONTAINER_XML_BYTES) ?: return null
        val document = SecureXml.parseDocument(bytes) ?: return null
        val root = document.documentElement ?: return null
        val rootfiles = descendants(root, "rootfile")
        if (rootfiles.isEmpty()) return null
        for (element in rootfiles) {
            if (!element.getAttribute("media-type").trim().equals(OPF_MEDIA_TYPE, ignoreCase = true)) continue
            normalizeRootfilePath(element.getAttribute("full-path"))?.let { return it }
        }
        for (element in rootfiles) {
            normalizeRootfilePath(element.getAttribute("full-path"))?.let { return it }
        }
        return null
    }

    private fun normalizeRootfilePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val relative = if (trimmed.startsWith("/")) trimmed.substring(1) else trimmed
        return EpubPaths.normalize(relative)
    }

    private fun readManifest(packageRoot: Element, packagePath: String): Map<String, ManifestItem> {
        val result = LinkedHashMap<String, ManifestItem>()
        val manifest = firstDescendant(packageRoot, "manifest") ?: packageRoot
        for (element in descendants(manifest, "item")) {
            val id = element.getAttribute("id").trim()
            if (id.isEmpty() || result.containsKey(id)) continue
            val hrefRaw = element.getAttribute("href").trim()
            if (hrefRaw.isEmpty()) continue
            val href = EpubPaths.resolve(packagePath, hrefRaw) ?: continue
            val mediaType = element.getAttribute("media-type").trim().lowercase().ifEmpty { null }
            val properties = element.getAttribute("properties")
                .trim()
                .split(WHITESPACE_PATTERN)
                .filter { it.isNotEmpty() }
                .toSet()
            result[id] = ManifestItem(href, mediaType, properties)
        }
        return result
    }

    private fun readSpine(packageRoot: Element, manifest: Map<String, ManifestItem>): Pair<List<SpineItem>, String?> {
        val spine = firstDescendant(packageRoot, "spine") ?: return emptyList<SpineItem>() to null
        val tocId = spine.getAttribute("toc").trim().ifEmpty { null }
        val spineItems = ArrayList<SpineItem>()
        for (element in descendants(spine, "itemref")) {
            if (spineItems.size >= BookLimits.MAX_CHAPTERS) break
            val idref = element.getAttribute("idref").trim()
            val item = manifest[idref] ?: continue
            spineItems.add(SpineItem(item.href, item.mediaType))
        }
        return spineItems to tocId
    }

    private fun readChapters(archive: ZipArchive, spine: List<SpineItem>): List<BookChapter>? {
        val chapters = ArrayList<BookChapter>(spine.size)
        for (spineItem in spine) {
            if (isBinaryMediaType(spineItem.mediaType)) {
                // Binary spine entries are not renderable text; keep the spine slot empty
                // rather than failing the whole book.
                chapters.add(BookChapter(chapters.size, null, spineItem.href, emptyList()))
                continue
            }
            val bytes = archive.read(spineItem.href, BookLimits.MAX_ENTRY_BYTES) ?: continue
            val blocks = ChapterBlocks.parse(bytes, spineItem.href) ?: return null
            chapters.add(
                BookChapter(
                    index = chapters.size,
                    title = deriveChapterTitle(blocks),
                    href = spineItem.href,
                    blocks = blocks,
                ),
            )
        }
        return chapters
    }

    private fun isBinaryMediaType(mediaType: String?): Boolean {
        if (mediaType == null) return false
        if (mediaType.endsWith("+xml") || mediaType == "text/html" || mediaType == "text/xml" ||
            mediaType == "application/xml" || mediaType == "application/xhtml+xml"
        ) {
            return false
        }
        return mediaType.startsWith("image/") ||
            mediaType.startsWith("audio/") ||
            mediaType.startsWith("video/") ||
            mediaType.startsWith("font/") ||
            mediaType.startsWith("application/font") ||
            mediaType == "application/vnd.ms-opentype" ||
            mediaType == "application/vnd.ms-fontobject" ||
            mediaType == "text/css" ||
            mediaType == "text/javascript" ||
            mediaType == "application/javascript"
    }

    private fun deriveChapterTitle(blocks: List<BookBlock>): String? {
        var levelTwo: String? = null
        for (block in blocks) {
            if (block !is BookBlock.Heading) continue
            val text = inlineRunsText(block.runs)
            if (text.isBlank()) continue
            if (block.level == 1) return truncateTitle(text)
            if (block.level == 2 && levelTwo == null) levelTwo = text
        }
        return levelTwo?.let { truncateTitle(it) }
    }

    private fun truncateTitle(text: String): String? {
        val collapsed = collapseWhitespace(text) ?: return null
        return if (collapsed.length <= BookLimits.MAX_DERIVED_TITLE_CHARS) {
            collapsed
        } else {
            collapsed.substring(0, BookLimits.MAX_DERIVED_TITLE_CHARS).trimEnd()
        }
    }

    private fun deriveToc(chapters: List<BookChapter>): List<BookTocEntry> =
        chapters.mapIndexed { index, chapter ->
            BookTocEntry(
                title = chapter.title ?: "Chapter ${index + 1}",
                chapterIndex = index,
                depth = 0,
            )
        }

    // ---- TOC: EPUB3 nav --------------------------------------------------

    private fun readNavToc(
        archive: ZipArchive,
        manifest: Map<String, ManifestItem>,
        chapters: List<BookChapter>,
    ): List<BookTocEntry> {
        val navItem = manifest.values.firstOrNull { it.properties.contains("nav") } ?: return emptyList()
        val bytes = archive.read(navItem.href, BookLimits.MAX_XML_BYTES) ?: return emptyList()
        val document = SecureXml.parseDocument(bytes) ?: return emptyList()
        val root = document.documentElement ?: return emptyList()
        val navElements = descendants(root, "nav")
        val tocNav = navElements.firstOrNull { isTocNavElement(it) } ?: navElements.firstOrNull() ?: return emptyList()
        val orderedList = firstDescendant(tocNav, "ol") ?: return emptyList()
        val chapterIndex = chapterIndexByHref(chapters)
        val entries = ArrayList<BookTocEntry>()
        parseNavList(orderedList, navItem.href, chapterIndex, 0, entries)
        return entries
    }

    private fun isTocNavElement(nav: Element): Boolean {
        val type = nav.getAttributeNS(EPUB_OPS_NS, "type").ifEmpty { nav.getAttribute("epub:type") }
            .ifEmpty { nav.getAttribute("type") }
        return type.trim().split(WHITESPACE_PATTERN).any { it == "toc" }
    }

    private fun parseNavList(
        orderedList: Element,
        documentPath: String,
        chapterIndex: Map<String, Int>,
        depth: Int,
        out: MutableList<BookTocEntry>,
    ) {
        if (depth > MAX_TOC_DEPTH || out.size >= MAX_TOC_ENTRIES) return
        for (item in directChildren(orderedList, "li")) {
            if (out.size >= MAX_TOC_ENTRIES) return
            var label: String? = null
            var href: String? = null
            var nested: Element? = null
            for (child in directChildren(item)) {
                when (localNameOf(child)) {
                    "a" -> {
                        if (href == null) {
                            href = child.getAttribute("href").trim().ifEmpty { null }
                            label = navLabelText(child)
                        }
                    }
                    "span" -> if (label == null) label = navLabelText(child)
                    "ol" -> nested = child
                    else -> Unit
                }
            }
            if (label != null && href != null) {
                val chapter = EpubPaths.resolve(documentPath, href)?.let { chapterIndex[it] }
                if (chapter != null) out.add(BookTocEntry(label, chapter, depth))
            }
            nested?.let { parseNavList(it, documentPath, chapterIndex, depth + 1, out) }
        }
    }

    private fun navLabelText(anchor: Element): String? {
        val text = collapseWhitespace(anchor.textContent)
        if (text != null) return text
        return collapseWhitespace(anchor.getAttribute("title"))
    }

    // ---- TOC: EPUB2 NCX --------------------------------------------------

    private fun readNcxToc(
        archive: ZipArchive,
        manifest: Map<String, ManifestItem>,
        spineTocId: String?,
        chapters: List<BookChapter>,
    ): List<BookTocEntry> {
        val ncxItem = spineTocId?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
            ?: return emptyList()
        val bytes = archive.read(ncxItem.href, BookLimits.MAX_XML_BYTES) ?: return emptyList()
        val document = SecureXml.parseDocument(bytes) ?: return emptyList()
        val root = document.documentElement ?: return emptyList()
        val navMap = firstDescendant(root, "navMap") ?: return emptyList()
        val chapterIndex = chapterIndexByHref(chapters)
        val entries = ArrayList<BookTocEntry>()
        parseNcxPoints(navMap, ncxItem.href, chapterIndex, 0, entries)
        return entries
    }

    private fun parseNcxPoints(
        parent: Element,
        documentPath: String,
        chapterIndex: Map<String, Int>,
        depth: Int,
        out: MutableList<BookTocEntry>,
    ) {
        if (depth > MAX_TOC_DEPTH || out.size >= MAX_TOC_ENTRIES) return
        for (navPoint in directChildren(parent, "navPoint")) {
            if (out.size >= MAX_TOC_ENTRIES) return
            val label = firstChild(navPoint, "navLabel")
                ?.let { firstDescendant(it, "text") }
                ?.let { collapseWhitespace(it.textContent) }
            val source = firstChild(navPoint, "content")
                ?.getAttribute("src")
                ?.trim()
                ?.ifEmpty { null }
            if (label != null && source != null) {
                val chapter = EpubPaths.resolve(documentPath, source)?.let { chapterIndex[it] }
                if (chapter != null) out.add(BookTocEntry(label, chapter, depth))
            }
            parseNcxPoints(navPoint, documentPath, chapterIndex, depth + 1, out)
        }
    }

    private fun chapterIndexByHref(chapters: List<BookChapter>): Map<String, Int> {
        val result = HashMap<String, Int>(chapters.size * 2)
        for (chapter in chapters) result[chapter.href] = chapter.index
        return result
    }

    // ---- cover -----------------------------------------------------------

    private fun readCover(
        archive: ZipArchive,
        packageRoot: Element,
        manifest: Map<String, ManifestItem>,
    ): ByteArray? {
        val coverItem = manifest.values.firstOrNull { it.properties.contains("cover-image") }
            ?: readCoverMetaId(packageRoot)?.let { manifest[it] }
            ?: return null
        return archive.read(coverItem.href, BookLimits.MAX_COVER_BYTES)
    }

    private fun readCoverMetaId(packageRoot: Element): String? {
        val metadata = firstDescendant(packageRoot, "metadata") ?: packageRoot
        for (meta in descendants(metadata, "meta")) {
            if (!meta.getAttribute("name").trim().equals("cover", ignoreCase = true)) continue
            val content = meta.getAttribute("content").trim()
            if (content.isNotEmpty()) return content
        }
        return null
    }

    // ---- helpers ---------------------------------------------------------

    private fun collectImageEntries(chapters: List<BookChapter>): Set<String> {
        val entries = LinkedHashSet<String>()
        for (chapter in chapters) {
            for (block in chapter.blocks) {
                if (block is BookBlock.Image) entries.add(block.entry)
            }
        }
        return entries
    }

    private fun localNameOf(node: Node): String {
        val local = node.localName
        return if (!local.isNullOrEmpty()) local else node.nodeName.substringAfter(':')
    }

    private fun firstDescendant(root: Element, localName: String): Element? {
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (localNameOf(element) == localName) return element
                firstDescendant(element, localName)?.let { return it }
            }
            child = child.nextSibling
        }
        return null
    }

    private fun descendants(root: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        collectDescendants(root, localName, out)
        return out
    }

    private fun collectDescendants(root: Node, localName: String, out: MutableList<Element>) {
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (localNameOf(element) == localName) out.add(element)
                collectDescendants(element, localName, out)
            }
            child = child.nextSibling
        }
    }

    private fun directChildren(root: Element): List<Element> {
        val out = ArrayList<Element>(root.childNodes.length.coerceAtMost(8))
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) out.add(child as Element)
            child = child.nextSibling
        }
        return out
    }

    private fun directChildren(root: Element, localName: String): List<Element> =
        directChildren(root).filter { localNameOf(it) == localName }

    private fun firstChild(root: Element, localName: String): Element? {
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (localNameOf(element) == localName) return element
            }
            child = child.nextSibling
        }
        return null
    }

    private fun firstNonBlankText(root: Element, localName: String): String? {
        for (element in descendants(root, localName)) {
            val text = collapseWhitespace(element.textContent)
            if (text != null) return text
        }
        return null
    }

    private fun containsElement(root: Node, localName: String): Boolean {
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (localNameOf(element) == localName) return true
                if (containsElement(element, localName)) return true
            }
            child = child.nextSibling
        }
        return false
    }

    private fun collapseWhitespace(raw: String?): String? {
        if (raw == null) return null
        val builder = StringBuilder(raw.length.coerceAtMost(512))
        var pendingSpace = false
        for (ch in raw) {
            if (ch.isWhitespace()) {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(ch)
            }
        }
        return builder.toString().ifEmpty { null }
    }

    private val WHITESPACE_PATTERN = Regex("\\s+")
}
