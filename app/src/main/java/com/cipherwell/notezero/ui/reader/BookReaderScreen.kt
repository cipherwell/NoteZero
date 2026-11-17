package com.cipherwell.notezero.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.book.BookBlock
import com.cipherwell.notezero.book.BookChapter
import com.cipherwell.notezero.book.BookInline
import com.cipherwell.notezero.book.BookListItem
import com.cipherwell.notezero.book.BookTocEntry
import com.cipherwell.notezero.book.EpubDocument
import com.cipherwell.notezero.book.EpubParser
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.attachments.AttachmentImages
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.locks.LockVisibility
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hard UI caps: the engine already bounds a chapter, but the renderer bounds it again. */
private const val MAX_RENDER_BLOCKS_PER_CHAPTER = 2_000
private const val MAX_RENDER_TEXT_CHARS_PER_CHAPTER = 200_000
private const val MAX_SEARCH_RESULTS = 100
private const val SEARCH_SNIPPET_CONTEXT_CHARS = 80
private const val STATE_SAVE_DEBOUNCE_MS = 500L

/**
 * Full-screen EPUB reader.
 *
 * The encrypted EPUB is decrypted and parsed in memory on [Dispatchers.IO]; the resulting
 * [EpubDocument] holds the compressed archive only long enough to decode inline images on
 * demand and is closed as soon as this composable leaves (or the vault locks). No chapter
 * text, title, bookmark or position is ever written outside the vault: reading state goes
 * through [NoteRepository.saveReadingState] (encrypted item payload) and appearance lives
 * in the non-secret [ReaderPrefs].
 *
 * Chrome follows the standard reading layout: tapping the page toggles the bars, the left
 * and right edge zones turn chapters, and every navigation affordance (TOC, search
 * results, bookmarks) scrolls the same [LazyColumn] whose item index is the 0-based
 * chapter index stored in the encrypted reading state.
 */
@Composable
fun BookReaderScreen(
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val flowItems by repository.items.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val verifiedLockedIds by repository.verifiedLockedIds.collectAsStateWithLifecycle()
    val item = flowItems.firstOrNull { it.id == itemId }
    val attachment = item?.attachments
        ?.firstOrNull { it.mimeType == EpubParser.MIME }
        ?: item?.attachments?.firstOrNull()
    val masked = item != null && LockVisibility.isItemMasked(item, verifiedLockedIds)

    val prefsStore = remember(context) { ReaderPrefs(context) }
    var preferences by remember { mutableStateOf(prefsStore.load()) }
    val palette = rememberReaderPalette(preferences.theme)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var loading by remember(itemId) { mutableStateOf(true) }
    var failed by remember(itemId) { mutableStateOf(false) }
    var document by remember(itemId) { mutableStateOf<EpubDocument?>(null) }
    var chromeVisible by remember(itemId) { mutableStateOf(true) }
    var notice by remember(itemId) { mutableStateOf<String?>(null) }

    var showToc by remember(itemId) { mutableStateOf(false) }
    var showSearch by remember(itemId) { mutableStateOf(false) }
    var searchQuery by remember(itemId) { mutableStateOf("") }
    var searchResults by remember(itemId) { mutableStateOf<List<BookSearchHit>>(emptyList()) }
    var showBookmarks by remember(itemId) { mutableStateOf(false) }
    var showBookmarkLabelDialog by remember(itemId) { mutableStateOf(false) }
    var bookmarkLabelInput by remember(itemId) { mutableStateOf("") }
    var showAppearance by remember(itemId) { mutableStateOf(false) }

    val bookmarkAddedNotice = stringResource(R.string.book_bookmark_added)
    val bookmarkDeletedNotice = stringResource(R.string.book_bookmark_deleted)
    val chapterCount = document?.chapters?.size ?: 0
    val currentChapter = if (chapterCount > 0) {
        listState.firstVisibleItemIndex.coerceIn(0, chapterCount - 1)
    } else {
        0
    }
    val progressFraction = if (chapterCount > 0) {
        (currentChapter + 1).toFloat() / chapterCount
    } else {
        0f
    }
    val progressPercent = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val restoredPosition = item?.readingState?.position ?: 0
    val title = item?.title?.takeIf { it.isNotBlank() }
        ?: document?.title?.takeIf { it.isNotBlank() }
        ?: attachment?.displayName.orEmpty()
    val author = item?.readingState?.author?.takeIf { it.isNotBlank() }
        ?: document?.author?.takeIf { it.isNotBlank() }

    val previousChapterLabel = stringResource(R.string.book_previous_chapter)
    val nextChapterLabel = stringResource(R.string.book_next_chapter)

    // Drop the decrypted archive as soon as the composition (or the current document) dies.
    DisposableEffect(document) {
        val owned = document
        onDispose { owned?.close() }
    }

    // A vault lock must never leave a parsed/decrypted book reachable in memory here.
    LaunchedEffect(status) {
        if (status != VaultStatus.UNLOCKED) {
            document?.close()
            document = null
            loading = false
        }
    }

    // Load + parse on IO. The item flow can arrive a beat after the screen if the shell is
    // still restoring; keep showing the loading state until both the item and its
    // attachment are known instead of flashing the error state.
    LaunchedEffect(itemId, attachment?.id, status, masked) {
        if (status != VaultStatus.UNLOCKED) return@LaunchedEffect
        if (item == null) {
            loading = true
            return@LaunchedEffect
        }
        if (masked || attachment == null) {
            document?.close()
            document = null
            failed = true
            loading = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        notice = null
        val parsed = withContext(Dispatchers.IO) {
            val bytes = runCatching { repository.attachmentBytes(itemId, attachment.id) }.getOrNull()
            if (bytes == null) null else EpubParser.parse(bytes)
        }
        if (repository.status.value != VaultStatus.UNLOCKED) {
            parsed?.close()
            return@LaunchedEffect
        }
        if (parsed == null || parsed.chapters.isEmpty()) {
            parsed?.close()
            failed = true
            loading = false
        } else {
            document = parsed
            failed = false
            loading = false
        }
    }

    // Restore the last chapter once the document is ready, and persist the chapter total
    // and restored index (repository-owned, encrypted; failures while locked are ignored).
    LaunchedEffect(document) {
        val book = document ?: return@LaunchedEffect
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
        val target = (fresh?.position ?: restoredPosition).coerceIn(0, book.chapters.lastIndex)
        if (target > 0) {
            listState.scrollToItem(target)
        }
        runCatching {
            repository.saveReadingState(
                itemId,
                (fresh ?: ReadingState()).copy(position = target, fraction = 0f, total = book.chapters.size),
            )
        }
    }

    // Debounced chapter position save: scrolling restarts the effect, so only a settled
    // chapter is persisted (>= 500 ms), never a transient scroll frame.
    LaunchedEffect(currentChapter, document, loading, failed) {
        val book = document ?: return@LaunchedEffect
        if (loading || failed) return@LaunchedEffect
        delay(STATE_SAVE_DEBOUNCE_MS)
        val chapter = currentChapter.coerceIn(0, book.chapters.lastIndex)
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
        if (fresh?.position == chapter && fresh.total == book.chapters.size) return@LaunchedEffect
        runCatching {
            repository.saveReadingState(
                itemId,
                (fresh ?: ReadingState()).copy(position = chapter, fraction = 0f, total = book.chapters.size),
            )
        }
    }

    // In-book search runs on a background thread over the already-parsed blocks; it never
    // touches the archive and returns at most MAX_SEARCH_RESULTS snippets.
    LaunchedEffect(searchQuery, document) {
        val book = document
        val query = searchQuery.trim()
        if (book == null || query.isEmpty()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        searchResults = withContext(Dispatchers.Default) { searchBook(book, query) }
    }

    fun goToChapter(index: Int) {
        val count = document?.chapters?.size ?: return
        val target = index.coerceIn(0, count - 1)
        scope.launch { listState.scrollToItem(target) }
    }

    fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        val updated = transform(preferences).clamped()
        preferences = updated
        prefsStore.save(updated)
    }

    /** Fresh encrypted state (repository copy) with [transform] applied, saved silently. */
    fun persistReadingState(transform: (ReadingState) -> ReadingState) {
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
            ?: ReadingState(total = chapterCount)
        scope.launch { runCatching { repository.saveReadingState(itemId, transform(fresh)) } }
    }

    val defaultBookmarkLabel = stringResource(R.string.book_chapter_label, currentChapter + 1)

    BackHandler { onClose() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag("book_reader"),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            val book = document
            when {
                loading -> {
                    BookReaderChrome(
                        title = title,
                        author = null,
                        percent = null,
                        palette = palette,
                        onBack = onClose,
                        onToc = null,
                        onSearch = null,
                        onBookmarks = null,
                        onAppearance = null,
                    )
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.book_reader_loading),
                                style = NoteZeroType.Body,
                                color = palette.secondaryText,
                                modifier = Modifier.testTag("book_loading"),
                            )
                        }
                    }
                }

                failed || book == null -> {
                    BookReaderChrome(
                        title = title,
                        author = null,
                        percent = null,
                        palette = palette,
                        onBack = onClose,
                        onToc = null,
                        onSearch = null,
                        onBookmarks = null,
                        onAppearance = null,
                    )
                    Text(
                        text = stringResource(R.string.book_reader_error),
                        style = NoteZeroType.Body,
                        color = palette.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(NoteZeroSpacing.l)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("book_error"),
                    )
                }

                else -> {
                    if (chromeVisible) {
                        BookReaderChrome(
                            title = title,
                            author = author,
                            percent = progressPercent,
                            palette = palette,
                            onBack = onClose,
                            onToc = { showToc = true },
                            onSearch = { showSearch = true },
                            onBookmarks = { showBookmarks = true },
                            onAppearance = { showAppearance = true },
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        // The wrapper owns the centre tap zone (chrome toggle) while the
                        // LazyColumn keeps its own scroll semantics/tag for chapter jumps.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { chromeVisible = !chromeVisible }
                                }
                                .testTag("book_tap_center"),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("book_list"),
                                contentPadding = PaddingValues(
                                    start = preferences.marginDp.dp,
                                    end = preferences.marginDp.dp,
                                    top = NoteZeroSpacing.l,
                                    bottom = NoteZeroSpacing.xxxl,
                                ),
                                verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
                            ) {
                                itemsIndexed(
                                    items = book.chapters,
                                    key = { index: Int, _: BookChapter -> "book_chapter_$index" },
                                ) { index, chapter ->
                                    ChapterView(
                                        chapter = chapter,
                                        chapterIndex = index,
                                        preferences = preferences,
                                        palette = palette,
                                        document = book,
                                    )
                                }
                            }
                        }

                        // Tap zones keep 48dp targets even with the chrome hidden. They sit
                        // above the list but only cover the narrow screen edges, so the page
                        // itself stays scrollable and centre taps still toggle the chrome.
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(NoteZeroSpacing.minTarget)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { goToChapter(currentChapter - 1) }
                                .semantics { contentDescription = previousChapterLabel }
                                .testTag("book_tap_left"),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(NoteZeroSpacing.minTarget)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { goToChapter(currentChapter + 1) }
                                .semantics { contentDescription = nextChapterLabel }
                                .testTag("book_tap_right"),
                        )
                    }

                    notice?.let { text ->
                        Text(
                            text = text,
                            style = NoteZeroType.Caption,
                            color = palette.secondaryText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("book_notice"),
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .testTag("book_progress_bar"),
                        color = palette.accent,
                        trackColor = palette.divider,
                    )
                }
            }
        }

        if (showToc && document != null) {
            TocDialog(
                toc = document?.toc.orEmpty(),
                onSelect = { entry ->
                    showToc = false
                    goToChapter(entry.chapterIndex)
                },
                onDismiss = { showToc = false },
            )
        }

        if (showSearch && document != null) {
            SearchDialog(
                query = searchQuery,
                results = searchResults,
                onQueryChange = { searchQuery = it },
                onSelect = { hit ->
                    showSearch = false
                    goToChapter(hit.chapterIndex)
                },
                onDismiss = {
                    showSearch = false
                    searchQuery = ""
                    searchResults = emptyList()
                },
            )
        }

        if (showAppearance) {
            AppearanceDialog(
                preferences = preferences,
                onPreferencesChange = { transform -> updatePreferences(transform) },
                onReset = {
                    updatePreferences { ReaderPreferences() }
                },
                onDismiss = { showAppearance = false },
            )
        }

        if (showBookmarks && document != null) {
            BookmarksDialog(
                bookmarks = item?.readingState?.bookmarks.orEmpty(),
                onAdd = {
                    showBookmarks = false
                    bookmarkLabelInput = ""
                    showBookmarkLabelDialog = true
                },
                onSelect = { bookmark ->
                    showBookmarks = false
                    goToChapter(bookmark.position)
                },
                onDelete = { bookmark ->
                    persistReadingState { state ->
                        state.copy(bookmarks = state.bookmarks.filterNot { it == bookmark })
                    }
                    notice = bookmarkDeletedNotice
                },
                onDismiss = { showBookmarks = false },
            )
        }

        if (showBookmarkLabelDialog) {
            val bookmarks = item?.readingState?.bookmarks.orEmpty()
            AlertDialog(
                onDismissRequest = { showBookmarkLabelDialog = false },
                modifier = Modifier.testTag("book_bookmark_label_dialog"),
                title = { Text(stringResource(R.string.book_bookmark_add), style = NoteZeroType.Heading) },
                text = {
                    OutlinedTextField(
                        value = bookmarkLabelInput,
                        onValueChange = { bookmarkLabelInput = it },
                        singleLine = true,
                        label = {
                            Text(
                                text = stringResource(R.string.book_bookmark_label),
                                style = NoteZeroType.Callout,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("book_bookmark_label"),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val label = bookmarkLabelInput.trim()
                                .ifBlank { defaultBookmarkLabel }
                                .take(MAX_BOOKMARK_LABEL_CHARS)
                            if (bookmarks.size < ReadingState.MAX_BOOKMARKS) {
                                persistReadingState { state ->
                                    state.copy(
                                        bookmarks = state.bookmarks + ReadingBookmark(
                                            position = currentChapter,
                                            label = label,
                                            createdAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                                notice = bookmarkAddedNotice
                            }
                            showBookmarkLabelDialog = false
                        },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("book_bookmark_save"),
                    ) {
                        Text(stringResource(R.string.book_bookmark_save), style = NoteZeroType.Button)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showBookmarkLabelDialog = false },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("book_bookmark_cancel"),
                    ) {
                        Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = NoteZeroShapes.sheet,
            )
        }
    }
}

/** Reader header: back, title/author, progress percent and the reader actions row. */
@Composable
private fun BookReaderChrome(
    title: String,
    author: String?,
    percent: Int?,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    onBookmarks: (() -> Unit)?,
    onAppearance: (() -> Unit)?,
) {
    Surface(color = palette.surface) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = NoteZeroSpacing.s, end = NoteZeroSpacing.m, top = NoteZeroSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NzIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_reader_back),
                    onClick = onBack,
                    modifier = Modifier.testTag("book_back"),
                    tint = palette.text,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = NoteZeroType.Callout,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("book_title"),
                    )
                    if (!author.isNullOrBlank()) {
                        Text(
                            text = author,
                            style = NoteZeroType.Caption,
                            color = palette.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (percent != null) {
                    Text(
                        text = stringResource(R.string.book_progress_percent, percent),
                        style = NoteZeroType.Caption,
                        color = palette.secondaryText,
                        modifier = Modifier.testTag("book_progress_percent"),
                    )
                }
            }
            if (onToc != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.s),
                ) {
                    NzIconButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.book_toc),
                        onClick = onToc,
                        modifier = Modifier.testTag("book_toc"),
                        tint = palette.secondaryText,
                    )
                    NzIconButton(
                        icon = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.book_search),
                        onClick = onSearch ?: {},
                        modifier = Modifier.testTag("book_search"),
                        tint = palette.secondaryText,
                    )
                    NzIconButton(
                        icon = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.book_bookmarks),
                        onClick = onBookmarks ?: {},
                        modifier = Modifier.testTag("book_bookmarks"),
                        tint = palette.secondaryText,
                    )
                    NzIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.book_appearance),
                        onClick = onAppearance ?: {},
                        modifier = Modifier.testTag("book_appearance"),
                        tint = palette.secondaryText,
                    )
                }
            }
        }
    }
}

/** One chapter: header + bounded block list. The item index is the chapter index. */
@Composable
private fun ChapterView(
    chapter: BookChapter,
    chapterIndex: Int,
    preferences: ReaderPreferences,
    palette: ReaderPalette,
    document: EpubDocument,
) {
    val blocks = remember(chapter) { boundedBlocks(chapter.blocks) }
    // Most EPUB chapters start with an <h1> that already carries the chapter title. Show the
    // synthetic chapter header only when the content does not start with that same heading, so
    // the reader never prints "Beginning" twice.
    val chapterTitle = chapter.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.book_chapter_label, chapterIndex + 1)
    val firstHeadingText = (blocks.firstOrNull() as? BookBlock.Heading)
        ?.runs
        ?.joinToString(separator = "") { it.text }
        ?.let(::normaliseHeading)
    val headingAlreadyShown = firstHeadingText != null &&
        firstHeadingText == normaliseHeading(chapterTitle)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("book_chapter"),
    ) {
        if (!headingAlreadyShown) {
            Text(
                text = chapterTitle,
                style = headingTextStyle(level = 1, preferences = preferences),
                color = palette.text,
                modifier = Modifier.padding(top = NoteZeroSpacing.m, bottom = NoteZeroSpacing.s),
            )
        } else {
            Spacer(Modifier.height(NoteZeroSpacing.m))
        }
        blocks.forEach { block ->
            BlockView(
                block = block,
                preferences = preferences,
                palette = palette,
                document = document,
            )
        }
    }
}

/** Renders one [BookBlock] with reader typography and palette colours. */
@Composable
private fun BlockView(
    block: BookBlock,
    preferences: ReaderPreferences,
    palette: ReaderPalette,
    document: EpubDocument,
) {
    val bodyStyle = bodyTextStyle(preferences)
    when (block) {
        is BookBlock.Heading -> Text(
            text = inlineText(block.runs, palette),
            style = headingTextStyle(level = block.level, preferences = preferences),
            color = palette.text,
            modifier = Modifier.padding(top = NoteZeroSpacing.m, bottom = NoteZeroSpacing.xs),
        )

        is BookBlock.Paragraph -> Text(
            text = inlineText(block.runs, palette),
            style = bodyStyle,
            color = palette.text,
            modifier = Modifier.padding(vertical = NoteZeroSpacing.xxs),
        )

        is BookBlock.ListBlock -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NoteZeroSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
        ) {
            block.items.forEachIndexed { itemIndex, item ->
                val depth = item.depth.coerceIn(0, MAX_LIST_DEPTH)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = NoteZeroSpacing.l * depth),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = if (block.ordered) "${itemIndex + 1}." else "•",
                        style = bodyStyle,
                        color = palette.secondaryText,
                        modifier = Modifier.widthIn(min = NoteZeroSpacing.l),
                    )
                    Text(
                        text = inlineText(item.runs, palette),
                        style = bodyStyle,
                        color = palette.text,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = NoteZeroSpacing.xxs),
                    )
                }
            }
        }

        is BookBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = NoteZeroSpacing.s),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(palette.accent),
            )
            Text(
                text = inlineText(block.runs, palette),
                style = bodyStyle.copy(fontStyle = FontStyle.Italic),
                color = palette.secondaryText,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = NoteZeroSpacing.m, top = NoteZeroSpacing.xxs),
            )
        }

        is BookBlock.Code -> Surface(
            // Reader themes are fixed grounds: always use the palette's code surface so dark
            // text stays readable on sepia/paper and white text stays readable at night.
            color = palette.codeBackground,
            shape = NoteZeroShapes.field,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NoteZeroSpacing.xs),
        ) {
            Text(
                text = block.text,
                style = monoTextStyle(preferences),
                color = palette.text,
                modifier = Modifier.padding(NoteZeroSpacing.m),
            )
        }

        BookBlock.Rule -> HorizontalDivider(
            color = palette.divider,
            modifier = Modifier.padding(vertical = NoteZeroSpacing.m),
        )

        is BookBlock.Image -> BookImage(
            entry = block.entry,
            alt = block.alt,
            document = document,
            palette = palette,
        )
    }
}

/** Inline runs styled with the reader palette (bold/italic/code/link). */
@Composable
private fun inlineText(runs: List<BookInline>, palette: ReaderPalette): AnnotatedString {
    val accent = palette.accent
    return remember(runs, accent) {
        buildAnnotatedString {
            runs.forEach { run ->
                val span = SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    color = if (run.link) accent else Color.Unspecified,
                    textDecoration = if (run.link) TextDecoration.Underline else null,
                )
                withStyle(span) { append(run.text) }
            }
        }
    }
}

/** Decodes one referenced image in memory (no cache) and renders it or its error label. */
@Composable
private fun BookImage(
    entry: String,
    alt: String,
    document: EpubDocument,
    palette: ReaderPalette,
) {
    val loadState by produceState<ImageLoadState>(
        ImageLoadState.Loading,
        entry,
        document,
    ) {
        value = withContext(Dispatchers.Default) {
            val bytes = document.imageBytes(entry)
            val bitmap = if (bytes == null) {
                null
            } else {
                AttachmentImages.decode(bytes, AttachmentImages.VIEWER_TARGET_PX)
            }
            if (bitmap == null) ImageLoadState.Failed else ImageLoadState.Ready(bitmap)
        }
    }
    when (val state = loadState) {
        ImageLoadState.Loading -> Unit
        ImageLoadState.Failed -> Text(
            text = stringResource(R.string.book_image_error),
            style = NoteZeroType.Caption,
            color = palette.secondaryText,
            modifier = Modifier
                .padding(vertical = NoteZeroSpacing.xs)
                .testTag("book_image_error"),
        )

        is ImageLoadState.Ready -> {
            DisposableEffect(state.bitmap) {
                onDispose { state.bitmap.recycle() }
            }
            val image: ImageBitmap = remember(state.bitmap) { state.bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = alt,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NoteZeroSpacing.s),
            )
        }
    }
}

/** TOC dialog; selecting an entry scrolls the chapter list to that chapter index. */
@Composable
private fun TocDialog(
    toc: List<BookTocEntry>,
    onSelect: (BookTocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("book_toc_dialog"),
        title = { Text(stringResource(R.string.book_toc), style = NoteZeroType.Heading) },
        text = {
            if (toc.isEmpty()) {
                Text(stringResource(R.string.book_toc_empty), style = NoteZeroType.Body)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(
                        items = toc,
                        key = { index: Int, _: BookTocEntry -> "toc_$index" },
                    ) { _, entry ->
                        Text(
                            text = entry.title,
                            style = NoteZeroType.Callout,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(entry) }
                                .padding(
                                    start = NoteZeroSpacing.l * entry.depth.coerceIn(0, MAX_TOC_DEPTH),
                                    top = NoteZeroSpacing.m,
                                    bottom = NoteZeroSpacing.m,
                                    end = NoteZeroSpacing.s,
                                )
                                .testTag("book_toc_entry"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("book_toc_close"),
            ) {
                Text(stringResource(R.string.common_close), style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/** In-book search dialog: query field, match count, snippets; selecting scrolls. */
@Composable
private fun SearchDialog(
    query: String,
    results: List<BookSearchHit>,
    onQueryChange: (String) -> Unit,
    onSelect: (BookSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("book_search_dialog"),
        title = { Text(stringResource(R.string.book_search), style = NoteZeroType.Heading) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = {
                        Text(
                            text = stringResource(R.string.book_search_hint),
                            style = NoteZeroType.Callout,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("book_search_query"),
                )
                if (query.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.book_search_results, results.size),
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = NoteZeroSpacing.s),
                    )
                }
                if (query.isNotBlank() && results.isEmpty()) {
                    Text(
                        text = stringResource(R.string.book_search_empty),
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = NoteZeroSpacing.m)
                            .testTag("book_search_empty"),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    itemsIndexed(
                        items = results,
                        key = { index: Int, hit: BookSearchHit -> "search_${hit.chapterIndex}_$index" },
                    ) { _, hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(hit) }
                                .padding(vertical = NoteZeroSpacing.s)
                                .testTag("book_search_result"),
                        ) {
                            Text(
                                text = hit.chapterTitle
                                    ?: stringResource(R.string.book_chapter_label, hit.chapterIndex + 1),
                                style = NoteZeroType.Callout,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = hit.snippet,
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("book_search_close"),
            ) {
                Text(stringResource(R.string.common_close), style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/** Bookmarks dialog: rows jump/delete, the add action opens the label dialog. */
@Composable
private fun BookmarksDialog(
    bookmarks: List<ReadingBookmark>,
    onAdd: () -> Unit,
    onSelect: (ReadingBookmark) -> Unit,
    onDelete: (ReadingBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("book_bookmarks_dialog"),
        title = { Text(stringResource(R.string.book_bookmarks), style = NoteZeroType.Heading) },
        text = {
            if (bookmarks.isEmpty()) {
                Text(stringResource(R.string.book_bookmarks_empty), style = NoteZeroType.Body)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(
                        items = bookmarks,
                        key = { index: Int, bookmark: ReadingBookmark -> "bookmark_${bookmark.createdAt}_$index" },
                    ) { _, bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(bookmark) }
                                .padding(vertical = NoteZeroSpacing.xs)
                                .testTag("book_bookmark_row"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bookmark.label,
                                    style = NoteZeroType.Callout,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.book_chapter_label,
                                        bookmark.position + 1,
                                    ),
                                    style = NoteZeroType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            NzIconButton(
                                icon = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                onClick = { onDelete(bookmark) },
                                modifier = Modifier.testTag("book_bookmark_delete"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAdd,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("book_bookmark_add"),
            ) {
                Text(stringResource(R.string.book_bookmark_add), style = NoteZeroType.Button)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("book_bookmarks_close"),
            ) {
                Text(stringResource(R.string.common_close), style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/** Appearance dialog: text size, line spacing, margins and the three reading themes. */
@Composable
private fun AppearanceDialog(
    preferences: ReaderPreferences,
    onPreferencesChange: ((ReaderPreferences) -> ReaderPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val marginsLabel = stringResource(R.string.book_margins)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("book_appearance_dialog"),
        title = { Text(stringResource(R.string.book_appearance), style = NoteZeroType.Heading) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
            ) {
                // Theme first: it is the most-used control and stays visible even when the
                // dialog body needs to scroll on a short screen.
                Text(
                    text = stringResource(R.string.book_theme),
                    style = NoteZeroType.Callout,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
                ) {
                    ReaderThemeChip(
                        label = stringResource(R.string.book_theme_paper),
                        selected = preferences.theme == ReaderTheme.PAPER,
                        onClick = { onPreferencesChange { it.copy(theme = ReaderTheme.PAPER) } },
                        tag = "book_theme_paper",
                    )
                    ReaderThemeChip(
                        label = stringResource(R.string.book_theme_sepia),
                        selected = preferences.theme == ReaderTheme.SEPIA,
                        onClick = { onPreferencesChange { it.copy(theme = ReaderTheme.SEPIA) } },
                        tag = "book_theme_sepia",
                    )
                    ReaderThemeChip(
                        label = stringResource(R.string.book_theme_night),
                        selected = preferences.theme == ReaderTheme.NIGHT,
                        onClick = { onPreferencesChange { it.copy(theme = ReaderTheme.NIGHT) } },
                        tag = "book_theme_night",
                    )
                }

                AppearanceStepperRow(
                    label = stringResource(R.string.book_text_size),
                    decreaseTag = "book_font_decrease",
                    increaseTag = "book_font_increase",
                    decreaseLabel = stringResource(R.string.book_font_decrease),
                    increaseLabel = stringResource(R.string.book_font_increase),
                    onDecrease = { onPreferencesChange { it.withSmallerFont() } },
                    onIncrease = { onPreferencesChange { it.withLargerFont() } },
                )
                AppearanceStepperRow(
                    label = stringResource(R.string.book_line_spacing),
                    decreaseTag = "book_line_spacing_decrease",
                    increaseTag = "book_line_spacing_increase",
                    onDecrease = { onPreferencesChange { it.withSmallerLineSpacing() } },
                    onIncrease = { onPreferencesChange { it.withLargerLineSpacing() } },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
                ) {
                    Text(
                        text = marginsLabel,
                        style = NoteZeroType.Callout,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        value = preferences.marginDp.toFloat(),
                        onValueChange = { value ->
                            onPreferencesChange { prefs ->
                                prefs.withMargin(
                                    (value / ReaderPreferences.MARGIN_STEP_DP).toInt() *
                                        ReaderPreferences.MARGIN_STEP_DP,
                                )
                            }
                        },
                        valueRange = ReaderPreferences.MIN_MARGIN_DP.toFloat()..
                            ReaderPreferences.MAX_MARGIN_DP.toFloat(),
                        steps = ((ReaderPreferences.MAX_MARGIN_DP - ReaderPreferences.MIN_MARGIN_DP) /
                            ReaderPreferences.MARGIN_STEP_DP) - 1,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("book_margin_slider")
                            .semantics { contentDescription = marginsLabel },
                    )
                }

                TextButton(
                    onClick = onReset,
                    modifier = Modifier.testTag("book_settings_reset"),
                ) {
                    Text(stringResource(R.string.book_settings_reset), style = NoteZeroType.Button)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("book_appearance_close"),
            ) {
                Text(stringResource(R.string.common_close), style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/** Label plus a 48dp decrease/increase pair for one numeric appearance setting. */
@Composable
private fun AppearanceStepperRow(
    label: String,
    decreaseTag: String,
    increaseTag: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseLabel: String = label,
    increaseLabel: String = label,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = NoteZeroType.Callout,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        NzIconButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = decreaseLabel,
            onClick = onDecrease,
            modifier = Modifier.testTag(decreaseTag),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        NzIconButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = increaseLabel,
            onClick = onIncrease,
            modifier = Modifier.testTag(increaseTag),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One reading theme choice; selection is exposed through the selectable semantics. */
@Composable
private fun ReaderThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = NoteZeroType.Caption) },
        modifier = Modifier
            .heightIn(min = NoteZeroSpacing.minTarget)
            .testTag(tag),
    )
}

/** Result of decoding one book image; keeps the loading/failed states distinct. */
private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data object Failed : ImageLoadState
    class Ready(val bitmap: Bitmap) : ImageLoadState
}

// ---- Typography helpers ---------------------------------------------------

@Composable
private fun bodyTextStyle(preferences: ReaderPreferences): TextStyle {
    val base = NoteZeroType.Body
    return base.copy(
        fontSize = base.fontSize * preferences.fontScale,
        lineHeight = base.lineHeight * preferences.fontScale * preferences.lineHeightMultiplier,
    )
}

@Composable
private fun headingTextStyle(level: Int, preferences: ReaderPreferences): TextStyle {
    val base = if (level <= 1) NoteZeroType.Title else NoteZeroType.Heading
    val factor = when (level) {
        1 -> 1.0f
        2 -> 0.92f
        3 -> 0.84f
        4 -> 0.78f
        5 -> 0.72f
        else -> 0.68f
    }
    return base.copy(
        fontWeight = FontWeight.Bold,
        fontSize = base.fontSize * factor * preferences.fontScale,
        lineHeight = base.lineHeight * factor * preferences.fontScale * preferences.lineHeightMultiplier,
    )
}

@Composable
private fun monoTextStyle(preferences: ReaderPreferences): TextStyle {
    val base = NoteZeroType.Mono
    return base.copy(
        fontSize = base.fontSize * preferences.fontScale,
        lineHeight = base.lineHeight * preferences.fontScale * preferences.lineHeightMultiplier,
    )
}

// ---- Pure helpers (unit-testable; no Android dependencies beyond Compose types) ----

/** Case/whitespace-insensitive comparison for synthetic chapter headers. */
private fun normaliseHeading(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase()

/** One in-book search result: chapter index + the matched chapter title + a snippet. */
internal data class BookSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String?,
    val snippet: String,
)

/** Case-insensitive in-memory search bounded to [MAX_SEARCH_RESULTS] hits. */
internal fun searchBook(
    document: EpubDocument,
    query: String,
    limit: Int = MAX_SEARCH_RESULTS,
): List<BookSearchHit> {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || limit <= 0) return emptyList()
    val hits = ArrayList<BookSearchHit>()
    for (chapter in document.chapters) {
        if (hits.size >= limit) break
        val text = chapter.plainText()
        var from = 0
        while (hits.size < limit) {
            val index = text.indexOf(trimmed, from, ignoreCase = true)
            if (index < 0) break
            hits.add(
                BookSearchHit(
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                    snippet = snippetAround(text, index, trimmed.length),
                ),
            )
            from = index + trimmed.length.coerceAtLeast(1)
        }
    }
    return hits
}

/** A short single-line context window around a match. */
internal fun snippetAround(text: String, index: Int, length: Int): String {
    val start = (index - SEARCH_SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
    val end = (index + length + SEARCH_SNIPPET_CONTEXT_CHARS).coerceAtMost(text.length)
    val collapsed = text.substring(start, end).replace(WHITESPACE, " ").trim()
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + collapsed + suffix
}

/** Caps one chapter before rendering: block count and total text characters. */
internal fun boundedBlocks(blocks: List<BookBlock>): List<BookBlock> {
    if (blocks.size <= MAX_RENDER_BLOCKS_PER_CHAPTER &&
        blocks.sumOf { blockTextLength(it) } <= MAX_RENDER_TEXT_CHARS_PER_CHAPTER
    ) {
        return blocks
    }
    val bounded = ArrayList<BookBlock>(minOf(blocks.size, MAX_RENDER_BLOCKS_PER_CHAPTER))
    var budget = MAX_RENDER_TEXT_CHARS_PER_CHAPTER
    for (block in blocks) {
        if (bounded.size >= MAX_RENDER_BLOCKS_PER_CHAPTER || budget <= 0) break
        val length = blockTextLength(block)
        if (length <= budget) {
            bounded.add(block)
            budget -= length
        } else {
            bounded.add(truncateBlock(block, budget))
            budget = 0
        }
    }
    return bounded
}

private fun blockTextLength(block: BookBlock): Int = when (block) {
    is BookBlock.Heading -> block.runs.textLength()
    is BookBlock.Paragraph -> block.runs.textLength()
    is BookBlock.Quote -> block.runs.textLength()
    is BookBlock.ListBlock -> block.items.sumOf { it.runs.textLength() }
    is BookBlock.Code -> block.text.length
    is BookBlock.Image -> block.alt.length
    BookBlock.Rule -> 0
}

private fun truncateBlock(block: BookBlock, maxChars: Int): BookBlock = when (block) {
    is BookBlock.Heading -> block.copy(runs = block.runs.truncated(maxChars))
    is BookBlock.Paragraph -> block.copy(runs = block.runs.truncated(maxChars))
    is BookBlock.Quote -> block.copy(runs = block.runs.truncated(maxChars))
    is BookBlock.ListBlock -> {
        var remaining = maxChars
        val items = ArrayList<BookListItem>()
        for (item in block.items) {
            if (remaining <= 0) break
            val length = item.runs.textLength()
            if (length <= remaining) {
                items.add(item)
                remaining -= length
            } else {
                items.add(item.copy(runs = item.runs.truncated(remaining)))
                remaining = 0
            }
        }
        block.copy(items = items)
    }

    is BookBlock.Code -> block.copy(text = block.text.take(maxChars.coerceAtLeast(0)))
    is BookBlock.Image -> block
    BookBlock.Rule -> block
}

private fun List<BookInline>.textLength(): Int = sumOf { it.text.length }

private fun List<BookInline>.truncated(maxChars: Int): List<BookInline> {
    if (maxChars <= 0) return emptyList()
    val out = ArrayList<BookInline>(size)
    var remaining = maxChars
    for (run in this) {
        if (remaining <= 0) break
        if (run.text.length <= remaining) {
            out.add(run)
            remaining -= run.text.length
        } else {
            out.add(run.copy(text = run.text.take(remaining)))
            remaining = 0
        }
    }
    return out
}

private const val MAX_LIST_DEPTH = 8
private const val MAX_TOC_DEPTH = 16
private const val MAX_BOOKMARK_LABEL_CHARS = 200
private val WHITESPACE = Regex("\\s+")
