package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.Agent
import ch.lauzhack.app.Heard
import ch.lauzhack.app.Move
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import ch.lauzhack.app.session.ANNOUNCE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Answers acted on before the utterance has ended: when two partial decisions in a row agree, or once the voice has
 * stopped on the words decided. */
private val EARLY = setOf("show", "slide", "next", "previous", "slides", "control", "close")

/** How long an announce may take to reach the screen; past this it was stopped on the bar, or failed. */
private val LANDS = ANNOUNCE + 1500.milliseconds

/** A pick is sure when it leads every other candidate by [SURE] and scores at least [MATCH], what unrelated items do
 * not reach (rehearsal/sure.py: no wrong pick of the scored runs was both). */
private const val SURE = 0.04
private const val MATCH = 0.75

/**
 * The agent of tune/CONTRACT.md: [catalog] retrieves the candidates with [embedder], [model] answers one line of the
 * contract's grammar for the prompt (constrained to the grammar when it is given, which is at the end of an utterance),
 * and the answer becomes a [Move]. An item picked by meaning (`show`, `open`, `search`) is a [Move.Ask] of it, with how
 * long it waits for a "no" that would stop it; until it is on screen, the agent decides on every word and acts on an
 * `undo` that two partial decisions agree on. Otherwise it decides every two words, and at once when the voice stops ([Heard.quiet]),
 * unless it has decided on those words already: then the answer it gave acts without asking the model again. Calls
 * run one at a time, and words newer than those of a decision in flight drop it.
 */
class ModelAgent(
    catalog: Catalog,
    embedder: Embedder,
    private val model: suspend (prompt: String, grammar: String?) -> String,
    private val time: TimeSource = TimeSource.Monotonic,
    private val note: (String) -> Unit = {},
) : Agent {
    @Volatile var catalog: Catalog = catalog

    /** The model's answer in the last decision, or null when it was not asked; for the log. */
    var answer: String? = null; private set

    private class Utterance(val n: Int, val start: State, val offered: List<Item>, val deck: Item.Slide?, val last: Item.Slide?) {
        var text = ""
        var words = 0
        var over = false
        var said = "nothing"
        var guess: Move = Move.Nothing
        val taken = mutableListOf<Move>()
    }

    /** The queries of the last decisions: the end of an utterance often repeats the words of its last partial. */
    private val queries = object : Embedder by embedder {
        private val seen = LinkedHashMap<String, Vector>()
        override suspend fun embed(text: String): Vector =
            seen.getOrPut(text) { embedder.embed(text) }.also { if (seen.size > 16) seen.remove(seen.keys.first()) }
    }

    private var stage: Item? = null   // the slide or picture on the stage, under any tab or app
    private var front: Item? = null
    private var last: Item.Slide? = null
    private var seen: Target? = null
    private var looked = false
    private var expect: Target? = null  // what the last move puts on screen; any other change came from elsewhere
    private var quiet = false           // the last move takes something off the screen
    private var shown: Item? = null
    private var shownAt = 0
    private var coming: Pair<Target, TimeMark>? = null // its announce, until it is on screen
    private var offer = emptyList<Item>()
    private var offerAt = 0
    private var u = Utterance(Int.MIN_VALUE, State(), emptyList(), null, null)

    private val turn = Mutex()
    private val gate = Mutex()
    private var newest = Heard(n = Int.MIN_VALUE)
    private var running: Job? = null

    override suspend fun anythingToDo(heard: Heard, onScreen: OnScreen): Move {
        gate.withLock {
            if (heard.n >= newest.n && heard != newest) {
                newest = heard
                running?.cancel()
            }
        }
        return turn.withLock {
            coroutineScope {
                val job = gate.withLock { if (heard == newest) async { step(heard, onScreen) }.also { running = it } else null }
                    ?: return@coroutineScope Move.Nothing
                try {
                    job.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    Move.Nothing
                }
            }
        }
    }

    private suspend fun step(heard: Heard, onScreen: OnScreen): Move {
        answer = null
        val cat = catalog
        observe(onScreen.target, cat, heard)
        if (heard.n != u.n || u.over && heard.text != u.text) begin(heard.n, cat)
        if (u.over) return Move.Nothing
        if (heard.text.isBlank()) return Move.Nothing.also { u.over = heard.ended }
        val words = heard.text.split(' ', '\t', '\r', '\n').count { it.isNotEmpty() }
        if (heard.quiet && !heard.ended && spoken(heard.text) == spoken(u.text)) return early(u.said, u.guess, heard.n, cat, agreed = true)
        if (!heard.ended && !heard.quiet && words < u.words + (if (coming != null) 1 else 2)) return Move.Nothing
        val open = (u.deck ?: u.last)?.target?.deck
        val score = cat.scores(queries, heard.text, heard.earlier.lastOrNull(), open)
        val s = u.start.copy(heard = heard.earlier, now = heard.text, done = heard.ended, candidates = cat.candidates(score, u.offered, open))
        note("candidates  \"${heard.text}\"${if (heard.ended) "" else " (still talking)"}  " +
            s.candidates.mapIndexed { i, c -> "${i + 1} ${cat.label(c, score)}" }.joinToString(" | "))
        if (heard.ended) cat.pool(score).forEach(note)
        val g = grammar(s)
        val said = model(prompt(s), if (heard.ended) g else null).trim().also { answer = it }.takeIf { Regex(g).matches(it) } ?: "nothing"
        u.text = heard.text
        u.words = words
        val move = move(said, s, cat, score)
        if (!heard.ended) {
            val agreed = heard.quiet || move == u.guess
            u.said = said
            u.guess = move
            return early(said, move, heard.n, cat, agreed)
        }
        u.over = true
        when {
            move in u.taken -> return Move.Nothing
            said.startsWith("ask") -> {
                if (coming != null) stopped() // a choice replaces an announce
                offer = (move as Move.Ask).options.map(cat::itemOf)
                offerAt = heard.n
            }
            move is Move.Show || move is Move.Ask || move is Move.Control || move == Move.Undo -> take(move, heard.n, cat)
            else -> {}
        }
        return move
    }

    /** [move], for answer [said] to words not yet finished, if it may act before the end and is [agreed]: the decision
     * before gave it too, or the voice has stopped on these words. */
    private fun early(said: String, move: Move, n: Int, cat: Catalog, agreed: Boolean): Move {
        val kind = said.substringBefore(' ') in EARLY || said == "undo" && coming != null
        if (!kind || !agreed || move == Move.Nothing || move in u.taken) return Move.Nothing
        take(move, n, cat)
        return move
    }

    /** What the audience sees now: a slide or picture on the stage, or a tab, file or app in front of it. */
    private fun observe(t: Target?, cat: Catalog, heard: Heard) {
        if (looked && same(t, seen)) {
            if (coming?.second?.let { it.elapsedNow() > LANDS } == true) stopped()
            return
        }
        seen = t
        val item = t?.let(cat::itemOf)
        when {
            item == null -> { stage = null; front = null }
            item is Item.Slide -> { stage = item; front = null; last = item }
            onStage(item) -> { stage = item; front = null }
            else -> front = item
        }
        offer = emptyList()
        if (looked && item != null && !quiet && !same(t, expect)) { // a pick tapped on the bar or the phone
            shown = item
            shownAt = if (heard.n == u.n) heard.n else heard.n - 1
        }
        looked = true
        expect = null
        quiet = false
        coming = null
    }

    /** Its announce never reached the screen: nothing it was about is shown. */
    private fun stopped() {
        coming = null
        shown = null
        expect = null
    }

    /** An utterance starts from the screen as it is now, or as an announce will leave it (the decider takes back only
     * what is on screen), with what was offered and shown before it. */
    private fun begin(n: Int, cat: Catalog) {
        val offered = if (n - offerAt in 1..3) offer else emptyList()
        val landing = coming?.first?.let(cat::itemOf)
        val over = landing != null && landing !is Item.Slide && !onStage(landing)
        val screen = landing ?: front ?: stage
        val start = State(
            screen = screen,
            behind = if (over || landing == null && front != null) stage as? Item.Slide else null,
            next = (screen as? Item.Slide)?.let { cat.slide(it.target.deck, it.page + 1) },
            offered = List(offered.size) { it + 1 },
            shown = shown?.takeIf { n - shownAt in 1..2 },
        )
        u = Utterance(n, start, offered, stage as? Item.Slide, last)
    }

    private fun take(m: Move, n: Int, cat: Catalog) {
        u.taken += m
        offer = emptyList()
        val t = (m as? Move.Show)?.target ?: (m as? Move.Ask)?.options?.single()
        val dropped = coming != null && t == null // an announce gives way to whatever else is done
        if (dropped) stopped()
        if (t != null) {
            shown = cat.itemOf(t)
            shownAt = n
            expect = t
            coming = if (m is Move.Ask) t to time.markNow() else null
        } else if (m == Move.Undo && !dropped || m == Move.Control("close")) {
            shown = null
            quiet = true
        }
    }

    /** What an answer of the grammar does, per the contract's table and Contract.kt's moves, with [score] for each
     * catalog item. */
    private fun move(said: String, s: State, cat: Catalog, score: DoubleArray): Move {
        val arg = said.substringAfter(' ', "")
        val now = front ?: stage
        fun show(c: Item?) = if (c == null || same(c.target, now?.target)) Move.Nothing else Move.Show(c.target)
        fun pick(c: Item?) = show(c).let { if (it is Move.Show) Move.Ask(listOf(it.target), doubt(c!!, s.candidates, cat, score)) else it }
        val deck = u.deck ?: u.last ?: cat.firstSlide
        fun page(p: Int) = deck?.let { cat.slide(it.target.deck, p) }
        return when (said.substringBefore(' ')) {
            "wait" -> Move.Wait
            "unsure" -> Move.Unsure
            "show" -> arg.toInt().let { if (it <= s.offered.size) show(s.candidates[it - 1]) else pick(s.candidates[it - 1]) } // an offered one is the presenter's choice
            "ask" -> Move.Ask(arg.split(' ').map { s.candidates[it.toInt() - 1].target }.distinct())
            "slide" -> show(page(arg.toInt()))
            "next" -> show(deck?.let { page(it.page + 1) })
            "previous" -> show(deck?.let { page(it.page - 1) })
            "slides" -> show(s.behind ?: u.last ?: cat.firstSlide)
            "open" -> cat.named(arg)?.let(::pick)
                ?: if ('.' in arg) pick(Catalog.site(Target.Site("https://" + arg.replace(" ", ""), arg))) else Move.Unsure
            "search" -> pick(Catalog.site(search(arg)))
            "click" -> Move.Control("click:$arg")
            "control" -> Move.Control(arg)
            "close" -> Move.Control("close")
            "undo" -> Move.Undo
            else -> Move.Nothing
        }
    }

    /** How much of the presenter's window a pick of [c] waits: none when it is sure, half when it is the best of the
     * [candidates] and scores as a match does, all of it for a guess, and for an address or a search, which no score
     * backs. */
    private fun doubt(c: Item, candidates: List<Item>, cat: Catalog, score: DoubleArray): Double {
        val s = cat.score(c, score) ?: return 1.0
        val lead = s - (candidates.filter { it != c }.mapNotNull { cat.score(it, score) }.maxOrNull() ?: return 1.0)
        return when {
            s < MATCH || lead <= 0 -> 1.0
            lead >= SURE -> 0.0
            else -> 0.5
        }
    }
}

/** The words of [text], without the punctuation and capitals the recognizer refines after they were said. */
private fun spoken(text: String) = text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter(String::isNotEmpty)

/** A web search for words the grammar spells with letters, digits, spaces and . - ' only. */
fun search(words: String) = Target.Site("https://www.google.com/search?q=" + words.replace(" ", "+").replace("'", "%27"), words)
