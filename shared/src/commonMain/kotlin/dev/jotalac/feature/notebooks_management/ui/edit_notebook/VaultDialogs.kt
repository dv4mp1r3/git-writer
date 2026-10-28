package dev.jotalac.feature.notebooks_management.ui.edit_notebook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jotalac.core.ui.components.AlertDialogTitleWithIcon
import dev.jotalac.core.utils.buildClipEntry
import git_writer.shared.generated.resources.Res
import git_writer.shared.generated.resources.cancel_button
import git_writer.shared.generated.resources.close
import git_writer.shared.generated.resources.vault_shield
import git_writer.shared.generated.resources.vault_copy_button
import git_writer.shared.generated.resources.vault_current_password_label
import git_writer.shared.generated.resources.vault_disable_confirm
import git_writer.shared.generated.resources.vault_disable_description
import git_writer.shared.generated.resources.vault_disable_title
import git_writer.shared.generated.resources.vault_disable_warning_host
import git_writer.shared.generated.resources.vault_disable_warning_others
import git_writer.shared.generated.resources.vault_enable_confirm
import git_writer.shared.generated.resources.vault_enable_description
import git_writer.shared.generated.resources.vault_enable_title
import git_writer.shared.generated.resources.vault_enable_warning_history
import git_writer.shared.generated.resources.vault_enable_warning_lost
import git_writer.shared.generated.resources.vault_password_confirm_label
import git_writer.shared.generated.resources.vault_password_generate
import git_writer.shared.generated.resources.vault_password_label
import git_writer.shared.generated.resources.vault_password_mismatch
import git_writer.shared.generated.resources.vault_recovery_key_label
import git_writer.shared.generated.resources.vault_password_too_short
import git_writer.shared.generated.resources.vault_show_recovery_key_button
import git_writer.shared.generated.resources.vault_unlock_button
import git_writer.shared.generated.resources.vault_change_password_confirm
import git_writer.shared.generated.resources.vault_change_password_title
import git_writer.shared.generated.resources.vault_new_password_label
import git_writer.shared.generated.resources.vault_recovery_key_description
import git_writer.shared.generated.resources.vault_recovery_key_prompt_description
import git_writer.shared.generated.resources.vault_recovery_key_prompt_title
import git_writer.shared.generated.resources.vault_recovery_key_title
import git_writer.shared.generated.resources.vault_unlock_description
import git_writer.shared.generated.resources.vault_unlock_password_toggle
import git_writer.shared.generated.resources.vault_unlock_recovery_toggle
import git_writer.shared.generated.resources.vault_unlock_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

private const val MIN_PASSWORD_LENGTH = 8

// without the characters that are easy to mix up
private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** A suggestion for the enable dialog: four groups of five unambiguous characters, ~100 bits. */
internal fun generatePassphrase(): String =
    (1..4).joinToString("-") {
        (1..5).map { PASSWORD_ALPHABET[Random.nextInt(PASSWORD_ALPHABET.length)] }.joinToString("")
    }

@Composable
private fun VaultPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: StringResource,
    modifier: Modifier = Modifier,
    visible: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = modifier,
    )
}

@Composable
private fun VaultHintText(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VaultDialogSpinnerButton(label: StringResource) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(stringResource(label))
    }
}

@Composable
internal fun VaultEnableDialog(
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= MIN_PASSWORD_LENGTH && password == confirm

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.vault_shield,
                text = Res.string.vault_enable_title,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                VaultHintText(stringResource(Res.string.vault_enable_description))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VaultPasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = Res.string.vault_password_label,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val generated = generatePassphrase()
                        password = generated
                        confirm = generated
                    }) {
                        Text(stringResource(Res.string.vault_password_generate), maxLines = 1)
                    }
                }
                if (password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH) {
                    VaultHintText(stringResource(Res.string.vault_password_too_short), isError = true)
                }
                VaultPasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = Res.string.vault_password_confirm_label,
                )
                if (confirm.isNotEmpty() && password != confirm) {
                    VaultHintText(stringResource(Res.string.vault_password_mismatch), isError = true)
                }
                HorizontalDivider()
                VaultHintText(stringResource(Res.string.vault_enable_warning_history))
                VaultHintText(stringResource(Res.string.vault_enable_warning_lost), isError = true)
                error?.let { VaultHintText(it, isError = true) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = valid && !busy) {
                if (busy) {
                    VaultDialogSpinnerButton(Res.string.vault_enable_confirm)
                } else {
                    Text(stringResource(Res.string.vault_enable_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.cancel_button))
            }
        },
    )
}

@Composable
internal fun VaultUnlockDialog(
    onDismiss: () -> Unit,
    onConfirm: (input: String, isRecoveryKey: Boolean) -> Unit,
    busy: Boolean,
    error: String?,
    description: StringResource = Res.string.vault_unlock_description,
) {
    var input by remember { mutableStateOf("") }
    var useRecoveryKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.vault_shield,
                text = Res.string.vault_unlock_title,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultHintText(stringResource(description))
                VaultPasswordField(
                    value = input,
                    onValueChange = { input = it },
                    label = if (useRecoveryKey) Res.string.vault_recovery_key_label else Res.string.vault_password_label,
                    visible = useRecoveryKey,
                )
                TextButton(onClick = {
                    useRecoveryKey = !useRecoveryKey
                    input = ""
                }) {
                    Text(
                        text = stringResource(if (useRecoveryKey) Res.string.vault_unlock_password_toggle else Res.string.vault_unlock_recovery_toggle),
                        maxLines = 1,
                    )
                }
                error?.let { VaultHintText(it, isError = true) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(input, useRecoveryKey) }, enabled = input.isNotBlank() && !busy) {
                if (busy) {
                    VaultDialogSpinnerButton(Res.string.vault_unlock_button)
                } else {
                    Text(stringResource(Res.string.vault_unlock_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.cancel_button))
            }
        },
    )
}

@Composable
internal fun VaultDisableDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    busy: Boolean,
    error: String?,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.vault_shield,
                text = Res.string.vault_disable_title,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultHintText(stringResource(Res.string.vault_disable_description))
                VaultHintText(stringResource(Res.string.vault_disable_warning_host))
                VaultHintText(stringResource(Res.string.vault_disable_warning_others))
                error?.let { VaultHintText(it, isError = true) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                if (busy) {
                    VaultDialogSpinnerButton(Res.string.vault_disable_confirm)
                } else {
                    Text(stringResource(Res.string.vault_disable_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.cancel_button))
            }
        },
    )
}

@Composable
internal fun VaultChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (current: String, new: String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = current.isNotBlank() && newPassword.length >= MIN_PASSWORD_LENGTH && newPassword == confirm

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.vault_shield,
                text = Res.string.vault_change_password_title,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultPasswordField(current, { current = it }, Res.string.vault_current_password_label)
                VaultPasswordField(newPassword, { newPassword = it }, Res.string.vault_new_password_label)
                if (newPassword.isNotEmpty() && newPassword.length < MIN_PASSWORD_LENGTH) {
                    VaultHintText(stringResource(Res.string.vault_password_too_short), isError = true)
                }
                VaultPasswordField(confirm, { confirm = it }, Res.string.vault_password_confirm_label)
                if (confirm.isNotEmpty() && newPassword != confirm) {
                    VaultHintText(stringResource(Res.string.vault_password_mismatch), isError = true)
                }
                error?.let { VaultHintText(it, isError = true) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(current, newPassword) }, enabled = valid && !busy) {
                if (busy) {
                    VaultDialogSpinnerButton(Res.string.vault_change_password_confirm)
                } else {
                    Text(stringResource(Res.string.vault_change_password_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.cancel_button))
            }
        },
    )
}

@Composable
internal fun VaultPasswordPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.vault_shield,
                text = Res.string.vault_recovery_key_prompt_title,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultHintText(stringResource(Res.string.vault_recovery_key_prompt_description))
                VaultPasswordField(password, { password = it }, Res.string.vault_password_label)
                error?.let { VaultHintText(it, isError = true) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = password.isNotBlank() && !busy) {
                if (busy) {
                    VaultDialogSpinnerButton(Res.string.vault_show_recovery_key_button)
                } else {
                    Text(stringResource(Res.string.vault_show_recovery_key_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.cancel_button))
            }
        },
    )
}

@Composable
internal fun VaultRecoveryKeyDialog(key: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.vault_shield,
                text = Res.string.vault_recovery_key_title,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultHintText(stringResource(Res.string.vault_recovery_key_description))
                SelectionContainer {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { clipboard.setClipEntry(buildClipEntry(key)) } }) {
                    Text(stringResource(Res.string.vault_copy_button))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.close))
                }
            }
        },
    )
}
