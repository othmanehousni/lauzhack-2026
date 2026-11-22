package ch.lauzhack.app.mole

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import kotlinx.coroutines.delay
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme
import java.io.File

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

private fun load(path: String) = File(path).takeIf { it.exists() }?.let { FontMgr.default.makeFromFile(it.path, 0) }
private val sfns by lazy { load("/System/Library/Fonts/SFNS.ttf") }
private val sfMono by lazy { load("/System/Library/Fonts/SFNSMono.ttf") }
private val faces = mutableMapOf<Triple<Float, Int, Boolean>, FontFamily>()

// Chrome draws -apple-system from SFNS.ttf with its optical size set to the CSS size. Compose caches file fonts by path
// and weight only, so it would keep the first optical size it loaded; each size and weight gets its own clone instead.
internal actual fun moleFont(size: Float, weight: Int, mono: Boolean): FontFamily = faces.getOrPut(Triple(size, weight, mono)) {
    val base = (if (mono) sfMono else sfns) ?: return@getOrPut if (mono) FontFamily.Monospace else FontFamily.SansSerif
    val face = base.makeClone(arrayOf(FontVariation("wght", weight.toFloat()), FontVariation("opsz", size.coerceIn(17f, 96f))), 0)
    FontFamily(Typeface(face, "mole-${if (mono) "mono" else "sans"}-$size-$weight"))
}

// SFNS.ttf's trak table, normal track: font units (of 2048 per em) added to each advance at these point sizes. CoreText,
// and so Chrome, applies it; Skia does not.
private val TRAK_SIZES = floatArrayOf(6f, 9f, 10f, 11f, 12f, 13f, 14f, 15f, 16f, 17f, 20f, 22f, 24f, 28f, 32f, 36f, 50f, 64f, 80f, 100f, 138f)
private val TRAK = floatArrayOf(82f, 38f, 24f, 12f, 0f, -12f, -22f, -32f, -40f, -52f, -46f, -24f, 6f, 28f, 26f, 21f, 14f, 7f, 0f, 0f, 0f)

internal actual fun trak(size: Float): Float {
    if (sfns == null) return 0f
    val i = TRAK_SIZES.indexOfFirst { it >= size }
    val units = when {
        i < 0 -> TRAK.last()
        i == 0 -> TRAK[0]
        else -> TRAK[i - 1] + (size - TRAK_SIZES[i - 1]) / (TRAK_SIZES[i] - TRAK_SIZES[i - 1]) * (TRAK[i] - TRAK[i - 1])
    }
    return units / 2048f * size
}
