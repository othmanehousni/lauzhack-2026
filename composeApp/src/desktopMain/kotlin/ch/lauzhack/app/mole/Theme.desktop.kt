package ch.lauzhack.app.mole

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

@Composable
internal actual fun systemReducesMotion() = false

// Compose's isSystemInDarkTheme() on desktop reads the theme once per process, so this asks skiko again each second.
// ponytail: a poll of one NSUserDefaults read; observe AppleInterfaceThemeChangedNotification if Compose ever exposes it.
@Composable
internal actual fun systemDark(): Boolean {
    var dark by remember { mutableStateOf(currentSystemTheme == SystemTheme.DARK) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            dark = currentSystemTheme == SystemTheme.DARK
        }
    }
    return dark
}

@Composable
internal actual fun SystemBars(paper: Color) = Unit
