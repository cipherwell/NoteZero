package com.cipherwell.notezero.ui.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.cipherwell.notezero.model.Block
import com.cipherwell.notezero.model.MarkdownBlockCodec
import com.cipherwell.notezero.ui.theme.NoteZeroPalette
import java.io.ByteArrayOutputStream

/**
 * Renders a note (Markdown source, as produced by `NoteExport.toMarkdown`) into a PDF
 * document entirely in memory.
 *
 * The PDF is a **document copy for the user's explicit export**: A4 pages, title as the first
 * heading, styled headings, body text with inline bold/italic/code/strike/link styling, lists,
 * checklists, fenced code blocks, quotes, rules and simple tables, plus page numbers. Nothing is
 * written to disk here: [render] returns the bytes and the SAF export target is the only writer
 * (exactly like Markdown/HTML export). Content is never logged.
 *
 * Bounds and failure modes: input is truncated to [MAX_INPUT_CHARS]; the document is capped at
 * [maxPages] content pages (a final page states that it was truncated); long blocks are split at
 * line boundaries across pages; tables are capped at [MAX_TABLE_COLUMNS] columns and
 * [MAX_TABLE_ROWS] rows (a note states the truncation). Any failure — including OOM — returns
 * `null` so the UI shows the existing "copy could not be written" status instead of crashing.
 *
 * Paper is always white with dark text, independent of the app theme: an exported document must
 * stay readable on paper and in other viewers.
 */
class NotePdfRenderer(
    private val maxPages: Int = MAX_PAGES,
) {

    /** Renders [markdown] to PDF bytes, or null when the document cannot be produced. */
    fun render(markdown: String): ByteArray? {
        val source = if (markdown.length > MAX_INPUT_CHARS) {
            markdown.substring(0, MAX_INPUT_CHARS)
        } else {
            markdown
        }
        val document = MarkdownBlockCodec.parse(source)
        val pdf = PdfDocument()
        return try {
            val writer = PageWriter(pdf)
            if (!writer.start()) return null
            drawBlocks(writer, document.blocks)
            if (writer.truncated) {
                if (!writer.finishCurrentAndStartOverflowPage()) writer.finishAll() else {
                    writer.drawNotice("Document truncated at $maxPages pages.")
                }
            }
            writer.finishAll()
            val bytes = ByteArrayOutputStream(INITIAL_OUTPUT_BYTES)
            pdf.writeTo(bytes)
            bytes.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { pdf.close() }
        }
    }

    // ------------------------------------------------------------------ blocks

    private fun drawBlocks(writer: PageWriter, blocks: List<Block>) {
        for (block in blocks) {
            if (writer.truncated) return
            when (block) {
                is Block.Heading -> drawHeading(writer, block)
                is Block.Paragraph -> drawParagraph(writer, block)
                is Block.Bullets -> drawBullets(writer, block)
                is Block.Numbered -> drawNumbered(writer, block)
                is Block.Checklist -> drawChecklist(writer, block)
                is Block.Code -> drawCode(writer, block)
                is Block.Quote -> drawQuote(writer, block)
                is Block.Rule -> drawRule(writer)
                is Block.Table -> drawTable(writer, block)
            }
        }
    }

    private fun drawHeading(writer: PageWriter, block: Block.Heading) {
        val size = headingSize(block.level)
        val layout = textLayout(
            runs = PdfInlineMarkdown.parse(block.text),
            textSize = size,
            color = TEXT,
            bold = true,
            width = CONTENT_WIDTH,
            lineSpacing = 1.18f,
        )
        writer.space(headingSpaceBefore(block.level))
        drawSliced(writer, layout, left = LEFT)
        writer.space(HEADING_SPACE_AFTER)
    }

    private fun drawParagraph(writer: PageWriter, block: Block.Paragraph) {
        val layout = textLayout(
            runs = PdfInlineMarkdown.parse(block.text),
            textSize = BODY_SIZE,
            color = TEXT,
            width = CONTENT_WIDTH,
            lineSpacing = 1.35f,
        )
        drawSliced(writer, layout, left = LEFT)
        writer.space(PARAGRAPH_SPACE_AFTER)
    }

    private fun drawBullets(writer: PageWriter, block: Block.Bullets) {
        block.items.forEach { item ->
            if (writer.truncated) return
            val indent = LEFT + listIndent(item.depth)
            val prefix = "• "
            drawListLine(writer, prefix = prefix, text = item.text, left = indent)
        }
        writer.space(PARAGRAPH_SPACE_AFTER)
    }

    private fun drawNumbered(writer: PageWriter, block: Block.Numbered) {
        block.items.forEachIndexed { index, item ->
            if (writer.truncated) return
            val indent = LEFT + listIndent(item.depth)
            val prefix = "${index + 1}. "
            drawListLine(writer, prefix = prefix, text = item.text, left = indent)
        }
        writer.space(PARAGRAPH_SPACE_AFTER)
    }

    private fun drawChecklist(writer: PageWriter, block: Block.Checklist) {
        block.items.forEach { item ->
            if (writer.truncated) return
            val prefix = if (item.checked) "[x] " else "[ ] "
            drawListLine(writer, prefix = prefix, text = item.text, left = LEFT)
        }
        writer.space(PARAGRAPH_SPACE_AFTER)
    }

    private fun drawListLine(writer: PageWriter, prefix: String, text: String, left: Float) {
        val width = (LEFT + CONTENT_WIDTH - left).coerceAtLeast(MIN_TEXT_WIDTH)
        val builder = SpannableStringBuilder(prefix)
        appendRuns(builder, PdfInlineMarkdown.parse(text))
        val paint = textPaint(BODY_SIZE, TEXT)
        val layout = StaticLayout.Builder
            .obtain(builder, 0, builder.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.32f)
            .build()
        drawSliced(writer, layout, left = left)
        writer.space(LIST_ITEM_SPACE)
    }

    private fun drawCode(writer: PageWriter, block: Block.Code) {
        val code = if (block.code.length > MAX_CODE_BLOCK_CHARS) {
            block.code.substring(0, MAX_CODE_BLOCK_CHARS) + "\n…"
        } else {
            block.code
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CODE_SIZE
            color = TEXT
            typeface = Typeface.MONOSPACE
        }
        val layout = StaticLayout.Builder
            .obtain(code, 0, code.length, paint, (CONTENT_WIDTH - CODE_PADDING * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.12f)
            .build()
        writer.space(CODE_SPACE_BEFORE)
        drawSliced(
            writer = writer,
            layout = layout,
            left = LEFT + CODE_PADDING,
            backgroundLeft = LEFT,
            backgroundRight = LEFT + CONTENT_WIDTH,
            background = CODE_BACKGROUND,
        )
        writer.space(CODE_SPACE_AFTER)
    }

    private fun drawQuote(writer: PageWriter, block: Block.Quote) {
        val builder = SpannableStringBuilder()
        appendRuns(builder, PdfInlineMarkdown.parse(block.text), color = SECONDARY)
        val paint = textPaint(BODY_SIZE, SECONDARY)
        val layout = StaticLayout.Builder
            .obtain(builder, 0, builder.length, paint, (CONTENT_WIDTH - QUOTE_INDENT).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.3f)
            .build()
        writer.space(QUOTE_SPACE_BEFORE)
        drawSliced(
            writer = writer,
            layout = layout,
            left = LEFT + QUOTE_INDENT,
            leftBar = SEPARATOR,
        )
        writer.space(QUOTE_SPACE_AFTER)
    }

    private fun drawRule(writer: PageWriter) {
        if (!writer.ensureSpace(RULE_SPACE)) return
        val canvas = writer.canvas ?: return
        val y = writer.y + RULE_SPACE / 2f
        canvas.drawLine(LEFT, y, LEFT + CONTENT_WIDTH, y, strokePaint(SEPARATOR, 0.8f))
        writer.advance(RULE_SPACE)
    }

    // ------------------------------------------------------------------ tables

    private fun drawTable(writer: PageWriter, block: Block.Table) {
        val columnCount = minOf(
            maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0),
            MAX_TABLE_COLUMNS,
        )
        if (columnCount <= 0) return
        val rows = block.rows.take(MAX_TABLE_ROWS)
        val headers = List(columnCount) { index -> block.headers.getOrElse(index) { "" } }
        val body = rows.map { row -> List(columnCount) { index -> row.getOrElse(index) { "" } } }
        val truncation = block.rows.size > rows.size

        val cellPaint = textPaint(TABLE_SIZE, TEXT)
        val boldPaint = textPaint(TABLE_SIZE, TEXT).apply { typeface = Typeface.DEFAULT_BOLD }
        val natural = FloatArray(columnCount) { column ->
            var width = cellPaint.measureText(headers[column])
            body.forEach { row -> width = maxOf(width, cellPaint.measureText(row[column])) }
            width + TABLE_CELL_PADDING * 2
        }
        val total = natural.sum()
        val scale = if (total > CONTENT_WIDTH) CONTENT_WIDTH / total else 1f
        val widths = FloatArray(columnCount) { natural[it] * scale }

        writer.space(TABLE_SPACE_BEFORE)
        drawTableRow(writer, headers, widths, header = true, boldPaint)
        body.forEach { row ->
            if (writer.truncated) return
            drawTableRow(writer, row, widths, header = false, boldPaint = null)
        }
        if (truncation) {
            drawNoticeLine(writer, "Table truncated: first ${rows.size} rows shown.")
        }
        writer.space(TABLE_SPACE_AFTER)
    }

    private fun drawTableRow(
        writer: PageWriter,
        cells: List<String>,
        widths: FloatArray,
        header: Boolean,
        boldPaint: TextPaint?,
    ) {
        val paint = boldPaint ?: textPaint(TABLE_SIZE, TEXT)
        val layouts = cells.mapIndexed { index, cell ->
            val text = if (cell.length > MAX_TABLE_CELL_CHARS) {
                cell.substring(0, MAX_TABLE_CELL_CHARS) + "…"
            } else {
                cell
            }
            val width = (widths[index] - TABLE_CELL_PADDING * 2).coerceAtLeast(1f)
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width.toInt())
                .setIncludePad(false)
                .build()
        }
        val rowHeight = (layouts.maxOfOrNull { it.height } ?: 0) + TABLE_CELL_PADDING * 2
        if (!writer.ensureSpace(rowHeight)) return
        val canvas = writer.canvas ?: return
        val top = writer.y
        if (header) {
            canvas.drawRect(LEFT, top, LEFT + widths.sum(), top + rowHeight, fillPaint(TABLE_HEADER))
        }
        var x = LEFT
        layouts.forEachIndexed { index, layout ->
            canvas.save()
            canvas.translate(x + TABLE_CELL_PADDING, top + TABLE_CELL_PADDING)
            layout.draw(canvas)
            canvas.restore()
            x += widths[index]
        }
        canvas.drawLine(LEFT, top + rowHeight, LEFT + widths.sum(), top + rowHeight, strokePaint(SEPARATOR, 0.6f))
        writer.advance(rowHeight)
    }

    // -------------------------------------------------------------- text utils

    private fun textLayout(
        runs: List<PdfInlineRun>,
        textSize: Float,
        color: Int,
        width: Float,
        lineSpacing: Float,
        bold: Boolean = false,
    ): StaticLayout {
        val builder = SpannableStringBuilder()
        appendRuns(builder, runs, color = color)
        val paint = textPaint(textSize, color).apply {
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        return StaticLayout.Builder
            .obtain(builder, 0, builder.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, lineSpacing)
            .build()
    }

    private fun appendRuns(
        builder: SpannableStringBuilder,
        runs: List<PdfInlineRun>,
        color: Int = TEXT,
    ) {
        runs.forEach { run ->
            val start = builder.length
            builder.append(run.text)
            val end = builder.length
            if (end <= start) return@forEach
            if (run.bold) builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.italic) {
                builder.setSpan(
                    if (run.bold) StyleSpan(Typeface.BOLD_ITALIC) else StyleSpan(Typeface.ITALIC),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (run.strike) builder.setSpan(
                android.text.style.StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (run.code) {
                builder.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(BackgroundColorSpan(CODE_BACKGROUND), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.link) {
                builder.setSpan(android.text.style.ForegroundColorSpan(LINK), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun textPaint(size: Float, color: Int): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
        }

    // ------------------------------------------------------------ page drawing

    /**
     * Draws [layout] starting at [left], splitting it at line boundaries across pages. Optional
     * background/left-bar rectangles are painted behind each slice (code/quote/table styling).
     */
    private fun drawSliced(
        writer: PageWriter,
        layout: StaticLayout,
        left: Float,
        backgroundLeft: Float = left,
        backgroundRight: Float = left + layout.width,
        background: Int? = null,
        leftBar: Int? = null,
    ) {
        var startLine = 0
        val lineCount = layout.lineCount
        val width = (backgroundRight - backgroundLeft).coerceAtLeast(1f)
        while (startLine < lineCount) {
            if (!writer.ensureSpace(1f)) return
            val canvas = writer.canvas ?: return
            val top = layout.getLineTop(startLine).toFloat()
            val available = writer.bottom - writer.y
            var endLine = startLine
            while (endLine < lineCount && (layout.getLineBottom(endLine) - top) <= available) {
                endLine += 1
            }
            if (endLine == startLine) endLine += 1 // a single line taller than the page: still draw it
            val bottom = if (endLine >= lineCount) layout.height.toFloat() else layout.getLineBottom(endLine - 1).toFloat()
            val sliceHeight = (bottom - top).coerceAtLeast(1f)
            canvas.save()
            canvas.clipRect(backgroundLeft, writer.y, backgroundLeft + width, writer.y + sliceHeight)
            background?.let {
                canvas.drawRect(backgroundLeft, writer.y, backgroundLeft + width, writer.y + sliceHeight, fillPaint(it))
            }
            leftBar?.let { bar ->
                canvas.drawRect(left - 8f, writer.y, left - 5f, writer.y + sliceHeight, fillPaint(bar))
            }
            canvas.translate(left, writer.y - top)
            layout.draw(canvas)
            canvas.restore()
            writer.advance(sliceHeight)
            startLine = endLine
            if (startLine < lineCount && !writer.newPage()) return
        }
    }

    private fun drawNoticeLine(writer: PageWriter, text: String) {
        if (!writer.ensureSpace(BODY_SIZE * 2f)) return
        val paint = textPaint(SMALL_SIZE, SECONDARY)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, CONTENT_WIDTH.toInt())
            .setIncludePad(false)
            .build()
        drawSliced(writer, layout, left = LEFT)
        writer.space(4f)
    }

    // -------------------------------------------------------------- page writer

    private inner class PageWriter(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
            private set
        var y: Float = TOP_MARGIN
            private set
        var truncated: Boolean = false
            private set
        private var pageIndex = 0

        val bottom: Float get() = PAGE_HEIGHT - BOTTOM_MARGIN

        fun start(): Boolean {
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
            return try {
                val newPage = document.startPage(info)
                newPage.canvas.drawColor(PAPER)
                page = newPage
                canvas = newPage.canvas
                pageIndex += 1
                y = TOP_MARGIN
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun finishPage() {
            page?.let { document.finishPage(it) }
            page = null
            canvas = null
        }

        /** Finishes the current page and starts the next one; false when the cap is reached. */
        fun newPage(): Boolean {
            finishPage()
            if (pageIndex >= maxPages) {
                truncated = true
                return false
            }
            return start()
        }

        /** Allows one extra page for the truncation notice after the content cap. */
        fun finishCurrentAndStartOverflowPage(): Boolean {
            finishPage()
            pageIndex += 1
            return start()
        }

        fun ensureSpace(space: Float): Boolean {
            if (canvas == null) {
                if (!start()) {
                    truncated = true
                    return false
                }
            }
            if (y + space <= bottom) return true
            return newPage()
        }

        fun space(space: Float) {
            if (canvas == null) return
            y += space.coerceAtLeast(0f)
        }

        fun advance(dy: Float) {
            y += dy.coerceAtLeast(0f)
        }

        fun drawNotice(text: String) {
            drawNoticeLine(this, text)
        }

        fun finishAll() {
            page?.let { finished ->
                drawFooter(finished.canvas)
                document.finishPage(finished)
            }
            page = null
            canvas = null
        }

        private fun drawFooter(canvas: Canvas) {
            val paint = textPaint(FOOTER_SIZE, SECONDARY)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$pageIndex", PAGE_WIDTH / 2f, PAGE_HEIGHT - 28f, paint)
        }
    }

    private fun fillPaint(color: Int): Paint = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun headingSize(level: Int): Float = when (level) {
        1 -> 22f
        2 -> 17f
        3 -> 14.5f
        else -> 12.5f
    }

    private fun headingSpaceBefore(level: Int): Float = when (level) {
        1 -> 0f
        2 -> 14f
        else -> 10f
    }

    private fun listIndent(depth: Int): Float = 14f + depth.coerceIn(0, 5) * 12f

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val LEFT = 48f
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * LEFT
        const val TOP_MARGIN = 54f
        const val BOTTOM_MARGIN = 58f
        const val MIN_TEXT_WIDTH = 60f
        const val MAX_PAGES = 200
        const val MAX_INPUT_CHARS = 1_000_000
        const val MAX_CODE_BLOCK_CHARS = 200_000
        const val MAX_TABLE_COLUMNS = 8
        const val MAX_TABLE_ROWS = 200
        const val MAX_TABLE_CELL_CHARS = 500
        const val INITIAL_OUTPUT_BYTES = 64 * 1024

        const val BODY_SIZE = 10.5f
        const val CODE_SIZE = 9f
        const val SMALL_SIZE = 9f
        const val TABLE_SIZE = 9f
        const val FOOTER_SIZE = 8f
        const val CODE_PADDING = 8f
        const val QUOTE_INDENT = 16f
        const val TABLE_CELL_PADDING = 5f
        const val HEADING_SPACE_AFTER = 8f
        const val PARAGRAPH_SPACE_AFTER = 6f
        const val LIST_ITEM_SPACE = 2.5f
        const val CODE_SPACE_BEFORE = 6f
        const val CODE_SPACE_AFTER = 8f
        const val QUOTE_SPACE_BEFORE = 6f
        const val QUOTE_SPACE_AFTER = 8f
        const val RULE_SPACE = 14f
        const val TABLE_SPACE_BEFORE = 6f
        const val TABLE_SPACE_AFTER = 8f

        // Brand tokens converted once; PDF paper is fixed white/light for print.
        val PAPER = NoteZeroPalette.SurfaceLight.toInt()
        val TEXT = NoteZeroPalette.TextPrimaryLight.toInt()
        val SECONDARY = NoteZeroPalette.TextSecondaryLight.toInt()
        val SEPARATOR = NoteZeroPalette.SeparatorLight.toInt()
        val CODE_BACKGROUND = NoteZeroPalette.SurfaceRaisedLight.toInt()
        val TABLE_HEADER = NoteZeroPalette.SurfaceRaisedLight.toInt()
        val LINK = NoteZeroPalette.AccentFillLight.toInt()
    }
}
