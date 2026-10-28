package dev.jotalac.core.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.map.Mapper
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import dev.jotalac.feature.vault.data.VaultFileStore
import dev.jotalac.feature.vault.data.VaultLayout
import dev.jotalac.feature.vault.domain.NoteVault
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.coil.PlatformFileFetcher
import org.koin.compose.koinInject

@Composable
fun ConfigureAppImageLoader() {
    val notebookRepository = koinInject<NotebookRepository>()
    val vault = koinInject<NoteVault>()
    val fileStore = koinInject<VaultFileStore>()
    val activeNotebookState = notebookRepository.activeNotebookState.collectAsState(initial = null)

    setSingletonImageLoaderFactory { context ->
        getAsyncImageLoader(
            context,
            getActiveNotebookDirectory = { activeNotebookState.value?.directoryPath },
            vault = vault,
            fileStore = fileStore,
        )
    }
}

fun getAsyncImageLoader(
    context: PlatformContext,
    getActiveNotebookDirectory: () -> String? = { null },
    vault: NoteVault? = null,
    fileStore: VaultFileStore? = null,
) = ImageLoader.Builder(context)
    .components {
        // the vault mapper is registered first: it claims unlocked vault paths, everything else
        // falls through to the plain file mapper below
        if (vault != null && fileStore != null) {
            add(Mapper<String, VaultImageModel> { data, _ ->
                resolveVaultImagePath(data, vault, getActiveNotebookDirectory())
            })
            add(VaultImageFetcher.Factory(vault, fileStore))
        }
        add(Mapper<String, PlatformFile> { data, _ ->
            resolveImagePath(data, getActiveNotebookDirectory())?.let { PlatformFile(it) }
        })
        add(PlatformFileFetcher.Factory())
    }
    .build()

/** The vault image model for a note image link, null for plain/web/out-of-notebook paths. */
internal fun resolveVaultImagePath(data: String, vault: NoteVault, notebookRoot: String?): VaultImageModel? {
    if (notebookRoot == null) return null
    if (vault.sessionForPath(notebookRoot) == null) return null // not an unlocked vault notebook

    val absolutePath = resolveImagePath(data, notebookRoot) ?: return null
    return try {
        VaultImageModel(notebookRoot, VaultLayout.relPathOf(notebookRoot, absolutePath))
    } catch (e: IllegalArgumentException) {
        null // the link points outside the notebook - nothing to decrypt
    }
}

/**
 * The file an image link in a note points to, null for web images (Coil's HTTP fetcher loads those).
 * Relative links are relative to the notebook folder, like `images/photo.png` written for pasted images.
 */
internal fun resolveImagePath(data: String, notebookRoot: String?): String? {
    if (data.startsWith("http://") || data.startsWith("https://")) return null

    val cleanPath = data
        .removePrefix("file://")
        .removePrefix("<")
        .removeSuffix(">")
        .trim()
        .decodePercentEscapes()

    val isAbsolute = cleanPath.startsWith("/") ||
            cleanPath.startsWith("\\") ||
            cleanPath.matches(Regex("^[a-zA-Z]:[/\\\\].*"))

    if (isAbsolute || notebookRoot == null) return cleanPath

    val cleanRoot = notebookRoot.trimEnd('/', '\\')
    val relativePath = cleanPath.removePrefix("./").removePrefix(".\\")
    return "$cleanRoot/$relativePath"
}

// "my%20photo.png" -> "my photo.png", how other editors write spaces (and other characters) in a link.
// A % that does not start a valid escape is kept - a file name may contain it ("50% off.png")
private fun String.decodePercentEscapes(): String {
    if ('%' !in this) return this

    val result = StringBuilder()
    // consecutive escapes are decoded together - a character outside ASCII is several escaped UTF-8 bytes
    val escapedBytes = mutableListOf<Byte>()
    fun flushEscapedBytes() {
        if (escapedBytes.isEmpty()) return
        result.append(escapedBytes.toByteArray().decodeToString())
        escapedBytes.clear()
    }

    var i = 0
    while (i < length) {
        val high = if (this[i] == '%' && i + 2 < length) this[i + 1].digitToIntOrNull(16) else null
        val low = if (high != null) this[i + 2].digitToIntOrNull(16) else null

        if (high != null && low != null) {
            escapedBytes += (high * 16 + low).toByte()
            i += 3
        } else {
            flushEscapedBytes()
            result.append(this[i])
            i++
        }
    }
    flushEscapedBytes()

    return result.toString()
}
