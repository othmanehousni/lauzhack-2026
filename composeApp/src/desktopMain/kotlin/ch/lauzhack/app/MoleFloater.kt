package ch.lauzhack.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import ch.lauzhack.app.mole.MoleBar
import ch.lauzhack.app.mole.MoleColors
import ch.lauzhack.app.mole.MoleTheme
import ch.lauzhack.app.mole.colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil

private val Paper = MoleColors.Light.paper
private val Ink = MoleColors.Light.ink
private val Ember = MoleColors.Light.ember
private const val RING = 1 // dp of hairline ring around the bar

@Composable
internal fun ApplicationScope.MoleFloater(lines: StateFlow<MoleLine>, onPick: (Int) -> Unit, onQuit: () -> Unit, theme: MoleTheme = MoleTheme.System) {
    val line by lines.collectAsState()
    val hidden = line.state == MoleState.Hidden
    // The window goes away once the bar's exit (180 ms wait + 260 ms) is over, so nothing is left to catch clicks.
    var visible by remember { mutableStateOf(!hidden) }
    LaunchedEffect(hidden) {
        if (hidden) delay(460)
        visible = !hidden
    }
    val state = rememberWindowState(
        position = WindowPosition(Alignment.BottomCenter),
        size = DpSize((340 + 2 * RING).dp, (76 + 2 * RING).dp),
    )
    val resetPosition = { state.position = WindowPosition(Alignment.BottomCenter) }

    if (isTraySupported) {
        Tray(
            icon = MoleTrayIcon,
            tooltip = "Molière",
            onAction = resetPosition,
            menu = {
                Item("Reset floater position", onClick = resetPosition)
                Separator()
                Item("Quit Molière", onClick = onQuit)
            },
        )
    }

    Window(
        onCloseRequest = onQuit,
        state = state,
        visible = visible,
        title = "Molière",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        // Keep the app underneath focused while the floater is visible or dragged.
        focusable = false,
    ) {
        // macOS computes a transparent window's shadow once, so it would outline the bar as it was, not as it is.
        LaunchedEffect(window) { window.rootPane.putClientProperty("Window.shadow", false) }
        val menu = if (theme.colors() == MoleColors.Dark) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation
        CompositionLocalProvider(LocalContextMenuRepresentation provides menu) {
            ContextMenuArea(
                items = {
                    listOf(
                        ContextMenuItem("Reset floater position", resetPosition),
                        ContextMenuItem("Quit Molière", onQuit),
                    )
                },
            ) {
                Box(Modifier.fillMaxSize().padding(RING.dp), contentAlignment = Alignment.BottomCenter) {
                    WindowDraggableArea {
                        MoleBar(line, onPick, Modifier.onSizeChanged { window.hug(it) }, theme)
                    }
                }
            }
        }
    }
}

// The window is the bar and its ring, no more, so clicks around the bar reach the app below. The bar is anchored at its
// bottom edge, so as the options open the window grows upward.
private fun ComposeWindow.hug(bar: IntSize) {
    val scale = graphicsConfiguration.defaultTransform.scaleY
    val w = ceil(bar.width / scale).toInt() + 2 * RING
    val h = ceil(bar.height / scale).toInt() + 2 * RING
    if (w != width || h != height) setBounds(x, y + height - h, w, h)
}

private object MoleTrayIcon : Painter() {
    override val intrinsicSize = Size(24f, 24f)

    override fun DrawScope.onDraw() {
        val unit = size.minDimension / 24f
        drawRoundRect(
            color = Ember,
            cornerRadius = CornerRadius(7f * unit),
        )
        drawOval(
            color = Ink,
            topLeft = Offset(4f * unit, 7f * unit),
            size = Size(16f * unit, 14f * unit),
        )
        drawLine(
            color = Paper,
            start = Offset(8f * unit, 11f * unit),
            end = Offset(17f * unit, 9f * unit),
            strokeWidth = 1.5f * unit,
            cap = StrokeCap.Round,
        )
        for ((x, y, radius) in listOf(Triple(8f, 11f, 3.1f), Triple(17f, 9f, 2.5f))) {
            drawCircle(Paper, radius * unit, Offset(x * unit, y * unit))
            drawCircle(Ink, radius * unit, Offset(x * unit, y * unit), style = Stroke(unit))
        }
    }
}
