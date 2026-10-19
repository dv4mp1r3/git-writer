package dev.jotalac.core.di

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test

class AppModulesTest {

    // checks that every class koin creates gets all of its constructor parameters - a missing definition
    // otherwise only shows up as a crash when the screen that needs it is opened
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `every dependency of the desktop app can be created`() {
        module { includes(appModules) }.verify(
            // single { HttpClient() } uses Ktor's factory function, which finds the platform's engine itself -
            // verify only sees the constructor of the HttpClient class, that takes these two
            extraTypes = listOf(HttpClientEngine::class, HttpClientConfig::class),
        )
    }
}
