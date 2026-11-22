package ch.lauzhack.app.session

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.Target
import ch.lauzhack.app.agent.CLASSIFY
import ch.lauzhack.app.agent.Catalog
import ch.lauzhack.app.agent.EXAMPLES
import ch.lauzhack.app.agent.Embeddings
import ch.lauzhack.app.agent.Intents
import ch.lauzhack.app.agent.MoleAgent
import ch.lauzhack.app.agent.SlideText
import ch.lauzhack.app.stage.AgentDesktop
import ch.lauzhack.app.stage.typed
import ch.lauzhack.app.stage.uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Typed lines through the real MoleAgent (the vectors of the parity dump as its embedder) and the real agent desktop on
 * a headless Chrome, as LeverTest runs it, so nothing shows on screen and no model loads:
 *
 *   MOLE_LIBRARY=<folder of PDFs> MOLE_PARITY=<parity dump> ./gradlew :composeApp:desktopTest --tests '*SessionLeverTest*' -i
 *
 * Skipped without either. Prints every line the mole showed, as it showed it, and the agent's reading of each utterance. */
class SessionLeverTest {
    private val apps = listOf("Notes", "Calculator", "Safari", "System Settings", "Maps", "Photos", "Preview", "Finder", "Keynote", "Mail",
        "Terminal", "Google Chrome") // brain.APPS

    private fun answers(port: Int) = runCatching { URI("http://127.0.0.1:$port/json/version").toURL().readText() }.isSuccess

    @Test
    fun aTalkThroughTheSession() = runBlocking {
        val library = System.getenv("MOLE_LIBRARY")?.let(::File) ?: return@runBlocking println("session lever skipped: set MOLE_LIBRARY")
        val dump = System.getenv("MOLE_PARITY")?.let(::File)?.takeIf { File(it, "python.json").exists() }
            ?: return@runBlocking println("session lever skipped: set MOLE_PARITY to the parity dump")
        System.setProperty("java.awt.headless", "true")

        val python = Json.parseToJsonElement(File(dump, "python.json").readText()).jsonObject
        val dim = python.getValue("dim").jsonPrimitive.int
        val texts = Json.decodeFromString<List<String>>(File(dump, "texts.json").readText())
        val raw = ByteBuffer.wrap(File(dump, "vectors.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val vectors = HashMap<String, List<Double>>()
        texts.forEachIndexed { i, t -> vectors.getOrPut(t) { List(dim) { raw.get(i * dim + it).toDouble() } } }
        val missing = mutableListOf<String>()
        val embedder = object : Embedder {
            override suspend fun embed(text: String) = Vector(vectors[text] ?: run { missing += text; error("the Python run never embedded \"$text\"") })
            override fun diff(embedding1: Vector, embedding2: Vector) = 1 - embedding1.cosineSimilarity(embedding2)
        }
        val embeddings = Embeddings(embedder)
        embeddings.remember(EXAMPLES.flatMap { it.second }.map { CLASSIFY + it })
        val slides = python.getValue("slides").jsonArray.map { it.jsonObject }.map {
            SlideText(it.getValue("deck").jsonPrimitive.content, it.getValue("page").jsonPrimitive.int, it.getValue("title").jsonPrimitive.content,
                it.getValue("text").jsonPrimitive.content)
        }
        val catalog = Catalog.build(embeddings, slides, apps, File("../fixtures/files.txt").readLines())
        val agent = MoleAgent(catalog, Intents.train(embeddings))

        val port = ServerSocket(0).use { it.localPort }
        val profile = Files.createTempDirectory("mole-chrome").toFile()
        val chrome = ProcessBuilder(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "--headless=new", "--remote-debugging-port=$port",
            "--user-data-dir=$profile", "--window-size=1920,1080", "--no-first-run", "--no-default-browser-check", "about:blank",
        ).redirectErrorStream(true).redirectOutput(File(profile.parentFile, "mole-session-chrome.log")).start()
        val keyboard = PipedOutputStream()
        val input = PipedInputStream(keyboard)
        try {
            val started = TimeSource.Monotonic.markNow()
            while (!answers(port)) {
                check(started.elapsedNow().inWholeSeconds < 20 && chrome.isAlive) { "headless Chrome did not start" }
                delay(100)
            }
            val desk = AgentDesktop(library, sandbox = null, hostPort = port)
            println("notes: ${desk.up()}")
            suspend fun pages() = desk.stage.devtools("/json/list").jsonArray.map { it.jsonObject }
                .filter { it["type"]!!.jsonPrimitive.content == "page" }.map { it["url"]!!.jsonPrimitive.content }.toSet()
            suspend fun onStage() = desk.stage.evaluate(desk.stage.home, "document.body.firstElementChild?.src")?.jsonPrimitive?.content
            fun png(deck: String, page: Int) = uri(desk.decks.first { it.deck == deck && it.page == page }.png)
            val home = setOf(uri(desk.stage.page))

            val mole = Mole(this)
            val lines = mutableListOf<Pair<Long, MoleLine>>()
            val t0 = TimeSource.Monotonic.markNow()
            val recorder = launch(Dispatchers.Unconfined) {
                mole.line.collect { l ->
                    val at = t0.elapsedNow().inWholeMilliseconds
                    lines += at to l
                    println("%6d ms  %-10s %-44s %s".format(at, l.state.name.lowercase(), l.title.orEmpty(), l.heard.take(60)) +
                        (if (l.options.isNotEmpty()) "  ${l.options.map { it.name }}" else "") + (if (l.card.isNotEmpty()) "  [${l.card}]" else ""))
                }
            }
            fun now() = t0.elapsedNow().inWholeMilliseconds
            val calls = mutableListOf<Triple<Long, Long, String>>() // each screen call: when it began, when it returned, what
            val inner = desk.asScreen()
            suspend fun <T> timed(what: String, call: suspend () -> T): T {
                val at = now()
                try {
                    return call()
                } finally {
                    calls += Triple(at, now(), what)
                    println("%6d ms  screen     %-44s %d ms".format(at, what, now() - at))
                }
            }
            val screen = object : SharedScreen {
                override val onScreen get() = inner.onScreen
                override suspend fun show(target: Target) = timed("show ${name(target)}") { inner.show(target) }
                override suspend fun control(name: String) = timed(name) { inner.control(name) }
                override suspend fun close() = timed("close") { inner.close() }
            }
            val session = Session(agent, screen, mole, typed(input).map { it.heard })
            val running = launch { session.run() }
            var n = 0 // the utterance heard before the line typed last
            var typedAt = 0L

            fun type(said: String) {
                typedAt = now()
                println("%6d ms  > %s".format(typedAt, said))
                keyboard.write("$said\n".toByteArray())
                keyboard.flush()
                n = session.heard.value.n
            }

            /** Types [said] as one utterance (unless [typed] already) and waits until the agent has had it and the mole has settled. */
            suspend fun say(said: String, typed: Boolean = false): MoleLine {
                if (!typed) type(said)
                withTimeout(25.seconds) {
                    while (true) {
                        val (at, l) = lines.last()
                        val heard = session.heard.value
                        if (heard.n > n && heard.ended && said.endsWith(heard.text) && l.state in setOf(MoleState.Hidden, MoleState.Idle, MoleState.Choosing) &&
                            t0.elapsedNow().inWholeMilliseconds - at > 1200) return@withTimeout
                        delay(100)
                    }
                }
                println("          agent: ${agent.thought?.let { "${it.act ?: "-"} ${it.offer.ifEmpty { "" }} ${it.reason}" }}")
                return lines.last().second
            }
            fun since(i: Int) = lines.drop(i).map { it.second.state }

            val acme = "Acme_Company_Overview_2026"
            assertEquals(MoleState.Idle, say("Hi everyone, thanks for joining. Can you all hear me okay?").state)
            assertEquals(home, pages())
            assertEquals(uri(desk.stage.wallpaper), onStage())

            var mark = lines.size
            say("Great. Okay, now that I'm connected, let's open up the company slides.")
            assertEquals(png(acme, 1), onStage())
            assertEquals(listOf(MoleState.Heard, MoleState.Ready, MoleState.Back, MoleState.Hidden), since(mark).dropWhile { it != MoleState.Heard })

            mark = lines.size
            say("Let's start with the problem we're solving.") // the agent is not sure enough to act: it announces the next slide
            val (announcedAt, announce) = lines.drop(mark).first { it.second.state == MoleState.Retrieving }
            assertEquals("Showing slide 2" to 2500L, announce.title to announce.timeoutMs)
            val shown = calls.first { it.third == "show slide 2" }
            assertEquals(png(acme, 2), onStage(), "shown once its window ran out")
            val ready = lines.drop(mark).first { it.second.state == MoleState.Ready }.first
            println("latency: typed to announce ${announcedAt - typedAt} ms, announce to screen call ${shown.first - announcedAt} ms, " +
                "screen call ${shown.second - shown.first} ms, typed to on screen ${shown.second - typedAt} ms, typed to ready ${ready - typedAt} ms")
            assertTrue(shown.first - announcedAt in 2500L..2600L, "the window, then at once")
            assertEquals(listOf(MoleState.Retrieving, MoleState.Picked, MoleState.Ready, MoleState.Back, MoleState.Hidden), since(mark).dropWhile { it != MoleState.Retrieving }.distinct())
            say("Warehouses simply can't hire enough people to pick orders, and labour ends up being more than half of fulfilment cost.")
            assertEquals(png(acme, 2), onStage(), "the talk stays on what is up")
            say("So what did we build? A mobile robot with a suction gripper that drops right into your existing shelving.")
            assertEquals(png(acme, 3), onStage())
            say("Next slide please.")
            assertEquals(png(acme, 4), onStage())
            say("Next slide please.") // the same words again: a second utterance, a second move
            assertEquals(png(acme, 5), onStage())

            val choosing = say("And can you pull up the offer we sent them?")
            assertEquals(MoleState.Choosing, choosing.state)
            println("options: ${choosing.options}")
            assertEquals(home, pages(), "nothing is shared before the pick")
            say("The second one.")
            val picked = uri(File("/Users/hamid/Documents/Clients/Dubois/${choosing.options[1].name}"))
            assertEquals(home + picked, pages())
            assertNotEquals(desk.stage.home, desk.stage.front, "the offer's tab in front")
            say("Okay, let's close that and go back to the slides.") // the agent goes back to the slides; the tab stays behind them
            assertEquals(desk.stage.home, desk.stage.front)
            assertEquals(png(acme, 5), onStage())

            mark = lines.size
            say("Actually, before that, let me jump to the questions people usually ask us at this point.") // a deck not in the library
            // both tries fail at once, before heard has been read, so the line goes from heard to failed
            assertEquals(listOf(MoleState.Heard, MoleState.Failed, MoleState.Back, MoleState.Hidden), since(mark).dropWhile { it != MoleState.Heard })
            println("failed: ${lines.drop(mark).last { it.second.state == MoleState.Failed }.second}")
            assertEquals(png(acme, 5), onStage())
            say("Next slide please.")
            assertEquals(png(acme, 6), onStage(), "the next request works")

            mark = lines.size
            say("Marc, sorry, you're asking about the garden shed? Let me open your current contract, it's in there.") // nothing fits well enough
            assertTrue(MoleState.Unsure in since(mark), "${since(mark)}")
            assertEquals(home + picked, pages(), "nothing opened")
            assertEquals(desk.stage.home, desk.stage.front)
            assertEquals(png(acme, 6), onStage())

            // the same suggestion again, turned down on the bar before its window runs out: nothing changes on screen
            mark = lines.size
            val before = calls.size
            val again = "Let's start with the problem we're solving."
            type(again)
            withTimeout(5.seconds) { while (lines.drop(mark).none { it.second.state == MoleState.Retrieving }) delay(10) }
            val stoppedAt = now()
            session.pick(-1)
            say(again, typed = true)
            val stopped = lines.drop(mark).first { it.second.state == MoleState.Back }
            println("latency: dismiss to \"${stopped.second.title}\" ${stopped.first - stoppedAt} ms")
            assertEquals("Okay, not shown.", stopped.second.title)
            assertEquals(before, calls.size, "nothing shown")
            assertEquals(png(acme, 6), onStage())

            running.cancel()
            recorder.cancel()
            assertEquals(emptyList(), missing, "every text the agent embedded is in the dump")
        } finally {
            keyboard.close()
            chrome.destroy()
            chrome.waitFor()
            profile.deleteRecursively()
        }
    }
}
