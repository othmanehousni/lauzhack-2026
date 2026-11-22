package ch.lauzhack.app.mole

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState

/** The phone companion of index.html's .phone: the meeting header with the mic chip, the mole centred with its options under it, and what is on screen. */
@Composable
fun MolePhone(
    line: MoleLine,
    onScreenName: String,
    thumbnail: ImageBitmap?,
    phoneLeads: Boolean,
    meeting: String,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The app on the phone never hides: when the bar dives away, the phone's mole goes back to listening.
    val shown = if (line.state == MoleState.Hidden) MoleLine(MoleState.Idle) else line
    val reduce = reduceMotion()
    val words = rememberWords(shown, reduce)
    val bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("Presenting", style = type(15f, 20f, 600, -.15f), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
                BasicText(meeting, style = type(12f, 16f, 400, color = Muted), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
            }
            Mic(phoneLeads)
        }
        // As the options open the centred column grows, so the mole glides up to make room.
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            MolePuppet(words.state, shown.timeoutMs, words.front, words.back, Modifier.width(200.dp).aspectRatio(154f / 114.8f))
            Spacer(Modifier.height(12.dp))
            Title(words, type(22f, 28f, 600, -.44f), TextAlign.Center)
            Heard(words, type(14f, 20f, tracking = -.15f, color = Ink.copy(alpha = .55f)), Modifier.padding(top = 4.dp).height(20.dp), TextAlign.Center)
            Options(words, phone = true, enabled = true, onPick)
        }
        Row(
            Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = maxOf(28.dp - bottom, 12.dp))
                .fillMaxWidth()
                .background(Fill, RoundedCornerShape(20.dp))
                .padding(8.dp),
            Arrangement.spacedBy(12.dp),
            Alignment.CenterVertically,
        ) {
            val thumb = RoundedCornerShape(12.dp)
            Box(Modifier.size(88.dp, 49.5.dp).clip(thumb).background(Stage).border(1.dp, Color.Black.copy(alpha = .1f), thumb)) {
                thumbnail?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f)) {
                BasicText("On screen", style = type(12f, 16f, 400, color = Muted))
                BasicText(onScreenName, style = type(15f, 20f, 500), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
            }
        }
    }
}

private val WAVE = floatArrayOf(0f, .3f, .6f, .15f) // the bars' negative animation delays, in seconds

// @keyframes wave from scale .4 to 1, 900 ms ease-in-out, infinite alternate.
private fun wave(t: Float): Float {
    val n = (t / .9f).toInt()
    val f = (t % .9f) / .9f
    return .4f + .6f * EaseInOut.transform(if (n % 2 == 0) f else 1 - f)
}

@Composable
private fun Mic(phone: Boolean) {
    val reduce = reduceMotion()
    val bg = animateColorAsState(if (phone) Ink else Fill, css(200, easing = Ease))
    val fg = animateColorAsState(if (phone) Paper else Ink, css(200, easing = Ease))
    val t = clock(!reduce)
    Row(
        Modifier.height(32.dp).drawBehind { drawRoundRect(bg.value, cornerRadius = CornerRadius(16.dp.toPx())) }.padding(start = 11.dp, end = 12.dp),
        Arrangement.spacedBy(8.dp),
        Alignment.CenterVertically,
    ) {
        Row(Modifier.height(12.dp), Arrangement.spacedBy(2.dp)) {
            for (delay in WAVE) Box(
                Modifier.width(2.dp).fillMaxHeight()
                    .graphicsLayer { scaleY = if (reduce) .4f else wave(t.value + delay) }
                    .drawBehind { drawRoundRect(fg.value, cornerRadius = CornerRadius(1.dp.toPx())) },
            )
        }
        Box {
            Source("Laptop", !phone, fg)
            Source("Phone", phone, fg)
        }
    }
}

@Composable
private fun Source(text: String, shown: Boolean, color: State<Color>) {
    val reduce = reduceMotion()
    val alpha = animateFloatAsState(if (shown) 1f else 0f, css(200, easing = Ease))
    val blur = animateFloatAsState(if (shown) 0f else 2f, if (reduce) snap() else css(200, easing = Ease))
    val dy = animateFloatAsState(if (shown) 0f else 4f, if (reduce) snap() else css(220))
    BasicText(
        text,
        Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = dy.value.dp.toPx()
            renderEffect = cssBlur(blur.value.dp.toPx())
        },
        style = type(13f, 13f, 500),
        color = { color.value },
    )
}
