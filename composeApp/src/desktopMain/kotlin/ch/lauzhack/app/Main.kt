package ch.lauzhack.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.application
import ch.lauzhack.app.agent.Catalog
import ch.lauzhack.app.agent.Intents
import ch.lauzhack.app.agent.MoleAgent
import ch.lauzhack.app.engine.desktopEngines
import ch.lauzhack.app.meeting.MeetingDetector
import ch.lauzhack.app.mole.Demo
import ch.lauzhack.app.mole.MoleTheme
import ch.lauzhack.app.mole.Playground
import ch.lauzhack.app.session.Laptop
import ch.lauzhack.app.session.hasDecks
import ch.lauzhack.app.stage.HOME
import ch.lauzhack.app.stage.typed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.prefs.Preferences
import kotlin.time.Duration.Companion.seconds

/**
 * Molière. With no flag, the real thing: asleep until a call, then listening and driving the shared screen.
 *   --playground  every state of the mole, one click away
 *   --demo        the scripted story on the floater
 *   --typed       lines from standard input instead of the microphone, one line one utterance; starts a meeting,
 *                 and keeps what the audience saw after each change in ~/Library/Logs/Moliere/screens
 *   --library DIR the decks for this run instead of the chosen folder
 *   --host        a Chrome window on this Mac as the shared screen instead of the VM
 */
fun main(args: Array<String>) {
    if ("--playground" in args || "--demo" in args) return application {
        val scope = rememberCoroutineScope()
        val demo = remember { Demo(scope) }
        LaunchedEffect(demo) { demo.play() }
        if ("--playground" in args) Playground(demo, onClose = ::exitApplication)
        else MoleFloater(demo.lines, demo::pick, onQuit = ::exitApplication)
    }

    teeToLog()
    val prefs = Preferences.userRoot().node("ch/lauzhack/moliere")
    val library = MutableStateFlow(File(args.after("--library") ?: prefs.get("decks", File(HOME, "Documents/Moliere").path)).absoluteFile)
    library.value.mkdirs()
    val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The tray's Quit, Cmd-Q and Ctrl-C all end here: the session, the ear and the phone server stop, and the helpers with them.
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { withTimeoutOrNull(3.seconds) { app.coroutineContext.job.cancelAndJoin() } } })

    val engines = desktopEngines()
    val detector = MeetingDetector()
    val typed = if ("--typed" in args) {
        val lines = Channel<Heard>(Channel.UNLIMITED)
        CoroutineScope(Dispatchers.IO).launch { typed().collect { lines.send(it.heard) } } // not app's: a read of stdin does not cancel
        lines.receiveAsFlow()
    } else null
    if (typed == null) app.launch { detector.run() } else detector.start("Typed lines")
    // The decider's factory goes here once a model is in engines.modelsDir; until then the rules decide.
    val agent: (Catalog, Intents) -> Agent = ::MoleAgent
    val laptop = Laptop(
        app,
        engines::embedder,
        library,
        detector.meeting.map { it?.title }.stateIn(app, SharingStarted.Eagerly, null),
        engines.download().filter { it.bytes < it.total }.map { it.file to it.bytes.toFloat() / it.total },
        agent,
        sandbox = if ("--host" in args) null else "base",
        typed = typed,
        shots = if (typed != null) File(LOGS, "screens").also { it.deleteRecursively() } else null,
    )
    log("Molière: decks in ${library.value}, ${if ("--host" in args) "Chrome on this Mac" else "the VM"} as the shared screen, ${if (typed != null) "typed lines" else "the microphone"}")

    application {
        var theme by remember { mutableStateOf(runCatching { MoleTheme.valueOf(prefs.get("theme", "")) }.getOrDefault(MoleTheme.System)) }
        val meeting by detector.meeting.collectAsState()
        val lib by library.collectAsState()
        val empty by produceState(false, lib) { while (true) { value = !hasDecks(lib); delay(3.seconds) } }
        MoleFloater(laptop.line, laptop::onPick, ::exitApplication, theme) {
            if (meeting == null) Item("Start a meeting", onClick = { detector.start("Meeting") })
            else Item("End the meeting", onClick = detector::stop)
            Separator()
            if (empty) Item("No PDF in ${lib.name} yet", enabled = false, onClick = {})
            Item("Open ${lib.name}", onClick = { Desktop.getDesktop().open(lib.also { it.mkdirs() }) })
            Item("Choose decks folder…", onClick = {
                chooseFolder(lib)?.let {
                    library.value = it
                    prefs.put("decks", it.path)
                    prefs.flush()
                }
            })
            Separator()
            Menu("Theme") {
                for (t in MoleTheme.entries) CheckboxItem(t.name, checked = t == theme, onCheckedChange = { // a tray menu has no radio items
                    theme = t
                    prefs.put("theme", t.name)
                    prefs.flush()
                })
            }
            Separator()
            Item("Quit Molière", onClick = ::exitApplication)
        }
    }
}

private fun Array<String>.after(flag: String) = getOrNull(indexOf(flag).takeIf { it >= 0 }?.plus(1) ?: -1)

private fun chooseFolder(start: File): File? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    runCatching { Desktop.getDesktop().requestForeground(true) } // a tray app is not in front, and its dialog would open behind
    val dialog = FileDialog(null as Frame?, "Choose the folder of your decks").apply {
        directory = start.parent
        file = start.name
        isVisible = true
    }
    return dialog.file?.let { File(dialog.directory, it) }
}
