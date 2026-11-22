package ch.lauzhack.app.mole

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Lang
import ch.lauzhack.app.Target
import ch.lauzhack.app.lang
import ch.lauzhack.app.ui
import ch.lauzhack.app.meeting.Meeting
import ch.lauzhack.app.stage.HOME
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// What to say for each of ui.sayLabels: the lines of DEMO.txt section 1, checked against the sample decks, and the last one from rehearsal/seen-1.txt.
private val SAY = listOf(
    listOf("Okay, let’s open up the company slides.", "Now let’s switch to the Q3 financials."),
    listOf("Next slide please.", "Go to slide 7."),
    listOf("So how big is this market really?", "What about churn?"),
    listOf("Open GitHub.", "Scroll down a bit.", "Close that."),
    listOf("No, not that one.", "No, go back."),
    listOf("Open the ferry timetable I had up this morning."),
)
private val LABEL = 176.dp
/** The system's language when it is one of Molière's, else English. */
internal val SYSTEM = Lang.of(Locale.getDefault().language)
private val TICK = svg("M3.9 9.45l3.3 3.3 6.9-7.35")

/** The tray's settings, changed the tray's way: the same state under the same keys, so each shows the other's change. */
internal class Settings(
    private val prefs: Preferences,
    val library: MutableStateFlow<File>,
    val history: MutableStateFlow<Boolean>,
    val window: MutableStateFlow<Duration>,
    val waits: List<Long>,
    val theme: MutableState<MoleTheme>,
    val language: MutableState<Lang?> = mutableStateOf(null), // null follows the system
) {
    fun decks(dir: File) = save { library.value = dir; put("decks", dir.path) }
    fun history(on: Boolean) = save { history.value = on; putBoolean("history", on) }
    fun window(ms: Long) = save { window.value = ms.milliseconds; putLong("window", ms) }
    fun theme(t: MoleTheme) = save { theme.value = t; put("theme", t.name) }
    fun language(l: Lang?) = save { language.value = l; lang = l ?: SYSTEM; put("language", l?.name ?: "") }

    private fun save(change: Preferences.() -> Unit) {
        prefs.change()
        prefs.flush()
    }
}

/** The window Molière opens with. Closing it leaves the mole in the menu bar; each new [raise] brings it to the front. */
@Composable
internal fun ApplicationScope.HomeWindow(raise: Int, onClose: () -> Unit, theme: MoleTheme, content: @Composable (Demo) -> Unit) {
    val scope = rememberCoroutineScope()
    val demo = remember { Demo(scope) }
    LaunchedEffect(demo) { demo.play() }
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(size = DpSize(720.dp, 780.dp)),
        title = "Molière",
        onPreviewKeyEvent = { it.type == KeyEventType.KeyDown && it.isMetaPressed && it.key == Key.W && true.also { onClose() } },
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(480, 560)
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        LaunchedEffect(raise) {
            window.toFront()
            runCatching { Desktop.getDesktop().requestForeground(true) } // a menu bar app is not in front, and its window would open behind
        }
        Column(Modifier.fillMaxSize().background(theme.colors().page)) {
            WindowDraggableArea { Spacer(Modifier.fillMaxWidth().height(28.dp)) } // the clear title bar
            content(demo)
        }
    }
}

/**
 * What Molière is and what it is doing, what to say to it, and the tray's settings. While asleep the bar plays [sample];
 * in a call, or whenever the bar has something to say, it shows [line].
 */
@Composable
internal fun Home(
    meeting: StateFlow<Meeting?>,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    line: StateFlow<MoleLine>,
    onPick: (Int) -> Unit,
    sample: StateFlow<MoleLine>,
    onSample: (Int) -> Unit,
    onScreen: StateFlow<OnScreen>,
    phone: StateFlow<Boolean>,
    settings: Settings,
    choose: (File) -> File?,
) {
    val theme by settings.theme
    val call by meeting.collectAsState()
    val now by line.collectAsState()
    val seen by onScreen.collectAsState()
    val phoned by phone.collectAsState()
    var heard by remember(call) { mutableStateOf("") }
    LaunchedEffect(now.heard) { if (now.heard.isNotEmpty()) heard = now.heard }
    val live = call != null || now.state != MoleState.Hidden
    val bar = if (!live) sample.collectAsState().value else if (now.state == MoleState.Hidden) MoleLine(MoleState.Idle) else now
    Themed(theme) {
        val c = LocalMoleColors.current
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxSize().background(c.page)) {
            val side = if (maxWidth < 600.dp) 24.dp else 48.dp
            val wide = maxWidth - side * 2 >= 520.dp
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = side, end = side, top = 12.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    Crossfade(call != null, animationSpec = css(200)) { inCall ->
                        Column {
                            BasicText(if (inCall) ui.inMeeting else ui.asleep, style = type(28f, 34f, 600, -.616f, c.ink))
                            Spacer(Modifier.height(12.dp))
                            if (inCall) Column(verticalArrangement = Arrangement.spacedBy(if (wide) 4.dp else 12.dp)) {
                                Field(ui.heard, wide) { Body(if (heard.isEmpty()) ui.nothingYet else ui.quote(heard), if (heard.isEmpty()) c.hint else c.ink, lines = 2) }
                                Field(ui.onScreen, wide) { Body(seen.label(), c.ink) }
                            } else Body(ui.about, c.muted, lines = 3)
                            Spacer(Modifier.height(24.dp))
                            Primary(if (inCall) ui.endMeeting else ui.startMeeting, if (inCall) onEnd else onStart)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    // Tall enough for the sample's two options to open upward clear of the label.
                    Box(Modifier.fillMaxWidth().heightIn(min = 252.dp).clip(RoundedCornerShape(16.dp)).background(c.fill).padding(bottom = 20.dp), Alignment.BottomCenter) {
                        if (!live) BasicText(ui.sample, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 14.dp), type(12f, 16f, 500, color = c.hint))
                        MoleBar(bar, if (live) onPick else onSample, theme = theme)
                    }
                    Section(ui.thingsToSay, ui.thingsNote) {
                        for ((what, lines) in ui.sayLabels.zip(SAY)) Field(what, wide) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { for (l in lines) Body(ui.quote(l), c.ink) }
                        }
                    }
                    Section(ui.settings) { Choices(settings, choose, wide) }
                    Column(Modifier.padding(top = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val quiet = type(13f, 18f, 400, -.039f, c.hint)
                        BasicText(ui.runsHere, style = quiet)
                        BasicText(if (call == null) ui.phoneLater else if (phoned) ui.phoneOn else ui.phoneOff, style = quiet)
                    }
                }
            }
            CompositionLocalProvider(LocalScrollbarStyle provides ScrollbarStyle(32.dp, 6.dp, RoundedCornerShape(3.dp), 150, c.hairline, c.hint)) {
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(3.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Choices(s: Settings, choose: (File) -> File?, wide: Boolean) {
    val c = LocalMoleColors.current
    val lib by s.library.collectAsState()
    val decks by produceState(count(lib), lib) {
        while (true) {
            value = count(lib)
            delay(3.seconds)
        }
    }
    val wait by s.window.collectAsState()
    val history by s.history.collectAsState()
    Field(ui.decks, wide) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), Arrangement.spacedBy(10.dp)) {
                TooltipArea({ Tip(lib.path.replaceFirst(HOME.path, "~")) }, Modifier.weight(1f, fill = false).alignByBaseline()) { Body(lib.name, c.ink) }
                BasicText(if (decks == 0) ui.noPdfHere else ui.deckCount(decks), Modifier.alignByBaseline(), type(13f, 18f, 400, -.039f, c.muted), softWrap = false, maxLines = 1)
            }
            Spacer(Modifier.width(16.dp))
            Secondary(ui.choose) { choose(lib)?.let(s::decks) }
        }
    }
    Field(ui.seenRecently, wide) { Check(ui.useHistory, history, s::history) }
    Field(ui.waitBefore, wide) { Segments(s.waits.map { ui.wait(it) to it }, wait.inWholeMilliseconds, s::window) }
    Field(ui.themeLabel, wide) { Segments(MoleTheme.entries.map { ui.theme(it) to it }, s.theme.value, s::theme) }
    Field(ui.language, wide) { Segments(listOf(ui.system to null) + Lang.entries.map { it.own to it }, s.language.value, s::language) }
}

private fun count(dir: File) = dir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }?.size ?: 0

private fun OnScreen.label() = when (val t = target) {
    null -> ui.desktop
    is Target.Slide -> ui.slideOf(name, t.page)
    else -> name
}

@Composable
private fun Section(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMoleColors.current
    Column(Modifier.fillMaxWidth().padding(top = 56.dp)) {
        BasicText(title, style = type(15f, 22f, 600, -.135f, c.ink))
        if (note != null) BasicText(note, Modifier.padding(top = 6.dp), type(13f, 18f, 400, -.039f, c.muted))
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

// A label in its own column on a wide window, above what it names on a narrow one.
@Composable
private fun Field(label: String, wide: Boolean, content: @Composable () -> Unit) {
    val style = type(13f, 22f, 500, -.039f, LocalMoleColors.current.muted)
    if (wide) Row {
        BasicText(label, Modifier.width(LABEL).padding(end = 16.dp).alignByBaseline(), style)
        Box(Modifier.weight(1f).alignByBaseline()) { content() }
    } else Column {
        BasicText(label, style = style)
        content()
    }
}

@Composable
private fun Body(text: String, color: Color, lines: Int = 1) =
    BasicText(text, style = type(15f, 22f, 400, -.135f, color), overflow = TextOverflow.Ellipsis, softWrap = lines > 1, maxLines = lines)

@Composable
private fun Tip(text: String) {
    val c = LocalMoleColors.current
    Box(Modifier.background(c.hairline, RoundedCornerShape(9.dp)).padding(1.dp).background(c.paper, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        BasicText(text, style = type(12f, 16f, 400, color = c.ink))
    }
}

// Press feedback and the keyboard's focus ring, on whatever [interaction] drives.
@Composable
private fun Modifier.feedback(interaction: MutableInteractionSource, radius: Dp, press: Boolean = true): Modifier {
    val c = LocalMoleColors.current
    val reduce = reduceMotion()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (press && pressed && !reduce) .96f else 1f, css(150))
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }.drawBehind {
        if (!focused) return@drawBehind
        val o = 3.dp.toPx() // outline 2px, offset 2px
        drawRoundRect(c.ink, Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o), CornerRadius(radius.toPx() + o), style = Stroke(2.dp.toPx()))
    }
}

// The one red thing besides the mole; its words are cut from the page.
@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.feedback(interaction, 20.dp).height(40.dp).background(c.ember, RoundedCornerShape(20.dp))
            .clickable(interaction, null, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        Alignment.Center,
    ) { BasicText(label, style = type(15f, 20f, 600, -.135f, c.page), softWrap = false, maxLines = 1) }
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.feedback(interaction, 8.dp).height(32.dp).background(c.fill, RoundedCornerShape(8.dp))
            .clickable(interaction, null, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        Alignment.Center,
    ) { BasicText(label, style = type(13f, 16f, 500, -.039f, c.ink), softWrap = false, maxLines = 1) }
}

@Composable
private fun Check(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    val fill = animateFloatAsState(if (on) 1f else 0f, css(150, easing = Ease))
    Row(
        Modifier.height(32.dp).toggleable(on, interaction, null, role = Role.Checkbox, onValueChange = onChange),
        Arrangement.spacedBy(10.dp),
        Alignment.CenterVertically,
    ) {
        Canvas(Modifier.feedback(interaction, 5.dp, press = false).size(18.dp)) {
            val r = 5.dp.toPx()
            drawRoundRect(c.ink, cornerRadius = CornerRadius(r), alpha = fill.value)
            if (on) scale(size.width / 18f, Offset.Zero) { drawPath(TICK, c.paper, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            else {
                val w = 1.5.dp.toPx()
                drawRoundRect(c.hint, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r - w / 2), Stroke(w))
            }
        }
        Body(label, c.ink)
    }
}

// index.html's .controls buttons as one choice: quiet until picked, then ink. The pick animates, a change of theme does not.
@Composable
private fun <T> Segments(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    val c = LocalMoleColors.current
    Row(Modifier.selectableGroup().background(c.fill, RoundedCornerShape(10.dp)).padding(2.dp), Arrangement.spacedBy(2.dp)) {
        for ((label, value) in options) {
            val on = value == selected
            val interaction = remember { MutableInteractionSource() }
            val k = animateFloatAsState(if (on) 1f else 0f, css(150, easing = Ease))
            Box(
                Modifier.feedback(interaction, 8.dp, press = false).height(28.dp).drawBehind { drawRoundRect(c.ink, cornerRadius = CornerRadius(8.dp.toPx()), alpha = k.value) }
                    .selectable(on, interaction, null, role = Role.RadioButton) { onPick(value) }.padding(horizontal = 12.dp),
                Alignment.Center,
            ) { BasicText(label, style = type(13f, 16f, 500, -.039f, Color.Unspecified), color = { lerp(c.ink, c.paper, k.value) }, softWrap = false, maxLines = 1) }
        }
    }
}
