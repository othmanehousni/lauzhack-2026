package ch.lauzhack.app.mole

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Lang
import ch.lauzhack.app.Target
import ch.lauzhack.app.meeting.Meeting
import ch.lauzhack.app.session.Mime
import ch.lauzhack.app.stage.HOME
import ch.lauzhack.app.lang
import ch.lauzhack.app.ui
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

// What to say, by what it does (ui.kinds: 0 a slide, 1 a site, 2 nothing): the lines of DEMO.txt section 1, checked
// against the sample decks, and the last one from rehearsal/seen-1.txt.
private val SAY = listOf(
    0 to listOf("Okay, let’s open up the company slides.", "Now let’s switch to the Q3 financials."),
    0 to listOf("Next slide please.", "Go to slide 7."),
    0 to listOf("So how big is this market really?", "What about churn?"),
    1 to listOf("Open GitHub.", "Scroll down a bit.", "Close that."),
    2 to listOf("No, not that one.", "No, go back."),
    1 to listOf("Open the ferry timetable I had up this morning."),
)
private val SIDE = 288.dp
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** One thing Molière heard in this meeting, when, and what it made of it. */
private class Heard(val at: String, val words: String, var did: String)

@Composable
internal fun Overview(
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
    scroll: ScrollState,
    toSay: Boolean,
    mime: Mime,
    onLanguage: () -> Unit,
) {
    val c = LocalMoleColors.current
    val f = frameOf(c)
    val call by meeting.collectAsState()
    val now by line.collectAsState()
    val seen by onScreen.collectAsState()
    val phoned by phone.collectAsState()
    val mode by settings.mode.collectAsState()
    val miming = mode == Mode.Mime
    val mimed by mime.line.collectAsState()
    val reading = mimed.state != MoleState.Hidden
    val ready by produceState(mime.ready()) {
        while (true) {
            value = mime.ready()
            delay(3.seconds)
        }
    }
    val heard = remember(call) { mutableStateListOf<Heard>() }
    LaunchedEffect(now) {
        if (call == null || now.heard.isEmpty()) return@LaunchedEffect
        val did = now.title ?: ui.titles[now.state].orEmpty()
        if (heard.firstOrNull()?.words == now.heard) heard[0] = Heard(heard[0].at, now.heard, did.ifEmpty { heard[0].did })
        else heard.add(0, Heard(LocalTime.now().format(CLOCK), now.heard, did))
    }
    val live = if (miming) reading else call != null || now.state != MoleState.Hidden
    val bar = when {
        miming -> if (reading) mimed else MoleLine(MoleState.Idle, ui.mouthWords)
        !live -> sample.collectAsState().value
        now.state == MoleState.Hidden -> MoleLine(MoleState.Idle)
        else -> now
    }
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var say by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val glide = css<Float>(200)
    LaunchedEffect(toSay) {
        val top = content ?: return@LaunchedEffect
        if (toSay) say?.let { scroll.animateScrollTo(top.localPositionOf(it, Offset.Zero).y.toInt() - 40, glide) }
        else scroll.animateScrollTo(0, glide)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 920.dp
        val side = if (maxWidth < 760.dp) 32.dp else 56.dp
        Column(Modifier.fillMaxSize().verticalScroll(scroll).onGloballyPositioned { content = it }.padding(horizontal = side, vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth()) {
                BasicText(ui.welcome, style = type(28f, 34f, 600, -.616f, c.ink))
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        val mole = Modifier.size(148.dp, 110.dp)
                        if (miming) Hero(ui.mimeTitle, ui.mimeLine, if (reading) ui.stopMiming else ui.startMiming, if (reading) mime::stop else mime::start, f.hero) { MimeMole(mole) }
                        else {
                            val inCall = call != null
                            Hero(if (inCall) ui.inMeeting else ui.heroTitle, if (inCall) ui.isOnScreen(seen.label()) else ui.heroLine,
                                if (inCall) ui.endMeeting else ui.startMeeting, if (inCall) onEnd else onStart, f.hero) {
                                MolePuppet(MoleState.Retrieving, null, "07", "", mole) // the mole with a slide in its paw
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Card(f) {
                            if (miming) {
                                Status(Icon.Camera, ui.lipReading, if (!ready) ui.lipsNotSetUp else if (reading) ui.lipsOn else ui.lipsReady) { Lamp(reading) }
                                if (!ready) {
                                    Rule()
                                    SetUp()
                                }
                            } else {
                                Status(Icon.Phone, ui.phone, if (call == null) ui.phoneLater else if (phoned) ui.phoneOn else ui.phoneOff) { Lamp(phoned) }
                                Rule()
                                Status(Icon.Screen, ui.onScreen, seen.label()) {}
                            }
                        }
                        if (!wide) {
                            Spacer(Modifier.height(16.dp))
                            Library(settings, choose, f)
                            Spacer(Modifier.height(16.dp))
                            Language(settings, f, onLanguage)
                        }
                        if (!miming) {
                            Spacer(Modifier.height(44.dp))
                            Column(Modifier.onGloballyPositioned { say = it }) {
                                if (call != null && heard.isNotEmpty()) {
                                    Label(ui.thisMeeting)
                                    Rows(heard.map { Triple(it.at, listOf(ui.quote(it.words)), it.did) })
                                } else {
                                    Label(ui.thingsToSay, ui.thingsNote)
                                    Rows(SAY.map { (kind, lines) -> Triple(ui.kinds[kind], lines.map(ui.quote), null) })
                                }
                            }
                        }
                        Spacer(Modifier.height(44.dp))
                        Label(if (live) ui.live else ui.sample)
                        // Tall enough for the sample's options to open upward inside it.
                        Box(Modifier.fillMaxWidth().heightIn(min = 252.dp).clip(RoundedCornerShape(16.dp)).background(f.card).border(1.dp, c.rule, RoundedCornerShape(16.dp)).padding(bottom = 20.dp), Alignment.BottomCenter) {
                            MoleBar(bar, if (live) onPick else onSample, theme = if (c == MoleColors.Dark) MoleTheme.Dark else MoleTheme.Light, mode = mode)
                        }
                    }
                    if (wide) Column(Modifier.width(SIDE), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Library(settings, choose, f)
                        Language(settings, f, onLanguage)
                    }
                }
            }
        }
        Scrollbar(scroll)
    }
}

@Composable
private fun Scrollbar(scroll: ScrollState) {
    val c = LocalMoleColors.current
    CompositionLocalProvider(LocalScrollbarStyle provides ScrollbarStyle(32.dp, 6.dp, RoundedCornerShape(3.dp), 150, c.hairline, c.hint)) {
        Box(Modifier.fillMaxSize()) { VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(3.dp)) }
    }
}

// The dark banner: what the mode does, or that Molière is in a meeting and what is on screen; the one red button; the mole.
@Composable
private fun Hero(title: String, line: String, button: String, onClick: () -> Unit, color: Color, mole: @Composable () -> Unit) {
    val light = Color(0xFFF4F2EF)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(start = 32.dp, end = 20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 28.dp)) {
            BasicText(title, style = type(22f, 28f, 600, -.4f, light))
            Spacer(Modifier.height(6.dp))
            BasicText(line, style = type(15f, 22f, 400, -.135f, light.copy(alpha = .72f)), maxLines = 3)
            Spacer(Modifier.height(20.dp))
            Primary(button, onClick)
        }
        Spacer(Modifier.width(16.dp))
        CompositionLocalProvider(LocalMoleColors provides MoleColors.Dark, content = mole)
    }
}

private val SETUP = listOf("git lfs pull", "lipreading/setup.sh")

// What sets the lip reader up, to copy into Terminal.
@Composable
private fun SetUp() {
    val c = LocalMoleColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Body(ui.runOnce, c.muted, lines = 2, size = 13f)
        SelectionContainer {
            BasicText(
                SETUP.joinToString("\n"),
                Modifier.fillMaxWidth().background(c.fill, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                type(13f, 20f, 500, -.039f, c.ink),
            )
        }
    }
}

@Composable
private fun Card(f: Frame, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMoleColors.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(f.card).border(1.dp, c.rule, RoundedCornerShape(16.dp)), content = content)
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalMoleColors.current.rule))

@Composable
private fun Tile(i: Icon) {
    val c = LocalMoleColors.current
    Canvas(Modifier.size(40.dp).background(c.paper, RoundedCornerShape(10.dp)).border(1.dp, c.rule, RoundedCornerShape(10.dp)).padding(10.dp)) { icon(i, c.ink) }
}

// One row of the status card: an icon, a title, one line, and a state or a button.
@Composable
private fun Status(i: Icon, title: String, line: String, trailing: @Composable () -> Unit) {
    val c = LocalMoleColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        Tile(i)
        Column(Modifier.weight(1f)) {
            BasicText(title, style = type(15f, 20f, 500, -.135f, c.ink), maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Body(line, c.muted, lines = 2, size = 13f)
        }
        trailing()
    }
}

// Filled when connected, a ring when not; the line beside it says which in words.
@Composable
private fun Lamp(on: Boolean) {
    val c = LocalMoleColors.current
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) c.ink else Color.Transparent).border(1.5.dp, if (on) c.ink else c.hint, CircleShape))
}

@Composable
private fun Label(text: String, note: String? = null) {
    val c = LocalMoleColors.current
    BasicText(text.uppercase(lang.locale()), style = type(12f, 16f, 600, .72f, c.muted))
    if (note != null) BasicText(note, Modifier.padding(top = 6.dp), type(13f, 18f, 400, -.039f, c.muted))
    Spacer(Modifier.height(14.dp))
}

private fun Lang.locale() = java.util.Locale.forLanguageTag(name.lowercase())

// The reference's list of dictations: on the left a time or what it does, the words, hairlines between, a quiet hover.
@Composable
private fun Rows(rows: List<Triple<String, List<String>, String?>>) {
    val c = LocalMoleColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.rule, RoundedCornerShape(16.dp))) {
        rows.forEachIndexed { n, (left, lines, under) ->
            if (n > 0) Rule()
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(Modifier.fillMaxWidth().background(if (hovered) c.hover else Color.Transparent).hoverable(interaction).padding(horizontal = 20.dp, vertical = 16.dp)) {
                BasicText(left, Modifier.width(104.dp).padding(end = 16.dp, top = 2.dp), type(13f, 18f, 400, -.039f, c.muted, tabular = true), maxLines = 2)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (l in lines) BasicText(l, style = type(15f, 22f, 400, -.135f, c.ink))
                    if (under != null) BasicText(under, style = type(13f, 18f, 400, -.039f, c.muted))
                }
            }
        }
    }
}

// The decks folder: how many decks and other files are in it, its name, and the choice of another.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Library(s: Settings, choose: (File) -> File?, f: Frame) {
    val c = LocalMoleColors.current
    val lib by s.library.collectAsState()
    val counts by produceState(count(lib), lib) {
        while (true) {
            value = count(lib)
            delay(3.seconds)
        }
    }
    Card(f) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Number(ui.deckCount(counts.first), counts.first)
            Number("${counts.second} ${ui.otherFiles}", counts.second)
        }
        Rule()
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            Canvas(Modifier.size(20.dp)) { icon(Icon.Folder, c.muted) }
            TooltipArea({ Tip(lib.path.replaceFirst(HOME.path, "~")) }, Modifier.weight(1f)) { Body(lib.name, c.ink, size = 13f) }
            Secondary(ui.choose) { choose(lib)?.let(s::decks) }
        }
    }
}

// "3 decks" with the number large, in whatever order the language puts its words.
@Composable
private fun Number(text: String, n: Int) {
    val c = LocalMoleColors.current
    val digits = n.toString()
    val at = text.indexOf(digits)
    BasicText(
        buildAnnotatedString {
            append(text.substring(0, at.coerceAtLeast(0)))
            if (at >= 0) withStyle(SpanStyle(fontSize = 30.sp, fontWeight = FontWeight(500), fontFamily = inter(30f, 500), letterSpacing = (-.6).sp)) { append(digits) }
            append(text.substring(if (at >= 0) at + digits.length else 0))
        },
        style = type(15f, 36f, 400, -.135f, c.ink, tabular = true),
        maxLines = 1,
    )
}

// The language the window reads in, and that the presenter speaks English to the mole.
@Composable
private fun Language(s: Settings, f: Frame, onClick: () -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Card(f, Modifier.feedback(interaction, 16.dp, press = false).hoverable(interaction).clickable(interaction, null, role = Role.Button, onClick = onClick)) {
        Row(Modifier.background(if (hovered) c.hover else Color.Transparent).padding(start = 24.dp, top = 22.dp, bottom = 18.dp, end = 12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                BasicText(ui.language, style = type(17f, 24f, 600, -.2f, c.ink))
                Spacer(Modifier.height(4.dp))
                Body((s.language.value ?: SYSTEM).own, c.ink, size = 13f)
                Spacer(Modifier.height(2.dp))
                Body(ui.spokenEnglish, c.muted, lines = 3, size = 13f)
            }
            MolePuppet(MoleState.Idle, null, "", "", Modifier.size(83.dp, 62.dp))
        }
    }
}

private fun count(dir: File): Pair<Int, Int> {
    val files = dir.listFiles { x -> x.isFile && !x.isHidden }.orEmpty()
    val decks = files.count { it.extension.equals("pdf", ignoreCase = true) }
    return decks to files.size - decks
}

internal fun OnScreen.label() = when (val t = target) {
    null -> ui.desktop
    is Target.Slide -> ui.slideOf(name, t.page)
    else -> name
}

@Composable
internal fun SettingsPage(s: Settings, choose: (File) -> File?) {
    val c = LocalMoleColors.current
    val f = frameOf(c)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (maxWidth < 760.dp) 32.dp else 56.dp
        val wide = maxWidth - side * 2 >= 720.dp
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = side, vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth()) {
                BasicText(ui.settings, style = type(28f, 34f, 600, -.616f, c.ink))
                Spacer(Modifier.height(28.dp))
                Card(f) { Choices(s, choose, wide) }
            }
        }
        Scrollbar(scroll)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Choices(s: Settings, choose: (File) -> File?, wide: Boolean) {
    val c = LocalMoleColors.current
    val lib by s.library.collectAsState()
    val decks by produceState(count(lib).first, lib) {
        while (true) {
            value = count(lib).first
            delay(3.seconds)
        }
    }
    val wait by s.window.collectAsState()
    val history by s.history.collectAsState()
    Field(ui.modeLabel, wide) { Segments(Mode.entries.map { ui.mode(it) to it }, s.mode.collectAsState().value, s::mode) }
    Rule()
    Field(ui.decks, wide) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), Arrangement.spacedBy(10.dp)) {
                TooltipArea({ Tip(lib.path.replaceFirst(HOME.path, "~")) }, Modifier.weight(1f, fill = false).alignByBaseline()) { Body(lib.name, c.ink) }
                BasicText(if (decks == 0) ui.noPdfHere else ui.deckCount(decks), Modifier.alignByBaseline(), type(13f, 18f, 400, -.039f, c.muted, tabular = true), softWrap = false, maxLines = 1)
            }
            Spacer(Modifier.width(16.dp))
            Secondary(ui.choose) { choose(lib)?.let(s::decks) }
        }
    }
    Rule()
    Field(ui.seenRecently, wide) { Check(ui.useHistory, history, s::history) }
    Rule()
    Field(ui.waitBefore, wide) { Segments(s.waits.map { ui.wait(it) to it }, wait.inWholeMilliseconds, s::window) }
    Rule()
    Field(ui.themeLabel, wide) { Segments(MoleTheme.entries.map { ui.theme(it) to it }, s.theme.value, s::theme) }
    Rule()
    Field(ui.language, wide) { Segments(listOf(ui.system to null) + Lang.entries.map { it.own to it }, s.language.value, s::language) }
}

private val LABEL: Dp = 200.dp

// A label in its own column on a wide panel, above what it names on a narrow one.
@Composable
private fun Field(label: String, wide: Boolean, content: @Composable () -> Unit) {
    val style = type(13f, 22f, 500, -.039f, LocalMoleColors.current.muted)
    val pad = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 16.dp)
    if (wide) Row(pad, verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.width(LABEL).padding(end = 16.dp), style)
        Box(Modifier.weight(1f)) { content() }
    } else Column(pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, style = style)
        content()
    }
}
