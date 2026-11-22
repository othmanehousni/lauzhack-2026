package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.Agent
import ch.lauzhack.app.Heard
import ch.lauzhack.app.Move
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import ch.lauzhack.app.session.ANNOUNCE
import ch.lauzhack.app.session.WAIT
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Answers acted on before the utterance has ended: when two partial decisions in a row agree, or once the voice has
 * stopped on the words decided. */
private val EARLY = setOf("show", "slide", "next", "previous", "slides", "control", "close")

/** How long an announce may take to reach the screen; past this it was stopped on the bar, or failed. */
private val LANDS = ANNOUNCE + 1500.milliseconds

/** How soon after an utterance nothing acted on the next one goes on from it: the presenter paused inside a sentence. */
private val JOIN = 2.seconds
private const val JOINED = 2 // utterances one goes on from, at most

/** A pick is sure when it leads every other candidate by [SURE] and scores at least [MATCH], what unrelated items do
 * not reach (rehearsal/sure.py: no wrong pick of the scored runs was both); likely when it is within [CLOSE] of the
 * best and scores at least [LIKELY]; a candidate picked by meaning under [BACKED] is not shown, no wanted one was. */
private const val SURE = 0.04
private const val MATCH = 0.75
private const val CLOSE = 0.03
private const val LIKELY = 0.72
private const val BACKED = 0.70

/** Words that ask for something to be shown. A pick by meaning of words without one, which do not name it either
 * ([names]), is shown only when it is sure: plain talk scores as a likely match of some slide, and in the sessions of
 * 22 November 60 of the 62 such picks were of talk. */
private val REQUEST = Regex("\\b(show(s|ing|n)?|open(s|ing)?|see|look|display|play|watch|pull up|bring up|put up|go to|go back|back to|jump|switch|move to|" +
    "return to|give me|get me|take (me|us)|on (the )?screen|what about|how about|slides?|diagrams?|deck|pictures?|figures?|charts?|graphs?)\\b")

/** `next` and `previous` are said with one of these, or of a slide the words surely match ("So what was the real issue?"
 * before the slide on it): the model also reads them into talk ("Maybe I can just for you."), and they change the slide
 * at once, with no announce to stop. */
private val ONWARD = Regex("\\b(next|forward|following|ahead|onward|advance|continue|skip|proceed|(move|moving|go|going|carry|keep) on|keep going)\\b")
private val BACKWARD = Regex("\\b(previous|back|backwards?|before|prior|earlier|last|rewind|return)\\b")

/**
 * The agent of tune/CONTRACT.md: [catalog] retrieves the candidates with [embedder], [model] answers one line of the
 * contract's grammar for the prompt (constrained to the grammar when it is given, which is at the end of an utterance),
 * and the answer becomes a [Move]. An item picked by meaning (`show`, `open`, `search`) is a [Move.Ask] of it, with how
 * long it waits for a "no" that would stop it, or [Move.Unsure] at the end when its score does not back it; until it is
 * on screen, the agent decides on every word and acts on an `undo` that two partial decisions agree on. Otherwise it
 * decides every two words, and at once when the voice stops ([Heard.quiet]) or a sentence ends with more after it
 * ([Heard.more], never decided as done), unless it has decided on those words already: then the answer it gave acts
 * without asking the model again. Calls
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

    /** [before]: the words of the [parts] utterances it goes on from, which nothing acted on; [moving]: the show the
     * last one made, not yet on screen. */
    private class Utterance(
        val n: Int, val start: State, val offered: List<Item>, val deck: Item.Slide?, val last: Item.Slide?,
        val before: String = "", val parts: Int = 0, val moving: Move.Show? = null,
    ) {
        var text = ""
        var words = 0
        var over = false
        var ended: TimeMark? = null
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
        if (heard.text.isBlank()) return Move.Nothing.also { if (heard.ended) over() }
        val done = heard.ended && !heard.more
        val stopped = heard.quiet || heard.more
        val now = listOf(u.before, heard.text).filter(String::isNotBlank).joinToString(" ")
        val earlier = heard.earlier.dropLast(u.parts)
        val words = now.split(' ', '\t', '\r', '\n').count { it.isNotEmpty() }
        if (stopped && !done && spoken(heard.text) == spoken(u.text)) return early(u.said, u.guess, heard.n, cat, agreed = true).also { if (heard.ended) { u.text = heard.text; over() } }
        if (!heard.ended && !heard.quiet && words < u.words + (if (coming != null) 1 else 2)) return Move.Nothing
        val open = (u.deck ?: u.last)?.target?.deck
        val score = cat.scores(queries, now, earlier.lastOrNull(), open)
        val s = u.start.copy(heard = earlier, now = now, done = done, candidates = cat.candidates(score, u.offered, open))
        note("candidates  \"$now\"${if (done) "" else " (still talking)"}  " +
            s.candidates.mapIndexed { i, c -> "${i + 1} ${cat.label(c, score)}" }.joinToString(" | "))
        if (done) cat.pool(score).forEach(note)
        val g = grammar(s)
        val said = model(prompt(s), if (done) g else null).trim().also { answer = it }.takeIf { Regex(g).matches(it) } ?: "nothing"
        u.text = heard.text
        u.words = words
        val move = move(said, s, cat, score)
        if (!done) {
            val agreed = heard.quiet || move == u.guess // the model reads "next" into "Sorry, sorry." even as words go on
            u.said = said
            u.guess = move
            return early(said, move, heard.n, cat, agreed).also { if (heard.ended) over() }
        }
        over()
        u.said = said
        when {
            move in u.taken || move == u.moving -> return Move.Nothing
            said.startsWith("ask") || move.isChoice -> {
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
        val kind = said.substringBefore(' ') in EARLY && move != Move.Unsure || said == "undo" && coming != null
        if (!kind || !agreed || move.isChoice || move == Move.Nothing || move in u.taken || move == u.moving) return Move.Nothing // a choice is offered once, when the words end
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

    private fun over() {
        u.over = true
        u.ended = time.markNow()
    }

    /** An utterance starts from the screen as it is now, or as an announce or the show the last utterance just made
     * will leave it (the decider takes back only what is on screen), with what was offered and shown before it. It
     * goes on from the last one if nothing acted on that one and it ended a moment ago ([JOIN], or [WAIT] after a
     * `wait`): a sentence cut by a pause is decided whole. */
    private fun begin(n: Int, cat: Catalog) {
        val was = u
        val joins = was.n == n - 1 && was.over && was.taken.isEmpty() && offerAt != was.n && was.parts < JOINED &&
            was.ended?.let { it.elapsedNow() <= if (was.said == "wait") WAIT else JOIN } == true
        val offered = if (n - offerAt in 1..3) offer else emptyList()
        val moving = (was.taken.lastOrNull() as? Move.Show)?.takeIf { same(it.target, expect) }
        val landing = coming?.first?.let(cat::itemOf) ?: moving?.target?.let(cat::itemOf)
        val over = landing != null && landing !is Item.Slide && !onStage(landing)
        val screen = landing ?: front ?: stage
        val start = State(
            screen = screen,
            behind = if (over || landing == null && front != null) stage as? Item.Slide else null,
            next = (screen as? Item.Slide)?.let { cat.slide(it.target.deck, it.page + 1) },
            offered = List(offered.size) { it + 1 },
            shown = shown?.takeIf { n - shownAt in 1..2 },
        )
        val slide = landing.takeIf { moving != null } as? Item.Slide
        val before = if (joins) listOf(was.before, was.text.trimEnd('.', '?', '!')).filter(String::isNotBlank).joinToString(" ") else "" // one sentence: the pause's mark goes
        u = Utterance(n, start, offered, slide ?: stage as? Item.Slide, slide ?: last, before, if (joins) was.parts + 1 else 0, moving)
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
        val words = s.now.lowercase()
        fun show(c: Item?) = if (c == null || same(c.target, now?.target)) Move.Nothing else Move.Show(c.target)
        fun pick(c: Item?, meaning: Boolean = false, named: Boolean = false) = show(c).let {
            if (it !is Move.Show) return@let it
            val d = doubt(c!!, s.candidates, cat, score, meaning, named)
            if (meaning && d != 0.0 && !REQUEST.containsMatchIn(words) && !names(words, c, cat)) return@let Move.Nothing
            if (d == null) return@let Move.Unsure
            Move.Ask(listOf(it.target) + if (meaning && d > 0) alike(c, cat, score) else emptyList(), d)
        }
        val deck = u.deck ?: u.last ?: cat.firstSlide
        fun page(p: Int) = deck?.let { cat.slide(it.target.deck, p) }
        fun step(said: Regex, by: Int): Move {
            val c = deck?.let { page(it.page + by) }
            val meant = c != null && doubt(c, s.candidates, cat, score, meaning = true, named = false) == 0.0
            return if (meant || said.containsMatchIn(words)) show(c) else Move.Nothing
        }
        return when (said.substringBefore(' ')) {
            "wait" -> Move.Wait
            "unsure" -> Move.Unsure
            "show" -> arg.toInt().let { if (it <= s.offered.size) show(s.candidates[it - 1]) else pick(s.candidates[it - 1], meaning = true) } // an offered one is the presenter's choice
            "ask" -> Move.Ask(arg.split(' ').map { s.candidates[it.toInt() - 1].target }.distinct())
            "slide" -> show(page(arg.toInt()))
            "next" -> step(ONWARD, 1)
            "previous" -> step(BACKWARD, -1)
            "slides" -> show(s.behind ?: u.last ?: cat.firstSlide)
            "open" -> cat.exactly(arg)?.let { pick(it, named = true) } ?: cat.named(arg)?.let(::pick)
                ?: if ('.' in arg) pick(Catalog.site(Target.Site("https://" + arg.replace(" ", ""), arg))) else Move.Unsure
            "search" -> pick(Catalog.site(search(arg)))
            "click" -> Move.Control("click:$arg")
            "control" -> Move.Control(arg)
            "close" -> Move.Control("close")
            "undo" -> Move.Undo
            else -> Move.Nothing
        }
    }

    /** The other files that score as file [c] does, the best three, when [c] scores short of a match: picked by meaning,
     * it is one of several the words fit and none they name, and the presenter chooses. A file that scores as a match
     * is announced even with another close behind ("the models diagram", with the architecture diagram a little under
     * it). Slides are not offered so: a deck is walked, not chosen from. */
    private fun alike(c: Item, cat: Catalog, score: DoubleArray): List<Target> {
        val s = cat.score(c, score)?.takeIf { c.target is Target.File && it < MATCH } ?: return emptyList()
        // Deeper than the eight the model reads: with a deck open its slides fill those, and the files wait just below.
        return cat.candidates(score, k = 32).filter { it != c && it.target is Target.File && cat.score(it, score)!! >= s - CLOSE }.take(3).map { it.target }
    }

    /** How much of the presenter's window a pick of [c] waits: none when it is sure, half when it is likely, all of it
     * otherwise, and for an address or a search, which no score backs; null for a candidate picked by [meaning] that
     * its score does not back. An item [named] exactly needs no lead: its name tells it from those that score as it does. */
    private fun doubt(c: Item, candidates: List<Item>, cat: Catalog, score: DoubleArray, meaning: Boolean, named: Boolean): Double? {
        val s = cat.score(c, score) ?: return 1.0
        if (meaning && s < BACKED) return null
        val lead = s - (candidates.filter { it != c }.mapNotNull { cat.score(it, score) }.maxOrNull() ?: return 1.0)
        return when {
            s >= MATCH && (lead >= SURE || named) -> 0.0
            s >= LIKELY && lead > -CLOSE -> 0.5
            else -> 1.0
        }
    }
}

/** Whether [words] say a word of [c]'s name that at most one other item's name has: "our tech stack" names "Moliere tech
 * stack.png", "Moliere" none of the pitch's figures. No slide is named so: a title is a sentence, and talk shares its words. */
private fun names(words: String, c: Item, cat: Catalog): Boolean {
    val said = spoken(words).toSet()
    return nameWords(c).any { w -> w in said && cat.items.count { w in nameWords(it) } <= 2 }
}

private fun nameWords(c: Item): List<String> = when (c) {
    is Item.File -> spoken(c.name.substringBeforeLast('.'))
    is Item.Site -> spoken(c.name)
    is Item.App -> spoken(c.name)
    is Item.Slide -> emptyList()
}.filter { it.length >= 4 }

private val Move.isChoice get() = this is Move.Ask && options.size > 1

/** The words of [text], without the punctuation and capitals the recognizer refines after they were said. */
private fun spoken(text: String) = text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter(String::isNotEmpty)

/** A web search for words the grammar spells with letters, digits, spaces and . - ' only. */
fun search(words: String) = Target.Site("https://www.google.com/search?q=" + words.replace(" ", "+").replace("'", "%27"), words)
