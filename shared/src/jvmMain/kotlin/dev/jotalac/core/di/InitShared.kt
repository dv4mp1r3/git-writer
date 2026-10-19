package dev.jotalac.core.di

import dev.jotalac.core.utils.AppExitTasks
import io.github.vinceglb.filekit.FileKit
import org.koin.mp.KoinPlatform

fun initFileKitJvm(appId: String) {
    FileKit.init(appId)
}

/** Runs the work the app registered for its exit (AppExitTasks), e.g. the last sync with auto sync on. */
suspend fun runAppExitTasks() {
    KoinPlatform.getKoin().get<AppExitTasks>().runAll()
}
