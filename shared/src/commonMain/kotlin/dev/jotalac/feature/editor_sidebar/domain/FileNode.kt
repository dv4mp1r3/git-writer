package dev.jotalac.feature.editor_sidebar.domain


sealed class FileNode {
    abstract val name: String
    abstract val path: String

    data class File(
        override val name: String,
        override val path: String,
        /** The real file on disk for vault notes (path is the virtual decrypted path); null for plain notebooks. */
        val diskPath: String? = null,
    ) : FileNode()

    data class Directory(
        override val name: String,
        override val path: String,
        val children: List<FileNode>,
    ) : FileNode()
}

