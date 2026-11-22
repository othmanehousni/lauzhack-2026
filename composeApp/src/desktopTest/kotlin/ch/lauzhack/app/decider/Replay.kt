package ch.lauzhack.app.decider

import ch.lauzhack.app.Heard
import ch.lauzhack.app.Move
import ch.lauzhack.app.Target
import ch.lauzhack.app.engine.Engines
import ch.lauzhack.app.stage.decks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Replays the eval scripts through [ModelAgent] as ParityTest replays them through MoleAgent: two words at a time, each
 * move applied to a [FakeDesk], each line scored as brain.evaluate scores it on what the screen shows after it, over the
 * same fixtures (the decks of fixtures/make_decks.py, fixtures/files.txt, brain.APPS, brain's websites). It loads the
 * models, so it runs on Modal only (eval/modal_decider.py): MOLE_CHECK=decider, MOLE_MODELS holding decider.litertlm
 * and the embedder, MOLE_CACHE, MOLE_LIBRARY holding the decks as PDFs, and MOLE_SCRIPTS naming scripts to replay.
 */
class Replay {
    private val apps = listOf("Notes", "Calculator", "Safari", "System Settings", "Maps", "Photos", "Preview", "Finder", "Keynote", "Mail",
        "Terminal", "Google Chrome")
    private val baseline = setOf("fresh_talk", "fresh_web", "meeting", "meeting_holdout", "talk", "talk_holdout", "talk_other", "tour", "web", "web_holdout")

    private class Call(val heard: String, val ended: Boolean, val answer: String?, val move: Move)

    private class Line(val script: String, val n: Int, val said: String, val want: List<String?>) {
        val calls = mutableListOf<Call>()
        var before: String? = null
        var after: String? = null
        var verdict = ""
    }

    @Test
    fun scripts() = runBlocking {
        assumeTrue("MOLE_CHECK=decider runs this", System.getenv("MOLE_CHECK") == "decider")
        val repo = File(System.getenv("MOLE_REPO") ?: "..")
        val engines = Engines(System.getenv("MOLE_MODELS"), System.getenv("MOLE_CACHE"))
        val client = engines.client(DECIDER) ?: error("no $DECIDER in ${engines.modelsDir}")
        val embedder = engines.embedder()
        val pages = decks(File(System.getenv("MOLE_LIBRARY")), File(engines.cacheDir, "stage"), width = 64)
        val files = File(repo, "fixtures/files.txt").readLines().filter { it.isNotBlank() }
        var mark = TimeSource.Monotonic.markNow()
        val catalog = Catalog.build(items(pages, apps, files), embedder)
        println("catalog: ${catalog.items.size} items (${pages.size} slides) embedded in ${mark.elapsedNow()}")
        val decide = decider(client)
        mark = TimeSource.Monotonic.markNow()
        println("engine started and first answer \"${decide("hello", null)}\" in ${mark.elapsedNow()}")
        val forced = decide("hello", "nothing|wait")
        println("the same prompt under the grammar nothing|wait: \"$forced\"")
        assertTrue(forced == "nothing" || forced == "wait", "the answer follows the grammar")

        var answer: String? = null
        val modelMs = mutableListOf<Pair<Boolean, Long>>()
        val callMs = mutableListOf<Pair<Boolean, Long>>()
        val model: suspend (String, String?) -> String = { p, g ->
            val t = TimeSource.Monotonic.markNow()
            decide(p, g).also { answer = it; modelMs += (g != null) to t.elapsedNow().inWholeMilliseconds }
        }
        val only = System.getenv("MOLE_SCRIPTS")?.split(',')?.toSet()
        val lines = mutableListOf<Line>()
        for (script in File(repo, "eval").listFiles { f -> f.extension == "jsonl" && (only == null || f.nameWithoutExtension in only) }!!.sortedBy { it.name }) {
            val agent = ModelAgent(catalog, embedder, model)
            val desk = FakeDesk()
            val said = mutableListOf<String>()
            script.readLines().filter { it.isNotBlank() }.forEachIndexed { i, json ->
                val o = Json.parseToJsonElement(json).jsonObject
                val line = Line(script.nameWithoutExtension, i + 1, o.getValue("say").jsonPrimitive.content,
                    o.getValue("want").jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.content })
                lines += line
                line.before = id(desk.onScreen.target)
                val w = line.said.split(' ').filter { it.isNotEmpty() }
                for (k in (2 until w.size step 2) + w.size) {
                    val heard = Heard(w.take(k).joinToString(" "), k == w.size, i + 1, said.takeLast(3))
                    answer = null
                    val t = TimeSource.Monotonic.markNow()
                    val move = agent.anythingToDo(heard, desk.onScreen)
                    callMs += heard.ended to t.elapsedNow().inWholeMilliseconds
                    desk.apply(move)
                    line.calls += Call(heard.text, heard.ended, answer, move)
                    assertEquals(Move.Nothing, agent.anythingToDo(heard, desk.onScreen), "polled again: the same words must not move twice")
                }
                said += line.said
                line.after = id(desk.onScreen.target)
            }
        }

        val ids = pages.map { "${it.deck} p${it.page}" }.toSet()
        lines.forEach { it.verdict = verdict(it, ids) }
        val groups = lines.map { it.script }.distinct().map { s -> s to lines.filter { it.script == s } } +
            ("total" to lines) + ("baseline 10" to lines.filter { it.script in baseline })
        println()
        for ((name, ls) in groups) {
            val c = ls.groupingBy { it.verdict }.eachCount()
            fun n(v: String) = c[v] ?: 0
            println("%-16s screen correct %d/%d  or suggested %d  false switches %d  missed %d  wrong %d"
                .format(name, n("ok"), ls.size, n("ok") + n("suggested"), n("false switch"), n("missed"), n("wrong")))
        }
        fun stats(label: String, ms: List<Pair<Boolean, Long>>) = listOf(false to "while speaking", true to "at the end").forEach { (done, what) ->
            val xs = ms.filter { it.first == done }.map { it.second }.sorted()
            if (xs.isNotEmpty()) println("%s %s: %d, median %d ms, p95 %d ms, max %d ms".format(label, what, xs.size, xs[xs.size / 2], xs[xs.size * 95 / 100], xs.last()))
        }
        println()
        stats("decisions", callMs)
        stats("model calls", modelMs)
        println("\nlines not ok:")
        for (l in lines.filter { it.verdict != "ok" }) {
            println("\n${l.script}:${l.n} ${l.verdict} \"${l.said}\"\n  want ${l.want}, screen ${l.before} then ${l.after}")
            l.calls.forEach { c -> println("  ${if (c.ended) "end" else "   "} ${c.answer ?: "-"} => ${act(c.move) ?: (c.move as? Move.Ask)?.let { "offer " + offer(it) } ?: c.move}  (${c.heard.split(' ').size} words)") }
        }
    }

    private fun id(t: Target?): String? = when (t) {
        null -> null
        is Target.Slide -> "slide ${t.deck} p${t.page}"
        is Target.Site -> "url ${t.url}"
        is Target.App -> "app ${t.name}"
        is Target.File -> "file ${t.path}"
    }

    /** A move as brain.py prints its action; what the vision model did then (search, click, unsure) is an `ask`. */
    private fun act(m: Move): String? = when (m) {
        is Move.Show -> (m.target as? Target.Site)?.takeIf { it == search(it.name) }?.let { "ask search ${it.name}" } ?: id(m.target)
        is Move.Control -> if (m.name == "close") "close" else if (m.name.startsWith("click:")) "ask click ${m.name.removePrefix("click:")}" else "control ${m.name}"
        Move.Unsure -> "ask unsure"
        Move.Undo -> "undo"
        else -> null
    }

    private fun offer(m: Move.Ask) = m.options.map { id(it)!! }

    /** brain.evaluate's verdict for a line, on what the screen showed before and after it and what was done or offered. */
    private fun verdict(line: Line, ids: Set<String>): String {
        fun shows(onair: String?) = line.want.any { w -> if (w == null) onair == null else w in ids && onair == "slide $w" }
        val taken = line.calls.mapNotNull { act(it.move) }
        val offered = line.calls.lastOrNull { it.move is Move.Ask }?.let { offer(it.move as Move.Ask) }.orEmpty()
        val kind = { a: String -> a.substringBefore(' ') }
        val did = line.want.any { w -> w != null && w !in ids && w != "keep" && taken.any { w == kind(it) || w == it } } ||
            "choose" in line.want && offered.size > 1
        val should = !shows(line.before) && "keep" !in line.want
        return when {
            taken.isNotEmpty() && !should -> "false switch"
            shows(line.after) || did || taken.isEmpty() && !should -> "ok"
            taken.isEmpty() && line.want.any { w -> w != null && offered.any { if (w in ids) it.substringAfter(' ') == w else w == kind(it) || w == it } } -> "suggested"
            taken.isNotEmpty() -> "wrong"
            else -> "missed"
        }
    }
}
