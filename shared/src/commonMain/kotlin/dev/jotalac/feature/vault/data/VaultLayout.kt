package dev.jotalac.feature.vault.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Path bookkeeping of the on-disk vault layout: the notebook folder holds the flat encrypted
 * files (`<cname>.gwv`) and the `.gw-vault/keystore`; everything the UI sees is a virtual
 * decrypted path under the notebook root.
 */
object VaultLayout {

    const val KEYSTORE_DIR = ".gw-vault"
    const val KEYSTORE_FILE = "keystore"
    /** The keystore path as git sees it (relative, `/`-separated) - for untracking and ignore rules. */
    const val KEYSTORE_GIT_PATH = "$KEYSTORE_DIR/$KEYSTORE_FILE"
    const val ENCRYPTED_EXTENSION = ".gwv"

    fun keystorePath(root: Path): Path = Path(Path(root, KEYSTORE_DIR), KEYSTORE_FILE)

    fun keystoreExists(root: String): Boolean = SystemFileSystem.metadataOrNull(keystorePath(Path(root))) != null

    /** True when [fullPath] is the notebook root itself or a path under it. */
    fun isUnder(root: String, fullPath: String): Boolean {
        if (fullPath == root) return true
        return fullPath.startsWith("$root/") || fullPath.startsWith("$root\\")
    }

    /**
     * The canonical (NFC, `/`-separated) note path relative to the notebook root.
     * Empty for the root itself - callers that need a note use [requireNonEmpty].
     */
    fun relPathOf(root: String, fullPath: String): String {
        require(isUnder(root, fullPath)) { "Path '$fullPath' does not belong to notebook '$root'" }
        val relative = fullPath.removePrefix(root).trimStart('/', '\\').replace('\\', '/')
        return relative
    }

    /** The absolute virtual path for a relative note path, with native separators. */
    fun joinVirtual(root: String, relPath: String): String {
        var path = Path(root)
        for (segment in relPath.split('/')) path = Path(path, segment)
        return path.toString()
    }

    /**
     * The longest notebook directory that is a parent of [fullPath], or null when the path
     * belongs to no known notebook.
     */
    fun notebookRootOf(notebookPaths: Collection<String>, fullPath: String): String? =
        notebookPaths
            .filter { isUnder(it, fullPath) }
            .maxByOrNull { it.length }
}
