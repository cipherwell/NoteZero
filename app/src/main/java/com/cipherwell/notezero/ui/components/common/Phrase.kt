package com.cipherwell.notezero.ui.components.common

import com.cipherwell.notezero.R
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.delay

/**
 * Clipboard handling for the recovery phrase.
 *
 * Security requirements (docs/DECISIONS.md / SPEC.md):
 *  - The clip is marked with [ClipDescription.EXTRA_IS_SENSITIVE] so Android 13+ does
 *    not show the content in the copy overlay / clipboard previews.
 *  - A visible in-app warning is shown before copying; the app offers an explicit
 *    "clear clipboard" action and clears it automatically after
 *    [CLEAR_AFTER_MILLIS] again on leaving the screen.
 *  - Nothing is logged; the phrase never touches disk.
 */
object SensitiveClipboard {

    /** Backport value of [ClipDescription.EXTRA_IS_SENSITIVE] for API < 33. */
    const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

    /** How long the copied phrase may sit in the clipboard before we wipe it. */
    const val CLEAR_AFTER_MILLIS: Long = 60_000L

    fun buildSensitiveClip(label: String, text: String): ClipData {
        val clip = ClipData.newPlainText(label, text)
        val extras = PersistableBundle()
        extras.putBoolean(sensitiveExtraKey(), true)
        clip.description.extras = extras
        return clip
    }

    fun copy(context: Context, label: String, text: String) {
        val clipboard = manager(context) ?: return
        runCatching { clipboard.setPrimaryClip(buildSensitiveClip(label, text)) }
    }

    fun clear(context: Context) {
        val clipboard = manager(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun sensitiveExtraKey(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            SENSITIVE_EXTRA
        }

    private fun manager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}

/**
 * The 12-word recovery phrase, numbered in a two-column grid so it can be read aloud
 * and compared while writing it down. Container semantics are provided by the caller
 * (test tag `recovery_phrase_words`).
 */
@Composable
fun RecoveryPhraseGrid(
    words: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
    ) {
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s)) {
                pair.forEachIndexed { columnIndex, word ->
                    val number = (rowIndex * 2 + columnIndex + 1).toString().padStart(2, '0')
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = NoteZeroShapes.row,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = NoteZeroSpacing.m,
                                vertical = NoteZeroSpacing.s,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = number,
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(NoteZeroSpacing.xxl),
                            )
                            Text(text = word, style = NoteZeroType.RecoveryWord, maxLines = 1)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Copy control for a recovery phrase: warning dialog first, then a sensitive clipboard
 * write with auto-clear and a manual "Clear clipboard now" action.
 */
@Composable
fun RecoveryPhraseCopyControl(
    words: List<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardLabel = stringResource(R.string.common_recovery_clipboard_label)
    var showWarning by remember { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val latestCopied = rememberUpdatedState(copied)

    DisposableEffect(Unit) {
        onDispose { if (latestCopied.value) SensitiveClipboard.clear(context) }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(SensitiveClipboard.CLEAR_AFTER_MILLIS)
            SensitiveClipboard.clear(context)
            copied = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!copied) {
            NzSecondaryButton(
                text = stringResource(R.string.common_copy_phrase),
                onClick = { showWarning = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            NzInfoBanner(
                text = stringResource(R.string.common_copied_clipboard_notice),
                tone = NzBannerTone.WARNING,
            )
            Spacer(Modifier.padding(top = NoteZeroSpacing.xs))
            NzTextButton(
                text = stringResource(R.string.common_clear_clipboard_now),
                onClick = {
                    SensitiveClipboard.clear(context)
                    copied = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showWarning) {
        NoteZeroConfirmDialog(
            title = stringResource(R.string.common_copy_phrase_title),
            message = stringResource(R.string.common_copy_phrase_message),
            confirmLabel = stringResource(R.string.common_copy_anyway),
            onConfirm = {
                showWarning = false
                SensitiveClipboard.copy(context, clipboardLabel, words.joinToString(" "))
                copied = true
            },
            onDismiss = { showWarning = false },
        )
    }
}
