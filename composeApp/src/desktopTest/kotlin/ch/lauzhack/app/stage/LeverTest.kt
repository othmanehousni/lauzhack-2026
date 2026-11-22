package ch.lauzhack.app.stage

import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** The shared screen timed step by step (slides, a website, files, controls, tabs), against a headless Chrome on this Mac, so nothing shows on screen:
 *
 *   MOLE_LIBRARY=<folder of PDFs> ./gradlew :composeApp:desktopTest --tests '*LeverTest*' -i
 *
 * Skipped without MOLE_LIBRARY. Thumbnails land in /tmp/mole-lever. The VM is only read (status, ls) and dry-run. */
class LeverTest {
    private val out = File("/tmp/mole-lever").apply { mkdirs() }
    private val rows = mutableListOf<Triple<String, Double, String>>()

    private suspend fun <T> timed(what: String, python: String = "", block: suspend () -> T): T {
        val t = TimeSource.Monotonic.markNow()
        return block().also { rows += Triple(what, t.elapsedNow().toDouble(DurationUnit.MILLISECONDS), python) }
    }

    private fun answers(port: Int) = runCatching { URI("http://127.0.0.1:$port/json/version").toURL().readText() }.isSuccess

    @Test
    fun stage() = runBlocking {
        assumeTrue("set MOLE_LIBRARY to run the lever", System.getenv("MOLE_LIBRARY") != null)
        val library = File(System.getenv("MOLE_LIBRARY"))
        System.setProperty("java.awt.headless", "true")
        val port = ServerSocket(0).use { it.localPort }
        val profile = Files.createTempDirectory("mole-chrome").toFile()
        val chrome = ProcessBuilder(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "--headless=new", "--remote-debugging-port=$port",
            "--user-data-dir=$profile", "--window-size=1920,1080", "--no-first-run", "--no-default-browser-check", "about:blank",
        ).redirectErrorStream(true).redirectOutput(File(out, "chrome.log")).start()
        try {
            val started = TimeSource.Monotonic.markNow()
            while (!answers(port)) {  // up() would open a visible Chrome if this one were not answering yet
                check(started.elapsedNow().inWholeSeconds < 20 && chrome.isAlive) { "headless Chrome did not start" }
                delay(100)
            }
            val fresh = Files.createTempDirectory("mole-decks").toFile()
            val rendered = timed("render every deck, fresh (PDFBox)") { decks(library, fresh) }
            fresh.deleteRecursively()
            val desk = AgentDesktop(library, sandbox = null, hostPort = port)
            val notes = timed("up: stage tab, preload ${rendered.size} slides, wallpaper, apps") { desk.up() }
            println("notes: $notes")
            val stage = desk.stage
            suspend fun pages() = stage.devtools("/json/list").jsonArray.map { it.jsonObject }.filter { it["type"]!!.jsonPrimitive.content == "page" }
            suspend fun js(expr: String, tab: String = stage.home) = stage.evaluate(tab, expr)?.jsonPrimitive?.content
            suspend fun onStage() = js("document.body.firstElementChild?.src")
            fun JsonObject.url() = this["url"]!!.jsonPrimitive.content

            assertEquals(listOf(uri(stage.page)), pages().map { it.url() })
            assertEquals(uri(stage.wallpaper), onStage())
            assertEquals(OnScreen(), desk.onScreen)
            println("${desk.decks.size} slides: " + desk.decks.joinToString(" | ") { "${it.deck} ${it.page} ${it.title} (${it.text.length})" })
            println("${desk.apps.size} apps, e.g. ${desk.apps.take(5)}")

            val decks = desk.decks.groupBy { it.deck }.values.toList()
            val (first, second) = decks[0] to decks[1]
            for ((deck, n) in listOf(first to 4, first to 2, second to 5, first to 4) + (1..16).map { decks[it % decks.size] to 1 + it % 6 }) {
                val shown = timed("slide swap", "10-50 ms") { desk.show(Target.Slide(deck[0].deck, n)) }
                assertEquals(Target.Slide(deck[0].deck, n, deck[n - 1].title), shown.target)
                assertEquals(uri(deck[n - 1].png), onStage())
                assertEquals("1920", js("String(document.body.firstElementChild.naturalWidth)"))
            }
            val thumb = timed("thumbnail of a slide") { desk.thumbnail() }!!
            File(out, "slide.jpg").writeBytes(thumb)
            assertEquals(1080, ImageIO.read(thumb.inputStream()).width)
            timed("thumbnail of the same slide again") { desk.thumbnail() }

            val wiki = Target.Site("https://en.wikipedia.org/wiki/Warehouse", "Wikipedia")
            timed("open a website in a new tab", "about 0.2 s") { desk.show(wiki) }
            val site = stage.front
            assertNotEquals(stage.home, site)
            assertTrue(pages().any { it.url().startsWith(wiki.url) })
            while (js("String(document.readyState == 'complete' && document.documentElement.scrollHeight > innerHeight)", site) != "true") delay(100)
            File(out, "site.jpg").writeBytes(timed("thumbnail of a tab (screenshot)") { desk.thumbnail() }!!)
            timed("control: scroll down") { desk.control("scroll down") }
            delay(800)
            assertTrue(js("String(scrollY)", site)!!.toDouble() > 0, js("JSON.stringify([location.href, document.title, document.body.innerText.length, scrollY, document.documentElement.scrollHeight, innerHeight])", site))
            timed("control: scroll down") { desk.control("scroll down") }
            timed("control: zoom in") { desk.control("zoom in") }
            assertEquals("1.25", js("document.body.style.zoom", site))

            val links = File(out, "links.html").apply {
                writeText("<title>links</title><a href=\"#q3\">Quarterly results</a> <button onclick=\"document.title='pressed'\">Show the robot</button>")
            }
            timed("open a local HTML file in a tab") { desk.show(Target.File(links.path)) }
            while (js("document.readyState", stage.front) != "complete") delay(50)
            timed("control: click a link by its words") { desk.control("click:quarterly results") }
            desk.control("click:the robot")
            assertEquals("#q3", js("location.hash", stage.front))
            assertEquals("pressed", js("document.title", stage.front))

            val pdf = desk.decks.last().let { File(library, "${it.deck}.pdf") }
            timed("open a local PDF in a tab", "about 0.2 s") { desk.show(Target.File(pdf.path)) }
            assertTrue(pages().any { it.url() == uri(pdf) })
            delay(1500)
            File(out, "pdf.jpg").writeBytes(desk.thumbnail()!!)

            val tabs = pages().size
            timed("back to the website, its tab reused") { desk.show(wiki) }
            assertEquals(site, stage.front)
            assertEquals(tabs, pages().size)

            timed("close the tab on screen") { desk.close() }
            assertEquals(tabs - 1, pages().size)
            assertEquals(stage.home, stage.front)
            assertEquals(Target.Slide(decks[16 % decks.size][0].deck, 5, decks[16 % decks.size][4].title), desk.onScreen.target)
            assertEquals(OnScreen(), timed("close on the stage: the desktop picture") { desk.close() })
            assertEquals(uri(stage.wallpaper), onStage())

            val oldHome = stage.home
            stage.devtools("/json/close/$oldHome")
            delay(300)
            timed("slide after the stage tab was closed: reopened") { desk.show(Target.Slide(first[0].deck, 3)) }
            assertNotEquals(oldHome, stage.home)
            assertEquals(uri(first[2].png), onStage())

            val asks = (1..6).map { n -> async { desk.show(Target.Slide(second[0].deck, n)) } } + async { desk.thumbnail(); OnScreen() }
            asks.awaitAll()
            assertEquals(uri(second[5].png), onStage())  // calls ran one at a time, in the order they were asked
            File(out, "last.jpg").writeBytes(desk.thumbnail()!!)

            println("\n| step | Kotlin | Python (README) |\n|---|---|---|")
            rows.groupBy { it.first }.forEach { (what, r) ->
                val ms = r.map { it.second }.sorted()
                val kotlin = if (ms.size == 1) "%.0f ms".format(ms[0]) else "%.1f / %.1f / %.1f ms (min / median / max of %d)".format(ms.first(), ms[ms.size / 2], ms.last(), ms.size)
                println("| $what | $kotlin | ${r[0].third} |")
            }
            val newest = desk.newestFiles(5)
            println("newest files: $newest")
            val word = newest[0].substringAfterLast('/').substringBefore('.')
            println("find \"$word\": ${desk.find(word)}")
            assertTrue(desk.find(word)!!.substringAfterLast('/').lowercase().contains(word.lowercase()))
        } finally {
            chrome.destroy()
            chrome.waitFor()
            profile.deleteRecursively()
        }
    }

    @Test
    fun vm() = runBlocking {
        assumeTrue("set MOLE_LIBRARY to run the lever", System.getenv("MOLE_LIBRARY") != null)
        val library = File(System.getenv("MOLE_LIBRARY"))
        System.setProperty("java.awt.headless", "true")
        val ad = agentDesktop ?: return@runBlocking println("agent-desktop is not installed")
        println("agent-desktop: $ad")
        val real = Vm("base", ad)
        println("status: ${real.status()}")
        println("missing sandbox: ${Vm("nosuchbox", ad).status()}")

        println("\n-- dry, sandbox VM:")
        val desk = AgentDesktop(library, "base", dry = true)
        println("notes: ${desk.up()}")
        val deck = desk.decks[0].deck
        desk.show(Target.Slide(deck, 2))
        desk.show(Target.Site("https://example.com", "Example"))
        desk.show(Target.File(File(library, "$deck.pdf").path))
        desk.show(Target.File("/Users/admin/Desktop/talk.key"))
        desk.show(Target.App("Preview"))
        desk.control("scroll down")
        desk.close()

        println("\n-- dry, no sandbox VM set up:")
        println("notes: ${AgentDesktop(library, "nosuchbox", dry = true).up()}")

        println("\n-- read-only in the VM:")
        println("$ ${quote(real.ssh())}")
        val apps = real.guest("ls /Applications /System/Applications /System/Applications/Utilities", check = false).lines().filter { it.endsWith(".app") }
        println("${apps.size} apps in the VM, e.g. ${apps.take(6)}")
        val page = File(DATA, "stage.html")
        println("VM reads ${page.path} and has Chrome: " + real.guest("test -r ${quote(page.path)} && test -d '/Applications/Google Chrome.app' && echo ok", check = false).trim())

        println("\n-- the ear:")
        val ear = Ear()
        val t = TimeSource.Monotonic.markNow()
        val exe = ear.built()
        println("built ${exe.path} in ${t.elapsedNow()}; again: ${TimeSource.Monotonic.markNow().also { ear.built() }.elapsedNow()}")
        println(exec(listOf("launchctl", "plist", "__TEXT,__info_plist", exe.path)).trim())
    }
}
