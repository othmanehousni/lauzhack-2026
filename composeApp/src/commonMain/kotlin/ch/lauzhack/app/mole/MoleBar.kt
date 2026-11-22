package ch.lauzhack.app.mole

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
import ch.lauzhack.app.ui
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val OPEN = setOf(Choosing, Picked) // states that show the options panel above the bar
private val NEEDS_YOU = setOf(Unsure, Waiting, Failed, Choosing) // the only states with the red dot
private val NUMBERS = listOf("one", "two", "three", "four", "five")
private const val HEARD_MAX = 44 // a live transcript keeps its newest words
private const val ANNOUNCE_MS = 2500L

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
    // Retrieving with exactly one option: about to show it, unless stopped within [announceMs].
    var announce by mutableStateOf(false)
    var announceMs = ANNOUNCE_MS
    var announced by mutableIntStateOf(0) // counts announces, so each one restarts the countdown
    private var ids = 0

    val open get() = state in OPEN && options.isNotEmpty()
    val front get() = if (state == Choosing) "1" else label // while choosing, the two fanned cards read 1 and 2
    val back get() = if (state == Choosing) "2" else label

    fun set(line: MoleLine, reduce: Boolean) {
        val prev = state
        state = line.state
        val next = line.state == Retrieving && line.options.size == 1
        if (next && (!announce || line.options != options)) {
            announceMs = line.timeoutMs ?: ANNOUNCE_MS
            announced++
        }
        announce = next
        if (line.state == Hidden) return
        if (line.options.isNotEmpty()) options = line.options
        pick = line.pick
        if (line.card.isNotEmpty()) label = line.card
        else if (line.state == Picked && line.pick != null) label = "${line.pick + 1}"
        swap(line.title ?: if (announce) line.options[0].name else ui.titles.getValue(line.state), animate = prev != Hidden, reduce)
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

/** The 340 dp overlay bar of mole/mole.css .mole-bar: the mole and its words, and the options panel growing upward above
 * them. In Mime [mode] the mole is the mime. */
@Composable
fun MoleBar(line: MoleLine, onPick: (Int) -> Unit, modifier: Modifier = Modifier, theme: MoleTheme = MoleTheme.System, mode: Mode = Mode.Present) = Themed(theme) {
    val c = LocalMoleColors.current
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
                drawRoundRect(c.hairline, Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o), CornerRadius(24.dp.toPx() + o))
            }
            .background(c.paper, RoundedCornerShape(24.dp)),
    ) {
        Options(words, phone = false, enabled = !hidden, onPick)
        Row(Modifier.heightIn(min = 76.dp).padding(start = 10.dp, end = 22.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            val mole = Modifier.align(Alignment.Bottom).padding(bottom = 9.dp).size(83.dp, 62.dp)
            if (mode == Mode.Mime) MimeMole(mole) else MolePuppet(words.state, line.timeoutMs, words.front, words.back, mole)
            BarWords(words, Modifier.weight(1f).padding(vertical = 10.dp))
            Stop(words, phone = false, enabled = !hidden, onPick, Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun BarWords(w: Words, modifier: Modifier) {
    val c = LocalMoleColors.current
    val reduce = reduceMotion()
    val needsYou = w.state in NEEDS_YOU
    // The words sit where the dot would be and move right only when it shows.
    val dx = animateDpAsState(if (needsYou) 0.dp else (-16).dp, if (reduce) snap() else css(260))
    val dy = animateDpAsState(if (w.oneLine && !w.announce) 8.5.dp else 0.dp, if (reduce) snap() else css(260))
    Box(modifier) {
        Dot(needsYou, dy)
        Column(Modifier.graphicsLayer { translationX = dx.value.toPx(); translationY = dy.value.toPx() }.padding(start = 16.dp)) {
            Title(w, type(15f, 20f, tracking = -.135f, color = c.ink), TextAlign.Start)
            Heard(w, type(13f, 16f, 400, -.039f, c.heard), type(13f, 16f, 400, -.039f, c.hint), Modifier.padding(top = 1.dp).height(16.dp), TextAlign.Start)
        }
    }
}

@Composable
private fun Dot(shown: Boolean, dy: State<Dp>) {
    val c = LocalMoleColors.current
    val reduce = reduceMotion()
    val alpha = animateFloatAsState(if (shown) 1f else 0f, css(160))
    val scale = animateFloatAsState(if (shown) 1f else .4f, if (reduce) snap() else css(220))
    Box(
        Modifier.offset(y = 6.dp)
            .graphicsLayer {
                translationY = dy.value.toPx()
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .size(8.dp)
            .drawBehind { drawRoundRect(c.ember, cornerRadius = CornerRadius(2.dp.toPx())) },
    )
}

/**
 * The announce's stop (mole.css .mole-stop): an ink pill whose fill drains to the left as the time to stop runs out, over
 * a quiet track. A 32 dp pill in a 40 dp target on the bar; on the phone, a 56 dp bar a thumb cannot miss. onPick(-1).
 */
@Composable
internal fun Stop(w: Words, phone: Boolean, enabled: Boolean, onPick: (Int) -> Unit, modifier: Modifier) {
    val c = LocalMoleColors.current
    val reduce = reduceMotion()
    val shown = animateFloatAsState(if (w.announce) 1f else 0f, css(160))
    if (!w.announce && shown.value == 0f) return
    val left = remember { Animatable(1f) }
    val slow = LocalSlowMotion.current
    LaunchedEffect(w.announced) {
        left.snapTo(1f)
        left.animateTo(0f, tween((w.announceMs * slow).roundToInt(), easing = LinearEasing))
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(if (pressed && !reduce) .96f else 1f, css(160))
    val height = if (phone) 56.dp else 32.dp
    val pill = RoundedCornerShape(height / 2)
    val label = if (phone) type(15f, 56f, 600, -.135f, c.ink) else type(13f, 32f, 500, -.039f, c.ink)
    Box(
        modifier
            .height(if (phone) 56.dp else 40.dp)
            .graphicsLayer { alpha = shown.value; scaleX = press.value; scaleY = press.value }
            .clickable(interaction, indication = null, enabled = enabled && w.announce, role = Role.Button) { onPick(-1) },
        Alignment.Center,
    ) {
        Box(Modifier.then(if (phone) Modifier.fillMaxWidth() else Modifier).height(height).background(c.wash, pill).padding(horizontal = 14.dp), Alignment.Center) {
            BasicText(ui.stop, style = label, softWrap = false, maxLines = 1)
        }
        Box(
            Modifier.matchParentSize().padding(vertical = if (phone) 0.dp else 4.dp)
                .drawWithContent { clipRect(right = size.width * left.value) { this@drawWithContent.drawContent() } }
                .background(c.ink, pill),
            Alignment.Center,
        ) { BasicText(ui.stop, style = label.copy(color = c.paper), softWrap = false, maxLines = 1) }
    }
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
                maxLines = 3, // a long name or question wraps; the bar's row grows past 76 dp only for a third line
            )
        }
    }
}

// The second line: what was heard, or during an announce, how to stop it by voice.
@Composable
internal fun Heard(w: Words, style: TextStyle, say: TextStyle, modifier: Modifier, align: TextAlign) {
    val heard = animateFloatAsState(if (w.oneLine || w.announce) 0f else 1f, css(200))
    val sayNo = animateFloatAsState(if (w.announce) 1f else 0f, css(200))
    Box(modifier.fillMaxWidth()) {
        for ((text, alpha, look) in listOf(Triple(ui.quote(w.heard), heard, style), Triple(ui.sayNo, sayNo, say))) BasicText(
            text,
            Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value },
            look.copy(textAlign = align),
            overflow = if (text == ui.sayNo) TextOverflow.Ellipsis else TextOverflow.StartEllipsis, // the newest words stay where supported (Android); desktop ends them
            softWrap = false,
            maxLines = 1,
        )
    }
}

// The options panel: rows open like grid-template-rows 0fr → 1fr, content pinned to the top and clipped.
@Composable
internal fun Options(w: Words, phone: Boolean, enabled: Boolean, onPick: (Int) -> Unit) {
    val c = LocalMoleColors.current
    val reduce = reduceMotion()
    val open = w.open
    val grow = animateFloatAsState(if (open) 1f else 0f, if (reduce) snap() else css(320))
    val fade = animateFloatAsState(if (open) 1f else 0f, if (open) css(220, 90) else css(140))
    val hint = type(12f, 16f, 400, color = c.hint)
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
            BasicText(
                say(w.options.size),
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = if (phone) 16.dp else 14.dp, bottom = 8.dp),
                hint.copy(textAlign = if (phone) TextAlign.Center else TextAlign.Start),
                softWrap = false,
                maxLines = 1,
            )
            Column(
                Modifier
                    .then(if (phone) Modifier else Modifier.drawBehind { drawRect(c.rule, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) })
                    .padding(bottom = if (phone) 8.dp else 9.dp),
                Arrangement.spacedBy(2.dp),
            ) {
                w.options.forEachIndexed { i, choice -> Option(i, choice, on = w.pick == i, picked = w.state == Picked, phone, enabled, onPick) }
            }
        }
    }
}

private fun say(n: Int): String {
    if (n < 2) return ""
    return ui.sayOneOf(NUMBERS.take(n))
}

@Composable
private fun Option(i: Int, choice: Choice, on: Boolean, picked: Boolean, phone: Boolean, enabled: Boolean, onPick: (Int) -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val bg = animateColorAsState(if (on) c.wash else if (hovered) c.hover else c.ink.copy(alpha = 0f), css(150, easing = Ease))
    val dim = animateFloatAsState(if (picked && !on) .35f else 1f, css(200))
    // The bar's 24 dp corner minus the panel's 10 dp inset, so the curves stay concentric.
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (phone) 52.dp else 36.dp) // a thumb needs a taller row than a cursor; a long name wraps
            .graphicsLayer { alpha = dim.value }
            .clip(shape)
            .drawBehind { drawRect(bg.value) }
            .then(if (focused) Modifier.border(2.dp, c.ink, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) { onPick(i) }
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(i, on, check = picked && on)
        Spacer(Modifier.width(10.dp))
        Glyph(choice.kind)
        Spacer(Modifier.width(10.dp))
        val name = type(14f, 20f, 400, -.084f, c.ink)
        val meta = type(12f, 16f, 400, color = c.hint)
        if (phone) {
            // On two versions of one file the end of the name is the difference, so the date drops under it.
            Column(Modifier.weight(1f)) {
                BasicText(choice.name, style = name, overflow = TextOverflow.Ellipsis, maxLines = 2)
                BasicText(choice.meta, style = meta, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
            }
        } else {
            BasicText(choice.name, Modifier.weight(1f), name, overflow = TextOverflow.Ellipsis, maxLines = 2)
            Spacer(Modifier.width(10.dp))
            // Capped, so a deck's long name cannot push the option's own name out; its end (the slide) is what differs.
            BasicText(choice.meta, Modifier.widthIn(max = 104.dp), meta.copy(textAlign = TextAlign.End), overflow = TextOverflow.StartEllipsis, softWrap = false, maxLines = 1)
        }
    }
}

private val CHECK = svg("M2.6 6.3l2.2 2.2 4.6-4.9")

// The number you can say, bare; it fills with ink only on the row that is picked.
@Composable
private fun Key(i: Int, on: Boolean, check: Boolean) {
    val c = LocalMoleColors.current
    val bg = animateColorAsState(if (on) c.ink else c.ink.copy(alpha = 0f), css(150, easing = Ease))
    val fg = animateColorAsState(if (on) c.paper else c.hint, css(150, easing = Ease))
    Box(Modifier.size(20.dp).drawBehind { drawRoundRect(bg.value, cornerRadius = CornerRadius(6.dp.toPx())) }, Alignment.Center) {
        if (check) Canvas(Modifier.size(12.dp)) {
            scale(size.width / 12f, Offset.Zero) { drawPath(CHECK, fg.value, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        } else BasicText("${i + 1}", style = type(12f, 12f, 500, color = Color.Unspecified, tabular = true), color = { fg.value })
    }
}

// Tiny cut-paper documents in the card's style: white paper, ink edge, faded ink lines. Same shapes as GLYPHS in mole.js.
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
private fun Glyph(kind: Choice.Kind) {
    val c = LocalMoleColors.current
    Canvas(Modifier.size(16.dp)) {
        val g = GLYPHS.getValue(kind)
        scale(size.width / 16f, Offset.Zero) {
            drawPath(g.paper, c.lens)
            drawPath(g.paper, c.rim, style = Stroke(1.1f, join = StrokeJoin.Round))
            drawPath(g.lines, c.rim, alpha = .45f, style = Stroke(1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
