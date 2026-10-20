@file:OptIn(ExperimentalForeignApi::class)

package dev.jotalac.feature.git_sync.data.libgit2

import cnames.structs.git_annotated_commit
import git2.GIT_CHECKOUT_OPTIONS_VERSION
import git2.GIT_CHECKOUT_SAFE
import git2.GIT_MERGE_ANALYSIS_FASTFORWARD
import git2.GIT_MERGE_ANALYSIS_UP_TO_DATE
import git2.GIT_OBJECT_COMMIT
import git2.git_annotated_commit_free
import git2.git_annotated_commit_from_ref
import git2.git_annotated_commit_id
import git2.git_checkout_options
import git2.git_checkout_options_init
import git2.git_checkout_tree
import git2.git_merge
import git2.git_merge_analysis
import git2.git_merge_analysis_tVar
import git2.git_merge_preference_tVar
import git2.git_object_free
import git2.git_object_lookup
import git2.git_oid
import git2.git_reference_create
import git2.git_reference_free
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

internal enum class MergeResult {
    /** The remote had nothing new. */
    UpToDate,

    /** The remote changes are in the current branch now. */
    Merged,

    /** The merge stopped at files it could not combine - see GitConflicts. */
    Conflicts,
}

/** Merges what the last fetch got from [remote] into the current branch - the second half of `git pull`. */
internal fun GitRepository.mergeFetched(remote: String): MergeResult = memScoped {
    val branch = currentBranch ?: throw GitException("HEAD is detached, there is no branch to merge into")
    // the remote does not have the branch (yet) - nothing to merge
    val upstream = findReference("refs/remotes/$remote/$branch") ?: return MergeResult.UpToDate

    val theirs = create(::git_annotated_commit_free) { git_annotated_commit_from_ref(it, handle, upstream) }
    val heads = allocArrayOf(theirs)
    val analysis = alloc<git_merge_analysis_tVar>()
    checkGit(git_merge_analysis(analysis.ptr, alloc<git_merge_preference_tVar>().ptr, handle, heads, 1u))

    when {
        analysis.value and GIT_MERGE_ANALYSIS_UP_TO_DATE != 0u -> MergeResult.UpToDate
        // nothing local that the remote does not have, also a new notebook without commits - only catch up
        analysis.value and GIT_MERGE_ANALYSIS_FASTFORWARD != 0u -> {
            fastForward(branch, git_annotated_commit_id(theirs) ?: throw GitException("the fetched commit has no id"))
            MergeResult.Merged
        }
        else -> merge(heads, message = "Merge $remote/$branch")
    }
}

private fun GitRepository.fastForward(branch: String, target: CPointer<git_oid>): Unit = memScoped {
    val commit = create(::git_object_free) { git_object_lookup(it, handle, target, GIT_OBJECT_COMMIT) }
    val options = alloc<git_checkout_options>()
    checkGit(git_checkout_options_init(options.ptr, GIT_CHECKOUT_OPTIONS_VERSION.convert()))
    options.checkout_strategy = GIT_CHECKOUT_SAFE

    // the files first: the checkout changes what differs from the commit the branch points at now
    checkGit(git_checkout_tree(handle, commit, options.ptr))
    create(::git_reference_free) { git_reference_create(it, handle, "refs/heads/$branch", target, 1, "pull: fast-forward") }
}

private fun GitRepository.merge(
    heads: CValuesRef<CPointerVar<git_annotated_commit>>,
    message: String,
): MergeResult {
    // writes conflict markers into the files it cannot combine
    checkGit(git_merge(handle, heads, 1u, null, null))
    if (hasConflicts()) return MergeResult.Conflicts

    commit(message)
    return MergeResult.Merged
}
