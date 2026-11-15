package com.cipherwell.notezero.ui.screens.unlock

import com.cipherwell.notezero.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.cipherwell.notezero.ui.UnlockFailure
import com.cipherwell.notezero.ui.UnlockUiState
import com.cipherwell.notezero.ui.components.common.NzBannerTone
import com.cipherwell.notezero.ui.components.common.NzBottomSpacer
import com.cipherwell.notezero.ui.components.common.NzFormSpacer
import com.cipherwell.notezero.ui.components.common.NzInfoBanner
import com.cipherwell.notezero.ui.components.common.NzLargeTitle
import com.cipherwell.notezero.ui.components.common.NzLoadingOverlay
import com.cipherwell.notezero.ui.components.common.NzPrimaryButton
import com.cipherwell.notezero.ui.components.common.NzSecondaryButton
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.components.common.SecureTextField
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Locked-vault screen: password first, optional biometric CTA (only when the device can
 * and the user enabled it) and a recovery-phrase escape hatch.
 *
 * Error copy is deliberately non-secret: it never echoes the password, the phrase or a
 * crypto exception message. A local retry cooldown is shown after repeated failures.
 */
@Composable
fun UnlockScreen(
    unlock: UnlockUiState,
    showBiometric: Boolean,
    onUnlock: (CharArray) -> Unit,
    onBiometricUnlock: () -> Unit,
    onUseRecoveryPhrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plain `remember`: a master password must never be written to saved instance state
    // (that Bundle leaves the process). Audit finding F3.
    var password by remember { mutableStateOf("") }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { passwordFocus.requestFocus() }
    LaunchedEffect(unlock.isWorking) {
        if (!unlock.isWorking) passwordFocus.requestFocus()
    }

    val errorText: String? = when {
        unlock.lockoutActive ->
            stringResource(R.string.unlock_error_lockout, unlock.lockoutSecondsLeft)
        unlock.failure == UnlockFailure.WRONG_PASSWORD ->
            stringResource(R.string.unlock_error_wrong_password)
        unlock.failure == UnlockFailure.CORRUPT_VAULT ->
            stringResource(R.string.unlock_error_corrupt)
        unlock.failure == UnlockFailure.BIOMETRIC_FAILED ->
            stringResource(R.string.unlock_error_biometric_failed)
        unlock.failure == UnlockFailure.BIOMETRIC_UNAVAILABLE ->
            stringResource(R.string.unlock_error_biometric_unavailable)
        unlock.failure == UnlockFailure.BIOMETRIC_LOCKOUT ->
            stringResource(R.string.unlock_error_biometric_lockout)
        else -> null
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NoteZeroSpacing.gutter)
                    .padding(vertical = NoteZeroSpacing.xxxl)
                    .testTag("unlock_screen"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = NoteZeroType.LargeTitle,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                NzFormSpacer(NoteZeroSpacing.s)
                Text(
                    text = stringResource(R.string.unlock_locked_title),
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                NzFormSpacer(NoteZeroSpacing.xxl)
                SecureTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.common_master_password),
                    modifier = Modifier.testTag("unlock_password"),
                    enabled = !unlock.isWorking && !unlock.lockoutActive,
                    isError = errorText != null,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    onImeAction = {
                        if (password.isNotEmpty()) {
                            onUnlock(password.toCharArray())
                            password = ""
                        }
                    },
                    focusRequester = passwordFocus,
                )
                if (errorText != null) {
                    NzFormSpacer(NoteZeroSpacing.m)
                    NzInfoBanner(
                        text = errorText,
                        tone = NzBannerTone.DANGER,
                        modifier = Modifier.testTag("unlock_error"),
                    )
                }
                NzFormSpacer(NoteZeroSpacing.xl)
                NzPrimaryButton(
                    text = stringResource(R.string.unlock_button),
                    onClick = {
                        onUnlock(password.toCharArray())
                        password = ""
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unlock_button"),
                    enabled = password.isNotEmpty() && !unlock.lockoutActive,
                    loading = unlock.isWorking,
                )
                if (showBiometric) {
                    NzFormSpacer(NoteZeroSpacing.m)
                    NzSecondaryButton(
                        text = stringResource(R.string.unlock_biometric),
                        onClick = onBiometricUnlock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("unlock_biometric"),
                        enabled = !unlock.isWorking && !unlock.lockoutActive,
                    )
                }
                NzFormSpacer(NoteZeroSpacing.l)
                NzTextButton(
                    text = stringResource(R.string.unlock_use_recovery),
                    onClick = onUseRecoveryPhrase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unlock_use_recovery"),
                    enabled = !unlock.isWorking,
                )
                Spacer(Modifier.height(NoteZeroSpacing.m))
                Text(
                    text = stringResource(R.string.unlock_tagline),
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                NzBottomSpacer()
            }
        }
        if (unlock.isWorking) {
            NzLoadingOverlay(label = stringResource(R.string.unlock_loading))
        }
    }
}
