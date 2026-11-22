package ch.lauzhack.app.stage

import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The app's cache, for what is slow to read again: the text of recent decks. */
val CACHE = File(HOME, "Library/Caches/Moliere")

private const val RECENT_DECKS = 5
private const val DRAWN_AHEAD = 60  // pages of a recent deck drawn in the background; show() draws a later one when asked
private const val MOST_FILES = 300

/** What the presenter saw lately, each list most recent first. */
data class Recents(val pages: List<Page>, val files: List<UsedFile>, val decks: List<RecentDeck>)

/** The shared screen the audience sees: a Chrome stage in the agent-desktop sandbox VM, or a Chrome window on this Mac
 * when the VM is not set up or `sandbox` is null. Calls run one at a time, so a loop may ask again before the last call
 * finished. With dry, it prints what it would do. With history off, recents() has no pages and no browser's data is read. */
class AgentDesktop(
    private val library: File,
    private val sandbox: String? = "base",
    private val dry: Boolean = false,
    private val data: File = DATA,
    private val hostPort: Int = HOST_PORT,
    history: Boolean = true,
    private val cache: File = CACHE,
    private val files: Files = Files(roots = FOLDERS.map { File(HOME, it) }),  // lists in the background from now on
    browsers: File = File(HOME, "Library/Application Support"),
) {
    private val lock = Mutex()
    private val history = if (history) History(browsers) else null
    private val drawer = Executors.newSingleThreadExecutor { Thread(it, "recent decks").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    internal val queued: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var recent: List<RecentDeck> = emptyList()
    internal lateinit var stage: Stage
    private var stageShows = OnScreen()  // what the stage tab shows, under any tab or app

    /** Every page of the decks in the library, after up(). */
    var decks: List<DeckPage> = emptyList(); private set

    /** The library's files that are not decks, the meeting's own as its decks are, not things seen lately. */
    val documents: List<String> get() = documents(library)

    /** The apps on the shared screen, after up(). */
    var apps: List<String> = emptyList(); private set

    var onScreen = OnScreen(); private set

    init {
        // from now on, so that the first recents() finds them read; again after the first walk, for those Spotlight lacked
        val ready = Runnable { runCatching { readyDecks(files.recent()) } }
        drawer.execute(ready)
        files.walked.thenRun { drawer.execute(ready) }
    }

    @Volatile private var shooting: Job? = null // a thumbnail being taken, which gives way to any other call

    private suspend fun <T> serial(block: suspend () -> T): T {
        shooting?.cancel()
        return lock.withLock { withContext(Dispatchers.IO) { block() } }
    }

    /** Checks what the shared screen needs, starts the VM if it is stopped and sets it up (Vm.setup) if it cannot show
     * the stage, renders the decks and brings up the stage on the desktop picture. Returns notes for the presenter, one
     * actionable line each, such as the fallback to a window on this Mac; throws with them when the shared screen cannot
     * come up. */
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
        if (vm != null && !dry && !vm.ready(stage.page)) {
            val did = try { vm.setup() } catch (e: CancellationException) { throw e } catch (e: Exception) { fail("setting up the sandbox VM $sandbox failed: ${e.message}") }
            notes += "the VM could not read ${stage.page} or had no Chrome, so the app set it up: ${did.joinToString("; ")}"
            if (!vm.ready(stage.page)) fail("the VM still cannot read ${stage.page} after its setup: keep the app's data under ${FOLDERS.joinToString { "~/$it" }}, which the VM mounts")
        }
        if (vm == null && !File("/Applications/Google Chrome.app").exists()) fail("Google Chrome is not installed on this Mac: https://www.google.com/chrome/")
        decks = decks(library, data)
        stage.up()
        stage.preload(decks.map { it.png } + stage.wallpaper)
        stage.show(stage.wallpaper)  // nothing shared yet: the desktop
        stageShows = OnScreen()
        onScreen = stageShows
        val folders = (if (dry) "" else "/Applications ") + "/System/Applications /System/Applications/Utilities" // dry: a fresh Mac's, not this one's
        val listing = if (vm != null && !dry) vm.guest("ls $folders", check = false) else exec(listOf("/bin/sh", "-c", "ls $folders"), check = false)
        apps = listing.lines().filter { it.endsWith(".app") }.map { it.removeSuffix(".app") }.distinct().sorted()
        notes
    }

    /** Puts the target on the shared screen; returns what the audience then sees. */
    suspend fun show(target: Target): OnScreen = serial {
        onScreen = when (target) {
            is Target.Slide -> {
                val other = recent.firstOrNull { it.pages[0].deck == target.deck }
                val pages = decks.filter { it.deck == target.deck }.ifEmpty { other?.pages.orEmpty() }
                val i = target.page - 1
                val slide = pages.getOrNull(i) ?: throw IllegalArgumentException("no page ${target.page} in deck ${target.deck}")
                if (other != null) drawMissing(File(other.path), listOf(slide))  // not drawn in the background yet
                stage.show(slide.png, (pages.subList(maxOf(i - 1, 0), i) + pages.drop(i + 1).take(2)).map { it.png }.filter(File::exists))
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

    /** A small JPEG of what the audience sees now, or null when an app covers the stage or another call came first:
     * Chrome may not answer a screenshot of a tab that is still loading, and no call for the presenter waits for one. */
    suspend fun thumbnail(): ByteArray? = coroutineScope {
        val shot = async { lock.withLock { withContext(Dispatchers.IO) { stage.thumbnail() } } }
        shooting = shot
        try {
            shot.await()
        } catch (e: CancellationException) {
            ensureActive()
            null
        }
    }

    /** The n showable files in Desktop, Documents and Downloads the presenter used most recently. */
    suspend fun newestFiles(n: Int = 1500): List<String> = withContext(Dispatchers.IO) { files.newest(n) }

    /** The most recently used showable file whose name holds every word of the query. */
    suspend fun find(query: String): String? = withContext(Dispatchers.IO) { files.find(query) }

    /** What the presenter saw lately, for the catalog: the pages they had open in a browser, unless history is off; the
     * files they used or changed; and the slide decks among recent PDFs outside the library, as pages like those of
     * decks. A deck not read yet is read and drawn in the background, for a later call. show() takes a page's or a
     * file's target, and a deck page as a Target.Slide. */
    suspend fun recents(): Recents = withContext(Dispatchers.IO) {
        val pages = async { history?.pages().orEmpty() }
        val spots = files.recent()
        val decks = readyDecks(spots)
        val listed = decks.map { it.path }.toSet() + pages.await().map { it.url.substringBefore('#') } + documents
        val used = spots.filter { it.path !in listed && uri(File(it.path)) !in listed && files.wanted(it.path) }.take(MOST_FILES)
        Recents(pages.await(), used.map { UsedFile(it.path, Instant.ofEpochMilli(it.seen())) }, decks)
    }

    /** The RECENT_DECKS most recent PDFs outside the library that may be decks, by the count and size of their pages
     * (Spotlight's, or the walk's), as decks once their text is read. The others are read, and their first DRAWN_AHEAD pages drawn, in the
     * background, for a later call. Only these decks' pictures are kept. */
    internal fun readyDecks(spots: List<Spot>): List<RecentDeck> {
        val names = library.listFiles { f -> f.extension.lowercase() == "pdf" }.orEmpty().map { it.nameWithoutExtension }.toMutableSet()
        val pdfs = spots.filter { it.path.endsWith(".pdf", ignoreCase = true) && it.pages in 3..MOST_SLIDES && (it.height == 0.0 || it.width / it.height in SCREEN) }
            .filter { names.add(File(it.path).nameWithoutExtension) }.take(RECENT_DECKS)
        val keys = pdfs.map { version(File(it.path)) }
        val ready = pdfs.zip(keys).mapNotNull { (spot, key) ->
            val pdf = File(spot.path)
            val out = File(data, "decks/.recent/$key")
            val known = File(cache, "decks/$key.json")
            val pages = if (known.isFile) runCatching { recentDeck(pdf, out, known) }.getOrNull() else null
            if ((!known.isFile || pages.orEmpty().take(DRAWN_AHEAD).any { !it.png.exists() }) && queued.add(key)) drawer.execute {
                runCatching { recentDeck(pdf, out, known)?.let { drawMissing(pdf, it.take(DRAWN_AHEAD)) } }
                queued.remove(key)
            }
            pages?.let { RecentDeck(spot.path, Instant.ofEpochMilli(spot.seen()), it) }
        }
        File(data, "decks/.recent").listFiles()?.filter { it.name !in keys }?.forEach { it.deleteRecursively() }
        return ready.also { recent = it }
    }
}
