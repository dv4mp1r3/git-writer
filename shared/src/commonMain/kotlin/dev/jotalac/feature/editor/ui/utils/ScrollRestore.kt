package dev.jotalac.feature.editor.ui.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Scrolls to [position] once the content is tall enough for it.
 *
 * A note does not have its final height in the first frame - images load asynchronously and start with
 * no height - and a [ScrollState] clamps its value to the current content height. Restoring the position
 * right away (`rememberScrollState(position)`) leaves the note scrolled only as far as that first, shorter
 * layout allowed.
 *
 * Gives up waiting after [timeout] (e.g. an image that never loads) and scrolls as far as it can, and
 * steps aside when the user starts scrolling in the meantime.
 */
suspend fun ScrollState.restoreScroll(position: Int, timeout: Duration = 2.seconds) {
    if (position <= 0) return

    val userScrolled = withTimeoutOrNull(timeout) {
        snapshotFlow { isScrollInProgress to maxValue }
            // maxValue is Int.MAX_VALUE until the content has been measured
            .first { (scrolling, max) -> scrolling || (max != Int.MAX_VALUE && max >= position) }
            .first
    } ?: false

    if (!userScrolled) scrollTo(position)
}

/**
 * Drops the scroll events that are still coming from a gesture made before this content was shown.
 *
 * A trackpad keeps sending scroll events for a moment after the fingers leave it (momentum). Switching tabs
 * during that time would scroll the note that was just opened - and make [restoreScroll] step aside, since
 * it looks like the user scrolling. Scroll events are dropped until they pause for [pause] (a new gesture
 * starts after a pause), at most for [maxDuration].
 */
@Composable
fun Modifier.ignoreLeftoverScroll(
    pause: Duration = 150.milliseconds,
    maxDuration: Duration = 2.seconds,
): Modifier {
    // when the content was shown - pointerInput only starts with the first event, too late to measure from
    val shownAt = remember { TimeSource.Monotonic.markNow() }

    return pointerInput(Unit) {
        var lastLeftover = shownAt
        awaitPointerEventScope {
            while (shownAt.elapsedNow() < maxDuration) {
                // Initial pass: before the scrollable below gets the event
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                if (lastLeftover.elapsedNow() >= pause) break

                event.changes.forEach { it.consume() }
                lastLeftover = TimeSource.Monotonic.markNow()
            }
        }
    }
}
