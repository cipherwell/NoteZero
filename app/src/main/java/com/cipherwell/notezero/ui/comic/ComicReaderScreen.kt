package com.cipherwell.notezero.ui.comic

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.book.ComicBook
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.attachments.AttachmentImages
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Re-bounds the page list in the UI even though the engine already caps a comic at 500 pages. */
private const val MAX_RENDERED_COMIC_PAGES = 500

/**
 * Decode bound for one page image. A phone page is ~1,100 px wide at 3x; 2,048 px (the
 * generic viewer bound) would keep ~22 MiB of ARGB per visible page in a paged list, so the
 * comic reader samples to a narrower box instead.
 */
private const val COMIC_PAGE_TARGET_PX = 1_600

private const val STATE_SAVE_DEBOUNCE_MS = 500L
private const val MAX_PAGE_INPUT_DIGITS = 6

/**
 * Full-screen CBZ comic reader.
 *
 * The [ComicBook] is produced by [com.cipherwell.notezero.book.BookLoader] before this screen
 * composes, so no archive/XML work happens here: pages are inflated from the bounded in-memory
 * archive one at a time ([ComicBook.pageBytes]) and decoded lazily by [ComicPage] as the paged
 * [LazyColumn] brings them on screen. Every decoded [Bitmap] is recycled by the composition
 * that owns it (and [ComicBook.close] runs on dispose), so neither page bytes nor pixels are
 * cached anywhere.
 *
 * Position semantics match the text reader: `readingState.position` is the 0-based page index
 * and `total` is [ComicBook.pageCount]. Bookmarks are page-based [ReadingBookmark]s stored in
 * the same encrypted reading state (never logged). There is deliberately no text search or
 * TOC for a comic.
 */
@Composable
fun ComicReaderScreen(
    comic: ComicBook,
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by repository.items.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == itemId }
    val scope = rememberCoroutineScope()

    val pageCount = comic.pageCount.coerceIn(0, MAX_RENDERED_COMIC_PAGES)
    val restoredPosition = (item?.readingState?.position ?: 0)
        .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoredPosition)
    val currentPage = if (pageCount > 0) {
        listState.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
    } else {
        0
    }

    var showPageJump by remember(itemId) { mutableStateOf(false) }
    var pageInput by remember(itemId) { mutableStateOf("") }
    var pageError by remember(itemId) { mutableStateOf<String?>(null) }
    var showBookmarks by remember(itemId) { mutableStateOf(false) }
    var notice by remember(itemId) { mutableStateOf<String?>(null) }
    var restored by remember(itemId) { mutableStateOf(false) }

    val title = comic.title.takeIf { it.isNotBlank() } ?: stringResource(R.string.comic_reader)
    val bookmarkAddedNotice = stringResource(R.string.book_bookmark_added)
    val bookmarkDeletedNotice = stringResource(R.string.book_bookmark_deleted)
    val defaultBookmarkLabel = stringResource(
        R.string.comic_bookmark_default,
        (currentPage + 1).coerceAtMost(pageCount.coerceAtLeast(1)),
    )
    val pageInvalidNotice = stringResource(R.string.comic_page_invalid, pageCount.coerceAtLeast(1))

    /** Fresh repository copy of the encrypted state with [transform] applied, saved silently. */
    fun persistReadingState(transform: (ReadingState) -> ReadingState) {
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
            ?: ReadingState(total = pageCount)
        scope.launch { runCatching { repository.saveReadingState(itemId, transform(fresh)) } }
    }

    fun addBookmark() {
        if (pageCount <= 0) return
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
        if ((fresh?.bookmarks?.size ?: 0) >= ReadingState.MAX_BOOKMARKS) return
        persistReadingState { state ->
            state.copy(
                position = currentPage,
                fraction = 0f,
                total = pageCount,
                bookmarks = state.bookmarks + ReadingBookmark(
                    position = currentPage,
                    label = defaultBookmarkLabel,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        notice = bookmarkAddedNotice
    }

    // The comic is owned by this screen: drop the bounded archive when the reader leaves,
    // exactly like the text reader closes its BookContent.
    DisposableEffect(comic) {
        onDispose { comic.close() }
    }

    // A vault lock hides the pages (the shell also removes this route); drop any open dialog
    // so no page number or bookmark label lingers on screen.
    LaunchedEffect(status) {
        if (status != VaultStatus.UNLOCKED) {
            showPageJump = false
            showBookmarks = false
        }
    }

    // Restore the saved page once the encrypted item flow is available (the shelf normally
    // already has it, in which case rememberLazyListState was seeded above and this is a no-op).
    LaunchedEffect(item?.readingState, pageCount) {
        if (restored || pageCount <= 0) return@LaunchedEffect
        val state = item?.readingState ?: return@LaunchedEffect
        val target = state.position.coerceIn(0, pageCount - 1)
        if (listState.firstVisibleItemIndex != target) listState.scrollToItem(target)
        restored = true
    }

    // Always record the page total so the shelf can show progress even before a page settles.
    LaunchedEffect(itemId, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
        if (fresh?.total == pageCount) return@LaunchedEffect
        runCatching {
            repository.saveReadingState(
                itemId,
                (fresh ?: ReadingState()).copy(
                    position = (fresh?.position ?: 0).coerceIn(0, pageCount - 1),
                    fraction = 0f,
                    total = pageCount,
                ),
            )
        }
    }

    // Debounced page save: only a settled page is persisted, never a transient scroll frame.
    LaunchedEffect(currentPage, pageCount, status) {
        if (status != VaultStatus.UNLOCKED || pageCount <= 0) return@LaunchedEffect
        delay(STATE_SAVE_DEBOUNCE_MS)
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
        if (fresh?.position == currentPage && fresh.total == pageCount) return@LaunchedEffect
        runCatching {
            repository.saveReadingState(
                itemId,
                (fresh ?: ReadingState()).copy(position = currentPage, fraction = 0f, total = pageCount),
            )
        }
    }

    BackHandler { onClose() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("comic_reader"),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = NoteZeroSpacing.s, end = NoteZeroSpacing.xs, top = NoteZeroSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NzIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_reader_back),
                    onClick = onClose,
                    modifier = Modifier.testTag("comic_back"),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = NoteZeroSpacing.s)) {
                    Text(
                        text = title,
                        style = NoteZeroType.Callout,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("comic_title"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.book_format_cbz),
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (pageCount > 0) {
                            Spacer(Modifier.width(NoteZeroSpacing.s))
                            Text(
                                text = stringResource(
                                    R.string.comic_page_progress,
                                    (currentPage + 1).coerceAtMost(pageCount),
                                    pageCount,
                                ),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (pageCount > 0) {
                    NzIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.comic_bookmark_add),
                        onClick = { addBookmark() },
                        modifier = Modifier.testTag("comic_bookmark_add"),
                    )
                    NzIconButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.comic_bookmarks),
                        onClick = { showBookmarks = true },
                        modifier = Modifier.testTag("comic_bookmarks"),
                    )
                }
            }

            if (pageCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = NoteZeroSpacing.l, end = NoteZeroSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = {
                            ((currentPage + 1).toFloat() / pageCount).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .testTag("comic_progress_bar"),
                    )
                    TextButton(
                        onClick = {
                            pageError = null
                            pageInput = (currentPage + 1).toString()
                            showPageJump = true
                        },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("comic_page_progress"),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.comic_page_progress,
                                (currentPage + 1).coerceAtMost(pageCount),
                                pageCount,
                            ),
                            style = NoteZeroType.Caption,
                        )
                    }
                }
            }

            notice?.let { text ->
                Text(
                    text = text,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("comic_notice"),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (pageCount <= 0) {
                    Text(
                        text = stringResource(R.string.comic_error),
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(NoteZeroSpacing.l)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("comic_error"),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("comic_pages"),
                        contentPadding = PaddingValues(
                            start = NoteZeroSpacing.l,
                            end = NoteZeroSpacing.l,
                            top = NoteZeroSpacing.s,
                            bottom = NoteZeroSpacing.xxxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.m),
                    ) {
                        itemsIndexed(
                            items = List(pageCount) { it },
                            key = { index: Int, _: Int -> "comic_page_$index" },
                        ) { index, _ ->
                            ComicPage(
                                comic = comic,
                                index = index,
                                total = pageCount,
                            )
                        }
                    }
                }
            }
        }

        if (showPageJump) {
            val total = pageCount.coerceAtLeast(1)
            AlertDialog(
                onDismissRequest = { showPageJump = false },
                modifier = Modifier.testTag("comic_page_jump_dialog"),
                title = { Text(stringResource(R.string.comic_page_jump), style = NoteZeroType.Heading) },
                text = {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = {
                            pageInput = it.filter(Char::isDigit).take(MAX_PAGE_INPUT_DIGITS)
                            pageError = null
                        },
                        singleLine = true,
                        label = {
                            Text(
                                text = stringResource(R.string.comic_page_field),
                                style = NoteZeroType.Callout,
                            )
                        },
                        isError = pageError != null,
                        supportingText = {
                            if (pageError != null) {
                                Text(
                                    text = pageError.orEmpty(),
                                    style = NoteZeroType.Caption,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("comic_page_error"),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("comic_page_field"),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val requested = pageInput.toIntOrNull()
                            if (requested == null || requested !in 1..total) {
                                pageError = pageInvalidNotice
                            } else {
                                showPageJump = false
                                scope.launch { listState.scrollToItem(requested - 1) }
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("comic_page_jump_confirm"),
                    ) {
                        Text(stringResource(R.string.common_go), style = NoteZeroType.Button)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showPageJump = false },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("comic_page_jump_cancel"),
                    ) {
                        Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = NoteZeroShapes.sheet,
            )
        }

        if (showBookmarks) {
            val bookmarks = item?.readingState?.bookmarks.orEmpty()
            AlertDialog(
                onDismissRequest = { showBookmarks = false },
                modifier = Modifier.testTag("comic_bookmarks_dialog"),
                title = { Text(stringResource(R.string.comic_bookmarks), style = NoteZeroType.Heading) },
                text = {
                    if (bookmarks.isEmpty()) {
                        Text(
                            text = stringResource(R.string.comic_bookmarks_empty),
                            style = NoteZeroType.Body,
                            modifier = Modifier.testTag("comic_bookmarks_empty"),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            itemsIndexed(
                                items = bookmarks,
                                key = { index: Int, bookmark: ReadingBookmark ->
                                    "comic_bookmark_${bookmark.createdAt}_$index"
                                },
                            ) { _, bookmark ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showBookmarks = false
                                            scope.launch {
                                                listState.scrollToItem(
                                                    bookmark.position.coerceIn(0, pageCount - 1),
                                                )
                                            }
                                        }
                                        .padding(vertical = NoteZeroSpacing.xs)
                                        .testTag("comic_bookmark_row"),
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
                                                R.string.comic_page_progress,
                                                (bookmark.position + 1).coerceAtMost(pageCount.coerceAtLeast(1)),
                                                pageCount.coerceAtLeast(1),
                                            ),
                                            style = NoteZeroType.Caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    NzIconButton(
                                        icon = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        onClick = {
                                            val fresh = repository.items.value
                                                .firstOrNull { it.id == itemId }
                                                ?.readingState
                                            if (fresh != null) {
                                                scope.launch {
                                                    runCatching {
                                                        repository.saveReadingState(
                                                            itemId,
                                                            fresh.copy(
                                                                bookmarks = fresh.bookmarks
                                                                    .filterNot { it == bookmark },
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                            notice = bookmarkDeletedNotice
                                        },
                                        modifier = Modifier.testTag("comic_bookmark_delete"),
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
                        onClick = { showBookmarks = false },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("comic_bookmarks_close"),
                    ) {
                        Text(stringResource(R.string.common_close), style = NoteZeroType.Button)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = NoteZeroShapes.sheet,
            )
        }
    }
}

/** One lazily decoded comic page: spinner while decoding, the image, or a non-secret error. */
@Composable
private fun ComicPage(
    comic: ComicBook,
    index: Int,
    total: Int,
) {
    val pageState by produceState<ComicPageState>(ComicPageState.Loading, comic, index) {
        value = withContext(Dispatchers.Default) {
            val bytes = comic.pageBytes(index)
            val bitmap = bytes?.let { AttachmentImages.decode(it, COMIC_PAGE_TARGET_PX) }
            if (bitmap == null) ComicPageState.Failed else ComicPageState.Ready(bitmap)
        }
    }
    when (val state = pageState) {
        ComicPageState.Loading -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(NoteZeroSpacing.s))
            Text(
                text = stringResource(R.string.comic_loading),
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ComicPageState.Failed -> Text(
            text = stringResource(R.string.comic_image_failed),
            style = NoteZeroType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NoteZeroSpacing.l)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("comic_page_image_error"),
        )

        is ComicPageState.Ready -> {
            val bitmap = state.bitmap
            // Free this page's pixels as soon as the list drops it (or the reader leaves).
            DisposableEffect(bitmap) {
                onDispose { bitmap.recycle() }
            }
            val image: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            Column(modifier = Modifier.fillMaxWidth().testTag("comic_page_frame")) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(
                        R.string.comic_page_progress,
                        index + 1,
                        total,
                    ),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        .testTag("comic_page"),
                )
                Spacer(Modifier.height(NoteZeroSpacing.xxs))
                Text(
                    text = stringResource(R.string.comic_page_progress, index + 1, total),
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** Decode state of one comic page; the ready bitmap is owned by the composition. */
private sealed interface ComicPageState {
    data object Loading : ComicPageState
    data object Failed : ComicPageState
    class Ready(val bitmap: Bitmap) : ComicPageState
}
