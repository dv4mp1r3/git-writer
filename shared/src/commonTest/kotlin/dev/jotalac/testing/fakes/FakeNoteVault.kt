package dev.jotalac.testing.fakes

import dev.jotalac.feature.editor_sidebar.domain.FileNode
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.crypto.OpenedVault
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A no-op vault: every notebook is plain. Vault-specific tests use [dev.jotalac.feature.vault.data.VaultManagerImpl]
 * against real folders instead.
 */
class FakeNoteVault(initialStates: Map<Long, VaultState> = emptyMap()) : NoteVault {

    override val states = MutableStateFlow(initialStates)

    override fun stateOf(notebook: Notebook): VaultState = states.value[notebook.id] ?: VaultState.Off

    override suspend fun refreshStates() {}

    override suspend fun unlock(notebook: Notebook, passphrase: String): Result<Unit> = Result.success(Unit)

    override suspend fun unlockWithRecoveryKey(notebook: Notebook, recoveryKey: String): Result<Unit> = Result.success(Unit)

    override suspend fun lock(notebook: Notebook) {}

    override suspend fun forgetKeyOnDevice(notebook: Notebook) {}

    override suspend fun createVault(notebook: Notebook, passphrase: String): Result<Unit> = Result.success(Unit)

    override suspend fun changePassphrase(notebook: Notebook, currentPassphrase: String, newPassphrase: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun exportRecoveryKey(notebook: Notebook, currentPassphrase: String): Result<String> =
        Result.success("GW1-fake")

    override fun recoveryKeyOf(notebook: Notebook): String? = null

    override suspend fun buildFileTree(notebook: Notebook): FileNode.Directory? = null

    override fun sessionForPath(directoryPath: String): OpenedVault? = null
}
