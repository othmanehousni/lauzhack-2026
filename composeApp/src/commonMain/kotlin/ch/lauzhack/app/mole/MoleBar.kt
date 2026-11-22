package ch.lauzhack.app.mole

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lauzhack.app.Choice
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.MoleState.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val TITLES = mapOf(
    Hidden to "", Idle to "You're driving.", Heard to "I've got it.", Working to "Searching…", Retrieving to "Found it.",
    Ready to "Ready to show.", Back to "Back to you.", Unsure to "Didn't catch that.", Thinking to "Thinking…", Waiting to "Go on…",
    Tunneling to "On it…", Failed to "Couldn't do that.", Hello to "Hi. I'm listening.", Choosing to "Which one?", Picked to "Opening it…",
)
private val OPEN = setOf(Choosing, Picked) // states that show the options panel above the bar
private val PULSE = setOf(Working, Retrieving, Thinking, Tunneling, Picked)
private val NUMBERS = listOf("one", "two", "three", "four", "five")
private const val HEARD_MAX = 44 // a live transcript keeps its newest words

internal class Span(val text: String, val id: Int, enter: Boolean, val shift: Float, val blur: Float) {
    val alpha = Animatable(if (enter) 0f else 1f)
    val dy = Animatable(if (enter) shift else 0f)
    val sigma = Animatable(if (enter) blur else 0f)
    var leaving by mutableStateOf(false)
}

// mole.js set(), step for step: the bar and the phone take a MoleLine the way the web mole takes set(). While hidden
// the words, rows and card stay as they were, so the exit shows what was there.
internal class Words {
    var state by mutableStateOf(Hidden)
    val titles = mutableStateListOf<Span>()
    var heard by mutableStateOf("")
    var oneLine by mutableStateOf(true)
    var options by mutableStateOf(emptyList<Choice>()) // kept until new ones arrive
    var pick by mutableStateOf<Int?>(null)
    var label by mutableStateOf("07") // the card's number, kept until changed
    private var ids = 0

    val open get() = state in OPEN && options.isNotEmpty()
    val front get() = if (state == Choosing) "1" else label // while choosing, the two fanned cards read 1 and 2
    val back get() = if (state == Choosing) "2" else label

    fun set(line: MoleLine, reduce: Boolean) {
        val prev = state
        state = line.state
        if (line.state == Hidden) return
        if (line.options.isNotEmpty()) options = line.options
        pick = line.pick
        if (line.card.isNotEmpty()) label = line.card
        else if (line.state == Picked && line.pick != null) label = "${line.pick + 1}"
        swap(line.title ?: TITLES.getValue(line.state), animate = prev != Hidden, reduce)
        oneLine = line.heard.isEmpty()
        if (line.heard.isNotEmpty()) heard = if (line.heard.length > HEARD_MAX) "…" + line.heard.takeLast(HEARD_MAX - 1).trimStart() else line.heard
    }

    private fun swap(text: String, animate: Boolean, reduce: Boolean) {
        if (titles.lastOrNull()?.text == text) return
        if (!animate) {
            titles.clear()
            titles += Span(text, ids++, enter = false, 0f, 0f)
            return
        }
        titles.forEach { it.leaving = true }
        titles += Span(text, ids++, enter = true, if (reduce) 0f else 6f, if (reduce) 0f else 2f)
    }
}

// Applied while composing, the way set() lands before the web's next frame: from an effect, every transition the line
// starts would begin a frame later, and the title swap two.
@Composable
internal fun rememberWords(line: MoleLine, reduce: Boolean): Words {
    val words = remember { Words() }
    remember(line) { words.set(line, reduce) }
    return words
}

/** The 340 dp overlay bar of mole/mole.css .mole-bar: the mole and its words, and the options panel growing upward above them. */
@Composable
fun MoleBar(line: MoleLine, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val reduce = reduceMotion()
    val words = rememberWords(line, reduce)
    val hidden = words.state == Hidden
    // Hiding waits 180 ms, so the mole dives before the bar goes.
    val fade = animateFloatAsState(if (hidden) 0f else 1f, css(180, if (hidden) 180 else 0))
    val sink = animateFloatAsState(if (hidden) 1f else 0f, if (reduce) snap() else css(260, if (hidden) 180 else 0))
    Column(
        modifier
            .graphicsLayer {
                alpha = fade.value
                translationY = 10.dp.toPx() * sink.value
                scaleX = 1 - .04f * sink.value
                scaleY = scaleX
                transformOrigin = TransformOrigin(.5f, 1f)
            }
            .width(340.dp)
            .drawBehind { // box-shadow: 0 0 0 1px, a hairline ring just outside the bar
                val o = 1.dp.toPx()
                drawRoundRect(Ink.copy(alpha = .1f), Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o), CornerRadius(24.dp.toPx() + o))
            }
            .background(Paper, RoundedCornerShape(24.dp)),
    ) {
        Options(words, phone = false, enabled = !hidden, onPick)
        Row(Modifier.height(76.dp).padding(start = 10.dp, end = 22.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            MolePuppet(words.state, line.timeoutMs, words.front, words.back, Modifier.align(Alignment.Bottom).padding(bottom = 9.dp).size(83.dp, 62.dp))
            BarWords(words, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BarWords(w: Words, modifier: Modifier) {
    val reduce = reduceMotion()
    val away = w.state == Idle || w.state == Back
    val dx = animateDpAsState(if (away) (-16).dp else 0.dp, if (reduce) snap() else css(260))
    val dy = animateDpAsState(if (w.oneLine) 8.5.dp else 0.dp, if (reduce) snap() else css(260))
    Box(modifier) {
        Dot(w.state, dy)
        Column(Modifier.graphicsLayer { translationX = dx.value.toPx(); translationY = dy.value.toPx() }.padding(start = 16.dp)) {
            Title(w, type(15f, 20f, tracking = -.15f), TextAlign.Start)
            Heard(w, type(12.5f, 16f, tracking = -.15f, color = Ink.copy(alpha = .55f)), Modifier.padding(top = 1.dp).height(16.dp), TextAlign.Start)
        }
    }
}

@Composable
private fun Dot(state: MoleState, dy: State<Dp>) {
    val reduce = reduceMotion()
    val away = state == Idle || state == Back
    val alpha = animateFloatAsState(if (away) 0f else 1f, css(160))
    val scale = animateFloatAsState(if (away) .4f else 1f, if (reduce) snap() else css(220))
    // Unsure and failed are ember; asking you, not driving, is plum.
    val color = animateColorAsState(
        when (state) { Unsure, Failed -> Ember; Choosing -> Plum; else -> Sage },
        if (reduce) snap() else css(200, easing = Ease),
    )
    val pulse = clock(state in PULSE)
    Box(
        Modifier.offset(y = 6.dp)
            .graphicsLayer {
                translationY = dy.value.toPx()
                this.alpha = alpha.value * if (state in PULSE) pulse(pulse.value) else 1f
                scaleX = scale.value
                scaleY = scale.value
            }
            .size(8.dp)
            .drawBehind { drawRoundRect(color.value, cornerRadius = CornerRadius(2.dp.toPx())) },
    )
}

// @keyframes mole-pulse { 50% { opacity: .35 } }, 1 s ease-in-out per half.
private fun pulse(t: Float): Float {
    val p = t % 1f
    return if (p < .5f) 1 - .65f * EaseInOut.transform(p * 2) else .35f + .65f * EaseInOut.transform(p * 2 - 1)
}

/** Seconds since [running] turned true, on the frame clock; 0 while not running. */
@Composable
internal fun clock(running: Boolean): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    val slow = LocalSlowMotion.current
    LaunchedEffect(running, slow) {
        t.value = 0f
        if (!running) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.value = (it - start) / 1e9f / slow }
    }
    return t
}

// CSS blur() takes a Gaussian sigma; BlurEffect takes a radius that Skia turns into sigma = 0.57735 r + 0.5.
internal fun cssBlur(sigma: Float): RenderEffect? = if (sigma <= .5f) null else ((sigma - .5f) / .57735f).let { BlurEffect(it, it, TileMode.Decal) }

// The title swap: the old line lifts away blurred while the new one rises into place.
@Composable
internal fun Title(w: Words, style: TextStyle, align: TextAlign) {
    val out = css<Float>(160)
    val into = css<Float>(220, 40)
    Box {
        for (span in w.titles) key(span.id) {
            LaunchedEffect(span.leaving) {
                coroutineScope {
                    if (span.leaving) {
                        launch { span.alpha.animateTo(0f, out) }
                        launch { span.dy.animateTo(-span.shift, out) }
                        launch { span.sigma.animateTo(span.blur, out) }
                    } else {
                        launch { span.alpha.animateTo(1f, into) }
                        launch { span.dy.animateTo(0f, into) }
                        launch { span.sigma.animateTo(0f, into) }
                    }
                }
                if (span.leaving) w.titles.remove(span)
            }
            BasicText(
                span.text,
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = span.alpha.value
                    translationY = span.dy.value.dp.toPx()
                    renderEffect = cssBlur(span.sigma.value.dp.toPx())
                },
                style.copy(textAlign = align),
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun Heard(w: Words, style: TextStyle, modifier: Modifier, align: TextAlign) {
    val alpha = animateFloatAsState(if (w.oneLine) 0f else 1f, css(200))
    BasicText(
        "“${w.heard}”",
        modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value },
        style.copy(textAlign = align),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
    )
}

// The options panel: rows open like grid-template-rows 0fr → 1fr, content pinned to the top and clipped.
@Composable
internal fun Options(w: Words, phone: Boolean, enabled: Boolean, onPick: (Int) -> Unit) {
    val reduce = reduceMotion()
    val open = w.open
    val grow = animateFloatAsState(if (open) 1f else 0f, if (reduce) snap() else css(320))
    val fade = animateFloatAsState(if (open) 1f else 0f, if (open) css(220, 90) else css(140))
    val hint = type(12f, 16f, tracking = -.15f, color = Ink.copy(alpha = .5f))
    Box(
        Modifier
            .clipToBounds()
            .layout { m, c ->
                val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                layout(p.width, (p.height * grow.value).roundToInt()) { p.place(0, 0) }
            }
            .graphicsLayer { alpha = fade.value }
            .padding(horizontal = 10.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = if (phone) 16.dp else 14.dp, bottom = 8.dp),
                if (phone) Arrangement.Center else Arrangement.SpaceBetween,
            ) {
                BasicText(say(w.options.size), style = hint, softWrap = false, maxLines = 1)
                if (!phone) BasicText("Only you see this", style = hint, softWrap = false, maxLines = 1) // everything on the phone is the presenter's alone
            }
            Column(
                Modifier
                    .then(if (phone) Modifier else Modifier.drawBehind { drawRect(Ink.copy(alpha = .08f), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) })
                    .padding(bottom = if (phone) 8.dp else 9.dp),
                Arrangement.spacedBy(2.dp),
            ) {
                w.options.forEachIndexed { i, c -> Option(i, c, on = w.pick == i, picked = w.state == Picked, phone, enabled, onPick) }
            }
        }
    }
}

private fun say(n: Int): String {
    if (n < 2) return ""
    val words = NUMBERS.take(n).map { "“$it”" }
    return "Say ${words.dropLast(1).joinToString(", ")} or ${words.last()}"
}

@Composable
private fun Option(i: Int, c: Choice, on: Boolean, picked: Boolean, phone: Boolean, enabled: Boolean, onPick: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val bg = animateColorAsState(Ink.copy(alpha = if (on) .06f else if (hovered) .04f else 0f), css(150, easing = Ease))
    val dim = animateFloatAsState(if (picked && !on) .35f else 1f, css(200))
    // The bar's 24 dp corner minus the panel's 10 dp inset, so the curves stay concentric.
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (phone) 52.dp else 36.dp) // a thumb needs a taller row than a cursor
            .graphicsLayer { alpha = dim.value }
            .clip(shape)
            .drawBehind { drawRect(bg.value) }
            .then(if (focused) Modifier.border(2.dp, Ink, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) { onPick(i) }
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(i, on, check = picked && on)
        Spacer(Modifier.width(10.dp))
        Glyph(c.kind)
        Spacer(Modifier.width(10.dp))
        val name = type(14f, 20f, tracking = -.15f)
        val meta = type(12f, 20f, tracking = -.15f, color = Ink.copy(alpha = .5f))
        if (phone) {
            // On two versions of one file the end of the name is the difference, so the date drops under it.
            Column(Modifier.weight(1f)) {
                BasicText(c.name, style = name, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
                BasicText(c.meta, style = meta, softWrap = false, maxLines = 1)
            }
        } else {
            BasicText(c.name, Modifier.weight(1f), name, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            BasicText(c.meta, style = meta, softWrap = false, maxLines = 1)
        }
    }
}

private val CHECK = svg("M2.6 6.3l2.2 2.2 4.6-4.9")

@Composable
private fun Key(i: Int, on: Boolean, check: Boolean) {
    val bg = animateColorAsState(if (on) Ink else Ink.copy(alpha = .06f), css(150, easing = Ease))
    val fg = animateColorAsState(if (on) Paper else Ink, css(150, easing = Ease))
    Box(Modifier.size(20.dp).drawBehind { drawRoundRect(bg.value, cornerRadius = CornerRadius(6.dp.toPx())) }, Alignment.Center) {
        if (check) Canvas(Modifier.size(12.dp)) {
            scale(size.width / 12f, Offset.Zero) { drawPath(CHECK, fg.value, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        } else BasicText("${i + 1}", style = type(11f, 11f, 600, -.15f, mono = true).copy(color = fg.value))
    }
}

// Tiny cut-paper documents in the card's style: cream paper, ink edge, faded ink lines. Same shapes as GLYPHS in mole.js.
private class Glyph(val paper: Path, val lines: Path)

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) = Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, r, r)) }

private val GLYPHS = mapOf(
    Choice.Kind.Pdf to Glyph(svg("M3.5 1.5h6l3 3v10h-9z"), svg("M9.5 1.5v3h3M5.5 8h5M5.5 10.5h5M5.5 13h3")),
    Choice.Kind.Slides to Glyph(rect(1.5f, 3.5f, 13f, 9f, 1f), svg("M4 7h6M4 9.5h8")),
    Choice.Kind.Sheet to Glyph(svg("M3.5 1.5h9v13h-9z"), svg("M3.5 5.5h9M3.5 9.5h9M8 5.5v9")),
    Choice.Kind.Site to Glyph(rect(1.5f, 2.5f, 13f, 11f, 1.5f), svg("M1.5 5.5h13M4 8.5h8M4 11h5")),
    Choice.Kind.Image to Glyph(rect(1.5f, 2.5f, 13f, 11f, 1f), svg("M1.5 12l4-4 3 3 2-2 4 4M9.75 5.5a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0-2.5 0")),
    Choice.Kind.Other to Glyph(svg("M3.5 1.5h6l3 3v10h-9z"), svg("M9.5 1.5v3h3")),
)

@Composable
private fun Glyph(kind: Choice.Kind) = Canvas(Modifier.size(16.dp)) {
    val g = GLYPHS.getValue(kind)
    scale(size.width / 16f, Offset.Zero) {
        drawPath(g.paper, Lens)
        drawPath(g.paper, Ink, style = Stroke(1.1f, join = StrokeJoin.Round))
        drawPath(g.lines, Ink, alpha = .45f, style = Stroke(1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
