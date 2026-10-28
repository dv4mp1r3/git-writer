package dev.jotalac.feature.notebooks_management.ui.edit_notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jotalac.core.utils.SnackbarManager
import dev.jotalac.core.utils.SnackbarText
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import dev.jotalac.feature.notebooks_management.ui.validateRemoteUrl
import dev.jotalac.feature.vault.data.VaultController
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import git_writer.shared.generated.resources.Res
import git_writer.shared.generated.resources.msg_notebook_updated
import git_writer.shared.generated.resources.msg_vault_disabled
import git_writer.shared.generated.resources.msg_vault_enabled
import git_writer.shared.generated.resources.msg_vault_key_forgotten
import git_writer.shared.generated.resources.msg_vault_locked
import git_writer.shared.generated.resources.msg_vault_password_changed
import git_writer.shared.generated.resources.msg_vault_unlocked
import git_writer.shared.generated.resources.vault_unlock_wrong
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** The vault sub-dialogs hosted on top of the edit-notebook dialog. */
sealed interface VaultDialog {
    data object Enable : VaultDialog
    data object Unlock : VaultDialog
    data object Disable : VaultDialog
    data object ChangePassword : VaultDialog
    data object RecoveryKeyPrompt : VaultDialog
    data class RecoveryKey(val key: String) : VaultDialog
}

data class EditNotebookState(
    val notebookName: String = "",
    val remoteUrl: String = "",
    val remoteUsername: String = "",
    val remotePassword: String = "",
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val vaultState: VaultState = VaultState.Off,
    val vaultBusy: Boolean = false,
    val vaultError: String? = null,
    val vaultDialog: VaultDialog? = null,
)

class EditNotebookViewModel(
    private val notebookRepository: NotebookRepository,
    private val snackbarManager: SnackbarManager,
    private val vault: NoteVault,
    private val vaultController: VaultController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditNotebookState())
    val uiState: StateFlow<EditNotebookState> = _uiState.asStateFlow()

    private var notebookId: Long = 0

    init {
        viewModelScope.launch {
            vault.states.collect { states ->
                _uiState.update { it.copy(vaultState = states[notebookId] ?: VaultState.Off) }
            }
        }
    }

    fun initWithNotebook(id: Long, name: String, remoteUrl: String?, remoteUsername: String?, remotePassword: String?) {
        notebookId = id
        _uiState.update {
            it.copy(
                notebookName = name,
                remoteUrl = remoteUrl ?: "",
                remoteUsername = remoteUsername ?: "",
                remotePassword = remotePassword ?: "",
                errorMessage = null,
                isLoading = false,
                vaultState = vault.states.value[id] ?: VaultState.Off,
                vaultBusy = false,
                vaultError = null,
                vaultDialog = null,
            )
        }
    }

    fun onEvent(event: EditNotebookEvent) {
        when (event) {
            is EditNotebookEvent.NameChanged -> _uiState.update {
                it.copy(
                    notebookName = event.name,
                    errorMessage = null
                )
            }

            is EditNotebookEvent.RemoteUrlChanged -> _uiState.update {
                it.copy(
                    remoteUrl = event.url,
                    errorMessage = null
                )
            }

            is EditNotebookEvent.UsernameChanged -> _uiState.update {
                it.copy(
                    remoteUsername = event.username,
                    errorMessage = null
                )
            }

            is EditNotebookEvent.PasswordChanged -> _uiState.update {
                it.copy(
                    remotePassword = event.password,
                    errorMessage = null
                )
            }

            is EditNotebookEvent.SaveNotebook -> saveNotebookEdit(event.onSuccess)
            is EditNotebookEvent.ClearRemoteCredentials -> _uiState.update {
                it.copy(
                    remoteUrl = "",
                    remoteUsername = "",
                    remotePassword = "",
                )
            }

            is EditNotebookEvent.ShowVaultDialog -> _uiState.update {
                it.copy(vaultDialog = event.dialog, vaultError = null)
            }
            EditNotebookEvent.DismissVaultDialog -> if (!_uiState.value.vaultBusy) _uiState.update {
                it.copy(vaultDialog = null, vaultError = null)
            }

            is EditNotebookEvent.VaultEnableConfirmed -> runVaultOperation(Res.string.msg_vault_enabled) {
                vaultController.enableEncryption(it, event.passphrase)
            }
            is EditNotebookEvent.VaultUnlockConfirmed -> unlockVault(event.input, event.isRecoveryKey)
            EditNotebookEvent.VaultDisableConfirmed -> runVaultOperation(Res.string.msg_vault_disabled) {
                vaultController.disableEncryption(it)
            }
            is EditNotebookEvent.VaultChangePasswordConfirmed -> runVaultOperation(Res.string.msg_vault_password_changed) {
                vault.changePassphrase(it, event.currentPassphrase, event.newPassphrase)
            }
            is EditNotebookEvent.VaultRecoveryKeyConfirmed -> exportRecoveryKey(event.passphrase)
            EditNotebookEvent.VaultLock -> runVaultOperation(Res.string.msg_vault_locked) {
                vault.lock(it)
                Result.success(Unit)
            }
            EditNotebookEvent.VaultForgetKey -> runVaultOperation(Res.string.msg_vault_key_forgotten) {
                vault.forgetKeyOnDevice(it)
                Result.success(Unit)
            }
        }
    }

    private suspend fun loadNotebook(): Notebook? =
        notebookRepository.getNotebookByIdAsFlow(notebookId).firstOrNull()

    /** Runs a vault flow; success closes the dialog, failure shows the message inside it. */
    private fun runVaultOperation(successMessage: StringResource, operation: suspend (Notebook) -> Result<Unit>) {
        viewModelScope.launch {
            val notebook = loadNotebook() ?: return@launch
            _uiState.update { it.copy(vaultBusy = true, vaultError = null) }
            operation(notebook)
                .onSuccess {
                    _uiState.update { it.copy(vaultBusy = false, vaultDialog = null, vaultError = null) }
                    snackbarManager.showMessage(SnackbarText.resource(successMessage))
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(vaultBusy = false, vaultError = error.message ?: "Vault operation failed") }
                }
        }
    }

    /** Unlocking from the dialog is the catch-up flow: unlock and pull the remote state in. */
    private fun unlockVault(input: String, isRecoveryKey: Boolean) {
        viewModelScope.launch {
            val notebook = loadNotebook() ?: return@launch
            _uiState.update { it.copy(vaultBusy = true, vaultError = null) }
            val result = if (isRecoveryKey) {
                vaultController.adoptEncryptedNotebookWithRecoveryKey(notebook, input)
            } else {
                vaultController.adoptEncryptedNotebook(notebook, input)
            }
            result.onSuccess {
                _uiState.update { it.copy(vaultBusy = false, vaultDialog = null, vaultError = null) }
                snackbarManager.showMessage(SnackbarText.resource(Res.string.msg_vault_unlocked))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { it.copy(vaultBusy = false, vaultError = getString(Res.string.vault_unlock_wrong)) }
            }
        }
    }

    private fun exportRecoveryKey(passphrase: String) {
        viewModelScope.launch {
            val notebook = loadNotebook() ?: return@launch
            _uiState.update { it.copy(vaultBusy = true, vaultError = null) }
            vault.exportRecoveryKey(notebook, passphrase)
                .onSuccess { key ->
                    _uiState.update { it.copy(vaultBusy = false, vaultDialog = VaultDialog.RecoveryKey(key), vaultError = null) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(vaultBusy = false, vaultError = getString(Res.string.vault_unlock_wrong)) }
                }
        }
    }

    private fun validateForm(state: EditNotebookState): String? {
        if (state.notebookName.isBlank()) {
            return "Notebook name cannot be empty"
        }

        val hasRemoteUrl = state.remoteUrl.isNotBlank()
        val hasUsername = state.remoteUsername.isNotBlank()
        val hasPassword = state.remotePassword.isNotBlank()

        if (hasRemoteUrl || hasUsername || hasPassword) {
            if (!hasRemoteUrl) {
                return "Please fill in repository URL"
            }
            if (!hasUsername || !hasPassword) {
                return "Please fill in all remote credential fields"
            }
            val urlError = validateRemoteUrl(state.remoteUrl.trim())
            if (urlError != null) {
                return urlError
            }
        }

        return null
    }

    private fun saveNotebookEdit(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val currentState = _uiState.value

            val validationError = validateForm(currentState)
            if (validationError != null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = validationError) }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val updateNotebookResult = notebookRepository.updateNotebook(
                id = notebookId,
                name = currentState.notebookName.trim(),
                remoteUrl = currentState.remoteUrl.trim().ifBlank { null },
                remoteUsername = currentState.remoteUsername.trim().ifBlank { null },
                remotePassword = currentState.remotePassword.trim().ifBlank { null },
            )

            updateNotebookResult.onSuccess {
                _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                snackbarManager.showMessage(SnackbarText.resource(Res.string.msg_notebook_updated))
                onSuccess()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = error.message ?: "Failed to update notebook",
                        isLoading = false,
                    )
                }
            }
        }
    }

    sealed interface EditNotebookEvent {
        data class NameChanged(val name: String) : EditNotebookEvent
        data class RemoteUrlChanged(val url: String) : EditNotebookEvent
        data class UsernameChanged(val username: String) : EditNotebookEvent
        data class PasswordChanged(val password: String) : EditNotebookEvent
        data class SaveNotebook(val onSuccess: () -> Unit) : EditNotebookEvent
        data object ClearRemoteCredentials : EditNotebookEvent

        data class ShowVaultDialog(val dialog: VaultDialog) : EditNotebookEvent
        data object DismissVaultDialog : EditNotebookEvent
        data class VaultEnableConfirmed(val passphrase: String) : EditNotebookEvent
        data class VaultUnlockConfirmed(val input: String, val isRecoveryKey: Boolean) : EditNotebookEvent
        data object VaultDisableConfirmed : EditNotebookEvent
        data class VaultChangePasswordConfirmed(val currentPassphrase: String, val newPassphrase: String) : EditNotebookEvent
        data class VaultRecoveryKeyConfirmed(val passphrase: String) : EditNotebookEvent
        data object VaultLock : EditNotebookEvent
        data object VaultForgetKey : EditNotebookEvent
    }
}
