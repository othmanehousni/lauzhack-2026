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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
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
    val plum: Color,
    val page: Color,
    val device: Color, // the phone's body, in the playground
    val outline: Color, // the thumbnail's edge
) {
    val lens = Color(0xFFF3EBD5) // cream paper: the card, the lenses, the documents
    val stack = Color(0xFFFDF8EC) // the pile of slides
    val rim = Color(0xFF1F1C1A) // ink on cream paper
    val ember = Color(0xFFF2531C)
    val sage = Color(0xFF8DAE93)
    val hairline = ink.copy(alpha = .1f)
    val rule = ink.copy(alpha = .08f)
    val wash = ink.copy(alpha = .06f) // a picked row, a key
    val hover = ink.copy(alpha = .04f)
    val hint = ink.copy(alpha = .5f)
    val heard = ink.copy(alpha = .55f)
    val fill = ink.copy(alpha = .05f)
    val muted = ink.copy(alpha = .64f)

    companion object {
        val Light = MoleColors(
            paper = Color(0xFFF2EDE4), ink = Color(0xFF1F1C1A), figure = Color(0xFF1F1C1A), bridge = Color(0xFFF3EBD5),
            tie = Color(0xFFF2531C), tieFold = Color(0xFFC4430B), tieKnot = Color(0xFFF78742), plum = Color(0xFF65496E),
            page = Color(0xFFF2EDE4), device = Color(0xFF1F1C1A), outline = Color.Black.copy(alpha = .1f),
        )
        val Dark = MoleColors(
            paper = Color(0xFF272421), ink = Color(0xFFF2EDE4), figure = Color(0xFFE3D6C2), bridge = Color(0xFF1F1C1A),
            tie = Color(0xFFC4430B), tieFold = Color(0xFF9D310B), tieKnot = Color(0xFFF2531C), plum = Color(0xFFAE88BB),
            page = Color(0xFF191714), device = Color(0xFF0F0D0C), outline = Color.White.copy(alpha = .1f),
        )
    }
}

internal val Deck = Color(0xFFFBF8F2) // index.html's --deck, the shared frame behind a slide; the presenter's, so not themed

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

/** What -apple-system (or ui-monospace) renders at this CSS size and weight on macOS; the platform default elsewhere. */
internal expect fun moleFont(size: Float, weight: Int, mono: Boolean = false): FontFamily

/** Px the system font's own tracking table adds to each letter at this size (Chrome applies it on macOS); 0 elsewhere. */
internal expect fun trak(size: Float): Float

/** A CSS transition: duration and delay in ms, stretched by [LocalSlowMotion]. */
@Composable
internal fun <T> css(ms: Int, delay: Int = 0, easing: Easing = EaseOut): TweenSpec<T> {
    val slow = LocalSlowMotion.current
    return tween((ms * slow).roundToInt(), (delay * slow).roundToInt(), easing)
}

internal fun type(size: Float, line: Float, weight: Int = 500, tracking: Float = 0f, color: Color, mono: Boolean = false) = TextStyle(
    color = color,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    fontFamily = moleFont(size, weight, mono),
    fontSynthesis = FontSynthesis.None,
    // px: CSS letter-spacing inherits as a length, so -0.01em on the 15px bar is -0.15px everywhere under it
    letterSpacing = (tracking + if (mono) 0f else trak(size)).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
