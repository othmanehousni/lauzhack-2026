package ch.lauzhack.app.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that load models, so they run on Modal only (eval/modal_engine.py), never on the Mac:
 * MOLE_CHECK=embedder|client|vectors, MOLE_MODELS=<models dir>, MOLE_CACHE=<cache dir>; vectors also reads
 * MOLE_TEXTS (a JSON array of strings) and writes MOLE_VECTORS (little-endian float32, n × 768).
 * Without MOLE_CHECK every test here is skipped.
 */
class EngineCheck {
    private val engines by lazy { Engines(System.getenv("MOLE_MODELS"), System.getenv("MOLE_CACHE")) }

    private fun only(check: String) = assumeTrue("MOLE_CHECK=$check runs this", System.getenv("MOLE_CHECK") == check)

    private fun download(vararg more: ModelFile) = runBlocking {
        val started = System.nanoTime()
        var shown = -1L
        engines.download(*more).collect { p -> (p.bytes * 10 / p.total).let { if (it != shown) { shown = it; println("${p.file} ${p.bytes}/${p.total}") } } }
        println("models ready in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    private fun rss(label: String) = File("/proc/self/status").takeIf { it.exists() }?.let { s -> println("$label: " + s.readLines().filter { it.startsWith("VmRSS") }.joinToString()) }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val started = System.nanoTime()
        return block().also { println("$label: ${(System.nanoTime() - started) / 1_000_000} ms") }
    }

    @Test fun embedder() {
        only("embedder")
        download()
        rss("before load")
        val embedder = timed("load") { engines.embedder() }
        println("backend: ${embedder.backend}")
        rss("after load")
        val texts = listOf(
            "task: classification | query: show me the revenue slide",
            "task: classification | query: can we look at the slide about revenue",
            "task: classification | query: my cat sleeps in the sun all afternoon",
            "task: search result | query: open the quarterly budget spreadsheet",
            "task: search result | query: pull up the Q3 budget sheet",
            "task: search result | query: what time is lunch today",
            "title: Q3 review | text: " + "Revenue grew in every region, led by the new subscription plans. ".repeat(40),
        )
        val vectors = runBlocking { texts.mapIndexed { i, t -> timed("embed ${i + 1} (${t.length} chars)") { embedder.embedFloats(t) } } }
        repeat(3) { runBlocking { timed("embed again") { embedder.embedFloats(texts[0]) } } }
        val parallel = runBlocking { timed("embed 4 at once") { (0..3).map { async(Dispatchers.Default) { embedder.embed(texts[it]) } }.awaitAll() } }
        rss("after embedding")
        embedder.close()
        fun cos(a: Int, b: Int) = vectors[a].indices.sumOf { vectors[a][it].toDouble() * vectors[b][it] }
        vectors.forEach { v ->
            assertEquals(768, v.size)
            assertEquals(1.0, sqrt(v.sumOf { it.toDouble() * it }), 1e-3)
        }
        assertEquals(1.0, parallel[1].cosineSimilarity(ai.koog.embeddings.base.Vector(vectors[1].map(Float::toDouble))), 1e-5)
        println("cos paraphrase ${"%.3f".format(cos(0, 1))} unrelated ${"%.3f".format(cos(0, 2))}; paraphrase ${"%.3f".format(cos(3, 4))} unrelated ${"%.3f".format(cos(3, 5))}")
        assertTrue(cos(0, 1) > cos(0, 2) + 0.1)
        assertTrue(cos(3, 4) > cos(3, 5) + 0.1)
    }

    @Serializable enum class Option { opt_A, opt_B }
    @Serializable data class Pick(@SerialName("pick_q7") val pick: Option)

    @Test fun client() {
        only("client")
        val file = ModelFile(
            "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            2_588_147_712,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        )
        download(file)
        rss("before load")
        val client = engines.client(file.name, maxNumTokens = 4096)!!
        val executor = MultiLLMPromptExecutor(client)
        val model = liteRTModel(file.name)
        val tools = listOf(
            ToolDescriptor(
                "show_slide", "Put one page of a slide deck on the shared screen",
                requiredParameters = listOf(
                    ToolParameterDescriptor("deck", "file name of the deck", ToolParameterType.String),
                    ToolParameterDescriptor("page", "page number, 1-based", ToolParameterType.Integer),
                ),
            ),
            ToolDescriptor(
                "open_site", "Open a website on the shared screen",
                requiredParameters = listOf(ToolParameterDescriptor("url", "full https URL", ToolParameterType.String)),
            ),
        )
        val greedy = LLMParams(temperature = 0.0)
        val ask = prompt("tools", greedy) {
            system("You put things on the shared screen for a presenter in a video call. Answer with exactly one tool call.")
            user("Decks: q3.pdf (12 pages), roadmap.pdf (8 pages). The presenter says: \"let's go to page 7 of the Q3 deck\".")
        }
        val reply = runBlocking { timed("tool call (first, loads the engine)") { executor.execute(ask, model, tools) } }
        println("reply: ${reply.parts}")
        rss("after tool call")
        val call = reply.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("show_slide", call.tool)
        assertEquals(7, call.argsJson["page"]!!.jsonPrimitive.int)
        assertTrue("q3" in call.argsJson["deck"]!!.jsonPrimitive.content.lowercase())
        val again = runBlocking { timed("tool call (engine loaded)") { executor.execute(ask, model, tools) } }
        assertEquals("show_slide", again.parts.filterIsInstance<MessagePart.Tool.Call>().single().tool)

        val choose = prompt("json", greedy) {
            system("You answer the presenter's question by picking an option.")
            user("Which one can you eat? opt_A: a chair. opt_B: an apple.")
        }
        val schema = LLMParams.Schema.JSON.Standard("pick", Json.parseToJsonElement(
            """{"type":"object","properties":{"pick_q7":{"type":"string","enum":["opt_A","opt_B"]}},"required":["pick_q7"]}""").jsonObject)
        val raw = runBlocking { timed("constrained json") { executor.execute(choose.withParams(LLMParams(temperature = 0.0, schema = schema)), model, emptyList()) } }
        println("constrained reply: ${raw.textContent()}")
        assertTrue(Json.parseToJsonElement(raw.textContent()).jsonObject["pick_q7"]!!.jsonPrimitive.content in setOf("opt_A", "opt_B"))
        val structured = runBlocking { timed("executeStructured") { executor.executeStructured<Pick>(choose, model).getOrThrow() } }
        println("executeStructured: ${structured.data} from ${structured.message.textContent()}")
        rss("after json")
        client.close()
    }

    @Test fun vectors() {
        only("vectors")
        val texts = Json.decodeFromString<List<String>>(File(System.getenv("MOLE_TEXTS")).readText())
        download()
        val embedder = engines.embedder()
        println("backend: ${embedder.backend}; ${texts.size} texts")
        val out = ByteBuffer.allocate(texts.size * GemmaEmbedder.DIMENSION * 4).order(ByteOrder.LITTLE_ENDIAN)
        val started = System.nanoTime()
        runBlocking { texts.forEachIndexed { i, t -> embedder.embedFloats(t).forEach(out::putFloat); if (i % 500 == 499) println("${i + 1} texts") } }
        println("${texts.size} texts in ${(System.nanoTime() - started) / 1_000_000} ms")
        embedder.close()
        File(System.getenv("MOLE_VECTORS")).writeBytes(out.array())
    }
}
