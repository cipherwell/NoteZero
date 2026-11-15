package com.cipherwell.notezero.ui.components.common

import com.cipherwell.notezero.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** Shared outlined text field with AA-compliant borders, labels and cursor. */
@Composable
fun NzTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle = NoteZeroType.Body,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = LocalNoteZeroColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.controlHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        enabled = enabled,
        isError = isError,
        label = { Text(text = label, style = NoteZeroType.FieldLabel) },
        supportingText = supportingText?.let {
            { Text(text = it, style = NoteZeroType.Caption) }
        },
        singleLine = singleLine,
        textStyle = textStyle,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onNext = { onImeAction() },
            onGo = { onImeAction() },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            errorTextColor = MaterialTheme.colorScheme.error,
            focusedBorderColor = colors.fieldBorderFocused,
            unfocusedBorderColor = colors.fieldBorder,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
            errorBorderColor = MaterialTheme.colorScheme.error,
            cursorColor = colors.accentText,
            focusedLabelColor = colors.accentText,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            errorLabelColor = MaterialTheme.colorScheme.error,
            focusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * Password entry with a Show/Hide affordance (text label, not an icon, so it is
 * self-describing). The field is marked with the `password` accessibility semantics so
 * assistive tech announces it correctly, and is never logged by the app.
 */
@Composable
fun SecureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    NzTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier.semantics { password() },
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        imeAction = imeAction,
        onImeAction = onImeAction,
        keyboardType = KeyboardType.Password,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        focusRequester = focusRequester,
        trailingIcon = {
            TextButton(
                onClick = { revealed = !revealed },
                modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
            ) {
                Text(
                    text = if (revealed) stringResource(R.string.common_hide) else stringResource(R.string.common_show),
                    style = NoteZeroType.Caption,
                    color = LocalNoteZeroColors.current.accentText,
                )
            }
        },
    )
}

/** Monospaced entry for a single recovery word. */
@Composable
fun WordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    NzTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        textStyle = NoteZeroType.RecoveryWord,
        keyboardType = KeyboardType.Ascii,
        imeAction = imeAction,
        onImeAction = onImeAction,
        focusRequester = focusRequester,
    )
}

/**
 * Labelled toggle row. The whole 48dp+ row is the switch target, has the Switch role and
 * a description, and the state is exposed by the switch itself (not colour-only).
 */
@Composable
fun NzSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.minTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = NoteZeroSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = NoteZeroType.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = NoteZeroSpacing.xxs),
                )
            }
        }
        Spacer(Modifier.width(NoteZeroSpacing.m))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Horizontal rule (1dp) matching the separator tokens. */
@Composable
fun NzDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier.padding(vertical = NoteZeroSpacing.s),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
    )
}
