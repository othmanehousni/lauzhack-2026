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
import ch.lauzhack.app.decider.DECIDER
import ch.lauzhack.app.decider.ModelBrain
import ch.lauzhack.app.engine.desktopEngines
import ch.lauzhack.app.meeting.MeetingDetector
import ch.lauzhack.app.mole.Demo
import ch.lauzhack.app.mole.MoleTheme
import ch.lauzhack.app.mole.Playground
import ch.lauzhack.app.session.Laptop
import ch.lauzhack.app.session.ANNOUNCE
import ch.lauzhack.app.session.RulesBrain
import ch.lauzhack.app.session.hasDecks
import ch.lauzhack.app.session.replayed
import ch.lauzhack.app.stage.HOME
import ch.lauzhack.app.stage.typed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Molière. With no flag, the real thing: asleep until a call, then listening and driving the shared screen.
 *   --playground  every state of the mole, one click away
 *   --demo        the scripted story on the floater
 *   --typed       lines from standard input instead of the microphone, one line one utterance; starts a meeting,
 *                 and keeps what the audience saw after each change in ~/Library/Logs/Moliere/screens
 *   --paced       with --typed, each line comes as speech does: word by word at 150 words a minute, then finished
 *   --audio FILE… the ear hears these audio files (or every one in a folder), in order, instead of the microphone;
 *                 starts a meeting, and keeps the screens as --typed does
 *   --window MS   how long an announce waits before it is shown, for this run (the tray's Wait before showing)
 *   --library DIR the decks for this run instead of the chosen folder
 *   --host        a Chrome window on this Mac as the shared screen instead of the VM
 *   --rules       the rules decide even when decider.litertlm is in the models folder
 *   --no-history  no browser history for this run, whatever the tray says
 *   --models DIR  another folder of models for this run (to compare a decider without replacing the installed one)
 *   --replay      what an ear heard, as hear.swift's lines on standard input, decided as in a call on a screen that only
 *                 follows the moves: no VM, Chrome, phone, window, log file, nor this Mac's files and pages (rehearsal/replay.py)
 */
fun main(args: Array<String>) {
    if ("--playground" in args || "--demo" in args) return application {
        val scope = rememberCoroutineScope()
        val demo = remember { Demo(scope) }
        LaunchedEffect(demo) { demo.play() }
        if ("--playground" in args) Playground(demo, onClose = ::exitApplication)
        else MoleFloater(demo.lines, demo::pick, onQuit = ::exitApplication)
    }

    val replay = "--replay" in args
    if (!replay) teeToLog() // another app may be running and writing it
    val prefs = Preferences.userRoot().node("ch/lauzhack/moliere")
    val library = MutableStateFlow(File(args.after("--library") ?: prefs.get("decks", File(HOME, "Documents/Moliere").path)).absoluteFile)
    library.value.mkdirs()
    val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The tray's Quit, Cmd-Q and Ctrl-C all end here: the session, the ear and the phone server stop, and the helpers with them.
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { withTimeoutOrNull(3.seconds) { app.coroutineContext.job.cancelAndJoin() } } })

    val engines = desktopEngines(args.after("--models"))
    val detector = MeetingDetector()
    val audio = audio(args)
    val typed = if ("--typed" in args) {
        val lines = Channel<Heard>(Channel.UNLIMITED)
        CoroutineScope(Dispatchers.IO).launch { // not app's: a read of stdin does not cancel
            typed(paced = "--paced" in args).collect {
                log("typed   \"${it.heard.text}\"${if (it.heard.ended) "" else " …"}")
                lines.send(it.heard)
            }
        }
        lines.receiveAsFlow()
    } else if (replay) replayed(System.`in`) else null
    when {
        typed != null -> detector.start("Typed lines")
        audio.isNotEmpty() -> detector.start("Audio files")
        else -> app.launch { detector.run() }
    }
    val model = "--rules" !in args && File(engines.modelsDir, DECIDER).isFile
    val history = MutableStateFlow("--no-history" !in args && prefs.getBoolean("history", true))
    val window = MutableStateFlow((args.after("--window")?.toLong() ?: prefs.getLong("window", ANNOUNCE.inWholeMilliseconds)).milliseconds)
    val laptop = Laptop(
        app,
        if (model) ModelBrain(engines) else RulesBrain(engines::embedder),
        library,
        detector.meeting.map { it?.title }.stateIn(app, SharingStarted.Eagerly, null),
        engines.download().filter { it.bytes < it.total }.map { it.file to it.bytes.toFloat() / it.total },
        history,
        sandbox = if ("--host" in args) null else "base",
        typed = typed,
        audio = audio,
        window = window,
        shots = if (!replay && (typed != null || audio.isNotEmpty())) File(LOGS, "screens").also { it.deleteRecursively() } else null,
        dry = replay,
    )
    log("Molière: decks in ${library.value}, ${if (replay) "nothing" else if ("--host" in args) "Chrome on this Mac" else "the VM"} as the shared screen, " +
        "${if (replay) "what an ear heard" else if (typed != null) "typed lines" else if (audio.isNotEmpty()) "${audio.size} audio files" else "the microphone"}, " +
        "${if (model) "the model decides" else "the rules decide"}, browser history ${if (history.value) "on" else "off"}, " +
        "announces wait ${window.value.inWholeMilliseconds} ms")
    if (replay) runBlocking { awaitCancellation() }

    application {
        var theme by remember { mutableStateOf(runCatching { MoleTheme.valueOf(prefs.get("theme", "")) }.getOrDefault(MoleTheme.System)) }
        val meeting by detector.meeting.collectAsState()
        val lib by library.collectAsState()
        val browsing by history.collectAsState()
        val wait by window.collectAsState()
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
            CheckboxItem("Use browser history", checked = browsing, onCheckedChange = {
                history.value = it
                prefs.putBoolean("history", it)
                prefs.flush()
            })
            Menu("Wait before showing") {
                for ((label, ms) in WAITS) CheckboxItem(label, checked = wait.inWholeMilliseconds == ms, onCheckedChange = {
                    window.value = ms.milliseconds
                    prefs.putLong("window", ms)
                    prefs.flush()
                })
            }
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

private val WAITS = listOf("2.5 seconds" to 2500L, "1.5 seconds" to 1500L, "None" to 0L)

/** The files after --audio, up to the next flag; a folder is every audio file in it, by name. */
private fun audio(args: Array<String>): List<File> =
    args.dropWhile { it != "--audio" }.drop(1).takeWhile { !it.startsWith("--") }.map { File(it).absoluteFile }.flatMap { f ->
        if (!f.isDirectory) listOf(f)
        else f.listFiles { x -> x.extension.lowercase() in setOf("aiff", "aif", "wav", "m4a", "caf", "mp3") }.orEmpty().sortedBy { it.name }
    }

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
