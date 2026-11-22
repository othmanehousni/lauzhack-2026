package ch.lauzhack.app.mole

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import ch.lauzhack.app.meeting.MeetingDetector
import ch.lauzhack.app.session.Mime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.prefs.AbstractPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

internal val WAITS = listOf(2500L, 1500L, 0L) // Main.kt's

/** Preferences in memory, so no test writes the user's. */
internal class MemoryPrefs : AbstractPreferences(null, "") {
    private val values = mutableMapOf<String, String>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String) = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() = Unit
    override fun keysSpi() = values.keys.toTypedArray()
    override fun childrenNamesSpi() = emptyArray<String>()
    override fun childSpi(name: String) = throw UnsupportedOperationException()
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}

/** The window reads and writes what the tray, the floater and the meeting detector do, both ways. */
class HomeTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun followsTheApp() {
        val prefs = MemoryPrefs()
        val history = MutableStateFlow(true)
        val settings = Settings(prefs, MutableStateFlow(File("library")), history, MutableStateFlow(2500.milliseconds), WAITS, mutableStateOf(MoleTheme.System))
        val detector = MeetingDetector()
        val line = MutableStateFlow(MoleLine(MoleState.Hidden))
        val onScreen = MutableStateFlow(OnScreen())
        val phone = MutableStateFlow(false)
        val scope = CoroutineScope(Dispatchers.Default)
        val mime = Mime(scope, null, settings.mode) // no lip reader here: not set up
        val scene = ImageComposeScene(1100, 760, Density(1f)) {
            Home(detector.meeting, { detector.start("Meeting") }, detector::stop, line, {}, MutableStateFlow(Announce), {}, onScreen, phone, settings, { null }, mime)
        }
        var frame = 0L
        fun nodes(): List<SemanticsNode> {
            repeat(40) {
                Snapshot.sendApplyNotifications()
                scene.render(++frame * 16_666_667L)
            }
            return scene.semanticsOwners.flatMap { all(it.rootSemanticsNode) }
        }
        fun texts() = nodes().mapNotNull { it.text() }
        fun click(label: String) = assertNotNull(nodes().first { it.text() == label }.config[SemanticsActions.OnClick].action).invoke()

        fun node(description: String) = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == description }
        fun tap(description: String) = assertNotNull(node(description).config[SemanticsActions.OnClick].action).invoke()
        fun selected(description: String) = node(description).config.getOrNull(SemanticsProperties.Selected)

        assertTrue(listOf("Welcome back", "The right slide, on cue.", "Your phone connects during a meeting.", "“Okay, let’s open up the company slides.”").all { it in texts() })
        assertEquals(true, selected("Home"))
        click("Start a meeting")
        assertNotNull(detector.meeting.value, "the window's Start a meeting is the tray's")
        assertTrue(listOf("Molière is in a meeting.", "End the meeting", "The desktop", "No phone connected.").all { it in texts() })

        line.value = MoleLine(MoleState.Heard, heard = "Next slide please.")
        onScreen.value = OnScreen(Target.Slide("Company_Overview", 2, "Problem"), "Company Overview")
        phone.value = true
        assertTrue(listOf("“Next slide please.”", "Company Overview, slide 2", "Company Overview, slide 2 is on screen.", "Phone connected.", "THIS MEETING").all { it in texts() })
        line.value = MoleLine(MoleState.Hidden)
        assertTrue("“Next slide please.”" in texts(), "what it heard last stays once the bar dives")

        tap("Things to say")
        assertEquals(true, selected("Things to say"))
        tap("Settings")
        assertEquals(true, selected("Settings"))
        assertTrue("Welcome back" !in texts(), "settings are a page of their own")
        click("Use browser history")
        assertEquals(false, history.value)
        assertEquals(false, prefs.getBoolean("history", true))
        history.value = true // as the tray's checkbox does
        assertEquals(ToggleableState.On, nodes().first { it.text() == "Use browser history" }.config.getOrNull(SemanticsProperties.ToggleableState))
        click("None")
        assertEquals(0.milliseconds, settings.window.value)
        assertEquals(0L, prefs.getLong("window", -1))
        click("Dark")
        assertEquals(MoleTheme.Dark, settings.theme.value)
        assertEquals("Dark", prefs.get("theme", ""))

        detector.stop() // the tray's End the meeting
        tap("Home")
        assertTrue("The right slide, on cue." in texts())
        assertNull(detector.meeting.value)

        // The mode, at the top of the settings: Mime turns the home page into the mime's, with what to run to set it up.
        tap("Settings")
        click("Mime")
        assertEquals(Mode.Mime, settings.mode.value)
        assertEquals("Mime", prefs.get("mode", ""))
        tap("Home")
        assertTrue(listOf("Speak without a sound.", "Start miming", "Lip reading isn't set up yet.", "git lfs pull\nlipreading/setup.sh").all { it in texts() }, "${texts()}")
        assertTrue("Start a meeting" !in texts())
        click("Start miming")
        val until = System.nanoTime() + 5_000_000_000L
        while (mime.line.value.state == MoleState.Hidden && System.nanoTime() < until) Thread.sleep(20)
        assertEquals(MoleLine(MoleState.Failed, "Lip reading isn't set up yet."), mime.line.value, "not set up is the bar's one line")
        click("Stop miming")
        assertEquals(MoleState.Hidden, mime.line.value.state)
        assertTrue("Start miming" in texts())
        tap("Settings")
        click("Present")
        assertEquals(Mode.Present, settings.mode.value)
        tap("Home")
        assertTrue("The right slide, on cue." in texts())
        scene.close()
        scope.cancel()
    }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
}
