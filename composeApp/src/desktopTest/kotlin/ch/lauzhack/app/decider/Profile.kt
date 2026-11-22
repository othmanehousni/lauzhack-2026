package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ch.lauzhack.app.Heard
import ch.lauzhack.app.engine.LiteRTClientConfig
import ch.lauzhack.app.engine.LiteRTLLMClient
import ch.lauzhack.app.engine.desktopEngines
import ch.lauzhack.app.stage.decks
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.time.TimeSource

private fun rss() = ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start().inputStream.bufferedReader().readText().trim().toLong() / 1024

private fun text(m: com.google.ai.edge.litertlm.Message) = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.replace('▁', ' ')

/** Where a decision's time goes, on this Mac's models: MOLE_CHECK=profile, MOLE_LIBRARY a folder of decks. */
@OptIn(ExperimentalApi::class)
class Profile {
    private val engines = desktopEngines()

    private inline fun <T> ms(block: () -> T): Pair<T, Long> {
        val t = TimeSource.Monotonic.markNow()
        return block() to t.elapsedNow().inWholeMilliseconds
    }

    @Test
    fun decisions() = runBlocking {
        assumeTrue("MOLE_CHECK=profile runs this", System.getenv("MOLE_CHECK") == "profile")
        val gemma = engines.embedder()
        var embedMs = 0L
        var embeds = 0
        val embedder = object : Embedder by gemma {
            override suspend fun embed(text: String) = ms { gemma.embed(text) }.let { (v, t) -> embedMs += t; embeds++; v }
        }
        val pages = decks(File(System.getenv("MOLE_LIBRARY")), Files.createTempDirectory("profile").toFile(), width = 64)
        val items = items(pages, listOf("Notes", "Preview", "Safari", "Keynote"), emptyList())
        val (catalog, buildMs) = ms { Catalog.build(items, embedder, meeting = pages.map { it.deck }.toSet()) }
        println("catalog of ${items.size} items, ${embeds} documents embedded in $buildMs ms")
        val client = LiteRTLLMClient(LiteRTClientConfig(engines.modelsDir, engines.cacheDir, backends = listOf(Backend.CPU())))
        val decide = decider(client)
        println("first unconstrained call ${ms { decide(prompt(State()), null) }.second} ms, constrained ${ms { decide(prompt(State()), grammar(State())) }.second} ms")

        val prompts = mutableListOf<Pair<String, String?>>()
        var modelMs = 0L
        val agent = ModelAgent(catalog, embedder, { p, g -> prompts += p to g; ms { decide(p, g) }.let { (a, t) -> modelMs += t; a } })
        val desk = FakeDesk(catalog.items.filterIsInstance<Item.Slide>().first().target)
        val lines = listOf("Next slide, please.", "So how big is this market, really?", "Go to slide 7.", "Close to forty percent of our customers renew every year.", "Open GitHub.", "Previous slide.")
        println("\ndecision                                     total  embed(n)  model  rest")
        for ((n, line) in lines.withIndex()) {
            val w = line.split(' ')
            val earlier = lines.take(n).takeLast(3)
            val pieces = (2 until w.size step 2).map { Heard(w.take(it).joinToString(" "), n = n + 1, earlier = earlier) } +
                Heard(line, n = n + 1, earlier = earlier, quiet = true) + Heard(line, true, n + 1, earlier)
            for (heard in pieces) {
                embedMs = 0; embeds = 0; modelMs = 0
                val (move, total) = ms { agent.anythingToDo(heard, desk.onScreen) }
                desk.apply(move)
                val how = if (heard.ended) "end " else if (heard.quiet) "qui " else "    "
                println("%-44s %5d  %4d(%d)  %5d  %4d  %s  %s".format(how + heard.text.take(38), total, embedMs, embeds, modelMs, total - embedMs - modelMs, agent.answer, move))
            }
        }
        val (p, g) = prompts.last { it.second != null }
        println("\nprompt and grammar in ${ms { repeat(100) { prompt(State()); grammar(State()) } }.second / 100.0} ms; a prompt of ${p.length} chars")

        println("\nfour answers at the end of an utterance, 750 ms apart, through the app's client")
        val rapid = prompts.filter { it.second != null }.take(4)
        val start = TimeSource.Monotonic.markNow()
        val took = rapid.mapIndexed { i, (p, g) -> async(Dispatchers.Default) { delay(750L * i); ms { decide(p, g) }.second } }.awaitAll()
        println("  $took ms (all done ${start.elapsedNow().inWholeMilliseconds} ms after the first)")
        client.close()
        gemma.close()
        if (System.getenv("MOLE_ENGINE") == null) return@runBlocking

        println("\nthe engine itself")
        val path = "${engines.modelsDir}/$DECIDER"
        val answers = mutableMapOf<Int?, List<String>>()
        for (threads in listOf(null, 6, 8)) {
            ExperimentalFlags.enableBenchmark = true
            val engine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(threads), cacheDir = engines.cacheDir))
            println("threads ${threads ?: "default"}: initialized in ${ms { engine.initialize() }.second} ms")
            val sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0)
            fun create(constrained: Boolean): Conversation {
                ExperimentalFlags.enableConversationConstrainedDecoding = constrained
                return engine.createConversation(ConversationConfig(samplerConfig = sampler, enableResponseFormat = constrained))
            }
            for (constrained in listOf(false, true)) {
                val made = List(3) { ms { create(constrained) } }
                val cloned = List(3) { ms { made[0].first.clone() } }
                println("  ${if (constrained) "constrained  " else "unconstrained"} create ${made.map { it.second }} ms, clone ${cloned.map { it.second }} ms")
                if (constrained) {
                    val a = text(cloned[0].first.sendMessage(p, maxOutputToken = 16, responseFormat = ResponseFormat.regex(g!!.replace(' ', '▁'))))
                    println("  a clone answers under the grammar: \"$a\"")
                }
                (made + cloned).forEach { runCatching { it.first.close() } }
            }
            val got = mutableListOf<String>()
            for ((i, pg) in prompts.withIndex()) {
                val (prompt, grammar) = pg
                val c = create(grammar != null)
                val (a, t) = ms { text(c.sendMessage(prompt, maxOutputToken = 16, responseFormat = grammar?.let { ResponseFormat.regex(it.replace(' ', '▁')) })) }
                val b = c.getBenchmarkInfo()
                got += a
                if (i < 6 || grammar != null && i == prompts.lastIndex) println("  %s %4d ms  prefill %d tokens at %.0f/s, first token %.0f ms, decode %d tokens at %.0f/s  \"%s\"".format(
                    if (grammar != null) "end" else "   ", t, b.lastPrefillTokenCount, b.lastPrefillTokensPerSecond, b.timeToFirstTokenInSecond * 1000, b.lastDecodeTokenCount, b.lastDecodeTokensPerSecond, a.trim()))
                c.close()
            }
            answers[threads] = got
            if (threads == null) {
                for (constrained in listOf(false, true, false, true)) {
                    val c = create(constrained)
                    val (both, t) = ms {
                        val answer = async(Dispatchers.IO) {
                            ms { text(c.sendMessage(p, maxOutputToken = 16, responseFormat = if (constrained) ResponseFormat.regex(g!!.replace(' ', '▁')) else null)) }
                        }
                        val made = async(Dispatchers.IO) { ms { create(true).also { it.close() } }.second }
                        answer.await() to made.await()
                    }
                    println("  a${if (constrained) " constrained" else "n unconstrained"} answer while a constrained conversation is created: answer ${both.first.second} ms \"${both.first.first.trim()}\", create ${both.second} ms, both $t ms")
                    c.close()
                }
                println("  RSS before ten idle conversations ${rss()} MB")
                val idle = List(10) { create(false) }
                println("  RSS after ${rss()} MB")
                idle.forEach { it.close() }
            }
            engine.close()
        }
        println("\nanswers the same as with the default threads: " + answers.mapValues { (_, v) -> v.zip(answers[null]!!).count { it.first == it.second } }.toString() + " of ${prompts.size}")
    }
}
