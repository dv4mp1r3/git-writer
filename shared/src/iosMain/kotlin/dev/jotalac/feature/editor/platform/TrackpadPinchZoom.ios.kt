package dev.jotalac.feature.editor.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

// touch pinches already reach transformable (iPad trackpad pinches are not delivered by Compose)
@Composable
actual fun Modifier.trackpadPinchZoom(onZoom: (zoomChange: Float, focus: Offset) -> Unit): Modifier = this
