package com.cipherwell.notezero.ui.screens.setup

import com.cipherwell.notezero.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.cipherwell.notezero.ui.SetupStep
import com.cipherwell.notezero.ui.SetupUiState
import com.cipherwell.notezero.ui.components.common.NzBottomSpacer
import com.cipherwell.notezero.ui.components.common.NzFormSpacer
import com.cipherwell.notezero.ui.components.common.NzInfoBanner
import com.cipherwell.notezero.ui.components.common.NzLargeTitle
import com.cipherwell.notezero.ui.components.common.NzLoadingOverlay
import com.cipherwell.notezero.ui.components.common.NzPrimaryButton
import com.cipherwell.notezero.ui.components.common.NzBannerTone
import com.cipherwell.notezero.ui.components.common.RecoveryPhraseCopyControl
import com.cipherwell.notezero.ui.components.common.RecoveryPhraseGrid
import com.cipherwell.notezero.ui.components.common.SecureTextField
import com.cipherwell.notezero.ui.components.common.WordTextField
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing

/**
 * First-run vault creation.
 *
 * Three gated steps:
 *  1. PASSWORD — master password + confirmation, live rule checklist.
 *  2. PHRASE   — the 12 recovery words, shown exactly once, with guarded clipboard and
 *                an explicit "written down" acknowledgement.
 *  3. CONFIRM  — the user must type 3 requested words before the shell unlocks.
 *
 * The screen never stores the password in the ViewModel; it hands a CharArray over and
 * the ViewModel wipes it after `setupVault` returns.
 */
@Composable
fun SetupScreen(
    setup: SetupUiState,
    onCreateVault: (CharArray) -> Unit,
    onAcknowledgePhrase: () -> Unit,
    onConfirmPhrase: (List<String>) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (setup.step) {
            SetupStep.PASSWORD -> PasswordStep(setup = setup, onCreateVault = onCreateVault)
            SetupStep.PHRASE -> PhraseStep(setup = setup, onAcknowledgePhrase = onAcknowledgePhrase)
            SetupStep.CONFIRM -> ConfirmStep(setup = setup, onConfirmPhrase = onConfirmPhrase)
            SetupStep.DONE -> Unit // Shell has already routed to the unlocked notes list.
        }
        if (setup.isWorking) {
            NzLoadingOverlay(label = stringResource(R.string.setup_loading))
        }
    }
}

@Composable
private fun PasswordStep(
    setup: SetupUiState,
    onCreateVault: (CharArray) -> Unit,
) {
    // Credentials live in plain `remember` on purpose: `rememberSaveable` writes them to the
    // Activity's saved instance state Bundle, which leaves the process (system_server) and
    // outlives the composable. Same rule as the backup flow (audit finding F3).
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mismatch by rememberSaveable { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }

    LaunchedEffect(setup.step) {
        if (setup.step == SetupStep.PHRASE) {
            // Never keep the typed password after the vault exists.
            password = ""
            confirmation = ""
        }
    }
    LaunchedEffect(Unit) { passwordFocus.requestFocus() }

    val errorText: String? = when {
        mismatch -> stringResource(R.string.setup_error_mismatch)
        setup.passwordRule != null ->
            stringResource(R.string.setup_error_rule_missing, PasswordPolicy.label(setup.passwordRule))
        setup.failed -> stringResource(R.string.setup_error_failed)
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.gutter)
            .testTag("setup_screen"),
    ) {
        Spacer(Modifier.height(NoteZeroSpacing.xxxl))
        NzLargeTitle(
            title = stringResource(R.string.setup_title),
            subtitle = stringResource(R.string.setup_subtitle),
        )
        NzFormSpacer(NoteZeroSpacing.xxl)
        SecureTextField(
            value = password,
            onValueChange = {
                password = it
                mismatch = false
            },
            label = stringResource(R.string.common_master_password),
            modifier = Modifier.testTag("setup_password"),
            isError = errorText != null,
            imeAction = ImeAction.Next,
            focusRequester = passwordFocus,
        )
        NzFormSpacer(NoteZeroSpacing.m)
        SecureTextField(
            value = confirmation,
            onValueChange = {
                confirmation = it
                mismatch = false
            },
            label = stringResource(R.string.setup_confirm_password),
            modifier = Modifier.testTag("setup_confirm"),
            isError = mismatch,
            imeAction = ImeAction.Done,
            onImeAction = {
                if (password.isNotEmpty() && confirmation.isNotEmpty()) {
                    if (password != confirmation) mismatch = true else onCreateVault(password.toCharArray())
                }
            },
        )
        NzFormSpacer(NoteZeroSpacing.l)
        PasswordRulesChecklist(password = password)
        if (errorText != null) {
            NzFormSpacer(NoteZeroSpacing.l)
            NzInfoBanner(
                text = errorText,
                tone = NzBannerTone.DANGER,
                modifier = Modifier.testTag("setup_error"),
            )
        }
        NzFormSpacer(NoteZeroSpacing.xxl)
        NzPrimaryButton(
            text = stringResource(R.string.setup_create),
            onClick = {
                if (PasswordPolicy.matches(password, confirmation)) {
                    onCreateVault(password.toCharArray())
                } else {
                    mismatch = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_create_button"),
            enabled = password.isNotEmpty() && confirmation.isNotEmpty(),
            loading = setup.isWorking,
        )
        NzFormSpacer(NoteZeroSpacing.m)
        NzInfoBanner(
            text = stringResource(R.string.setup_recovery_warning),
            tone = NzBannerTone.INFO,
        )
        NzBottomSpacer()
    }
}

@Composable
private fun PhraseStep(
    setup: SetupUiState,
    onAcknowledgePhrase: () -> Unit,
) {
    val phrase = setup.phrase ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.gutter)
            .testTag("setup_screen"),
    ) {
        Spacer(Modifier.height(NoteZeroSpacing.xxxl))
        NzLargeTitle(
            title = stringResource(R.string.setup_phrase_title),
            subtitle = stringResource(R.string.setup_phrase_subtitle),
        )
        NzFormSpacer(NoteZeroSpacing.xxl)
        RecoveryPhraseGrid(
            words = phrase,
            modifier = Modifier.testTag("recovery_phrase_words"),
        )
        NzFormSpacer(NoteZeroSpacing.l)
        NzInfoBanner(
            text = stringResource(R.string.setup_phrase_warning),
            tone = NzBannerTone.WARNING,
        )
        NzFormSpacer(NoteZeroSpacing.m)
        RecoveryPhraseCopyControl(words = phrase, modifier = Modifier.testTag("setup_copy_phrase"))
        NzFormSpacer(NoteZeroSpacing.xxl)
        NzPrimaryButton(
            text = stringResource(R.string.setup_phrase_acknowledge),
            onClick = onAcknowledgePhrase,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_confirmed_button"),
        )
        NzBottomSpacer()
    }
}

@Composable
private fun ConfirmStep(
    setup: SetupUiState,
    onConfirmPhrase: (List<String>) -> Boolean,
) {
    val entries = setup.confirmIndices.size
    // Typed recovery words are credentials too: plain `remember`, never saved instance state.
    var words by remember { mutableStateOf(List(entries) { "" }) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.gutter)
            .testTag("setup_screen"),
    ) {
        Spacer(Modifier.height(NoteZeroSpacing.xxxl))
        NzLargeTitle(
            title = stringResource(R.string.setup_confirm_title),
            subtitle = stringResource(R.string.setup_confirm_subtitle),
        )
        NzFormSpacer(NoteZeroSpacing.xxl)
        setup.confirmIndices.forEachIndexed { position, wordIndex ->
            WordTextField(
                value = words[position],
                onValueChange = { value ->
                    words = words.toMutableList().also { it[position] = value }
                },
                label = stringResource(R.string.setup_word_label, wordIndex + 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_confirm_word_$position"),
                isError = setup.confirmMismatch,
                imeAction = if (position == entries - 1) ImeAction.Done else ImeAction.Next,
                focusRequester = if (position == 0) firstFocus else null,
            )
            NzFormSpacer(NoteZeroSpacing.m)
        }
        if (setup.confirmMismatch) {
            NzInfoBanner(
                text = stringResource(R.string.setup_confirm_mismatch),
                tone = NzBannerTone.DANGER,
                modifier = Modifier.testTag("setup_error"),
            )
            NzFormSpacer(NoteZeroSpacing.l)
        }
        NzFormSpacer(NoteZeroSpacing.s)
        NzPrimaryButton(
            text = stringResource(R.string.setup_finish),
            onClick = { onConfirmPhrase(words) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_finish_button"),
            enabled = words.all { it.isNotBlank() },
            loading = setup.isWorking,
        )
        NzFormSpacer(NoteZeroSpacing.m)
        NzInfoBanner(
            text = stringResource(R.string.setup_rotate_hint),
            tone = NzBannerTone.INFO,
        )
        NzBottomSpacer()
    }
}
