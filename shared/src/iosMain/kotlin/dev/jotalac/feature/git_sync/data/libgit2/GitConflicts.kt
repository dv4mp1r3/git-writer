@file:OptIn(ExperimentalForeignApi::class)

package dev.jotalac.feature.git_sync.data.libgit2

import git2.GIT_ITEROVER
import git2.git_blob_free
import git2.git_blob_lookup
import git2.git_blob_rawcontent
import git2.git_blob_rawsize
import git2.git_index_add_bypath
import git2.git_index_conflict_get
import git2.git_index_conflict_iterator_free
import git2.git_index_conflict_iterator_new
import git2.git_index_conflict_next
import git2.git_index_entry
import git2.git_index_has_conflicts
import git2.git_index_remove_bypath
import git2.git_index_write
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/*
* A merge that cannot combine a file leaves it "in conflict": the index keeps the common ancestor, our
* (local) and their (remote) version of it, and the file in the working tree gets conflict markers.
* */

/** Whether a merge left files it could not combine. */
internal fun GitRepository.hasConflicts(): Boolean = memScoped {
    git_index_has_conflicts(index()) != 0
}

/** The files a merge could not combine, relative to the working tree. */
internal fun GitRepository.conflictedFiles(): Set<String> = memScoped {
    val iterator = create(::git_index_conflict_iterator_free) { git_index_conflict_iterator_new(it, index()) }
    val ancestor = alloc<CPointerVar<git_index_entry>>()
    val ours = alloc<CPointerVar<git_index_entry>>()
    val theirs = alloc<CPointerVar<git_index_entry>>()

    buildSet {
        while (true) {
            val result = git_index_conflict_next(ancestor.ptr, ours.ptr, theirs.ptr, iterator)
            if (result == GIT_ITEROVER) break
            checkGit(result)

            // a side that deleted the file has no entry
            val entry = ours.value ?: theirs.value ?: ancestor.value ?: continue
            add(entry.pointed.path?.toKString() ?: continue)
        }
    }
}

/**
 * Resolves the conflict of [path] with the local or the remote version: the file gets that version and is
 * staged as resolved. When the kept side deleted the file, it is deleted.
 */
internal fun GitRepository.resolveConflict(path: String, keepLocalChanges: Boolean): Unit = memScoped {
    val index = index()
    val ancestor = alloc<CPointerVar<git_index_entry>>()
    val ours = alloc<CPointerVar<git_index_entry>>()
    val theirs = alloc<CPointerVar<git_index_entry>>()
    checkGit(git_index_conflict_get(ancestor.ptr, ours.ptr, theirs.ptr, index, path))

    val kept = if (keepLocalChanges) ours.value else theirs.value
    val file = Path(workdir, path)
    // staging the file (or its deletion) also marks the conflict resolved
    if (kept == null) {
        SystemFileSystem.delete(file, mustExist = false)
        checkGit(git_index_remove_bypath(index, path))
    } else {
        val blob = create(::git_blob_free) { git_blob_lookup(it, handle, kept.pointed.id.ptr) }
        val content = git_blob_rawcontent(blob)?.readBytes(git_blob_rawsize(blob).toInt()) ?: ByteArray(0)
        SystemFileSystem.sink(file).buffered().use { it.write(content) }
        checkGit(git_index_add_bypath(index, path))
    }
    checkGit(git_index_write(index))
}
