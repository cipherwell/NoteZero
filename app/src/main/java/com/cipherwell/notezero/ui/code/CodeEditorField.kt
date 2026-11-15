package com.cipherwell.notezero.ui.code

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import com.cipherwell.notezero.code.CodeLanguage
import com.cipherwell.notezero.code.CodeLexers
import com.cipherwell.notezero.code.LineIndex
import com.cipherwell.notezero.code.SyntaxToken
import com.cipherwell.notezero.code.TextRange as CodeTextRange
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * The editable source area: monospace [BasicTextField] with a line-number gutter synced to
 * the shared vertical scroll, syntax colours from the pure-Kotlin lexers, a current-line
 * highlight and bracket-pair highlight.
 *
 * The whole document is laid out inside our own [verticalScroll] (the text field itself is
 * measured with unbounded height, so it never scrolls internally) — that single scroll
 * state is what keeps the gutter in sync with the code, including soft-wrapped files.
 *
 * Security: no content is logged, copied or persisted here; rendering only.
 */
@Composable
internal fun CodeEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    state: CodeEditorViewModel.State,
    palette: CodeSyntaxPalette,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val fontSizeSp = state.fontSizeSp.sp
    val textStyle = remember(fontSizeSp) {
        NoteZeroType.Mono.copy(
            fontSize = fontSizeSp,
            lineHeight = (fontSizeSp.value * 1.5f).sp,
        )
    }
    val tokens = remember(state.body, state.language, state.syntaxHighlighting) {
        if (!state.syntaxHighlighting || state.language == CodeLanguage.PLAIN) {
            emptyList()
        } else {
            runCatching { CodeLexers.forLanguage(state.language).tokenize(state.body) }
                .getOrDefault(emptyList())
        }
    }
    val annotated = remember(
        state.body,
        tokens,
        state.matches,
        state.currentMatch,
        state.bracketHighlight,
        palette,
    ) {
        buildAnnotatedSource(state.body, tokens, state.matches, state.currentMatch, state.bracketHighlight, palette)
    }
    val visualTransformation = remember(annotated) {
        VisualTransformation { TransformedText(annotated, OffsetMapping.Identity) }
    }
    val lineIndex = remember(state.body) { LineIndex.of(state.body) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    val charWidthPx = remember(textMeasurer, textStyle) {
        val measured = textMeasurer.measure(AnnotatedString("0000"), textStyle)
        measured.size.width / 4f
    }
    val gutterPaddingPx = with(density) { 8.dp.toPx() }
    val lineCount = lineIndex.lineCount
    val gutterWidth = remember(charWidthPx, lineCount, gutterPaddingPx) {
        val digits = lineCount.toString().length.coerceAtLeast(2)
        with(density) { (digits * charWidthPx + gutterPaddingPx * 2).toDp() }
    }
    val gutterTextWidth = gutterWidth // same width for the canvas and the spacer
    val longestLinePx = remember(state.body, charWidthPx) {
        (longestLineLength(state.body) * charWidthPx + 32f * density.density)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val contentWidthPx = maxOf(longestLinePx, viewportWidthPx)
        val contentWidth = with(density) { contentWidthPx.toDp() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll),
        ) {
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = viewportHeight)) {
                Canvas(
                    modifier = Modifier
                        .width(gutterTextWidth)
                        .height(
                            with(density) {
                                (layoutResult?.size?.height?.toFloat() ?: 0f).toDp()
                            },
                        )
                        .testTag(CodeTags.LINE_NUMBERS),
                ) {
                    val layout = layoutResult ?: return@Canvas
                    drawLineNumbers(
                        layout = layout,
                        lineIndex = lineIndex,
                        cursor = state.cursor,
                        style = textStyle,
                        palette = palette,
                        textMeasurer = textMeasurer,
                        paddingPx = gutterPaddingPx,
                        scrollOffsetPx = verticalScroll.value.toFloat(),
                        viewportHeightPx = viewportHeight.toPx(),
                    )
                }
                Spacer(Modifier.width(4.dp))

                val fieldModifier = Modifier
                    .heightIn(min = viewportHeight)
                    .weight(1f)

                if (state.wordWrap) {
                    SourceTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = textStyle,
                        palette = palette,
                        visualTransformation = visualTransformation,
                        layoutResult = layoutResult,
                        onLayout = { layoutResult = it },
                        state = state,
                        focusRequester = focusRequester,
                        modifier = fieldModifier.fillMaxWidth(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll),
                    ) {
                        SourceTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = textStyle,
                            palette = palette,
                            visualTransformation = visualTransformation,
                            layoutResult = layoutResult,
                            onLayout = { layoutResult = it },
                            state = state,
                            focusRequester = focusRequester,
                            modifier = Modifier.width(contentWidth).heightIn(min = viewportHeight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    palette: CodeSyntaxPalette,
    visualTransformation: VisualTransformation,
    layoutResult: TextLayoutResult?,
    onLayout: (TextLayoutResult) -> Unit,
    state: CodeEditorViewModel.State,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .testTag(CodeTags.EDITOR),
        textStyle = textStyle.copy(color = palette.text),
        cursorBrush = SolidColor(palette.keyword),
        visualTransformation = visualTransformation,
        onTextLayout = onLayout,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Default,
        ),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawEditorHighlights(
                            layout = layoutResult,
                            cursor = state.cursor,
                            bracketHighlight = state.bracketHighlight,
                            palette = palette,
                        )
                    },
            ) {
                innerTextField()
            }
        },
    )
}

/**
 * Composes the syntax-highlighted source as an [AnnotatedString]: base text, then one
 * span per lexer token, then find-match backgrounds and the bracket-pair background.
 */
internal fun buildAnnotatedSource(
    text: String,
    tokens: List<SyntaxToken>,
    matches: List<CodeTextRange>,
    currentMatch: Int,
    bracketHighlight: CodeTextRange?,
    palette: CodeSyntaxPalette,
): AnnotatedString = buildAnnotatedString {
    append(text)
    tokens.forEach { token ->
        val start = token.start.coerceIn(0, text.length)
        val end = token.end.coerceIn(start, text.length)
        if (end > start) addStyle(palette.styleFor(token.type), start, end)
    }
    matches.forEachIndexed { index, range ->
        val start = range.start.coerceIn(0, text.length)
        val end = range.end.coerceIn(start, text.length)
        if (end > start) {
            addStyle(
                SpanStyle(
                    background = if (index == currentMatch) {
                        palette.findCurrentBackground
                    } else {
                        palette.findMatchBackground
                    },
                ),
                start,
                end,
            )
        }
    }
    bracketHighlight?.let { range ->
        val start = range.start.coerceIn(0, text.length)
        val end = range.end.coerceIn(start, text.length)
        if (end > start) {
            addStyle(SpanStyle(background = palette.bracketMatchBackground), start, start + 1)
            if (end > start + 1) {
                addStyle(SpanStyle(background = palette.bracketMatchBackground), end - 1, end)
            }
        }
    }
}

/** Draws logical line numbers at the exact y of their first visual line (wrap-aware). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineNumbers(
    layout: TextLayoutResult,
    lineIndex: LineIndex,
    cursor: Int,
    style: TextStyle,
    palette: CodeSyntaxPalette,
    textMeasurer: TextMeasurer,
    paddingPx: Float,
    scrollOffsetPx: Float,
    viewportHeightPx: Float,
) {
    val textLength = layout.layoutInput.text.length
    val currentLine = safeLineOf(lineIndex, cursor)
    val headerStyle = style.copy(color = palette.gutterText)
    val currentStyle = style.copy(color = palette.gutterCurrentText)
    for (line in 0 until lineIndex.lineCount) {
        val offset = lineIndex.startOfLine(line)
        val visualLine = safeVisualLine(layout, offset, textLength)
        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        val lineHeight = bottom - top
        if (top + lineHeight < scrollOffsetPx - lineHeight || top > scrollOffsetPx + viewportHeightPx + lineHeight) {
            continue
        }
        val label = (line + 1).toString()
        val labelWidth = textMeasurer.measure(AnnotatedString(label), headerStyle).size.width.toFloat()
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(size.width - paddingPx - labelWidth, top),
            style = if (line == currentLine) currentStyle else headerStyle,
        )
    }
}

/** Current-line background + bracket-pair backgrounds, drawn behind the text. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditorHighlights(
    layout: TextLayoutResult?,
    cursor: Int,
    bracketHighlight: CodeTextRange?,
    palette: CodeSyntaxPalette,
) {
    if (layout == null) return
    val textLength = layout.layoutInput.text.length
    val visualLine = safeVisualLine(layout, cursor, textLength)
    val top = layout.getLineTop(visualLine)
    val bottom = layout.getLineBottom(visualLine)
    if (bottom > top) {
        drawRect(
            color = palette.currentLineBackground,
            topLeft = Offset(0f, top),
            size = Size(size.width, bottom - top),
        )
    }
    bracketHighlight?.let { range ->
        drawCharBackground(layout, range.start, palette.bracketMatchBackground)
        drawCharBackground(layout, range.end - 1, palette.bracketMatchBackground)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCharBackground(
    layout: TextLayoutResult,
    offset: Int,
    color: Color,
) {
    val textLength = layout.layoutInput.text.length
    if (offset < 0 || offset >= textLength) return
    val box = runCatching { layout.getBoundingBox(offset) }.getOrNull() ?: return
    drawRect(
        color = color,
        topLeft = box.topLeft,
        size = Size(box.width.coerceAtLeast(2f), box.height),
    )
}

private fun safeLineOf(lineIndex: LineIndex, offset: Int): Int {
    if (lineIndex.lineCount <= 0) return 0
    return lineIndex.lineOf(offset.coerceAtLeast(0)).coerceIn(0, lineIndex.lineCount - 1)
}

private fun safeVisualLine(layout: TextLayoutResult, offset: Int, textLength: Int): Int {
    if (layout.lineCount <= 0) return 0
    val safeOffset = if (textLength == 0) 0 else offset.coerceIn(0, textLength - 1)
    return runCatching { layout.getLineForOffset(safeOffset) }.getOrDefault(0)
        .coerceIn(0, layout.lineCount - 1)
}

/** Longest physical line in characters; used to size the no-wrap horizontal content. */
private fun longestLineLength(text: String): Int {
    var max = 0
    var current = 0
    for (index in text.indices) {
        if (text[index] == '\n') {
            if (current > max) max = current
            current = 0
        } else {
            current++
        }
    }
    return maxOf(max, current)
}
