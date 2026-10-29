package dev.jotalac.core.di

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.jotalac.AppViewModel
import dev.jotalac.core.data.ActiveNotebookManager
import dev.jotalac.core.data.UserSettings
import dev.jotalac.core.data.UserSettingsManager
import dev.jotalac.core.database.AppDatabase
import dev.jotalac.core.utils.AppExitTasks
import dev.jotalac.core.utils.HttpRemoteReachability
import dev.jotalac.core.utils.RemoteReachability
import dev.jotalac.core.utils.RemoteStatusMonitor
import dev.jotalac.core.utils.SnackbarManager
import dev.jotalac.feature.editor.data.EditorRepositoryImpl
import dev.jotalac.feature.editor.domain.EditorRepository
import dev.jotalac.feature.editor.ui.EditorViewModel
import dev.jotalac.feature.editor_sidebar.ui.EditorSidebarViewModel
import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import dev.jotalac.feature.notebooks_management.data.NotebookRepositoryImpl
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import dev.jotalac.feature.notebooks_management.ui.create_notebook.CreateNotebookViewModel
import dev.jotalac.feature.notebooks_management.ui.edit_notebook.EditNotebookViewModel
import dev.jotalac.feature.notebooks_management.ui.list_notebooks.NotebookListViewModel
import dev.jotalac.feature.settings.ui.SettingsViewModel
import dev.jotalac.feature.vault.data.VaultController
import dev.jotalac.feature.vault.data.VaultEditorRepository
import dev.jotalac.feature.vault.data.VaultFileStore
import dev.jotalac.feature.vault.data.VaultGitSyncRepository
import dev.jotalac.feature.vault.data.VaultKeystoreMigration
import dev.jotalac.feature.vault.data.VaultManagerImpl
import dev.jotalac.feature.vault.data.VaultMerger
import dev.jotalac.feature.vault.domain.NoteVault
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

// platform specific module
expect val platformModule: Module

val coreModule = module {
    includes(platformModule)

    //local database
    single {
        get<RoomDatabase.Builder<AppDatabase>>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    single { get<AppDatabase>().getNotebooksDao() }

    single { SnackbarManager() }

    single { ActiveNotebookManager(get()) }

    single<UserSettings> { UserSettingsManager(get()) }

    single { AppExitTasks() }

    single { HttpClient() }
    single<RemoteReachability> { HttpRemoteReachability(get()) }
    single { RemoteStatusMonitor(get(), get()) }


}

val featureModules = module {
    //notebook management
    single<NotebookRepository> {
        NotebookRepositoryImpl(
            notebookDao = get(),
            activeNotebookManager = get(),
            gitSyncRepository = get(),
            notebookPathProvider = get(),
        )
    }
    //files management
    single { VaultFileStore() }

    single<NoteVault> {
        VaultManagerImpl(
            allNotebooks = { get<NotebookRepository>().getAllNotebooks() },
            secureKeyStorage = get(),
            fileStore = get(),
        )
    }

    // the decorator intercepts every note I/O for vault notebooks; plain notebooks fall through
    single<EditorRepository> {
        VaultEditorRepository(
            base = EditorRepositoryImpl(),
            notebookRepository = get(),
            vault = get(),
            fileStore = get(),
        )
    }

    // the same for git sync: conflicts of vault notebooks auto-merge on the decrypted content,
    // and conflict names travel as real note paths. The notebook repository is resolved late:
    // it is built on this interface, so a constructor parameter would be a dependency cycle.
    single<GitSyncRepository> {
        VaultGitSyncRepository(
            base = get(qualifier = gitSyncBase),
            allNotebooks = { get<NotebookRepository>().getAllNotebooks().first() },
            vault = get(),
            fileStore = get(),
            merger = get(),
        )
    }

    single { VaultMerger() }

    // the enable / disable / catch-up flows: note conversion + git history rewrite in one place
    single { VaultController(get(), get(), get()) }

    // takes the keystore out of the repositories of already encrypted notebooks (one-time,
    // retried after syncs because older app versions can commit it again)
    single {
        VaultKeystoreMigration(
            notebookRepository = get(),
            git = get(),
            fileStore = get(),
        )
    }

    viewModelOf(::EditorViewModel)
    viewModelOf(::CreateNotebookViewModel)
    viewModelOf(::EditNotebookViewModel)
    viewModelOf(::NotebookListViewModel)
    viewModelOf(::EditorSidebarViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::AppViewModel)
}

val appModules = listOf(coreModule, featureModules)

// start koin, if not already started
fun initKoin() {
    if (KoinPlatform.getKoinOrNull() != null) return

    startKoin {
        modules(appModules)
    }
}