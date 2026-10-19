package dev.jotalac.core.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CoroutineUtilsTest {

    @Test
    fun `an exception becomes a failed result`() {
        val result = suspendRunCatching { error("disk full") }

        assertEquals("disk full", result.exceptionOrNull()?.message)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancelled coroutine stops instead of carrying on with a failed result`() = runTest {
        // the reason it exists - runCatching would catch the cancellation and let the coroutine continue,
        // e.g. a note load for a tab that was already closed would still update the editor
        var continuedAfterCancel = false
        val job = launch {
            suspendRunCatching { awaitCancellation() }
            continuedAfterCancel = true
        }
        advanceUntilIdle()

        job.cancel()
        job.join()

        assertFalse(continuedAfterCancel)
    }
}
