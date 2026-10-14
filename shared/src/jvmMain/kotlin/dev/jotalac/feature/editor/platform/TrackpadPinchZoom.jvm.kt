package dev.jotalac.feature.editor.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.jotalac.core.ui.window.LocalDesktopAwtWindow
import dev.jotalac.core.utils.isMacOsPlatform
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.swing.JComponent
import javax.swing.RootPaneContainer

// Windows touchpads send pinches as ctrl + scroll, which transformable already zooms; Linux AWT has no pinch
@Composable
actual fun Modifier.trackpadPinchZoom(onZoom: (zoomChange: Float, focus: Offset) -> Unit): Modifier {
    val rootPane = (LocalDesktopAwtWindow.current as? RootPaneContainer)?.rootPane
    if (!isMacOsPlatform || rootPane == null) return this

    val currentOnZoom by rememberUpdatedState(onZoom)
    // pinches are delivered for the whole window and without a position - only the hovered composable
    // zooms, around the pointer
    var pointerPosition by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(rootPane) {
        val listener = MacPinchGestures.add(rootPane) { zoomChange ->
            pointerPosition?.let { currentOnZoom(zoomChange, it) }
        }
        onDispose { MacPinchGestures.remove(rootPane, listener) }
    }

    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // only observing, the events are left for the other handlers
                val event = awaitPointerEvent()
                pointerPosition = event.changes.firstOrNull()
                    ?.takeIf { event.type != PointerEventType.Exit }
                    ?.position
            }
        }
    }
}

/*
* The JDK hands macOS trackpad pinches to com.apple.eawt.event listeners. The package is internal (needs
* `--add-exports`, set for every way of running the app in desktopApp/build.gradle.kts) and missing on
* other JDKs, so it is used via reflection.
* */
private object MacPinchGestures {
    private const val PACKAGE = "com.apple.eawt.event"

    // turned off after the first failure, so a missing flag is reported once, not for every image
    private var isAvailable = true

    // returns the listener (needed to remove it), null when pinch gestures are unavailable
    fun add(component: JComponent, onPinch: (zoomChange: Float) -> Unit): Any? {
        if (!isAvailable) return null

        return runCatching {
            val listenerType = Class.forName("$PACKAGE.MagnificationListener")
            val getMagnification = Class.forName("$PACKAGE.MagnificationEvent").getMethod("getMagnification")

            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    // magnification is the change, e.g. 0.05 = 5 % bigger
                    "magnify" -> onPinch(1f + (getMagnification.invoke(args[0]) as Double).toFloat())
                    // the JDK looks the listener up when removing it
                    "equals" -> return@newProxyInstance proxy === args[0]
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                }
                null
            }
            gestureUtilities("addGestureListenerTo").invoke(null, component, listener)
            listener
        }.onFailure {
            isAvailable = false
            println(
                "Trackpad pinch zoom unavailable - the JVM needs " +
                    "--add-exports=java.desktop/$PACKAGE=ALL-UNNAMED ($it)"
            )
        }.getOrNull()
    }

    fun remove(component: JComponent, listener: Any?) {
        if (listener == null) return
        runCatching { gestureUtilities("removeGestureListenerFrom").invoke(null, component, listener) }
    }

    private fun gestureUtilities(name: String): Method = Class.forName("$PACKAGE.GestureUtilities")
        .getMethod(name, JComponent::class.java, Class.forName("$PACKAGE.GestureListener"))
}
