package dev.jotalac.feature.editor_sidebar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jotalac.feature.vault.domain.VaultState
import git_writer.shared.generated.resources.Res
import git_writer.shared.generated.resources.sidebar_locked_description
import git_writer.shared.generated.resources.sidebar_locked_title
import git_writer.shared.generated.resources.vault_shield
import git_writer.shared.generated.resources.vault_keystore_import_button
import git_writer.shared.generated.resources.vault_password_label
import git_writer.shared.generated.resources.vault_locked_no_keystore_description
import git_writer.shared.generated.resources.vault_recovery_key_label
import git_writer.shared.generated.resources.vault_unlock_button
import git_writer.shared.generated.resources.vault_unlock_password_toggle
import git_writer.shared.generated.resources.vault_unlock_recovery_toggle
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Replaces the file tree while the active notebook is an encrypted vault without its key. */
@Composable
fun VaultLockedPanel(
    locked: VaultState.Locked,
    busy: Boolean,
    error: String?,
    onUnlock: (input: String, isRecoveryKey: Boolean) -> Unit,
    onImportKeystore: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var useRecoveryKey by remember { mutableStateOf(false) }
    // without a local keystore the recovery key (or an imported keystore file) is the only way in
    val recoveryOnly = !locked.hasLocalKeystore
    val effectiveRecovery = useRecoveryKey || recoveryOnly

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.vault_shield),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.sidebar_locked_title),
                style = MaterialTheme.typography.titleSmall,
            )
        }

        Text(
            text = stringResource(if (recoveryOnly) Res.string.vault_locked_no_keystore_description else Res.string.sidebar_locked_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = {
                Text(stringResource(if (effectiveRecovery) Res.string.vault_recovery_key_label else Res.string.vault_password_label))
            },
            singleLine = true,
            visualTransformation = if (effectiveRecovery) VisualTransformation.None else PasswordVisualTransformation(),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = { onUnlock(input, effectiveRecovery) },
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text(stringResource(Res.string.vault_unlock_button))
            }
        }

        if (recoveryOnly) {
            TextButton(
                onClick = onImportKeystore,
                enabled = !busy,
            ) {
                Text(
                    text = stringResource(Res.string.vault_keystore_import_button),
                    maxLines = 1,
                )
            }
        } else {
            TextButton(
                onClick = {
                    useRecoveryKey = !useRecoveryKey
                    input = ""
                },
                enabled = !busy,
            ) {
                Text(
                    text = stringResource(if (useRecoveryKey) Res.string.vault_unlock_password_toggle else Res.string.vault_unlock_recovery_toggle),
                    maxLines = 1,
                )
            }
        }
    }
}
