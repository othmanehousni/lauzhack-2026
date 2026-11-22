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
        val scene = ImageComposeScene(720, 780, Density(1f)) {
            Home(detector.meeting, { detector.start("Meeting") }, detector::stop, line, {}, MutableStateFlow(Announce), {}, onScreen, phone, settings, { null })
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

        assertTrue("Molière is asleep." in texts())
        assertTrue("Your phone connects during a meeting." in texts())
        click("Start a meeting")
        assertNotNull(detector.meeting.value, "the window's Start a meeting is the tray's")
        assertTrue(listOf("Molière is in a meeting.", "End the meeting", "Nothing yet", "The desktop", "No phone connected.").all { it in texts() })

        line.value = MoleLine(MoleState.Heard, heard = "Next slide please.")
        onScreen.value = OnScreen(Target.Slide("Company_Overview", 2, "Problem"), "Company Overview")
        phone.value = true
        assertTrue(listOf("“Next slide please.”", "Company Overview, slide 2", "Phone connected.").all { it in texts() })
        line.value = MoleLine(MoleState.Hidden)
        assertTrue("“Next slide please.”" in texts(), "what it heard last stays once the bar dives")

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
        assertTrue("Molière is asleep." in texts())
        assertNull(detector.meeting.value)
        scene.close()
    }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
}
