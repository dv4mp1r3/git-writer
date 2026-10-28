@file:OptIn(ExperimentalForeignApi::class)

package dev.jotalac.feature.git_sync.data.libgit2

import cnames.structs.git_credential
import dev.jotalac.core.domain.ProxyConfig
import dev.jotalac.core.domain.ProxyProtocol
import git2.GIT_CLONE_OPTIONS_VERSION
import git2.GIT_DIRECTION_FETCH
import git2.GIT_FETCH_OPTIONS_VERSION
import git2.GIT_PROXY_OPTIONS_VERSION
import git2.GIT_PROXY_SPECIFIED
import git2.GIT_PUSH_OPTIONS_VERSION
import git2.GIT_REMOTE_CALLBACKS_VERSION
import git2.git_clone
import git2.git_clone_options
import git2.git_clone_options_init
import git2.git_credential_userpass_plaintext_new
import git2.git_fetch_options
import git2.git_fetch_options_init
import git2.git_proxy_options
import git2.git_proxy_options_init
import git2.git_push_options
import git2.git_push_options_init
import git2.git_remote_callbacks
import git2.git_remote_connect
import git2.git_remote_create_anonymous
import git2.git_remote_fetch
import git2.git_remote_free
import git2.git_remote_init_callbacks
import git2.git_remote_lookup
import git2.git_remote_push
import git2.git_strarray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.getPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString

/*
* Everything that talks to the server. libgit2 asks for credentials, and reports what the server did with a
* push, through C callbacks - see withRemoteCallbacks.
* */

/**
 * The username and the password or token for the remote. Without a username the token is sent as the
 * username too, like the JGit implementation does - git hosts accept a token in either field.
 */
internal class GitCredentials(username: String?, val password: String) {
    val username: String = username?.takeIf { it.isNotBlank() } ?: password
}

/** Checks that [url] can be reached with [credentials], without downloading anything (`git ls-remote`). */
internal fun checkRemoteAccess(url: String, credentials: GitCredentials, proxy: ProxyConfig? = null): Unit = memScoped {
    val remote = create(::git_remote_free) { git_remote_create_anonymous(it, null, url) }
    val callbacks = alloc<git_remote_callbacks>()
    checkGit(git_remote_init_callbacks(callbacks.ptr, GIT_REMOTE_CALLBACKS_VERSION.convert()))
    val proxyOptions = allocProxyOptions(proxy)

    // connecting already signs in and lists the remote's branches
    withRemoteCallbacks(callbacks, credentials) {
        withProxyCredentials(proxyOptions, proxy) {
            git_remote_connect(remote, GIT_DIRECTION_FETCH, callbacks.ptr, proxyOptions?.ptr, null)
        }
    }
}

internal fun GitRepository.Companion.clone(
    url: String,
    path: String,
    credentials: GitCredentials,
    proxy: ProxyConfig? = null,
): GitRepository = memScoped {
    val options = alloc<git_clone_options>()
    checkGit(git_clone_options_init(options.ptr, GIT_CLONE_OPTIONS_VERSION.convert()))
    setupProxyOptions(options.fetch_opts.proxy_opts, proxy)

    openWith { out ->
        withRemoteCallbacks(options.fetch_opts.callbacks, credentials) {
            withProxyCredentials(options.fetch_opts.proxy_opts, proxy) { git_clone(out, url, path, options.ptr) }
        }
    }
}

/** Downloads what is new on [remote] (`git fetch`). */
internal fun GitRepository.fetch(remote: String, credentials: GitCredentials, proxy: ProxyConfig? = null): Unit = memScoped {
    val remoteHandle = create(::git_remote_free) { git_remote_lookup(it, handle, remote) }
    val options = alloc<git_fetch_options>()
    checkGit(git_fetch_options_init(options.ptr, GIT_FETCH_OPTIONS_VERSION.convert()))
    setupProxyOptions(options.proxy_opts, proxy)

    withRemoteCallbacks(options.callbacks, credentials) {
        withProxyCredentials(options.proxy_opts, proxy) { git_remote_fetch(remoteHandle, null, options.ptr, null) }
    }
}

/** Uploads the current branch to the branch of the same name on [remote] (`git push`, force overwrites the remote history). */
internal fun GitRepository.push(
    remote: String,
    credentials: GitCredentials,
    force: Boolean = false,
    proxy: ProxyConfig? = null,
): Unit = memScoped {
    val branch = currentBranch ?: throw GitException("HEAD is detached, there is no branch to push")
    val remoteHandle = create(::git_remote_free) { git_remote_lookup(it, handle, remote) }
    val options = alloc<git_push_options>()
    checkGit(git_push_options_init(options.ptr, GIT_PUSH_OPTIONS_VERSION.convert()))
    setupProxyOptions(options.proxy_opts, proxy)
    val refspecs = alloc<git_strarray> {
        strings = allocArrayOf("${if (force) "+" else ""}refs/heads/$branch:refs/heads/$branch".cstr.ptr)
        count = 1u
    }

    withRemoteCallbacks(options.callbacks, credentials) {
        withProxyCredentials(options.proxy_opts, proxy) { git_remote_push(remoteHandle, refspecs.ptr, options.ptr) }
    }
}

// --- proxy

/**
 * Fills the already-initialized [target] to route the operation through [proxy]; without one the struct
 * keeps its default (no proxy). The libgit2 builtin HTTP client tunnels through CONNECT, so only HTTP
 * proxies are possible here.
 */
private fun MemScope.setupProxyOptions(target: git_proxy_options, proxy: ProxyConfig?) {
    if (proxy == null) return
    if (proxy.protocol != ProxyProtocol.HTTP) throw GitException("SOCKS proxies are not supported on iOS")
    target.type = GIT_PROXY_SPECIFIED
    // the C string lives in this memScope - the git call using it happens inside the same scope
    target.url = proxy.toProxyUrl().cstr.getPointer(this)
}

/** A standalone proxy options struct for [git_remote_connect], which validates the version field - null means none. */
private fun MemScope.allocProxyOptions(proxy: ProxyConfig?): git_proxy_options? {
    if (proxy == null) return null
    if (proxy.protocol != ProxyProtocol.HTTP) throw GitException("SOCKS proxies are not supported on iOS")
    val options = alloc<git_proxy_options>()
    checkGit(git_proxy_options_init(options.ptr, GIT_PROXY_OPTIONS_VERSION.convert()))
    setupProxyOptions(options, proxy)
    return options
}

/** Answers the proxy's authentication prompt with the proxy credentials for the duration of [call]. */
private inline fun <T> withProxyCredentials(target: git_proxy_options?, proxy: ProxyConfig?, call: () -> T): T {
    if (target == null || proxy == null || (proxy.username.isBlank() && proxy.password.isBlank())) return call()
    val state = CallbackState(GitCredentials(proxy.username, proxy.password))
    val payload = StableRef.create(state)
    try {
        target.credentials = credentialsCallback
        target.payload = payload.asCPointer()
        return call()
    } finally {
        payload.dispose()
    }
}

// --- callbacks

/** What the C callbacks of one network operation share with it - handed to them as their payload. */
private class CallbackState(val credentials: GitCredentials) {
    var credentialsSent = false
    var credentialsRejected = false
    val rejectedPushes = mutableListOf<String>()
}

/**
 * Runs the network operation [call] with [callbacks] answering for [credentials]. Also turns into errors what
 * libgit2 reports only to the callbacks: rejected credentials, and branches the server refused to update.
 */
private inline fun withRemoteCallbacks(
    callbacks: git_remote_callbacks,
    credentials: GitCredentials,
    call: () -> Int,
): Int {
    val state = CallbackState(credentials)
    val payload = StableRef.create(state)
    try {
        callbacks.credentials = credentialsCallback
        callbacks.push_update_reference = pushUpdateCallback
        callbacks.payload = payload.asCPointer()

        val result = call()
        if (result < 0 && state.credentialsRejected) {
            throw GitException("Authentication failed - check the username and the token")
        }
        checkGit(result)
        if (state.rejectedPushes.isNotEmpty()) {
            throw GitException("Push rejected: ${state.rejectedPushes.joinToString()}")
        }
        return result
    } finally {
        payload.dispose()
    }
}

// asked once per operation - asked again means the server did not accept them, so stop instead of retrying
private val credentialsCallback = staticCFunction {
        out: CPointer<CPointerVar<git_credential>>?, _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: UInt,
        payload: COpaquePointer? ->
    val state = payload!!.asStableRef<CallbackState>().get()
    if (state.credentialsSent) {
        state.credentialsRejected = true
        -1
    } else {
        state.credentialsSent = true
        git_credential_userpass_plaintext_new(out, state.credentials.username, state.credentials.password)
    }
}

// called for every pushed branch - with a status message when the server refused to update it
private val pushUpdateCallback = staticCFunction {
        refName: CPointer<ByteVar>?, status: CPointer<ByteVar>?, payload: COpaquePointer? ->
    if (status != null) {
        payload!!.asStableRef<CallbackState>().get().rejectedPushes += "${refName?.toKString()} (${status.toKString()})"
    }
    0
}
