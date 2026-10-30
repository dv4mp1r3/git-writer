package dev.jotalac.feature.vault.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jotalac.core.ui.components.AlertDialogTitleWithIcon
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import dev.jotalac.feature.vault.data.VaultController
import dev.jotalac.feature.vault.data.VaultKeystoreMigration
import git_writer.shared.generated.resources.Res
import git_writer.shared.generated.resources.cancel_button
import git_writer.shared.generated.resources.vault_cleanup_confirm
import git_writer.shared.generated.resources.vault_cleanup_description
import git_writer.shared.generated.resources.vault_cleanup_later
import git_writer.shared.generated.resources.vault_cleanup_title
import git_writer.shared.generated.resources.vault_cleanup_warning_history
import git_writer.shared.generated.resources.vault_shield
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Offers to rewrite the repository history of a notebook whose keystore the migration has just
 * untracked: after that the only place the passphrase-protected keystore can still survive is the
 * old commits. Confirming replaces the history with one commit of the current state and
 * force-pushes it ([VaultController.purgeVaultHistory]); "later" hides the offer until the next
 * app start. A failed rewrite keeps the dialog open with the error.
 */
@Composable
fun VaultHistoryCleanupHost(migration: VaultKeystoreMigration = koinInject()) {
    val notebookRepository = koinInject<NotebookRepository>()
    val vaultController = koinInject<VaultController>()
    val pending by migration.pendingHistoryCleanup.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val notebookId = pending.firstOrNull() ?: return
    val notebook by remember(notebookId) {
        notebookRepository.getNotebookByIdAsFlow(notebookId)
    }.collectAsStateWithLifecycle(initialValue = null)
    val current = notebook ?: return

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) migration.clearPendingHistoryCleanup(notebookId) },
        title = {
            AlertDialogTitleWithIcon(
                iconResource = Res.drawable.vault_shield,
                text = Res.string.vault_cleanup_title,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = current.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(Res.string.vault_cleanup_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.vault_cleanup_warning_history),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        vaultController.purgeVaultHistory(current)
                            .onSuccess { migration.clearPendingHistoryCleanup(notebookId) }
                            .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
            ) {
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.vault_cleanup_confirm))
                    }
                } else {
                    Text(stringResource(Res.string.vault_cleanup_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { migration.clearPendingHistoryCleanup(notebookId) }, enabled = !busy) {
                Text(stringResource(Res.string.vault_cleanup_later))
            }
        },
    )
}
