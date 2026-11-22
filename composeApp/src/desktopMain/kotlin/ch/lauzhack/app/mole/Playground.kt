package ch.lauzhack.app.mole

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import ch.lauzhack.app.MoleState

private val WALLS = listOf(Color(0xFF141416), Color(0xFFF2531C), Color(0xFF1F1C1A), Color.White, Color(0xFFDFE6DC)) // index.html's wallpapers, not themed

/** `--playground`: every state one click away, on the bar and on the phone, with slow-mo, reduced motion and the theme. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApplicationScope.Playground(demo: Demo, onClose: () -> Unit) {
    var slow by remember { mutableStateOf(false) }
    var reduce by remember { mutableStateOf(false) }
    var phoneLeads by remember { mutableStateOf(false) }
    var wall by remember { mutableStateOf(4) }
    var theme by remember { mutableStateOf(MoleTheme.System) }
    val line by demo.lines.collectAsState()
    Window(onCloseRequest = onClose, title = "Molière playground", state = rememberWindowState(size = DpSize(1120.dp, 820.dp))) {
        // The page runs under a clear title bar, so the window's chrome takes the theme with it.
        LaunchedEffect(window) {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        val c = theme.colors()
        CompositionLocalProvider(LocalMoleColors provides c, LocalReduceMotion provides reduce, LocalSlowMotion provides if (slow) 5f else 1f) {
            Row(Modifier.fillMaxSize().background(c.page).padding(start = 32.dp, top = 40.dp, end = 32.dp, bottom = 32.dp), Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f), Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in MoleState.entries) Chip(s.name.lowercase(), line == Samples[s]) { demo.show(Samples.getValue(s)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, l) in Choices) Chip(label, line == l) { demo.show(l) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Play story", false) { demo.play() }
                        Chip("Slow-mo", slow) { slow = !slow }
                        Chip("Reduced motion", reduce) { reduce = !reduce }
                        Chip("Walk away", phoneLeads) { phoneLeads = !phoneLeads }
                        Chip("Wallpaper", false) { wall = (wall + 1) % WALLS.size }
                        Chip("Theme: ${theme.name}", false) { theme = MoleTheme.entries[(theme.ordinal + 1) % MoleTheme.entries.size] }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(WALLS[wall]), Alignment.BottomCenter) {
                        MoleBar(line, demo::pick, Modifier.padding(bottom = 24.dp), theme)
                    }
                }
                Box(Modifier.size(300.dp, 650.dp).background(c.device, RoundedCornerShape(46.dp)).padding(8.dp).clip(RoundedCornerShape(38.dp))) {
                    MolePhone(line, "Market size", null, phoneLeads, "Home insurance review", demo::pick, theme = theme)
                }
            }
        }
    }
}

// index.html's .controls button.
@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalMoleColors.current
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) c.ink else c.fill).clickable(onClick = onClick).padding(horizontal = 12.dp),
        Alignment.Center,
    ) { BasicText(label, style = type(13f, 13f, 500, color = if (on) c.paper else c.ink)) }
}
