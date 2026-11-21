package ch.lauzhack.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "LauzHack 2026") {
        App()
    }
}
