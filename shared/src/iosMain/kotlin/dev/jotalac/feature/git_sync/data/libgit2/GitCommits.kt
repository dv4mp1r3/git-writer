@file:OptIn(ExperimentalForeignApi::class)

package dev.jotalac.feature.git_sync.data.libgit2

import git2.GIT_ENOTFOUND
import git2.GIT_INDEX_ADD_DEFAULT
import git2.GIT_RESET_HARD
import git2.GIT_STATUS_OPTIONS_VERSION
import git2.GIT_STATUS_OPT_INCLUDE_UNTRACKED
import git2.GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS
import git2.GIT_STATUS_SHOW_INDEX_AND_WORKDIR
import git2.GIT_ITEROVER
import git2.git_commit_create
import git2.git_commit_free
import git2.git_commit_lookup
import git2.git_commit_message
import git2.git_commit_author
import git2.git_index_add_all
import git2.git_index_remove_bypath
import git2.git_index_update_all
import git2.git_index_write
import git2.git_index_write_tree
import git2.git_object_free
import git2.git_oid
import git2.git_repository_state_cleanup
import git2.git_reset
import git2.git_revparse_single
import git2.git_revwalk_free
import git2.git_revwalk_new
import git2.git_revwalk_next
import git2.git_revwalk_push_head
import git2.git_signature_default
import git2.git_signature_free
import git2.git_signature_now
import git2.git_status_list_entrycount
import git2.git_status_list_free
import git2.git_status_list_new
import git2.git_status_options
import git2.git_status_options_init
import git2.git_tree_free
import git2.git_tree_lookup
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString

/** Whether the working tree has anything to commit - new, edited or deleted files. */
internal fun GitRepository.hasChanges(): Boolean = memScoped {
    val options = alloc<git_status_options>()
    checkGit(git_status_options_init(options.ptr, GIT_STATUS_OPTIONS_VERSION.convert()))
    options.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR
    options.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED or GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS

    val status = create(::git_status_list_free) { git_status_list_new(it, handle, options.ptr) }
    git_status_list_entrycount(status) > 0u
}

/** Stages everything that changed in the working tree (`git add -A`), without committing. */
internal fun GitRepository.stageEverything(): Unit = memScoped {
    val index = index()
    // new and edited files, then the deleted ones
    checkGit(git_index_add_all(index, null, GIT_INDEX_ADD_DEFAULT, null, null))
    checkGit(git_index_update_all(index, null, null, null))
    checkGit(git_index_write(index))
}

/**
 * Removes [path] from the index while keeping the working-tree file (`git rm --cached`).
 * False when the path was not tracked.
 */
internal fun GitRepository.untrackPath(path: String): Boolean = memScoped {
    val index = index()
    val result = git_index_remove_bypath(index, path)
    if (result == GIT_ENOTFOUND) return@memScoped false
    checkGit(result)
    checkGit(git_index_write(index))
    true
}

/** Commits everything that changed in the working tree (`git add -A` and `git commit`). False when nothing did. */
internal fun GitRepository.commitAll(message: String): Boolean {
    if (!hasChanges()) return false
    stageEverything()
    commit(message)
    return true
}

/**
 * Commits what is staged on top of HEAD. During a merge the merged commit becomes the second parent, which
 * completes the merge.
 */
internal fun GitRepository.commit(message: String): Unit = memScoped {
    val treeId = alloc<git_oid>()
    checkGit(git_index_write_tree(treeId.ptr, index()))
    val tree = create(::git_tree_free) { git_tree_lookup(it, handle, treeId.ptr) }

    val merging = isMerging
    // none before the first commit
    val parents = listOfNotNull(findCommit("HEAD"), if (merging) findCommit("MERGE_HEAD") else null)

    // the user.name and user.email of the git config - the app sets none, so mostly the fallback
    val signature = lookup(::git_signature_free) { git_signature_default(it, handle) }
        ?: create(::git_signature_free) { git_signature_now(it, "Git Writer", "gitwriter@localhost") }

    val commitId = alloc<git_oid>()
    checkGit(
        git_commit_create(
            commitId.ptr, handle, "HEAD", signature, signature, null, message, tree,
            parents.size.convert(), allocArrayOf(parents),
        )
    )

    // the merge is done - forget its state (MERGE_HEAD, MERGE_MSG)
    if (merging) checkGit(git_repository_state_cleanup(handle))
}

/**
 * Commits what is staged as the only commit of [branch] - it has no parents, so the commits the branch
 * had before leave its history. The working tree keeps looking unchanged: it is what the commit was made from.
 */
internal fun GitRepository.commitOrphan(message: String, branch: String): Unit = memScoped {
    val treeId = alloc<git_oid>()
    checkGit(git_index_write_tree(treeId.ptr, index()))
    val tree = create(::git_tree_free) { git_tree_lookup(it, handle, treeId.ptr) }

    // the user.name and user.email of the git config - see commit()
    val signature = lookup(::git_signature_free) { git_signature_default(it, handle) }
        ?: create(::git_signature_free) { git_signature_now(it, "Git Writer", "gitwriter@localhost") }

    val commitId = alloc<git_oid>()
    checkGit(
        git_commit_create(
            commitId.ptr, handle, "refs/heads/$branch", signature, signature, null, message, tree,
            0u.convert(), null,
        )
    )
}

/** Throws away the changes in the working tree and the index, also a merge in progress (`git reset --hard`). */
internal fun GitRepository.resetHard(to: String = "HEAD"): Unit = memScoped {
    val head = create(::git_object_free) { git_revparse_single(it, handle, to) }
    checkGit(git_reset(handle, head, GIT_RESET_HARD, null))
}

/** The messages of the commits in HEAD's history, newest first (`git log`). */
internal fun GitRepository.commitMessages(): List<String> = memScoped {
    val walker = create(::git_revwalk_free) { git_revwalk_new(it, handle) }
    checkGit(git_revwalk_push_head(walker))

    val oid = alloc<git_oid>()
    buildList {
        while (true) {
            val result = git_revwalk_next(oid.ptr, walker)
            if (result == GIT_ITEROVER) break
            checkGit(result)
            val commit = create(::git_commit_free) { git_commit_lookup(it, handle, oid.ptr) }
            add(git_commit_message(commit)?.toKString()?.trim() ?: "")
        }
    }
}

/** The author name of the commit HEAD points at. */
internal fun GitRepository.lastCommitAuthorName(): String = memScoped {
    val walker = create(::git_revwalk_free) { git_revwalk_new(it, handle) }
    checkGit(git_revwalk_push_head(walker))

    val oid = alloc<git_oid>()
    checkGit(git_revwalk_next(oid.ptr, walker))
    val commit = create(::git_commit_free) { git_commit_lookup(it, handle, oid.ptr) }
    git_commit_author(commit)?.pointed?.name?.toKString() ?: ""
}
