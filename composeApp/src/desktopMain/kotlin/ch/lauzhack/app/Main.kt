package ch.lauzhack.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.application
import ch.lauzhack.app.decider.ModelBrain
import ch.lauzhack.app.engine.desktopEngines
import ch.lauzhack.app.meeting.MeetingDetector
import ch.lauzhack.app.mole.Demo
import ch.lauzhack.app.mole.Home
import ch.lauzhack.app.mole.HomeWindow
import ch.lauzhack.app.mole.Mode
import ch.lauzhack.app.mole.MoleTheme
import ch.lauzhack.app.mole.Playground
import ch.lauzhack.app.mole.SYSTEM
import ch.lauzhack.app.mole.Settings
import ch.lauzhack.app.session.Laptop
import ch.lauzhack.app.session.ANNOUNCE
import ch.lauzhack.app.session.Mime
import ch.lauzhack.app.session.hasDecks
import ch.lauzhack.app.session.lipreading
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
import kotlinx.coroutines.flow.combine
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
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Molière. With no flag, the real thing: asleep until a call, then listening and driving the shared screen; or in Mime
 * mode, chosen in the window or the tray, reading your lips at the camera with lipreading/, which says them aloud.
 *   --playground  every state of the mole, one click away
 *   --demo        the scripted story on the floater
 *   --typed       lines from standard input instead of the microphone, one line one utterance; starts a meeting,
 *                 and keeps what the audience saw after each change in ~/Library/Logs/Moliere/screens
 *   --paced       with --typed, each line comes as speech does: word by word at 150 words a minute, then finished
 *   --audio FILE… the ear hears these audio files (or every one in a folder), in order, instead of the microphone;
 *                 starts a meeting, and keeps the screens as --typed does
 *   --window MS   the longest a pick waits before it is shown, for a guess; a sure one is shown at once, for this
 *                 run (the tray's Wait before showing)
 *   --library DIR the decks for this run instead of the chosen folder
 *   --host        a Chrome window on this Mac as the shared screen instead of the VM
 *   --no-history  no browser history for this run, whatever the tray says
 *   --models DIR  another folder of models for this run (to compare a decider without replacing the installed one)
 *   --lipreading DIR  the teammates' lip reader for Mime mode, remembered for the next runs
 *   --replay      what an ear heard, as hear.swift's lines on standard input, decided as in a call on a screen that only
 *                 follows the moves: no VM, Chrome, phone, window, log file, nor this Mac's files and pages (rehearsal/replay.py)
 */
fun main(args: Array<String>) {
    checkFlags(args)
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
    val history = MutableStateFlow("--no-history" !in args && prefs.getBoolean("history", true))
    val window = MutableStateFlow((args.after("--window")?.toLong() ?: prefs.getLong("window", ANNOUNCE.inWholeMilliseconds)).milliseconds)
    // A scripted run presents. Miming, the laptop sleeps through a call: no ear, no decider, no shared screen.
    val mode = MutableStateFlow(if (typed != null || audio.isNotEmpty()) Mode.Present else runCatching { Mode.valueOf(prefs.get("mode", "")) }.getOrDefault(Mode.Present))
    val laptop = Laptop(
        app,
        ModelBrain(engines),
        library,
        combine(detector.meeting, mode) { m, now -> m?.title?.takeIf { now == Mode.Present } }.stateIn(app, SharingStarted.Eagerly, null),
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
        "browser history ${if (history.value) "on" else "off"}, " +
        "picks wait up to ${window.value.inWholeMilliseconds} ms")
    if (replay) runBlocking { awaitCancellation() }
    val language = Lang.entries.firstOrNull { it.name == prefs.get("language", "") } // null follows the system
    if (typed == null && audio.isEmpty()) lang = language ?: SYSTEM // a scripted run reads English, as its scorers do
    val mime = Mime(app, lipreading(args.after("--lipreading"), prefs), mode)
    val line = combine(mode, laptop.line, mime.line) { now, presenting, miming -> if (now == Mode.Mime) miming else presenting }
        .stateIn(app, SharingStarted.Eagerly, laptop.line.value)

    application {
        val settings = remember { Settings(prefs, library, history, window, WAITS, mutableStateOf(runCatching { MoleTheme.valueOf(prefs.get("theme", "")) }.getOrDefault(MoleTheme.System)), mutableStateOf(language), mode) }
        var theme by settings.theme
        // Opens with the app but not for a scripted run, which may run while someone works on this Mac.
        var home by remember { mutableIntStateOf(if (typed == null && audio.isEmpty()) 1 else 0) }
        if (home > 0) HomeWindow(home, { home = 0 }, theme) { demo ->
            Home(detector.meeting, { detector.start("Meeting") }, detector::stop, laptop.line, laptop::onPick, demo.lines, demo::pick,
                laptop.onScreen, laptop.phone, settings, ::chooseFolder, mime)
        }
        val meeting by detector.meeting.collectAsState()
        val lib by library.collectAsState()
        val browsing by history.collectAsState()
        val wait by window.collectAsState()
        val now by mode.collectAsState()
        val mimed by mime.line.collectAsState()
        val empty by produceState(false, lib) { while (true) { value = !hasDecks(lib); delay(3.seconds) } }
        MoleFloater(line, laptop::onPick, ::exitApplication, theme, now) {
            Item(ui.openApp, onClick = { home++ })
            for (m in Mode.entries) CheckboxItem(ui.mode(m), checked = m == now, onCheckedChange = { settings.mode(m) }) // a tray menu has no radio items
            Separator()
            if (now == Mode.Mime) {
                if (mimed.state == MoleState.Hidden) Item(ui.startMiming, onClick = mime::start) else Item(ui.stopMiming, onClick = mime::stop)
            } else if (meeting == null) Item(ui.startMeeting, onClick = { detector.start("Meeting") })
            else Item(ui.endMeeting, onClick = detector::stop)
            Separator()
            if (empty) Item(ui.noPdfIn(lib.name), enabled = false, onClick = {})
            Item(ui.openFolder(lib.name), onClick = { Desktop.getDesktop().open(lib.also { it.mkdirs() }) })
            Item(ui.chooseFolder, onClick = {
                chooseFolder(lib)?.let {
                    library.value = it
                    prefs.put("decks", it.path)
                    prefs.flush()
                }
            })
            CheckboxItem(ui.useHistory, checked = browsing, onCheckedChange = {
                history.value = it
                prefs.putBoolean("history", it)
                prefs.flush()
            })
            Menu(ui.waitBefore) {
                for (ms in WAITS) CheckboxItem(ui.wait(ms), checked = wait.inWholeMilliseconds == ms, onCheckedChange = {
                    window.value = ms.milliseconds
                    prefs.putLong("window", ms)
                    prefs.flush()
                })
            }
            Separator()
            Menu(ui.themeLabel) {
                for (t in MoleTheme.entries) CheckboxItem(ui.theme(t), checked = t == theme, onCheckedChange = { // a tray menu has no radio items
                    theme = t
                    prefs.put("theme", t.name)
                    prefs.flush()
                })
            }
            Menu(ui.language) {
                val chosen = settings.language.value
                CheckboxItem(ui.system, checked = chosen == null, onCheckedChange = { settings.language(null) })
                for (l in Lang.entries) CheckboxItem(l.own, checked = l == chosen, onCheckedChange = { settings.language(l) })
            }
            Separator()
            Item(ui.quit, onClick = ::exitApplication)
        }
    }
}

private val SWITCHES = setOf("--playground", "--demo", "--typed", "--paced", "--host", "--no-history", "--replay")
private val VALUED = setOf("--window", "--library", "--models", "--lipreading")

/** Stops on a flag without its value or a --window that is not milliseconds; warns of a flag it does not know, which
 * may be a typo of one it does (--no-histroy would read the history). */
private fun checkFlags(args: Array<String>) {
    fun stop(why: String): Nothing {
        System.err.println("Molière: $why. The flags are listed above fun main in Main.kt.")
        exitProcess(2)
    }
    var i = 0
    while (i < args.size) when (val a = args[i++]) {
        in SWITCHES -> Unit
        in VALUED -> if (args.getOrNull(i++)?.startsWith("--") != false) stop("$a needs a value")
        "--audio" -> while (i < args.size && !args[i].startsWith("--")) i++
        else -> System.err.println("Molière: unknown flag $a, ignored")
    }
    if ("--window" in args && args.after("--window")?.toLongOrNull() == null) stop("--window takes milliseconds")
}

private fun Array<String>.after(flag: String) = getOrNull(indexOf(flag).takeIf { it >= 0 }?.plus(1) ?: -1)

private val WAITS = listOf(2500L, 1500L, 0L) // a sure pick is shown at once

/** The files after --audio, up to the next flag; a folder is every audio file in it, by name. */
private fun audio(args: Array<String>): List<File> =
    args.dropWhile { it != "--audio" }.drop(1).takeWhile { !it.startsWith("--") }.map { File(it).absoluteFile }.flatMap { f ->
        if (!f.isDirectory) listOf(f)
        else f.listFiles { x -> x.extension.lowercase() in setOf("aiff", "aif", "wav", "m4a", "caf", "mp3") }.orEmpty().sortedBy { it.name }
    }

private fun chooseFolder(start: File): File? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    runCatching { Desktop.getDesktop().requestForeground(true) } // a tray app is not in front, and its dialog would open behind
    val dialog = FileDialog(null as Frame?, ui.chooseFolderTitle).apply {
        directory = start.parent
        file = start.name
        isVisible = true
    }
    return dialog.file?.let { File(dialog.directory, it) }
}
