package ch.lauzhack.app

import androidx.compose.ui.window.application

fun main() = application {
    MoleFloater(onQuit = ::exitApplication)
}
