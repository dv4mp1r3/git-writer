package dev.jotalac.core.utils

import coil3.ImageLoader
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dev.jotalac.feature.vault.data.VaultFileStore
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultLockedException
import kotlinx.io.IOException
import okio.Buffer

/** An image link that has to be decrypted from a vault notebook before decoding. */
class VaultImageModel(val rootPath: String, val relPath: String)

/**
 * Decrypts images of a vault notebook in memory; registered before the plain file fetcher, so
 * it only sees paths whose notebook is an unlocked vault (the mapper returns null otherwise).
 */
class VaultImageFetcher(
    private val vault: NoteVault,
    private val fileStore: VaultFileStore,
    private val model: VaultImageModel,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val session = vault.sessionForPath(model.rootPath) ?: throw VaultLockedException()
        val bytes = fileStore.read(model.rootPath, session.vault, model.relPath)
            ?: throw IOException("Image not found in vault: ${model.relPath}")
        return SourceFetchResult(
            source = ImageSource(Buffer().apply { write(bytes) }, okio.FileSystem.SYSTEM),
            mimeType = null,
            dataSource = coil3.decode.DataSource.MEMORY,
        )
    }

    class Factory(
        private val vault: NoteVault,
        private val fileStore: VaultFileStore,
    ) : Fetcher.Factory<VaultImageModel> {
        override fun create(data: VaultImageModel, options: Options, imageLoader: ImageLoader): Fetcher =
            VaultImageFetcher(vault, fileStore, data)
    }
}
