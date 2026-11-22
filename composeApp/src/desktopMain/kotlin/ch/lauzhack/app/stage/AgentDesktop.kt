package ch.lauzhack.app.stage

import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The shared screen the audience sees: a Chrome stage in the agent-desktop sandbox VM, or a Chrome window on this Mac
 * when the VM is not set up or `sandbox` is null. Calls run one at a time, so a loop may ask again before the last call
 * finished. With dry, it prints what it would do. */
class AgentDesktop(
    private val library: File,
    private val sandbox: String? = "base",
    private val dry: Boolean = false,
    private val data: File = DATA,
    private val hostPort: Int = HOST_PORT,
) {
    private val lock = Mutex()
    private val files = Files()  // lists in the background from now on
    internal lateinit var stage: Stage
    private var stageShows = OnScreen()  // what the stage tab shows, under any tab or app

    /** Every page of the decks in the library, after up(). */
    var decks: List<DeckPage> = emptyList(); private set

    /** The apps on the shared screen, after up(). */
    var apps: List<String> = emptyList(); private set

    var onScreen = OnScreen(); private set

    private suspend fun <T> serial(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** Checks what the shared screen needs, starts the VM if it is stopped, renders the decks and brings up the stage on
     * the desktop picture. Returns notes for the presenter, one actionable line each, such as the fallback to a window
     * on this Mac; throws with them when the shared screen cannot come up. */
    suspend fun up(): List<String> = serial {
        val notes = mutableListOf<String>()
        fun fail(line: String): Nothing = error((notes + line).joinToString("\n"))
        if (library.listFiles { f -> f.extension.lowercase() == "pdf" }.isNullOrEmpty()) notes += "no PDFs in $library: add your decks"
        val fallback = "so the shared screen is a Chrome window on this Mac: share the window titled \"$TITLE\""
        val ad = agentDesktop
        var vm = when {
            sandbox == null -> null
            ad == null -> null.also { notes += "agent-desktop is not installed (npm install -g agent-desktop), $fallback" }
            else -> Vm(sandbox, ad, dry)
        }
        if (vm != null) {
            val box = vm.up()
            if (box.state == null) {
                notes += "the sandbox VM $sandbox is not set up (One-time VM setup in README.md), $fallback"
                vm = null
            } else if (!dry && box.state != "running") {
                val why = listOf("message", "next").mapNotNull { box[it]?.jsonPrimitive?.content }.joinToString(" ")
                fail("sandbox $sandbox is ${box.state}. $why (One-time VM setup in README.md)")
            }
        }
        stage = Stage(vm, data, if (vm != null) TUNNEL_PORT else hostPort, dry)
        if (vm != null && !dry && vm.guest("test -r ${quote(stage.page.path)} && test -d '/Applications/Google Chrome.app' && echo ok", check = false).trim() != "ok") {
            fail("the VM cannot read ${stage.page} or has no Chrome: run `uv run vm.py` once")
        }
        if (vm == null && !File("/Applications/Google Chrome.app").exists()) fail("Google Chrome is not installed on this Mac: https://www.google.com/chrome/")
        decks = decks(library, data)
        stage.up()
        stage.preload(decks.map { it.png } + stage.wallpaper)
        stage.show(stage.wallpaper)  // nothing shared yet: the desktop
        stageShows = OnScreen()
        onScreen = stageShows
        val folders = "/Applications /System/Applications /System/Applications/Utilities"
        val listing = if (vm != null && !dry) vm.guest("ls $folders", check = false) else exec(listOf("/bin/sh", "-c", "ls $folders"), check = false)
        apps = listing.lines().filter { it.endsWith(".app") }.map { it.removeSuffix(".app") }.distinct().sorted()
        notes
    }

    /** Puts the target on the shared screen; returns what the audience then sees. */
    suspend fun show(target: Target): OnScreen = serial {
        onScreen = when (target) {
            is Target.Slide -> {
                val pages = decks.filter { it.deck == target.deck }
                val i = target.page - 1
                val slide = pages.getOrNull(i) ?: throw IllegalArgumentException("no page ${target.page} in deck ${target.deck}")
                stage.show(slide.png, (pages.subList(maxOf(i - 1, 0), i) + pages.drop(i + 1).take(2)).map { it.png })
                OnScreen(target.copy(title = slide.title), slide.deck.replace('_', ' ')).also { stageShows = it }
            }
            is Target.File -> {
                val path = File(target.path).absolutePath
                OnScreen(target, File(path).name).also { if (stage.open(path) == "stage") stageShows = it }
            }
            is Target.Site -> {
                stage.open(target.url)
                OnScreen(target, target.name.ifEmpty { target.url.substringAfter("://").substringBefore('/') })
            }
            is Target.App -> {
                stage.app(target.name)
                OnScreen(target, target.name)
            }
        }
        onScreen
    }

    /** One of CONTROLS, or "click:<words>" for the link or button whose words match, on what is on screen. */
    suspend fun control(name: String) = serial { stage.control(name) }

    /** Closes the app or tab on screen and goes back to the stage; on the stage itself, shows the desktop picture.
     * Returns what the audience then sees. */
    suspend fun close(): OnScreen = serial {
        if (!stage.covered && stage.front == stage.home) stageShows = OnScreen()
        stage.close()
        onScreen = stageShows
        onScreen
    }

    /** A small JPEG of what the audience sees now, or null when an app covers the stage. */
    suspend fun thumbnail(): ByteArray? = serial { stage.thumbnail() }

    /** The n most recently changed showable files in Desktop, Documents and Downloads. */
    suspend fun newestFiles(n: Int = 1500): List<String> = withContext(Dispatchers.IO) { files.newest(n) }

    /** The newest showable file whose name holds every word of the query. */
    suspend fun find(query: String): String? = withContext(Dispatchers.IO) { files.find(query) }
}
