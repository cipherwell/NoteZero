package com.cipherwell.notezero.ui.screens.recovery

import com.cipherwell.notezero.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.cipherwell.notezero.ui.RecoveryFailure
import com.cipherwell.notezero.ui.RecoveryStep
import com.cipherwell.notezero.ui.RecoveryUiState
import com.cipherwell.notezero.ui.components.common.NzBackButton
import com.cipherwell.notezero.ui.components.common.NzBannerTone
import com.cipherwell.notezero.ui.components.common.NzBottomSpacer
import com.cipherwell.notezero.ui.components.common.NzFormSpacer
import com.cipherwell.notezero.ui.components.common.NzInfoBanner
import com.cipherwell.notezero.ui.components.common.NzLargeTitle
import com.cipherwell.notezero.ui.components.common.NzLoadingOverlay
import com.cipherwell.notezero.ui.components.common.NzPrimaryButton
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.components.common.SecureTextField
import com.cipherwell.notezero.ui.components.common.WordTextField
import com.cipherwell.notezero.ui.screens.setup.PasswordPolicy
import com.cipherwell.notezero.ui.screens.setup.PasswordRulesChecklist
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing

/**
 * Phrase-only recovery for a locked vault.
 *
 * Step 1 validates the 12-word checksum through the repository, step 2 re-wraps the vault
 * key under a new master password. The recovery phrase is **not** rotated: the same phrase
 * is re-wrapped under a fresh salt, so the words the user already wrote down keep working
 * (decision #12, audit finding F2). Rotating to a new phrase is the explicit Settings action.
 * The phrase and the new password live only in this composable / a wiped CharArray.
 */
@Composable
fun RecoveryScreen(
    recovery: RecoveryUiState,
    onSubmitPhrase: (List<String>) -> Unit,
    onSubmitNewPassword: (words: List<String>, newPassword: CharArray) -> Unit,
    onBack: () -> Unit,
    onBackToPhrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    val words = remember { mutableStateListOf(*Array(12) { "" }) }

    Box(modifier = modifier.fillMaxSize()) {
        when (recovery.step) {
            RecoveryStep.PHRASE -> PhraseStep(
                recovery = recovery,
                words = words,
                onSubmitPhrase = onSubmitPhrase,
                onBack = onBack,
            )
            RecoveryStep.NEW_PASSWORD -> NewPasswordStep(
                recovery = recovery,
                words = words,
                onSubmitNewPassword = onSubmitNewPassword,
                onBackToPhrase = onBackToPhrase,
            )
        }
        if (recovery.isWorking) {
            NzLoadingOverlay(label = stringResource(R.string.recovery_loading))
        }
    }
}

@Composable
private fun PhraseStep(
    recovery: RecoveryUiState,
    words: MutableList<String>,
    onSubmitPhrase: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val errorText: String? = when {
        recovery.invalidPhrase -> stringResource(R.string.recovery_error_invalid)
        recovery.failure == RecoveryFailure.WRONG_PHRASE ->
            stringResource(R.string.recovery_error_wrong_phrase)
        recovery.failure == RecoveryFailure.CORRUPT_VAULT ->
            stringResource(R.string.recovery_error_corrupt)
        recovery.failure == RecoveryFailure.FAILED ->
            stringResource(R.string.recovery_error_failed)
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.gutter)
            .testTag("recovery_screen"),
    ) {
        Spacer(Modifier.height(NoteZeroSpacing.m))
        NzBackButton(onClick = onBack)
        Spacer(Modifier.height(NoteZeroSpacing.m))
        NzLargeTitle(
            title = stringResource(R.string.recovery_heading),
            subtitle = stringResource(R.string.recovery_subtitle),
        )
        NzFormSpacer(NoteZeroSpacing.xxl)
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(NoteZeroSpacing.s)) {
                pair.forEachIndexed { columnIndex, _ ->
                    val position = rowIndex * 2 + columnIndex
                    WordTextField(
                        value = words[position],
                        onValueChange = { words[position] = it },
                        label = stringResource(R.string.recovery_word_label, position + 1),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recovery_word_$position"),
                        isError = errorText != null,
                        imeAction = if (position == 11) ImeAction.Done else ImeAction.Next,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            NzFormSpacer(NoteZeroSpacing.s)
        }
        if (errorText != null) {
            NzFormSpacer(NoteZeroSpacing.s)
            NzInfoBanner(
                text = errorText,
                tone = NzBannerTone.DANGER,
                modifier = Modifier.testTag("recovery_error"),
            )
        }
        NzFormSpacer(NoteZeroSpacing.xl)
        NzPrimaryButton(
            text = stringResource(R.string.recovery_continue),
            onClick = { onSubmitPhrase(words.toList()) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_continue_button"),
            enabled = words.all { it.isNotBlank() },
            loading = recovery.isWorking,
        )
        NzFormSpacer(NoteZeroSpacing.m)
        NzTextButton(
            text = stringResource(R.string.recovery_back_to_unlock),
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_back_button"),
        )
        NzBottomSpacer()
    }
}

@Composable
private fun NewPasswordStep(
    recovery: RecoveryUiState,
    words: MutableList<String>,
    onSubmitNewPassword: (words: List<String>, newPassword: CharArray) -> Unit,
    onBackToPhrase: () -> Unit,
) {
    // New master password: plain `remember`, never saved instance state (audit F3).
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mismatch by rememberSaveable { mutableStateOf(false) }
    val errorText: String? = when {
        mismatch -> stringResource(R.string.recovery_error_mismatch)
        recovery.passwordRule != null ->
            stringResource(R.string.recovery_error_rule_missing, PasswordPolicy.label(recovery.passwordRule))
        recovery.failure == RecoveryFailure.CORRUPT_VAULT ->
            stringResource(R.string.recovery_error_corrupt_password)
        recovery.failure == RecoveryFailure.FAILED ->
            stringResource(R.string.recovery_error_failed)
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.gutter)
            .testTag("recovery_screen"),
    ) {
        Spacer(Modifier.height(NoteZeroSpacing.m))
        NzBackButton(onClick = onBackToPhrase)
        Spacer(Modifier.height(NoteZeroSpacing.m))
        NzLargeTitle(
            title = stringResource(R.string.recovery_new_password_title),
            subtitle = stringResource(R.string.recovery_new_password_subtitle),
        )
        NzFormSpacer(NoteZeroSpacing.xxl)
        SecureTextField(
            value = password,
            onValueChange = {
                password = it
                mismatch = false
            },
            label = stringResource(R.string.recovery_new_password_label),
            modifier = Modifier.testTag("recovery_new_password"),
            isError = errorText != null,
            imeAction = ImeAction.Next,
        )
        NzFormSpacer(NoteZeroSpacing.m)
        SecureTextField(
            value = confirmation,
            onValueChange = {
                confirmation = it
                mismatch = false
            },
            label = stringResource(R.string.recovery_confirm_password_label),
            modifier = Modifier.testTag("recovery_confirm_password"),
            isError = mismatch,
        )
        NzFormSpacer(NoteZeroSpacing.l)
        PasswordRulesChecklist(password = password)
        if (errorText != null) {
            NzFormSpacer(NoteZeroSpacing.l)
            NzInfoBanner(
                text = errorText,
                tone = NzBannerTone.DANGER,
                modifier = Modifier.testTag("recovery_error"),
            )
        }
        NzFormSpacer(NoteZeroSpacing.xl)
        NzPrimaryButton(
            text = stringResource(R.string.recovery_reset),
            onClick = {
                if (PasswordPolicy.matches(password, confirmation)) {
                    onSubmitNewPassword(words.toList(), password.toCharArray())
                } else {
                    mismatch = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_reset_button"),
            enabled = password.isNotEmpty() && confirmation.isNotEmpty(),
            loading = recovery.isWorking,
        )
        NzFormSpacer(NoteZeroSpacing.m)
        NzTextButton(
            text = stringResource(R.string.recovery_back_to_phrase),
            onClick = onBackToPhrase,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_back_to_phrase_button"),
        )
        NzBottomSpacer()
    }
}
