package ch.lauzhack.app.agent

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.Heard
import ch.lauzhack.app.Move
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * Replays every eval script through [MoleAgent.anythingToDo] as brain.evaluate does (two words at a time, scored on what
 * the screen shows after each line) with the vectors the Python run embedded, and compares each decision with the
 * Python's. The dump comes from `modal run eval/modal_eval.py --parity .stage/parity`; without it the test only says so.
 */
class ParityTest {
    private val repo = File("..")
    private val dump = File(System.getenv("MOLE_PARITY") ?: "../.stage/parity")
    private val apps = listOf("Notes", "Calculator", "Safari", "System Settings", "Maps", "Photos", "Preview", "Finder", "Keynote", "Mail",
        "Terminal", "Google Chrome") // brain.APPS

    private class Call(val script: String, val line: Int, val said: String, val end: Boolean, val act: String?, val offer: List<String>, val why: String)

    private class Line(val script: String, val n: Int, val said: String, val want: List<String?>)

    @Test
    fun crc32IsZlibs() = assertEquals(907060870L, crc32("hello".encodeToByteArray()))

    @Test
    fun replaysTheEvalsAsThePythonDid() = runBlocking {
        if (!File(dump, "python.json").exists()) return@runBlocking println("no parity dump in $dump: modal run eval/modal_eval.py --parity .stage/parity")
        val python = Json.parseToJsonElement(File(dump, "python.json").readText()).jsonObject
        val dim = python.getValue("dim").jsonPrimitive.int
        val texts = Json.decodeFromString<List<String>>(File(dump, "texts.json").readText())
        val raw = ByteBuffer.wrap(File(dump, "vectors.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val vectors = HashMap<String, List<Double>>()
        texts.forEachIndexed { i, t -> vectors.getOrPut(t) { List(dim) { raw.get(i * dim + it).toDouble() } } }
        val embedder = object : Embedder {
            override suspend fun embed(text: String) = Vector(vectors[text] ?: error("the Python run never embedded \"$text\""))
            override fun diff(embedding1: Vector, embedding2: Vector) = 1 - embedding1.cosineSimilarity(embedding2)
        }
        val embeddings = Embeddings(embedder)
        embeddings.remember(EXAMPLES.flatMap { it.second }.map { CLASSIFY + it })
        val cold = TimeSource.Monotonic.markNow()
        val intents = Intents.train(embeddings)
        val coldTime = cold.elapsedNow()
        val warm = TimeSource.Monotonic.markNow()
        Intents.train(embeddings)
        println("classifier: ${EXAMPLES.sumOf { it.second.size }} sentences, trained in $coldTime on a cold JVM, ${warm.elapsedNow()} warm")
        val slides = python.getValue("slides").jsonArray.map { it.jsonObject }
            .map { SlideText(it.str("deck"), it.getValue("page").jsonPrimitive.int, it.str("title"), it.str("text")) }
        val catalog = Catalog.build(embeddings, slides, apps, File(repo, "fixtures/files.txt").readLines())

        val lines = mutableListOf<Line>()
        val mine = mutableListOf<Call>()
        val thoughts = mutableListOf<Thought>()
        val dumped = python.getValue("calls").jsonArray.map { it.jsonObject.str("script") }.toSet() // scripts added since the dump have no vectors
        for (script in File(repo, "eval").listFiles { f -> f.extension == "jsonl" && f.nameWithoutExtension in dumped }!!.sortedBy { it.name }) {
            val agent = MoleAgent(catalog, intents)
            var screen = View()
            script.readLines().filter { it.isNotBlank() }.forEachIndexed { i, json ->
                val o = Json.parseToJsonElement(json).jsonObject
                val line = Line(script.nameWithoutExtension, i + 1, o.str("say"), o.getValue("want").jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.content })
                lines += line
                val w = words(line.said)
                for (n in (2 until w.size step 2) + w.size) {
                    val heard = Heard(w.take(n).joinToString(" "), n == w.size)
                    val move = agent.anythingToDo(heard, OnScreen(screen.onair))
                    val t = agent.thought!!
                    assertEquals(expected(t), move)
                    when (val a = t.act) {
                        is Action.Show -> screen = screen.after(a.target)
                        is Action.Control -> if (a.name == "close") screen = screen.closed()
                        is Action.Request -> screen = screen.after(Target.Site("", a.said)) // what the vision model opened
                        null -> {}
                    }
                    assertEquals(Move.Nothing, agent.anythingToDo(heard, OnScreen(screen.onair)), "polled again: the same words must not move twice")
                    mine += Call(line.script, line.n, heard.text, heard.ended, t.act?.let(::py), t.offer.map(::py), narrate(t))
                    thoughts += t
                }
            }
        }

        val theirs = python.getValue("calls").jsonArray.map { it.jsonObject }.map {
            Call(it.str("script"), it.getValue("line").jsonPrimitive.int, it.str("said"), it.getValue("end").jsonPrimitive.content == "true",
                if (it.getValue("do") is JsonNull) null else it.str("do"), it.getValue("offer").jsonArray.map { o -> o.jsonPrimitive.content }, it.str("why"))
        }
        assertEquals(theirs.map { Triple(it.script, it.said, it.end) }, mine.map { Triple(it.script, it.said, it.end) }, "the replay calls decide() as the Python did")
        val ids = catalog.slides.map { "${it.deck} p${it.page}" }.toSet()
        val pyScore = score(lines, theirs, ids)
        val ktScore = score(lines, mine, ids)
        val printed = python.str("stdout").lines().filter { "screen correct" in it }.map { it.substringBefore("  decision") }
        assertEquals(printed, summaries(lines, pyScore), "the Kotlin scorer reads the Python's decisions as brain.evaluate did")

        println("\n%-16s | %-82s | %s".format("script", "Python (brain.py, --router none)", "Kotlin (MoleAgent)"))
        printed.zip(summaries(lines, ktScore)).forEach { (p, k) -> println("%-16s | %-82s | %s".format(p.take(16).trim(), p.drop(17), k.drop(17))) }
        var probGap = 0.0
        theirs.zip(thoughts).forEach { (c, t) -> probGap = maxOf(probGap, gap(c.why, t)) }
        println("largest gap between the Python's and the Kotlin's printed top-three probabilities: %.3f (the Python prints two decimals)".format(probGap))
        val differ = theirs.indices.filter { theirs[it].act != mine[it].act || theirs[it].offer != mine[it].offer }
        println("${differ.size} of ${theirs.size} decisions differ")
        for (i in differ) {
            val p = theirs[i]
            val k = mine[i]
            val line = lines.first { it.script == p.script && it.n == p.line }
            println("\n${p.script}:${p.line} \"${p.said}\"${if (p.end) " (end)" else ""}\n  want ${line.want}, verdict Python ${pyScore[line]} Kotlin ${ktScore[line]}" +
                "\n  Python ${p.act ?: "-"} ${p.offer.ifEmpty { "" }} (${p.why})\n  Kotlin ${k.act ?: "-"} ${k.offer.ifEmpty { "" }} (${k.why})")
        }
        assertEquals(emptyList(), differ.map { "${theirs[it].script}:${theirs[it].line} ${theirs[it].said}" }, "every decision as the Python's")
    }

    private fun expected(t: Thought): Move = when (val a = t.act) {
        is Action.Show -> Move.Show(a.target)
        is Action.Control -> Move.Control(a.name)
        is Action.Request -> Move.Unsure
        null -> when {
            t.offer.isNotEmpty() -> Move.Ask(t.offer)
            t.ended && t.reading?.best == Kind.Unfinished -> Move.Wait
            else -> Move.Nothing
        }
    }

    /** An action as brain.py prints it: its kind and target. */
    private fun py(a: Action): String = when (a) {
        is Action.Show -> py(a.target)
        is Action.Control -> if (a.name == "close") "close" else "control ${a.name}"
        is Action.Request -> "ask ${a.said}"
    }

    private fun py(t: Target): String = when (t) {
        is Target.Slide -> "slide ${t.deck} p${t.page}"
        is Target.Site -> "url ${t.url}"
        is Target.App -> "app ${t.name}"
        is Target.File -> "file ${t.path}"
    }

    private fun narrate(t: Thought): String {
        val r = t.reading ?: return t.reason.name
        return r.top().joinToString { (label, p) -> "$label %.2f".format(p) } + "; ${r.best.name.lowercase()} %.2f".format(r.p) +
            (if (t.byClause) " (clause)" else "") + ": ${t.reason}"
    }

    /** How far the Kotlin's top-three probabilities are from the ones the Python printed in `why`. */
    private fun gap(why: String, t: Thought): Double {
        val r = t.reading ?: return 0.0
        val printed = why.substringBefore(";").split(", ").mapNotNull { s -> s.substringAfterLast(' ').toDoubleOrNull()?.let { s.substringBeforeLast(' ') to it } }
        return printed.maxOfOrNull { (label, p) -> abs((r.probs[label] ?: 0.0) - p) } ?: 0.0
    }

    /** brain.evaluate's verdict for each line, from the decisions made while it was spoken. */
    private fun score(lines: List<Line>, calls: List<Call>, ids: Set<String>): Map<Line, String> {
        val out = LinkedHashMap<Line, String>()
        var stage: String? = null
        var front: String? = null
        var last: String? = null
        var script = ""
        for (line in lines) {
            if (line.script != script) {
                script = line.script
                stage = null; front = null; last = null
            }
            fun onair() = front ?: stage
            fun shows(onair: String?) = line.want.any { w -> if (w == null) onair == null else w in ids && onair == "slide $w" }
            val before = onair()
            val mine = calls.filter { it.script == line.script && it.line == line.n }
            val taken = mine.mapNotNull { it.act }
            val offered = mine.lastOrNull { it.offer.isNotEmpty() }?.offer.orEmpty()
            for (a in taken) {
                when {
                    a.startsWith("slide ") -> { stage = a; front = null; last = a }
                    a.startsWith("file ") && suffix(a).lowercase() in ON_STAGE -> { stage = a; front = null }
                    a == "close" -> if (front != null) front = null else stage = null
                    a.startsWith("control ") -> {}
                    else -> front = a
                }
            }
            val kind = { a: String -> a.substringBefore(' ') }
            var did = line.want.any { w -> w != null && w !in ids && w != "keep" && taken.any { w == kind(it) || w == it } }
            did = did || "choose" in line.want && offered.size > 1
            val should = !shows(before) && "keep" !in line.want
            out[line] = when {
                taken.isNotEmpty() && !should -> "false switch"
                shows(onair()) || did || taken.isEmpty() && !should -> "ok"
                taken.isEmpty() && line.want.any { w -> w != null && offered.any { if (w in ids) it.substringAfter(' ') == w else w == kind(it) || w == it } } -> "suggested"
                taken.isNotEmpty() -> "wrong"
                else -> "missed"
            }
        }
        return out
    }

    private fun summaries(lines: List<Line>, verdicts: Map<Line, String>): List<String> =
        (lines.map { it.script }.distinct().map { s -> s to lines.filter { it.script == s } } + ("total" to lines)).map { (name, ls) ->
            val c = ls.groupingBy { verdicts.getValue(it) }.eachCount()
            fun n(v: String) = c[v] ?: 0
            "%-16s screen correct %d/%d  or suggested %d  false switches %d  missed %d  wrong %d"
                .format(name, n("ok"), ls.size, n("ok") + n("suggested"), n("false switch"), n("missed"), n("wrong"))
        }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
}
