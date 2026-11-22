package ch.lauzhack.app.mole

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import ch.lauzhack.app.ui

/**
 * The phone companion of index.html's .phone: the meeting header with the mic chip, the mole centred with its options
 * under it, and what is on screen. Until [connected], only the mole looking for the laptop: nothing it said before holds.
 */
@Composable
fun MolePhone(
    line: MoleLine,
    onScreenName: String,
    thumbnail: ImageBitmap?,
    phoneLeads: Boolean,
    meeting: String,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    theme: MoleTheme = MoleTheme.System,
    connected: Boolean = true,
    micIssue: String? = null,
) = Themed(theme) {
    val c = LocalMoleColors.current
    SystemBars(c.paper)
    // The app on the phone never hides: when the bar dives away, the phone's mole goes back to listening.
    val shown = when {
        !connected -> MoleLine(MoleState.Tunneling, ui.lookingForLaptop)
        line.state == MoleState.Hidden -> MoleLine(MoleState.Idle)
        else -> line
    }
    val reduce = reduceMotion()
    val words = rememberWords(shown, reduce)
    Column(modifier.fillMaxSize().background(c.paper).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 16.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            if (connected) {
                Column(Modifier.weight(1f)) {
                    BasicText(ui.presenting, style = type(15f, 20f, 600, -.135f, c.ink), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
                    BasicText(meeting, style = type(12f, 16f, 400, color = c.muted), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
                }
                Mic(phoneLeads)
            }
        }
        if (connected && micIssue != null) {
            BasicText(micIssue, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = type(13f, 18f, 400, color = c.ink))
        }
        val preview = thumbnail.takeIf { connected }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            Arrangement.Center,
            Alignment.CenterHorizontally,
        ) {
            MolePuppet(words.state, shown.timeoutMs, words.front, words.back, Modifier.width(if (preview == null) 200.dp else 128.dp).aspectRatio(154f / 114.8f))
            Spacer(Modifier.height(12.dp))
            Title(words, type(22f, 28f, 600, -.396f, c.ink), TextAlign.Center)
            val second = Modifier.padding(top = 4.dp).height(20.dp)
            if (connected) Heard(words, type(14f, 20f, 400, -.084f, c.heard), type(14f, 20f, 400, -.084f, c.hint), second, TextAlign.Center)
            // The laptop serves the phone only during a call, so one asleep and one out of reach look the same from here.
            else BasicText(ui.connectsInCall, second.fillMaxWidth(), type(14f, 20f, 400, -.084f, c.hint).copy(textAlign = TextAlign.Center), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
            Options(words, phone = true, enabled = true, onPick)
        }
        Stop(words, phone = true, enabled = true, onPick, Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth())
        if (connected && (preview != null || onScreenName.isNotEmpty())) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(ui.onScreen, style = type(12f, 16f, 400, color = c.muted))
                    BasicText(onScreenName.ifEmpty { ui.desktop }, style = type(15f, 20f, 500, -.135f, c.ink), overflow = TextOverflow.Ellipsis, maxLines = 1)
                }
                if (preview != null) {
                    val shape = RoundedCornerShape(12.dp)
                    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        Image(
                            preview, ui.sharedScreen,
                            Modifier.fillMaxWidth().height(minOf(maxWidth * (9f / 16f), maxHeight))
                                .clip(shape).background(Deck).border(1.dp, c.outline, shape),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
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
    val c = LocalMoleColors.current
    val reduce = reduceMotion()
    val bg = animateColorAsState(if (phone) c.ink else c.fill, css(200, easing = Ease))
    val fg = animateColorAsState(if (phone) c.paper else c.ink, css(200, easing = Ease))
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
            Source(ui.laptop, !phone, fg)
            Source(ui.phone, phone, fg)
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
        style = type(13f, 13f, 500, -.084f, Color.Unspecified), // the page's -0.006em at 14px, inherited as a length
        color = { color.value },
    )
}
