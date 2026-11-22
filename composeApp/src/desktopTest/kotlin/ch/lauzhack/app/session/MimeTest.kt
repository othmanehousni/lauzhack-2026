package ch.lauzhack.app.session

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.mole.MemoryPrefs
import ch.lauzhack.app.mole.Mode
import ch.lauzhack.app.mole.MoleBar
import ch.lauzhack.app.mole.MoleTheme
import ch.lauzhack.app.ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.io.Reader
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// lipread.py's prints, byte for byte: progress lines, the live line redrawn after a carriage return and an erase with no
// newline, a finished sentence, a failure in parentheses.
private const val LOADING = "printf 'Loading models...\\n'"
private const val LISTENING = "printf 'Using your personal lip profile.\\nSpeaking finished sentences in your cloned voice (V turns it off).\\n" +
    "Listening. Mouth sentences and pause between them. SPACE pauses/resumes, Q quits.\\n'"

/** Mime mode with a fake lip reader: lipreading/.venv/bin/python is a shell script printing what lipread.py prints. No
 * camera, model, sound or network. */
class MimeTest {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun after() {
        scope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    // A lipreading/ folder whose reader runs [script] (as `python lipread.py`), set up unless told otherwise. The script
    // writes its process id to pid, and starts a child, as the real one's threads and helpers, whose id is in child.
    private fun folder(script: String, venv: Boolean = true, weights: Int = 2048): File {
        val dir = Files.createTempDirectory("lipreading").toFile().also { dirs += it }
        File(dir, "lipread.py").writeText("")
        File(dir, "mlx_weights").mkdirs()
        File(dir, "mlx_weights/model.safetensors").writeBytes(ByteArray(weights))
        if (venv) File(dir, ".venv/bin").mkdirs()
        if (venv) File(dir, ".venv/bin/python").apply { writeText("#!/bin/sh\necho \$\$ > pid\nsleep 60 & echo \$! > child\n$script\n"); setExecutable(true) }
        return dir
    }

    private fun alive(dir: File, name: String) = ProcessHandle.of(File(dir, name).readText().trim().toLong()).map { it.isAlive }.orElse(false)

    private suspend fun gone(dir: File) = withTimeout(10.seconds) { while (alive(dir, "pid") || alive(dir, "child")) delay(50) }

    @Test
    fun readsWhatLipreadPrints() = runBlocking {
        val prints = listOf(
            "Loading models...\n", "Using your personal lip profile.\n", "Listening. Mouth sentences and pause between them. SPACE pauses/resumes, Q quits.\n",
            "\r\u001B[K… can", "\r\u001B[K… can you", "\r\u001B[K… can you hear", " me", "\r\u001B[K> Can you hear me?\n",
            "\r\u001B[K  (corrector failed: boom)\n", "\r\u001B", "[K(could not read that sentence: x)\n", "\n",
        )
        val chunks = object : Reader() { // one read per print, as the pipe delivers each flush
            val left = ArrayDeque(prints)
            override fun read(buf: CharArray, off: Int, len: Int) = left.removeFirstOrNull()?.also { it.toCharArray().copyInto(buf, off) }?.length ?: -1
            override fun close() = Unit
        }
        val lines = mutableListOf<String>()
        assertEquals(
            listOf(
                MoleLine(MoleState.Idle, ui.mouthWords),
                MoleLine(MoleState.Idle, ui.readingLips, "can"),
                MoleLine(MoleState.Idle, ui.readingLips, "can you"),
                MoleLine(MoleState.Idle, ui.readingLips, "can you hear"),
                MoleLine(MoleState.Idle, ui.readingLips, "can you hear me"),
                MoleLine(MoleState.Heard, "Can you hear me?"),
                MoleLine(MoleState.Unsure, ui.couldntRead),
                MoleLine(MoleState.Unsure, ui.couldntRead),
            ),
            lipread(chunks) { lines += it }.toList(),
        )
        assertEquals("Can you hear me?", lines.first { it.startsWith(">") }.removePrefix("> "), "every ended line, escapes out")
    }

    @Test
    fun startsShowsAndStops() = runBlocking {
        val dir = folder(
            "$LOADING\nsleep 0.5\n$LISTENING\nsleep 0.4\nprintf '\\r\\033[K… can you'\nsleep 0.4\nprintf '\\r\\033[K… can you hear me'\nsleep 0.4\n" +
                "printf '\\r\\033[K> Can you hear me?\\n'\nexec sleep 60",
        )
        val mime = Mime(scope, dir, MutableStateFlow(Mode.Mime))
        val seen = CopyOnWriteArrayList<MoleLine>()
        val watch = scope.launch { mime.line.collect { seen += it } }
        delay(200)
        assertTrue(!File(dir, "pid").exists(), "nothing runs before it is started")
        mime.start()
        mime.start() // a second start is the same reader
        withTimeout(10.seconds) { mime.line.first { it.state == MoleState.Heard } }
        assertTrue(alive(dir, "pid") && alive(dir, "child"))
        mime.stop()
        assertEquals(MoleState.Hidden, mime.line.value.state)
        gone(dir)
        delay(300)
        watch.cancel()
        assertEquals(
            listOf(
                MoleLine(MoleState.Hidden),
                MoleLine(MoleState.Working, ui.lipsLoading), // until "Listening."
                MoleLine(MoleState.Idle, ui.mouthWords),
                MoleLine(MoleState.Idle, ui.readingLips, "can you"),
                MoleLine(MoleState.Idle, ui.readingLips, "can you hear me"),
                MoleLine(MoleState.Heard, "Can you hear me?"), // once
                MoleLine(MoleState.Hidden),
            ),
            seen,
        )
    }

    @Test
    fun switchingBackToPresentStopsIt() = runBlocking {
        val dir = folder("$LOADING\n$LISTENING\nexec sleep 60")
        val mode = MutableStateFlow(Mode.Mime)
        val mime = Mime(scope, dir, mode)
        mime.start()
        withTimeout(10.seconds) { mime.line.first { it.title == ui.mouthWords } }
        mode.value = Mode.Present
        withTimeout(10.seconds) { mime.line.first { it.state == MoleState.Hidden } }
        gone(dir)
    }

    @Test
    fun aCrashSaysWhyAndLeavesNothingRunning() = runBlocking {
        // The child keeps the output open, so only ending the group ends the read.
        val dir = folder("$LOADING\nprintf 'Traceback (most recent call last):\\nModuleNotFoundError: No module named cv2\\n' >&2\nexit 1")
        val mime = Mime(scope, dir, MutableStateFlow(Mode.Mime))
        mime.start()
        assertEquals(
            MoleLine(MoleState.Failed, ui.lipsStopped, "ModuleNotFoundError: No module named cv2"),
            withTimeout(10.seconds) { mime.line.first { it.state == MoleState.Failed } },
        )
        gone(dir)
        mime.stop()
        assertEquals(MoleState.Hidden, mime.line.value.state)
    }

    @Test
    fun notSetUpIsAState() = runBlocking {
        val pointer = folder("exit 0", weights = 134) // Git LFS's pointer, not the weights
        val noVenv = folder("", venv = false)
        assertTrue(setUp(folder("exit 0")))
        for (dir in listOf(pointer, noVenv, null)) {
            val mime = Mime(scope, dir, MutableStateFlow(Mode.Mime))
            assertTrue(!mime.ready())
            mime.start()
            assertEquals(MoleLine(MoleState.Failed, ui.lipsNotSetUp), withTimeout(10.seconds) { mime.line.first { it.state != MoleState.Hidden } })
            assertTrue(!File(pointer, "pid").exists())
        }
    }

    @Test
    fun findsTheFolder() {
        val prefs = MemoryPrefs()
        val dir = folder("exit 0")
        assertEquals(dir.absoluteFile, lipreading(dir.path, prefs))
        assertEquals(dir.absoluteFile, lipreading(null, prefs), "the flag's folder is remembered")
        assertEquals("lipreading", lipreading(null, MemoryPrefs())?.name, "this repository's, beside composeApp/")
        assertEquals("lipreading", lipreading(null, MemoryPrefs().apply { put("lipreading", "/nowhere") })?.name, "a remembered folder that is gone is skipped")
    }

    // The mime on the bar, for a look at build/bar-shots/mime*.png: its bow tie is the mole's red, in both themes.
    @Test
    fun theBarShowsTheMime() {
        val out = File("build/bar-shots").apply { mkdirs() }
        for ((theme, suffix) in listOf(MoleTheme.Light to "", MoleTheme.Dark to "-dark")) {
            val scene = ImageComposeScene(360 * 2, 100 * 2, Density(2f)) {
                MoleBar(MoleLine(MoleState.Idle, ui.readingLips, "can you hear me"), {}, theme = theme, mode = Mode.Mime)
            }
            var image = scene.render(0)
            for (frame in 1..30) image = scene.render(frame * 1_000_000_000L / 60)
            scene.close()
            File(out, "mime$suffix.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            val pixels = image.toComposeImageBitmap().toPixelMap()
            val red = (0 until 200).sumOf { x -> (0 until 200).count { y -> pixels[x, y].let { it.red > .7f && it.green < .3f && it.blue < .3f } } }
            assertTrue(red > 100, "$red red pixels where the bow tie is")
        }
    }
}
