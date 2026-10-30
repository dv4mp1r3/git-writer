package dev.jotalac.feature.notebooks_management.ui.edit_notebook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.jotalac.core.ui.components.AlertDialogTitleWithIcon
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.notebooks_management.ui.components.SubmittableTextField
import dev.jotalac.feature.vault.domain.VaultState
import dev.jotalac.feature.vault.ui.VaultKeystoreExportDialog
import dev.jotalac.feature.vault.ui.VaultKeystoreImportDialog
import git_writer.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EditNotebookDialog(
    notebook: Notebook,
    onDismiss: () -> Unit,
    viewModel: EditNotebookViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initWithNotebook(
            id = notebook.id,
            name = notebook.name,
            remoteUrl = notebook.remoteUrl,
            remoteUsername = notebook.remoteUsername,
            remotePassword = notebook.remotePassword,
        )
    }

    fun onSubmit() {
        viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.SaveNotebook {
            onDismiss()
        })
    }

    AlertDialog(
        onDismissRequest = { if (!state.isLoading) onDismiss() },
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.edit_pencil,
                text = Res.string.edit_notebook_title,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SubmittableTextField(
                    value = state.notebookName,
                    onValueChange = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.NameChanged(it)) },
                    label = Res.string.notebook_name_placeholder,
                    modifier = Modifier.fillMaxWidth(),
                    submit = { onSubmit() }
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.remote_credentials_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )

                    TextButton(
                        onClick = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ClearRemoteCredentials) },
                    ) {
                        Text(
                            text = stringResource(Res.string.clear_button),
                            maxLines = 1,
                        )
                    }

                }

                OutlinedTextField(
                    value = state.remoteUrl,
                    onValueChange = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.RemoteUrlChanged(it)) },
                    label = { Text(stringResource(Res.string.git_repository_url_input_label)) },
                    placeholder = { Text(stringResource(Res.string.git_remote_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth > 400.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = state.remoteUsername,
                                onValueChange = {
                                    viewModel.onEvent(
                                        EditNotebookViewModel.EditNotebookEvent.UsernameChanged(
                                            it
                                        )
                                    )
                                },
                                label = { Text(stringResource(Res.string.username_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.remotePassword,
                                onValueChange = {
                                    viewModel.onEvent(
                                        EditNotebookViewModel.EditNotebookEvent.PasswordChanged(
                                            it
                                        )
                                    )
                                },
                                label = { Text(stringResource(Res.string.git_auth_placeholder)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = state.remoteUsername,
                                onValueChange = {
                                    viewModel.onEvent(
                                        EditNotebookViewModel.EditNotebookEvent.UsernameChanged(
                                            it
                                        )
                                    )
                                },
                                label = { Text(stringResource(Res.string.username_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = state.remotePassword,
                                onValueChange = {
                                    viewModel.onEvent(
                                        EditNotebookViewModel.EditNotebookEvent.PasswordChanged(
                                            it
                                        )
                                    )
                                },
                                label = { Text(stringResource(Res.string.git_auth_placeholder)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                HorizontalDivider()

                Text(
                    text = stringResource(Res.string.vault_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (val vaultState = state.vaultState) {
                    VaultState.Off -> {
                        VaultStatusText(Res.string.vault_status_off)
                        VaultSectionButton(stringResource(Res.string.vault_enable_button)) {
                            viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.Enable))
                        }
                    }

                    is VaultState.Unlocked -> {
                        VaultStatusText(Res.string.vault_status_unlocked)
                        if (vaultState.hasLocalKeystore) {
                            // the passphrase re-wraps the local keystore copy only - without one there is nothing to change
                            VaultSectionButton(stringResource(Res.string.vault_change_password_button)) {
                                viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.ChangePassword))
                            }
                            VaultSectionButton(stringResource(Res.string.vault_keystore_export_button)) {
                                viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.KeystoreExport))
                            }
                        } else {
                            VaultSectionButton(stringResource(Res.string.vault_keystore_import_button)) {
                                viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.KeystoreImport))
                            }
                        }
                        VaultSectionButton(stringResource(Res.string.vault_show_recovery_key_button)) {
                            viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.RecoveryKeyPrompt))
                        }
                        VaultSectionButton(stringResource(Res.string.vault_history_cleanup_button)) {
                            viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultHistoryCleanup)
                        }
                        VaultSectionButton(stringResource(Res.string.vault_lock_button)) {
                            viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultLock)
                        }
                        VaultSectionButton(stringResource(Res.string.vault_forget_key_button)) {
                            viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultForgetKey)
                        }
                        VaultSectionButton(
                            text = stringResource(Res.string.vault_disable_button),
                            isError = true,
                        ) {
                            viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.Disable))
                        }
                    }

                    is VaultState.Locked -> {
                        VaultStatusText(Res.string.vault_status_locked)
                        VaultSectionButton(stringResource(Res.string.vault_unlock_button)) {
                            viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.Unlock))
                        }
                        if (!vaultState.hasLocalKeystore) {
                            VaultSectionButton(stringResource(Res.string.vault_keystore_import_button)) {
                                viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.ShowVaultDialog(VaultDialog.KeystoreImport))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit() },
                enabled = state.notebookName.isNotBlank() && !state.isLoading
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(stringResource(Res.string.save_button))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isLoading
            ) {
                Text(stringResource(Res.string.cancel_button))
            }
        }
    )

    when (val dialog = state.vaultDialog) {
        VaultDialog.Enable -> VaultEnableDialog(
            onDismiss = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.DismissVaultDialog) },
            onConfirm = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultEnableConfirmed(it)) },
            busy = state.vaultBusy,
            error = state.vaultError,
        )

        VaultDialog.Unlock -> VaultUnlockDialog(
            onDismiss = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.DismissVaultDialog) },
            onConfirm = { input, isRecoveryKey ->
                viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultUnlockConfirmed(input, isRecoveryKey))
            },
            busy = state.vaultBusy,
            error = state.vaultError,
        )

        VaultDialog.KeystoreImport -> VaultKeystoreImportDialog(
            notebook = notebook,
            onFinished = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultKeystoreImportFinished(it)) },
        )

        VaultDialog.KeystoreExport -> VaultKeystoreExportDialog(
            notebook = notebook,
            onFinished = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultKeystoreExportFinished(it)) },
        )

        VaultDialog.Disable -> VaultDisableDialog(
            onDismiss = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.DismissVaultDialog) },
            onConfirm = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultDisableConfirmed) },
            busy = state.vaultBusy,
            error = state.vaultError,
        )

        VaultDialog.ChangePassword -> VaultChangePasswordDialog(
            onDismiss = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.DismissVaultDialog) },
            onConfirm = { current, new ->
                viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultChangePasswordConfirmed(current, new))
            },
            busy = state.vaultBusy,
            error = state.vaultError,
        )

        VaultDialog.RecoveryKeyPrompt -> VaultPasswordPromptDialog(
            onDismiss = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.DismissVaultDialog) },
            onConfirm = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.VaultRecoveryKeyConfirmed(it)) },
            busy = state.vaultBusy,
            error = state.vaultError,
        )

        is VaultDialog.RecoveryKey -> VaultRecoveryKeyDialog(
            key = dialog.key,
            onDismiss = { viewModel.onEvent(EditNotebookViewModel.EditNotebookEvent.DismissVaultDialog) },
        )

        null -> {}
    }
}

@Composable
private fun VaultStatusText(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VaultSectionButton(text: String, isError: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}