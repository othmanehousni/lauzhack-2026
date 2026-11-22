package ch.lauzhack.app.mole

import android.provider.Settings
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun systemReducesMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

// The manifest keeps the activity through uiMode changes, so the configuration, and this, update in place.
@Composable
internal actual fun systemDark() = isSystemInDarkTheme()

// Edge to edge, the bars are drawn over the paper. enableEdgeToEdge() sets their icons once, for the system theme, so a
// forced theme or a live change needs them set again.
@Composable
internal actual fun SystemBars(paper: Color) {
    val view = LocalView.current
    val light = paper.luminance() > .5f
    LaunchedEffect(view, light) {
        val bars = APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_NAVIGATION_BARS
        view.windowInsetsController?.setSystemBarsAppearance(if (light) bars else 0, bars)
    }
}
