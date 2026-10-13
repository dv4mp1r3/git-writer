package dev.jotalac.feature.editor.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import coil3.compose.rememberAsyncImagePainter
import dev.jotalac.feature.editor.platform.trackpadPinchZoom
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.TransformableState
import io.github.vinceglb.filekit.PlatformFile
import kotlin.math.exp

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
private const val ZOOM_SPEED = 0.01f

// image the can be zoomed and moved around
@Composable
fun ZoomableImage(
    imageFile: PlatformFile,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Inside,
) {
    val painter = rememberAsyncImagePainter(imageFile)
    var zoom by remember { mutableFloatStateOf(MIN_ZOOM) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }

    // opening another image starts it fitted again
    LaunchedEffect(imageFile) {
        zoom = MIN_ZOOM
        pan = Offset.Zero
    }

    fun panBounds(): Offset {
        val drawn = painter.intrinsicSize.fittedInto(viewport, contentScale)

        return Offset(
            x = ((zoom * drawn.width - viewport.width) / 2f).coerceAtLeast(0f),
            y = ((zoom * drawn.height - viewport.height) / 2f).coerceAtLeast(0f),
        )
    }

    fun moveTo(x: Float, y: Float) {
        val bounds = panBounds()
        pan = Offset(x.coerceIn(-bounds.x, bounds.x), y.coerceIn(-bounds.y, bounds.y))
    }

    // zooms keeping the image point under focus (cursor / fingers) in place
    fun zoomBy(factor: Float, focus: Offset) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // graphicsLayer scales around the center, so the focus is measured from it
        val focusFromCenter = focus - viewport.center
        val newPan = focusFromCenter - (focusFromCenter - pan) * (newZoom / zoom)

        zoom = newZoom
        moveTo(newPan.x, newPan.y)
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewport = it.toSize() }
            .clipToBounds()
            .pointerInput(painter) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue

                        // ctrl + scroll (also Windows touchpad pinch) is already zoomed and consumed by
                        // transformable below, which gets the event first
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.isConsumed || change.scrollDelta.y == 0f) continue

                        zoomBy(exp(-change.scrollDelta.y * ZOOM_SPEED), change.position)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .trackpadPinchZoom { zoomChange, focus -> zoomBy(zoomChange, focus) }
            .transformable(
                remember {
                    TransformableState { centroid, zoomChange, panChange, rotationChange ->
                        zoomBy(zoomChange, centroid)
                        moveTo(pan.x + panChange.x, pan.y + panChange.y)
                    }
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        zoom = MIN_ZOOM
                        pan = Offset.Zero
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        )
    }
}

/** The size the image is drawn at, which is what the pan bounds have to be measured against. */
private fun Size.fittedInto(viewport: Size, contentScale: ContentScale): Size {
    if (!isSpecified || viewport == Size.Zero || !viewport.isSpecified) return viewport

    val fit = when (contentScale) {
        // these fill the view, so the view itself is the drawn size
        ContentScale.Crop, ContentScale.FillBounds -> return viewport
        // never enlarged past its own size
        ContentScale.Inside -> minOf(1f, viewport.width / width, viewport.height / height)
        else -> minOf(viewport.width / width, viewport.height / height)
    }
    return Size(width * fit, height * fit)
}
