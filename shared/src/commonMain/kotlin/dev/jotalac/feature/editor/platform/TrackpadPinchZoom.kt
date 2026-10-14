package dev.jotalac.feature.editor.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

// pinch-to-zoom on desktop trackpad (compose multiplatfrom doesn't provide this by any way)
@Composable
expect fun Modifier.trackpadPinchZoom(onZoom: (zoomChange: Float, focus: Offset) -> Unit): Modifier
