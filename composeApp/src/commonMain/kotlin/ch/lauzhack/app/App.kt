package ch.lauzhack.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ch.lauzhack.app.mole.Demo
import ch.lauzhack.app.mole.MolePhone

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val demo = remember { Demo(scope) }
    LaunchedEffect(demo) { demo.play() }
    val line by demo.lines.collectAsState()
    MolePhone(line, onScreenName = "Market size", thumbnail = null, phoneLeads = false, meeting = "Home insurance review", onPick = demo::pick)
}
