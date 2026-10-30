package dev.jotalac.feature.vault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.jotalac.core.utils.suspendRunCatching
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.pdf_export.defaultExportDirectory
import dev.jotalac.feature.vault.domain.NoteVault
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.write
import org.koin.compose.koinInject

/**
 * Saves the notebook's local keystore file: asks where, then writes the JSON. [onFinished] gets the
 * saved file's name, null when the save dialog was cancelled, or why the export failed.
 */
@Composable
fun VaultKeystoreExportDialog(notebook: Notebook, onFinished: (Result<String?>) -> Unit) {
    val currentOnFinished by rememberUpdatedState(onFinished)
    val vault = koinInject<NoteVault>()

    LaunchedEffect(notebook.id) {
        currentOnFinished(
            suspendRunCatching {
                val json = vault.localKeystoreJson(notebook).getOrThrow()
                    ?: error("No local keystore in '${notebook.name}'")
                val file = FileKit.openFileSaver(
                    suggestedName = "${notebook.name}-vault-keystore",
                    defaultExtension = "json",
                    directory = defaultExportDirectory(),
                ) ?: return@suspendRunCatching null // the user closed the save dialog
                file.write(json.encodeToByteArray())
                file.name
            }
        )
    }
}

/**
 * Picks a keystore file and installs it into [notebook] (which must not already use a keystore for
 * another key). [onFinished] gets the import result, or null when the picker was cancelled.
 */
@Composable
fun VaultKeystoreImportDialog(notebook: Notebook, onFinished: (Result<Unit>?) -> Unit) {
    val currentOnFinished by rememberUpdatedState(onFinished)
    val vault = koinInject<NoteVault>()

    LaunchedEffect(notebook.id) {
        val result = suspendRunCatching {
            val file = FileKit.openFilePicker(FileKitType.File()) ?: return@suspendRunCatching null
            val json = file.readBytes().decodeToString()
            vault.importKeystore(notebook, json).getOrThrow()
            file.name
        }
        // success(null) is the cancelled picker - not a result at all
        currentOnFinished(if (result.getOrNull() == null && result.isSuccess) null else result.map { })
    }
}
