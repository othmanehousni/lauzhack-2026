package ch.lauzhack.app.mole

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import lauzhack2026.composeapp.generated.resources.InterVariable
import lauzhack2026.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.Font
import kotlin.math.roundToInt

/** Light or dark: [System] follows the OS, like mole.css with no data-theme; [Light] and [Dark] force one, like data-theme. */
enum class MoleTheme { System, Light, Dark }

/**
 * The :root tokens of mole/mole.css and the phone's of index.html, by role, in sRGB. [Light] and [Dark] are the two sides
 * of each light-dark(); MoleColorsTest holds them to the CSS.
 */
data class MoleColors(
    val paper: Color, // the bar and the phone's screen
    val ink: Color, // words, focus rings, a picked key
    val figure: Color, // the mole, its molehill, dirt and ground line
    val bridge: Color,
    val tie: Color,
    val tieFold: Color,
    val tieKnot: Color,
    val ember: Color, // the one accent, a signal red: the mole needs you
    val page: Color,
    val device: Color, // the phone's body, in the playground
    val outline: Color, // the thumbnail's edge
) {
    val lens = Color.White // white paper: the card, the lenses, the documents
    val stack = Color(0xFFEEEEEE) // the pile of slides
    val rim = Color(0xFF161616) // ink on white paper
    val hairline = ink.copy(alpha = .1f)
    val rule = ink.copy(alpha = .08f)
    val wash = ink.copy(alpha = .06f) // a picked row, the stop button's track
    val hover = ink.copy(alpha = .04f)
    val hint = ink.copy(alpha = .6f)
    val heard = ink.copy(alpha = .66f)
    val fill = ink.copy(alpha = .05f)
    val muted = ink.copy(alpha = .64f)

    companion object {
        val Light = MoleColors(
            paper = Color.White, ink = Color(0xFF161616), figure = Color(0xFF161616), bridge = Color.White,
            tie = Color(0xFFE8111F), tieFold = Color(0xFFBB0916), tieKnot = Color(0xFFFC675C), ember = Color(0xFFE8111F),
            page = Color.White, device = Color(0xFF161616), outline = Color.Black.copy(alpha = .1f),
        )
        val Dark = MoleColors(
            paper = Color(0xFF222222), ink = Color(0xFFEEEEEE), figure = Color(0xFFD7D7D7), bridge = Color(0xFF161616),
            tie = Color(0xFFCB0316), tieFold = Color(0xFF9D0410), tieKnot = Color(0xFFF9423D), ember = Color(0xFFF9423D),
            page = Color(0xFF0F0F0F), device = Color(0xFF060606), outline = Color.White.copy(alpha = .1f),
        )
    }
}

internal val Deck = Color.White // index.html's --deck, the shared frame behind a slide; the presenter's, so not themed

internal val LocalMoleColors = staticCompositionLocalOf { MoleColors.Light }

@Composable
internal fun MoleTheme.colors() = if (this == MoleTheme.Dark || this == MoleTheme.System && systemDark()) MoleColors.Dark else MoleColors.Light

@Composable
internal fun Themed(theme: MoleTheme, content: @Composable () -> Unit) = CompositionLocalProvider(LocalMoleColors provides theme.colors(), content = content)

/** Whether the OS is in dark mode, following it as it changes. */
@Composable
internal expect fun systemDark(): Boolean

/** Gives the status and navigation bars icons that read on [paper] (Android; nothing elsewhere). */
@Composable
internal expect fun SystemBars(paper: Color)

internal val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f) // --ease-out
internal val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) // CSS ease
internal val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f) // CSS ease-in-out

/** Reduced motion for the mole. Android also follows the system's animator duration scale. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Stretches every mole animation by this factor, like --slow in mole.css. */
val LocalSlowMotion = staticCompositionLocalOf { 1f }

@Composable
internal fun reduceMotion() = LocalReduceMotion.current || systemReducesMotion()

@Composable
internal expect fun systemReducesMotion(): Boolean

/** A CSS transition: duration and delay in ms, stretched by [LocalSlowMotion]. */
@Composable
internal fun <T> css(ms: Int, delay: Int = 0, easing: Easing = EaseOut): TweenSpec<T> {
    val slow = LocalSlowMotion.current
    return tween((ms * slow).roundToInt(), (delay * slow).roundToInt(), easing)
}

/** Inter at this CSS size and weight. Chrome sets its optical size to the size in px (font-optical-sizing: auto), within the font's 14 to 32. */
@Composable
internal fun inter(size: Float, weight: Int) = FontFamily(
    Font(Res.font.InterVariable, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", size.coerceIn(14f, 32f)))),
)

// tracking in px: CSS letter-spacing inherits as a length, so -0.009em on the 15px bar is -0.135px everywhere under it.
@Composable
internal fun type(size: Float, line: Float, weight: Int = 500, tracking: Float = 0f, color: Color, tabular: Boolean = false) = TextStyle(
    color = color,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    fontFamily = inter(size, weight),
    fontSynthesis = FontSynthesis.None,
    letterSpacing = tracking.sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
