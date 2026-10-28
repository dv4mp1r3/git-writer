package dev.jotalac.feature.notebooks_management.ui.edit_notebook

import dev.jotalac.core.utils.SnackbarManager
import dev.jotalac.core.utils.SnackbarText
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.notebooks_management.ui.edit_notebook.EditNotebookViewModel.EditNotebookEvent
import dev.jotalac.feature.vault.data.VaultController
import dev.jotalac.feature.vault.data.VaultFileStore
import dev.jotalac.testing.fakes.FakeGitSyncRepository
import dev.jotalac.testing.fakes.FakeNotebookRepository
import dev.jotalac.testing.fakes.FakeNotebookRepository.NotebookUpdate
import dev.jotalac.testing.fakes.FakeNoteVault
import dev.jotalac.testing.viewModelTest
import git_writer.shared.generated.resources.Res
import git_writer.shared.generated.resources.msg_notebook_updated
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditNotebookViewModelTest {

    private val notebooks = FakeNotebookRepository()
    private val snackbar = SnackbarManager()
    private val vault = FakeNoteVault()
    private val gitSync = FakeGitSyncRepository()

    private val validUrl = "https://github.com/me/notes.git"
    private var dialogClosed = false

    /** The edit dialog opened for a notebook without a remote. */
    private fun editDialog() = EditNotebookViewModel(
        notebooks, snackbar, vault,
        VaultController(gitSync, vault, VaultFileStore()),
    ).apply {
        initWithNotebook(id = 3, name = "Work", remoteUrl = null, remoteUsername = null, remotePassword = null)
    }

    private fun EditNotebookViewModel.save() = onEvent(EditNotebookEvent.SaveNotebook { dialogClosed = true })

    private fun TestScope.collectSnackbars(): List<SnackbarText> {
        val received = mutableListOf<SnackbarText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snackbar.messages.collect { received += it }
        }
        return received
    }

    /** Asserts the form was rejected with [error] and nothing was saved. */
    private fun EditNotebookViewModel.assertRejected(error: String) {
        assertEquals(error, uiState.value.errorMessage)
        assertTrue(notebooks.notebookUpdates.isEmpty())
        assertFalse(dialogClosed)
    }

    // --- validation

    @Test
    fun `a blank name is not saved`() = viewModelTest {
        val dialog = editDialog()
        dialog.onEvent(EditNotebookEvent.NameChanged("   "))

        dialog.save()
        advanceUntilIdle()

        dialog.assertRejected("Notebook name cannot be empty")
    }

    @Test
    fun `a remote url without credentials asks for them`() = viewModelTest {
        val dialog = editDialog()
        dialog.onEvent(EditNotebookEvent.RemoteUrlChanged(validUrl))

        dialog.save()
        advanceUntilIdle()

        dialog.assertRejected("Please fill in all remote credential fields")
    }

    @Test
    fun `credentials without a remote url ask for the url`() = viewModelTest {
        val dialog = editDialog()
        dialog.onEvent(EditNotebookEvent.UsernameChanged("me"))
        dialog.onEvent(EditNotebookEvent.PasswordChanged("token"))

        dialog.save()
        advanceUntilIdle()

        dialog.assertRejected("Please fill in repository URL")
    }

    @Test
    fun `an ssh remote url is rejected because only http is supported`() = viewModelTest {
        val dialog = editDialog()
        dialog.onEvent(EditNotebookEvent.RemoteUrlChanged("git@github.com:me/notes.git"))
        dialog.onEvent(EditNotebookEvent.UsernameChanged("me"))
        dialog.onEvent(EditNotebookEvent.PasswordChanged("token"))

        dialog.save()
        advanceUntilIdle()

        dialog.assertRejected("Remote URL must be over HTTP")
    }

    @Test
    fun `a remote url that is not a git repository url is rejected`() = viewModelTest {
        val dialog = editDialog()
        dialog.onEvent(EditNotebookEvent.RemoteUrlChanged("https://github.com/me/notes"))
        dialog.onEvent(EditNotebookEvent.UsernameChanged("me"))
        dialog.onEvent(EditNotebookEvent.PasswordChanged("token"))

        dialog.save()
        advanceUntilIdle()

        dialog.assertRejected("Remote URL must end with .git")
    }

    @Test
    fun `editing a field clears the previous error`() = viewModelTest {
        val dialog = editDialog()
        dialog.onEvent(EditNotebookEvent.NameChanged(""))
        dialog.save()
        advanceUntilIdle()

        dialog.onEvent(EditNotebookEvent.NameChanged("W"))

        assertNull(dialog.uiState.value.errorMessage)
    }

    // --- saving

    @Test
    fun `a valid form saves trimmed values and closes the dialog`() = viewModelTest {
        notebooks.notebookResult = Result.success(Notebook(3, "Work", "/nb/Work", validUrl, "me", "token"))
        val dialog = editDialog()
        val messages = collectSnackbars()
        dialog.onEvent(EditNotebookEvent.NameChanged("  Work  "))
        dialog.onEvent(EditNotebookEvent.RemoteUrlChanged(" $validUrl "))
        dialog.onEvent(EditNotebookEvent.UsernameChanged(" me "))
        dialog.onEvent(EditNotebookEvent.PasswordChanged("token"))

        dialog.save()
        advanceUntilIdle()

        assertEquals(listOf(NotebookUpdate(3, "Work", validUrl, "me", "token")), notebooks.notebookUpdates)
        assertTrue(dialogClosed)
        assertEquals(listOf<SnackbarText>(SnackbarText.resource(Res.string.msg_notebook_updated)), messages)
    }

    @Test
    fun `clearing the remote saves the notebook without one`() = viewModelTest {
        notebooks.notebookResult = Result.success(Notebook(3, "Work", "/nb/Work", null, null, null))
        val dialog = EditNotebookViewModel(
            notebooks, snackbar, vault,
            VaultController(gitSync, vault, VaultFileStore()),
        ).apply {
            initWithNotebook(id = 3, name = "Work", remoteUrl = validUrl, remoteUsername = "me", remotePassword = "token")
        }

        dialog.onEvent(EditNotebookEvent.ClearRemoteCredentials)
        dialog.save()
        advanceUntilIdle()

        // null, not "" - the repository removes the git remote for a missing url
        assertEquals(listOf(NotebookUpdate(3, "Work", null, null, null)), notebooks.notebookUpdates)
    }

    @Test
    fun `a failed save shows why and keeps the dialog open`() = viewModelTest {
        notebooks.notebookResult = Result.failure(IllegalStateException("A notebook with this name already exists"))
        val dialog = editDialog()
        dialog.onEvent(EditNotebookEvent.NameChanged("Home"))

        dialog.save()
        advanceUntilIdle()

        assertEquals("A notebook with this name already exists", dialog.uiState.value.errorMessage)
        assertFalse(dialog.uiState.value.isLoading)
        assertFalse(dialogClosed)
    }
}
