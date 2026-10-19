package dev.jotalac.core.utils

/**
 * Work to finish before the desktop app exits, e.g. the editor's last sync - the desktop window waits for it
 * when it is closed (runAppExitTasks). Mobile apps get no such moment, they sync when going to the background.
 */
class AppExitTasks {
    private val tasks = mutableMapOf<Any, suspend () -> Unit>()

    fun register(owner: Any, task: suspend () -> Unit) {
        tasks[owner] = task
    }

    fun unregister(owner: Any) {
        tasks.remove(owner)
    }

    // one failing task must not keep the others from running
    suspend fun runAll() {
        tasks.values.toList().forEach { task -> suspendRunCatching { task() } }
    }
}
