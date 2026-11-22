package ch.lauzhack.app.mole

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import ch.lauzhack.app.Lang
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.lang
import ch.lauzhack.app.meeting.Meeting
import ch.lauzhack.app.ui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

/** The window's own tones around the mole's: a warm frame, the lighter panel in it, a card on the panel, the dark banner. */
internal class Frame(val frame: Color, val panel: Color, val card: Color, val hero: Color)

private val LightFrame = Frame(Color(0xFFF4F2EF), Color(0xFFFCFCFA), Color(0xFFF6F5F2), Color(0xFF161616)) // oklch(.962 .005 85), (.99 .002 85), (.97 .004 85)
private val DarkFrame = Frame(Color(0xFF0E0D0C), Color(0xFF171614), Color(0xFF1F1E1C), Color(0xFF060605)) // oklch(.16 .003 85), (.2 .003 85), (.235 .003 85), (.12 .002 85)

internal fun frameOf(c: MoleColors) = if (c == MoleColors.Dark) DarkFrame else LightFrame

/** The window Molière opens with. Closing it leaves the mole in the menu bar; each new [raise] brings it to the front. */
@Composable
internal fun ApplicationScope.HomeWindow(raise: Int, onClose: () -> Unit, theme: MoleTheme, content: @Composable (Demo) -> Unit) {
    val scope = rememberCoroutineScope()
    val demo = remember { Demo(scope) }
    LaunchedEffect(demo) { demo.play() }
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        title = "Molière",
        onPreviewKeyEvent = { it.type == KeyEventType.KeyDown && it.isMetaPressed && it.key == Key.W && true.also { onClose() } },
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(720, 560)
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        LaunchedEffect(raise) {
            window.toFront()
            runCatching { Desktop.getDesktop().requestForeground(true) } // a menu bar app is not in front, and its window would open behind
        }
        Column(Modifier.fillMaxSize().background(frameOf(theme.colors()).frame)) {
            WindowDraggableArea { Spacer(Modifier.fillMaxWidth().height(28.dp)) } // the clear title bar
            content(demo)
        }
    }
}

internal enum class Page { Home, Say, Settings }

/**
 * What Molière is and what it is doing, what to say to it, and the tray's settings: a rail on the left, a panel with the
 * page. While asleep the bar plays [sample]; in a call, or whenever the bar has something to say, it shows [line].
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
    Themed(theme) {
        val c = LocalMoleColors.current
        val f = frameOf(c)
        var page by remember { mutableStateOf(Page.Home) }
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxSize().background(f.frame)) {
            Column(Modifier.width(64.dp).fillMaxHeight().padding(top = 4.dp, bottom = 16.dp).selectableGroup(), horizontalAlignment = Alignment.CenterHorizontally) {
                MolePuppet(MoleState.Idle, null, "", "", Modifier.size(44.dp, 33.dp))
                Spacer(Modifier.height(20.dp))
                RailItem(ui.home, page == Page.Home, Icon.Home) { page = Page.Home }
                Spacer(Modifier.height(4.dp))
                RailItem(ui.thingsToSay, page == Page.Say, Icon.Say) { page = Page.Say }
                Spacer(Modifier.weight(1f))
                RailItem(ui.settings, page == Page.Settings, Icon.Settings) { page = Page.Settings }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(f.panel).border(1.dp, c.rule, RoundedCornerShape(16.dp)),
            ) {
                if (page == Page.Settings) SettingsPage(settings, choose)
                else Overview(meeting, onStart, onEnd, line, onPick, sample, onSample, onScreen, phone, settings, choose, scroll, page == Page.Say) { page = Page.Settings }
            }
        }
    }
}

internal enum class Icon { Home, Say, Settings, Phone, Screen, Folder }

internal fun DrawScope.icon(i: Icon, color: Color) {
    val s = size.width / 24f
    val line = Stroke(1.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float) = drawRoundRect(color, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r * s), style = line)
    fun seg(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, Offset(x0 * s, y0 * s), Offset(x1 * s, y1 * s), line.width, StrokeCap.Round)
    when (i) {
        Icon.Home -> { box(4f, 4f, 6.5f, 6.5f, 1.8f); box(13.5f, 4f, 6.5f, 6.5f, 1.8f); box(4f, 13.5f, 6.5f, 6.5f, 1.8f); box(13.5f, 13.5f, 6.5f, 6.5f, 1.8f) }
        Icon.Say -> scale(s, Offset.Zero) { drawPath(BUBBLE, color, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        Icon.Settings -> {
            seg(4f, 8f, 11f, 8f); seg(17f, 8f, 20f, 8f); seg(4f, 16f, 7f, 16f); seg(13f, 16f, 20f, 16f)
            drawCircle(color, 2.5f * s, Offset(14f * s, 8f * s), style = line); drawCircle(color, 2.5f * s, Offset(10f * s, 16f * s), style = line)
        }
        Icon.Phone -> { box(7f, 3f, 10f, 18f, 2.5f); seg(11f, 18f, 13f, 18f) }
        Icon.Screen -> { box(3f, 4.5f, 18f, 12f, 2f); seg(12f, 16.5f, 12f, 19.5f); seg(8.5f, 19.5f, 15.5f, 19.5f) }
        Icon.Folder -> scale(s, Offset.Zero) { drawPath(FOLDER, color, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

private val BUBBLE = svg("M6.5 4.5h11a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5H11.5l-4.5 3.5v-3.5h-.5A2.5 2.5 0 0 1 4 14V7a2.5 2.5 0 0 1 2.5-2.5z")
private val FOLDER = svg("M3.5 7.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailItem(label: String, selected: Boolean, i: Icon, onClick: () -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    TooltipArea({ Tip(label) }, delayMillis = 400, tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(8.dp, 0.dp))) {
        Canvas(
            Modifier.feedback(interaction, 10.dp, press = false).size(40.dp)
                .background(if (selected) c.wash else if (hovered) c.hover else Color.Transparent, RoundedCornerShape(10.dp))
                .hoverable(interaction)
                .selectable(selected, interaction, null, role = Role.Tab, onClick = onClick)
                .semantics { contentDescription = label },
        ) {
            inset(10.dp.toPx()) { icon(i, if (selected) c.ink else c.muted) }
        }
    }
}

@Composable
internal fun Body(text: String, color: Color, lines: Int = 1, size: Float = 15f) =
    BasicText(text, style = if (size == 15f) type(15f, 22f, 400, -.135f, color) else type(size, size + 5f, 400, -.039f, color), overflow = TextOverflow.Ellipsis, softWrap = lines > 1, maxLines = lines)

@Composable
internal fun Tip(text: String) {
    val c = LocalMoleColors.current
    Box(Modifier.background(c.hairline, RoundedCornerShape(9.dp)).padding(1.dp).background(c.paper, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        BasicText(text, style = type(12f, 16f, 400, color = c.ink))
    }
}

// Press feedback and the keyboard's focus ring, on whatever [interaction] drives.
@Composable
internal fun Modifier.feedback(interaction: MutableInteractionSource, radius: Dp, press: Boolean = true): Modifier {
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
internal fun Primary(label: String, onClick: () -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.feedback(interaction, 20.dp).height(40.dp).background(c.ember, RoundedCornerShape(20.dp))
            .clickable(interaction, null, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        Alignment.Center,
    ) { BasicText(label, style = type(15f, 20f, 600, -.135f, c.page), softWrap = false, maxLines = 1) }
}

// Near-black, like the reference's Connect: the ink with the paper's words.
@Composable
internal fun Secondary(label: String, onClick: () -> Unit) {
    val c = LocalMoleColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.feedback(interaction, 10.dp).height(32.dp).background(c.ink, RoundedCornerShape(10.dp))
            .clickable(interaction, null, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        Alignment.Center,
    ) { BasicText(label, style = type(13f, 16f, 500, -.039f, c.paper), softWrap = false, maxLines = 1) }
}

@Composable
internal fun Check(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun <T> Segments(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
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
