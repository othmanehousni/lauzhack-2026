package ch.lauzhack.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToSvgPainter

private val Paper = Color(0xFFF2EDE4)
private val Ink = Color(0xFF1F1C1A)
private val Ember = Color(0xFFF2531C)

@Composable
internal fun ApplicationScope.MoleFloater(onQuit: () -> Unit) {
    val state = rememberWindowState(
        position = WindowPosition(Alignment.BottomCenter),
        width = 388.dp,
        height = 124.dp,
    )
    val resetPosition = { state.position = WindowPosition(Alignment.BottomCenter) }

    if (isTraySupported) {
        Tray(
            icon = MoleTrayIcon,
            tooltip = "The Mole",
            onAction = resetPosition,
            menu = {
                Item("Reset floater position", onClick = resetPosition)
                Separator()
                Item("Quit The Mole", onClick = onQuit)
            },
        )
    }

    Window(
        onCloseRequest = onQuit,
        state = state,
        title = "The Mole",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        // Keep the app underneath focused while the floater is visible or dragged.
        focusable = false,
    ) {
        ContextMenuArea(
            items = {
                listOf(
                    ContextMenuItem("Reset floater position", resetPosition),
                    ContextMenuItem("Quit The Mole", onQuit),
                )
            },
        ) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                WindowDraggableArea {
                    MoleBar()
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalResourceApi::class)
private fun MoleBar() {
    val density = LocalDensity.current
    val mole = remember(density) {
        requireNotNull(MoleTrayIcon.javaClass.getResourceAsStream("/mole-idle.svg")) {
            "Missing desktop resource: mole-idle.svg"
        }.use { it.readBytes().decodeToSvgPainter(density) }
    }
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = Modifier
            .size(width = 340.dp, height = 76.dp)
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = Ink.copy(alpha = 0.12f),
                spotColor = Ink.copy(alpha = 0.16f),
            )
            .background(Paper, shape)
            .border(1.dp, Ink.copy(alpha = 0.10f), shape)
            .padding(start = 10.dp, end = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = mole,
            contentDescription = "The Mole",
            modifier = Modifier
                .align(Alignment.Bottom)
                .padding(bottom = 9.dp)
                .size(width = 83.dp, height = 62.dp),
        )
        BasicText(
            text = "You're driving.",
            style = TextStyle(
                color = Ink,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-0.15).sp,
            ),
            maxLines = 1,
        )
    }
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
