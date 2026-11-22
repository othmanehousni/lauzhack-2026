package ch.lauzhack.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.application
import ch.lauzhack.app.mole.Demo
import ch.lauzhack.app.mole.Playground

fun main(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    val demo = remember { Demo(scope) }
    LaunchedEffect(demo) { demo.play() }
    if ("--playground" in args) Playground(demo, onClose = ::exitApplication)
    else MoleFloater(demo.lines, demo::pick, onQuit = ::exitApplication)
}
