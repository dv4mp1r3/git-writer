package dev.jotalac.feature.editor.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

// touch pinches and trackpad pinches (delivered as scale events) already reach transformable
@Composable
actual fun Modifier.trackpadPinchZoom(onZoom: (zoomChange: Float, focus: Offset) -> Unit): Modifier = this
