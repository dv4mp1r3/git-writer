package dev.jotalac.core.di

import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

/*
* DI graph smoke test. Guards against top-level property initialization cycles: coreModule
* includes platformModule at construction time, and the platform modules read gitSyncBase -
* when both files' class initializers interleave, a module or a qualifier is silently read
* as null and the definition registers under the wrong key (or not at all), which only
* surfaces as a runtime NoDefinitionFoundException on app start.
* */
class KoinGraphTest {

    @Test
    fun `core module registers the qualified platform git implementation`() {
        val koin = koinApplication { modules(coreModule) }.koin
        assertNotNull(koin.get<GitSyncRepository>(qualifier = gitSyncBase))
    }

    @Test
    fun `the full app graph registers the qualified platform git implementation`() {
        val koin = koinApplication { modules(appModules) }.koin
        assertNotNull(koin.get<GitSyncRepository>(qualifier = gitSyncBase))
    }
}
