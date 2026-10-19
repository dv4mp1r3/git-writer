package dev.jotalac.core.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jotalac.core.utils.isDesktopPlatform
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Material tooltip with [text] for [content], shown after hovering it for [delayTime] on desktop - Material's
 * own hover shows it at once (and TooltipArea, which has a delay, is desktop only) - and on long press on
 * mobile.
 */
@Composable
fun DelayedTooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayTime: Duration = 1.seconds,
    anchorPosition: TooltipAnchorPosition = TooltipAnchorPosition.Above,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tooltipState = rememberTooltipState()

    // leaving earlier cancels the wait, leaving later cancels show(), which hides the tooltip again
    if (isDesktopPlatform) {
        LaunchedEffect(isHovered, delayTime) {
            if (isHovered) {
                delay(delayTime)
                // UserInput: stays while hovered, the default priority would hide it after 1.5 s
                tooltipState.show(MutatePriority.UserInput)
            }
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(anchorPosition, 10.dp),
        tooltip = { PlainTooltip { Text(text) } },
        state = tooltipState,
        // hover is watched here, so the content does not have to pass its interaction source in
        modifier = modifier.hoverable(interactionSource),
        // on desktop shown by the hover above, on mobile by Material's long press
        enableUserInput = !isDesktopPlatform,
        content = content,
    )
}
