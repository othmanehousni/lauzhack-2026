package ch.lauzhack.app.mole

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The :root tokens of mole/mole.css, all sRGB hex. index.html's --ink-lch, oklch(0.229 0.006 56.1), converts to this
// same #1f1c1a, so its --muted and --fill are Ink at .64 and .05.
internal val Paper = Color(0xFFF2EDE4)
internal val Ink = Color(0xFF1F1C1A)
internal val Ember = Color(0xFFF2531C)
internal val EmberDeep = Color(0xFFC4430B)
internal val EmberLight = Color(0xFFF78742)
internal val Lens = Color(0xFFF3EBD5)
internal val Pile = Color(0xFFFDF8EC)
internal val Sage = Color(0xFF8DAE93)
internal val Plum = Color(0xFF65496E)
internal val Muted = Ink.copy(alpha = .64f)
internal val Fill = Ink.copy(alpha = .05f)
internal val Stage = Color(0xFFFBF8F2) // the shared frame behind a slide

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

internal fun type(size: Float, line: Float, weight: Int = 500, tracking: Float = 0f, color: Color = Ink, mono: Boolean = false) = TextStyle(
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
