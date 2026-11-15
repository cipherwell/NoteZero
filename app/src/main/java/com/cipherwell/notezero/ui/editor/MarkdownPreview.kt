package com.cipherwell.notezero.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.model.Block
import com.cipherwell.notezero.model.BlockDocument
import com.cipherwell.notezero.model.CheckItem
import com.cipherwell.notezero.model.ListItem
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Renders a parsed [BlockDocument] as the read-only note preview. Checklists are
 * interactive: tapping one calls [onToggleCheckItem] with the zero-based index of that
 * item among all checklist items in the document, and the editor rewrites the Markdown.
 */
@Composable
fun MarkdownPreview(
    document: BlockDocument,
    onToggleCheckItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocksWithChecklistStart = remember(document) {
        var offset = 0
        document.blocks.map { block ->
            val start = offset
            if (block is Block.Checklist) offset += block.items.size
            block to start
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.m),
        verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
    ) {
        if (document.blocks.isEmpty()) {
            Text(
                text = "Nothing to preview yet. Switch to Edit and start writing Markdown.",
                style = NoteZeroType.Callout,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        blocksWithChecklistStart.forEach { (block, checklistStart) ->
            when (block) {
                is Block.Heading -> InlineMarkdown(
                    text = block.text,
                    style = headingStyle(block.level),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is Block.Paragraph -> InlineMarkdown(
                    text = block.text,
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is Block.Bullets -> BulletList(block.items)
                is Block.Numbered -> NumberedList(block.items)
                is Block.Checklist -> ChecklistBlock(
                    items = block.items,
                    startIndex = checklistStart,
                    onToggle = onToggleCheckItem,
                )

                is Block.Code -> CodeBlock(block)
                is Block.Quote -> QuoteBlock(block)
                is Block.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = NoteZeroSpacing.xs),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                is Block.Table -> TableBlock(block)
            }
        }
    }
}

private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> NoteZeroType.Title
    2 -> NoteZeroType.Heading
    else -> NoteZeroType.Body.copy(fontWeight = FontWeight.SemiBold)
}

@Composable
private fun BulletList(items: List<ListItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs)) {
        items.forEach { item ->
            Row(modifier = Modifier.padding(start = (item.depth * 16).dp)) {
                Text(
                    text = "•",
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(NoteZeroSpacing.s))
                InlineMarkdown(
                    text = item.text,
                    style = NoteZeroType.Body,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NumberedList(items: List<ListItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.padding(start = (item.depth * 16).dp)) {
                Text(
                    text = "${index + 1}.",
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(NoteZeroSpacing.s))
                InlineMarkdown(
                    text = item.text,
                    style = NoteZeroType.Body,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChecklistBlock(
    items: List<CheckItem>,
    startIndex: Int,
    onToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs)) {
        items.forEachIndexed { index, item ->
            val absoluteIndex = startIndex + index
            // Whole row is a >=48dp tap target so the checklist is easy to toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .clickable { onToggle(absoluteIndex) }
                    .testTag("checklist_item"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { onToggle(absoluteIndex) },
                )
                InlineMarkdown(
                    text = item.text,
                    style = NoteZeroType.Body,
                    modifier = Modifier.weight(1f),
                    color = if (item.checked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: Block.Code) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NoteZeroShapes.row,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(modifier = Modifier.padding(NoteZeroSpacing.m)) {
            val language = block.language
            if (!language.isNullOrBlank()) {
                Text(
                    text = language,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = block.code,
                style = NoteZeroType.Mono,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun QuoteBlock(block: Block.Quote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.width(NoteZeroSpacing.m))
        InlineMarkdown(
            text = block.text,
            style = NoteZeroType.Body.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TableBlock(block: Block.Table) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = NoteZeroSpacing.xs),
        ) {
            block.headers.forEach { header ->
                Text(
                    text = header,
                    style = NoteZeroType.Callout,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = NoteZeroSpacing.xs),
                )
            }
        }
        HorizontalDivider(color = borderColor)
        block.rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NoteZeroSpacing.xs),
            ) {
                row.forEachIndexed { index, cell ->
                    Text(
                        text = cell,
                        style = NoteZeroType.Callout,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = NoteZeroSpacing.xs),
                    )
                    if (index == row.lastIndex) Unit
                }
            }
            HorizontalDivider(color = borderColor)
        }
    }
}

/** Inline Markdown text: **bold**, *italic*, `code`, [links](url), ~~strikethrough~~. */
@Composable
fun InlineMarkdown(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val annotated = remember(text, linkColor, codeColor, codeBackground) {
        buildAnnotatedString {
            MarkdownInline.parse(text).forEach { token ->
                when (token) {
                    is InlineToken.Text -> append(token.text)
                    is InlineToken.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.text)
                    }

                    is InlineToken.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.text)
                    }

                    is InlineToken.BoldItalic -> withStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                    ) { append(token.text) }

                    is InlineToken.Code -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                            color = codeColor,
                        ),
                    ) { append(token.text) }

                    is InlineToken.Link -> withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ) { append(token.text) }

                    is InlineToken.Strikethrough -> withStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                    ) { append(token.text) }
                }
            }
        }
    }
    Text(text = annotated, style = style, color = color, modifier = modifier)
}
