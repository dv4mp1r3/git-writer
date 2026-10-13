package dev.jotalac

import android.app.Application
import android.util.Log
import dev.jotalac.core.di.initFileKitAndroid
import dev.jotalac.core.di.initKoinAndroid
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainApplication : Application() {
    // failures of background work (e.g. widget refresh) are only logged, they must not crash the app
    private val appScopeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("MainApplication", "Uncaught exception in appScope", throwable)
    }

    // outlives activities - for work that must finish even when the activity is destroyed
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + appScopeExceptionHandler)

    override fun onCreate() {
        super.onCreate()

        initKoinAndroid(this.applicationContext)

    }
}
