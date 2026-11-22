package ch.lauzhack.app.mole

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

@Composable
internal actual fun systemReducesMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

internal actual fun moleFont(size: Float, weight: Int, mono: Boolean): FontFamily = if (mono) FontFamily.Monospace else FontFamily.Default

internal actual fun trak(size: Float) = 0f
