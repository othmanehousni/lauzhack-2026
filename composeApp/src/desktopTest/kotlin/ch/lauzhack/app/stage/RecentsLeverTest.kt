package ch.lauzhack.app.stage

import ch.lauzhack.app.Target
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** What recents() finds on this Mac, and show() for each kind of item it returns, against a headless Chrome:
 *
 *   MOLE_RECENTS=1 ./gradlew :composeApp:desktopTest --tests '*RecentsLeverTest*' -i
 *
 * Skipped without MOLE_RECENTS. The real run reads the browsers' history and Spotlight, walks Desktop, Documents and
 * Downloads as the app does when Spotlight lacks files, and draws recent decks into a temporary folder; it prints counts
 * and timings only, never a title, an address or a file's name. */
class RecentsLeverTest {
    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t = TimeSource.Monotonic.markNow()
        return block() to t.elapsedNow().inWholeMilliseconds
    }

    private fun tmp(name: String) = createTempDirectory(name).toFile().canonicalFile

    @Test
    fun realMac() = runBlocking {
        System.getenv("MOLE_RECENTS") ?: return@runBlocking println("recents lever skipped: set MOLE_RECENTS")
        System.setProperty("java.awt.headless", "true")
        val tmp = tmp("mole-recents")
        try {
            val history = History()
            println("Chromium profiles with a History database: ${history.profiles.keys.sorted()}")
            val since = Instant.now().minus(Duration.ofDays(14))
            for ((name, db) in history.profiles.toSortedMap()) {
                val (rows, ms) = timed { history.chromium(db, since) }
                println("  $name: ${db.length() / 1_000_000} MB, ${rows.size} pages of 14 days before the address rules, copied and read in $ms ms")
            }
            val (pages, cold) = timed { history.pages() }
            val (_, warm) = timed { history.pages() }
            println("history: ${pages.size} pages (${pages.count { it.url.startsWith("file:") }} local), first $cold ms, again $warm ms")
            println("  pages seen in the last day: ${pages.count { it.seen > Instant.now().minus(Duration.ofDays(1)) }}")

            val files = Files()
            val (recent, r1) = timed { files.recent() }
            val (_, r2) = timed { files.recent() }
            println("Spotlight, files used or changed in 14 days: ${recent.size}, first $r1 ms, again $r2 ms")
            println("  by kind: ${recent.groupingBy { it.path.substringAfterLast('.', "").lowercase() }.eachCount().toSortedMap()}")
            println("  opened (last-used date) rather than only changed: ${recent.count { it.used > 0 }}")
            val (newest, n1) = timed { files.newest() }
            val (_, n2) = timed { files.newest() }
            println("newestFiles: ${newest.size}, $n1 ms waiting for the background list, again $n2 ms; the list holds ${files.newest(Int.MAX_VALUE).size} files")

            fun slideShaped(s: Spot) = s.path.endsWith(".pdf", true) && s.pages in 3..MOST_SLIDES && (s.height == 0.0 || s.width / s.height in SCREEN)
            fun probe(what: String, spots: List<Spot>) {
                println("$what: ${spots.size} slide-shaped by Spotlight's first page and count")
                var bytes = 0L
                for ((i, s) in spots.take(5).withIndex()) {
                    val pdf = File(s.path)
                    val out = File(tmp, "probe/${what.length}/$i")
                    val (deck, textMs) = timed { recentDeck(pdf, out, File(out, "known.json")) }
                    val (again, againMs) = timed { recentDeck(pdf, out, File(out, "known.json")) }
                    if (deck == null) {
                        println("  candidate $i: not a deck by its pages (checked in $textMs ms)")
                        continue
                    }
                    val (_, drawMs) = timed { drawMissing(pdf, deck) }
                    bytes += deck.sumOf { it.png.length() }
                    println("  deck $i: ${deck.size} pages, ${pdf.length() / 1000} kB; text read in $textMs ms, from the cache in $againMs ms (${again?.size} pages); drawn in $drawMs ms, ${drawMs / deck.size} ms a page")
                }
                println("  pictures of those decks: ${bytes / 1_000_000} MB")
            }
            println("recent PDFs: ${recent.count { it.path.endsWith(".pdf", true) }}")
            probe("recent PDFs", recent.filter(::slideShaped))
            // What a deck costs, on this Mac's slide-shaped PDFs of any date, code repositories included
            val places = FOLDERS.flatMap { listOf("-onlyin", File(HOME, it).path) }
            val attrs = listOf("kMDItemLastUsedDate", "kMDItemNumberOfPages", "kMDItemPageWidth", "kMDItemPageHeight").flatMap { listOf("-attr", it) }
            val any = spots(exec(listOf("mdfind") + places + attrs + "kMDItemContentTypeTree == \"com.adobe.pdf\" && kMDItemNumberOfPages >= 3"))
                .filter(::slideShaped).sortedByDescending { it.seen() }
            probe("PDFs anywhere in the folders", any)

            val library = System.getenv("MOLE_LIBRARY")?.let(::File) ?: File(tmp, "library")
            val desk = AgentDesktop(library, sandbox = null, data = File(tmp, "data"), cache = File(tmp, "cache"), files = files)
            val (first, f1) = timed { desk.recents() }
            println("recents(), first call: ${first.pages.size} pages, ${first.files.size} files, ${first.decks.size} decks ready, $f1 ms")
            val (_, settle) = timed { desk.settled() }
            println("  decks read and drawn in the background within $settle ms")
            repeat(3) {
                val (r, ms) = timed { desk.recents() }
                println("recents(), warm: ${r.pages.size} pages, ${r.files.size} files, ${r.decks.size} decks (${r.decks.sumOf { it.pages.size }} pages), $ms ms")
            }
            val off = AgentDesktop(library, sandbox = null, data = File(tmp, "data"), cache = File(tmp, "cache"), history = false, files = files)
            val (o, oms) = timed { off.recents() }
            println("recents() with history off: ${o.pages.size} pages, ${o.files.size} files, ${o.decks.size} decks, $oms ms")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** The same without Spotlight, from the app's own walk of the folders; no browser history is read. */
    @Test
    fun realMacWithoutSpotlight() = runBlocking {
        System.getenv("MOLE_RECENTS") ?: return@runBlocking println("recents lever skipped: set MOLE_RECENTS")
        System.setProperty("java.awt.headless", "true")
        val tmp = tmp("mole-recents-walk")
        try {
            val roots = FOLDERS.map { File(HOME, it) }
            val said = mutableListOf<String>()
            val spotlight = Files().also { it.newest() }  // once Spotlight's list is in
            val spotlit = spotlight.recent()
            val (_, s2) = timed { spotlight.recent() }
            println("Spotlight alone: ${spotlit.size} files of 14 days, warm recent() $s2 ms")
            val (alone, w1) = timed { Files(roots, note = { said += it }) { "" }.also { it.walked.get(2, TimeUnit.MINUTES) } }
            val (again, w2) = timed { alone.walk() }  // the next tick is 20 s away
            println("walk without Spotlight: first $w1 ms, again $w2 ms, ${again.size} files (${again.count { it.used > 0 }} with a last-use date, ${again.count { it.pages >= 3 }} PDFs of 3 pages or more)")
            val (lone, l1) = timed { alone.recent() }
            val (_, l2) = timed { alone.recent() }
            println("  recent(): ${lone.size} files, $l1 ms, again $l2 ms")
            val a = spotlit.map { it.path }.toSet()
            val b = lone.map { it.path }.toSet()
            println("  of the 14 days' files: ${(a intersect b).size} in both, ${(a - b).size} from Spotlight only, ${(b - a).size} from the walk only")
            println("  Spotlight's only, by kind: ${(a - b).groupingBy { (if (File(it).isDirectory) "folder " else "") + it.substringAfterLast('.').lowercase() }.eachCount()}")
            val top = lone.filter { alone.wanted(it.path) }.take(300)
            println("  the 300 newest files recents() offers: ${top.count { it.path !in a }} not in Spotlight's answer; by kind ${top.groupingBy { it.path.substringAfterLast('.').lowercase() }.eachCount()}; by folder ${top.groupingBy { p -> roots.first { p.path.startsWith(it.path) }.name }.eachCount()}; oldest ${(System.currentTimeMillis() - top.last().seen()) / 60_000} min ago")
            val both = Files(roots, note = { said += it }).also { it.newest() }
            val joined = both.recent()
            val warm = List(5) { timed { both.recent() }.second }
            println("  Spotlight and the walk together: ${joined.size} files, warm recent() $warm ms; Spotlight alone again ${List(5) { timed { spotlight.recent() }.second }} ms")
            said.forEach { println("  said: $it") }
            val desk = AgentDesktop(File(tmp, "library"), sandbox = null, data = File(tmp, "data"), cache = File(tmp, "cache"), history = false, files = alone)
            val (_, settle) = timed { desk.settled() }
            repeat(3) {
                val (r, ms) = timed { desk.recents() }
                println("recents() without Spotlight, warm: ${r.files.size} files, ${r.decks.size} decks (${r.decks.sumOf { it.pages.size }} pages), $ms ms (settled in $settle ms)")
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun answers(port: Int) = runCatching { URI("http://127.0.0.1:$port/json/version").toURL().readText() }.isSuccess

    @Test
    fun showsEachKindOfRecentItem() = runBlocking {
        System.getenv("MOLE_RECENTS") ?: return@runBlocking println("recents lever skipped: set MOLE_RECENTS")
        System.setProperty("java.awt.headless", "true")
        val tmp = tmp("mole-recents-show")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pricing") { ex ->
            val body = "<title>Pricing</title><h1>Plans</h1>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val port = ServerSocket(0).use { it.localPort }
        val chrome = ProcessBuilder(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "--headless=new", "--remote-debugging-port=$port",
            "--user-data-dir=${File(tmp, "chrome")}", "--window-size=1920,1080", "--no-first-run", "--no-default-browser-check", "about:blank",
        ).redirectErrorStream(true).redirectOutput(File(tmp, "chrome.log")).start()
        try {
            val started = TimeSource.Monotonic.markNow()
            while (!answers(port)) {
                check(started.elapsedNow().inWholeSeconds < 20 && chrome.isAlive) { "headless Chrome did not start" }
                delay(100)
            }
            val desktop = File(tmp, "Desktop").apply { mkdirs() }
            val talk = File(desktop, "my talk.html").apply { writeText("<title>Talk</title><p>local</p>") }
            val pricing = "http://127.0.0.1:${server.address.port}/pricing"
            historyDb(File(tmp, "support/Google/Chrome/Default/History"), listOf(
                Visit(pricing, "Pricing", Duration.ofMinutes(30)), Visit(uri(talk), "Talk", Duration.ofMinutes(10)),
            ))
            val pages = History(File(tmp, "support"), listOf(desktop)).pages()
            assertEquals(listOf(uri(talk), pricing), pages.map { it.url })

            val library = File(tmp, "library")
            pdf(File(library, "intro.pdf"), slides(2))
            val roadmap = pdf(File(desktop, "roadmap.pdf"), slides(4)).modified(Duration.ofHours(1))
            val notes = File(desktop, "notes.txt").apply { writeText("plan, price\nPro, 20\n") }.modified(Duration.ofHours(1))
            val spotlight = listOf(
                spotLine(roadmap, Duration.ofMinutes(20), "kMDItemNumberOfPages = 4", "kMDItemPageWidth = 960", "kMDItemPageHeight = 540"),
                spotLine(notes, Duration.ofMinutes(5)),
            ).joinToString("\n")
            val desk = AgentDesktop(library, sandbox = null, data = File(tmp, "data"), cache = File(tmp, "cache"), hostPort = port,
                history = false, files = Files { if ("kMDItemFSContentChangeDate >=" in it.last()) spotlight else "" })
            desk.up()
            val stage = desk.stage
            suspend fun js(expr: String, tab: String = stage.front) = stage.evaluate(tab, expr)?.jsonPrimitive?.content
            suspend fun loaded() { while (js("location.href") == "about:blank" || js("document.readyState") != "complete") delay(50) }

            for (page in pages) {
                val (shown, ms) = timed { desk.show(page.target) }
                loaded()
                assertEquals(page.url, js("location.href"))
                assertEquals(page.title, js("document.title"))
                assertEquals(page.title, shown.name)
                println("history page (${if (page.url.startsWith("file:")) "file://" else "http"}) shown in a tab in $ms ms")
            }

            val seen = desk.settled()
            assertEquals(listOf(notes.path), seen.files.map { it.path })
            desk.show(seen.files[0].target)
            loaded()
            assertEquals(uri(notes), js("location.href"))

            val deck = seen.decks.single()
            assertEquals(roadmap.path, deck.path)
            val third = deck.pages[2]
            third.png.delete()  // as if the background had not drawn it yet
            val (shown, ms) = timed { desk.show(Target.Slide(third.deck, 3)) }
            assertEquals(Target.Slide("roadmap", 3, "Slide 3"), shown.target)
            assertEquals(uri(third.png), js("document.body.firstElementChild?.src", stage.home))
            assertEquals("1920", js("String(document.body.firstElementChild.naturalWidth)", stage.home))
            println("a deck page from outside the library, drawn on demand and shown in $ms ms")
            val (_, again) = timed { desk.show(Target.Slide(third.deck, 2)) }
            assertEquals(uri(deck.pages[1].png), js("document.body.firstElementChild?.src", stage.home))
            println("the next page, drawn in the background, shown in $again ms")
        } finally {
            chrome.destroy()
            chrome.waitFor()
            server.stop(0)
            tmp.deleteRecursively()
        }
    }
}
