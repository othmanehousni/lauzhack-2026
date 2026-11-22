package ch.lauzhack.app.session

import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.log
import ch.lauzhack.app.mole.Mode
import ch.lauzhack.app.stage.HOME
import ch.lauzhack.app.ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.io.Reader
import java.lang.ProcessBuilder.Redirect.DISCARD
import java.util.concurrent.TimeUnit.SECONDS
import java.util.prefs.Preferences

/** The teammates' lip reader, lipreading/: [flag] (--lipreading), which is remembered, else the folder remembered, else
 * lipreading/ in or beside the working directory (./gradlew :composeApp:run works in composeApp/), else the repository
 * on the Desktop. Null if none has lipread.py. */
internal fun lipreading(flag: String?, prefs: Preferences): File? {
    if (flag != null) prefs.put("lipreading", File(flag).absolutePath).also { prefs.flush() }
    val here = File(System.getProperty("user.dir")).absoluteFile
    return (listOfNotNull(flag, prefs.get("lipreading", null)).map(::File) +
        listOf(File(here, "lipreading"), File(here.parentFile, "lipreading"), File(HOME, "Desktop/lauzhack-2026/lipreading")))
        .firstOrNull { File(it, "lipread.py").isFile }?.absoluteFile
}

/** Whether lipreading/setup.sh made its Python environment and Git LFS brought the lip model, not its small pointer. */
internal fun setUp(dir: File) = File(dir, ".venv/bin/python").canExecute() && File(dir, "mlx_weights/model.safetensors").length() >= 1024

/**
 * Mime mode's lip reader: lipread.py in [folder], from [start] until [stop], [mode] turning Present, or [scope] ending
 * when the app quits. It opens the camera and a camera window of its own (its keys, Space, V and Q, are that window's;
 * it never reads standard input) and says each finished sentence in the user's voice itself, so Molière only shows on
 * [line] what it prints: Hidden while stopped. It leads a process group of its own, ended whole, so nothing it started
 * keeps the camera, even after it crashed.
 */
class Mime(private val scope: CoroutineScope, val folder: File?, mode: StateFlow<Mode>) {
    private val shown = MutableStateFlow(MoleLine(MoleState.Hidden))
    val line: StateFlow<MoleLine> = shown
    private var job: Job? = null

    init {
        scope.launch { mode.collect { if (it != Mode.Mime) stop() } }
    }

    fun ready() = folder != null && setUp(folder)

    @Synchronized
    fun start() {
        if (job == null) job = scope.launch(Dispatchers.IO) { read(coroutineContext.job) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        shown.value = MoleLine(MoleState.Hidden)
    }

    // Only the running reader's lines, so none comes in after a stop.
    @Synchronized
    private fun show(l: MoleLine, from: Job, ended: Boolean = false) {
        if (job != from) return
        shown.value = l
        if (ended) job = null
    }

    private suspend fun read(me: Job): Unit = coroutineScope {
        val dir = folder?.takeIf(::setUp) ?: return@coroutineScope show(MoleLine(MoleState.Failed, ui.lipsNotSetUp), me, ended = true)
        show(MoleLine(MoleState.Working, ui.lipsLoading), me)
        val p = try {
            ProcessBuilder("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or die", File(dir, ".venv/bin/python").path, "lipread.py")
                .directory(dir).redirectErrorStream(true)
                .apply { environment() += mapOf("PYTHONUNBUFFERED" to "1", "PYTHONIOENCODING" to "utf-8") }
                .start()
        } catch (e: IOException) {
            log("lipreading: could not start: $e")
            return@coroutineScope show(MoleLine(MoleState.Failed, ui.lipsStopped, e.message.orEmpty()), me, ended = true)
        }
        log("lipreading: started in $dir, process group ${p.pid()}")
        // A stop ends the group, and so does the reader's own end: what it started may hold its output open, and the read below.
        val group = launch { try { awaitCancellation() } finally { end(p) } }
        launch { runInterruptible { p.waitFor() }; group.cancel() }
        var last = ""
        lipread(p.inputStream.reader()) { log("lipreading  $it"); if (it.isNotEmpty()) last = it }.collect { show(it, me) }
        val code = p.waitFor()
        log("lipreading: stopped with code $code")
        show(if (code == 0) MoleLine(MoleState.Hidden) else MoleLine(MoleState.Failed, ui.lipsStopped, last), me, ended = true) // Q in its window is 0
    }
}

// The whole process group [p] leads: asked, then two seconds later told.
private fun end(p: Process) {
    kill("TERM", p.pid())
    p.waitFor(2, SECONDS)
    kill("KILL", p.pid())
}

private fun kill(signal: String, group: Long) {
    ProcessBuilder("/bin/kill", "-$signal", "--", "-$group").redirectErrorStream(true).redirectOutput(DISCARD).start().waitFor()
}

private val ESCAPE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

/**
 * What lipread.py prints, as the bar shows it. A carriage return and an erase start its live line again: "… " and the
 * words so far, with no newline, shown once a read ends on it; "> " and a sentence, then a newline, is a finished one;
 * "Listening." comes once its models are loaded; a line in parentheses is a sentence it could not read. [onLine] gets
 * every line it ends, without escapes.
 */
internal fun lipread(reader: Reader, onLine: (String) -> Unit = {}): Flow<MoleLine> = flow {
    val buf = CharArray(4096)
    val line = StringBuilder()
    fun text() = ESCAPE.replace(line, "").trim()
    while (true) {
        val n = reader.read(buf)
        if (n < 0) break
        for (i in 0 until n) when (val ch = buf[i]) {
            '\r' -> line.clear()
            '\n' -> {
                val s = text()
                line.clear()
                onLine(s)
                when {
                    s.startsWith("> ") -> emit(MoleLine(MoleState.Heard, s.removePrefix("> ")))
                    s.startsWith("Listening.") -> emit(MoleLine(MoleState.Idle, ui.mouthWords))
                    s.startsWith("(") -> emit(MoleLine(MoleState.Unsure, ui.couldntRead))
                }
            }
            else -> line.append(ch)
        }
        val live = text()
        if (live.startsWith("… ")) emit(MoleLine(MoleState.Idle, ui.readingLips, live.removePrefix("… ")))
    }
}
