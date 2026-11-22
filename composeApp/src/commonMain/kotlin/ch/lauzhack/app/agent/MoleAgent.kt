package ch.lauzhack.app.agent

import ch.lauzhack.app.Agent
import ch.lauzhack.app.Heard
import ch.lauzhack.app.Move
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target

/** The kind of request a classifier label stands for: the label's first word. */
enum class Kind { Aside, Talk, Unfinished, Later, Pick, Slide, Next, Previous, Return, Open, Search, Click, Close, Control }

internal fun kindOf(label: String): Kind? = Kind.entries.firstOrNull { it.name.lowercase() == label.substringBefore(' ') }

/** Never change the screen; Later also cancels a request before it. */
private val QUIET = setOf(Kind.Aside, Kind.Unfinished, Kind.Later)

/** What the agent does to the shared screen. */
sealed interface Action {
    data class Show(val target: Target) : Action
    /** One of stage.py's CONTROLS, or "close". */
    data class Control(val name: String) : Action
    /** A request nothing here names a target for ("search for robots", "click the pricing link"): for the vision model. */
    data class Request(val said: String) : Action
}

/** What the audience sees: the stage (a slide or a picture; null is the desktop), a tab or app in front of it, and the
 * last slide shown, where the talk is in its deck. */
data class View(val stage: Target? = null, val front: Target? = null, val last: Target.Slide? = null) {
    val onair: Target? get() = front ?: stage

    fun after(t: Target): View = when {
        t is Target.Slide -> View(t, null, t)
        t is Target.File && suffix(t.path).lowercase() in ON_STAGE -> View(t, null, last)
        else -> copy(front = t)
    }

    fun closed(): View = if (front != null) copy(front = null) else copy(stage = null)

    /** The view once the screen reports `t`: what [after] or [closed] left, read back from the screen. */
    fun seen(t: Target?): View = when {
        t == onair -> this
        t == null -> View(last = last)
        front != null && t == stage -> copy(front = null)
        else -> after(t)
    }
}

/** One way to read the sentence, as a whole or by one of its clauses: each label's probability, and each kind's that
 * the screen as the sentence began allows, renormalized. */
class Reading(val probs: Map<String, Double>, val groups: Map<Kind, Double>) {
    val best: Kind = groups.maxBy { it.value }.key
    val p: Double = groups.getValue(best)

    fun top(n: Int = 3): List<Pair<String, Double>> = probs.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
}

/** Why the agent did what it did with a sentence. */
enum class Reason {
    NotEnglish, WaitsForTheEnd, Unsure, NothingToOffer, Suggested, Settled, Remark,
    TalkNotOnSlides, FollowsTheTalk, SuggestsAhead, StaysOnSlide,
    SlideNumber, Next, Previous, BackToSlides, Address, Place, ClosestSlide, SlidesNoneNamed,
    File, WhichFile, NoFile, Accepted, PickedByPosition, PickedByDescription, NoPick,
    ForVisionModel, Control, Close,
}

/** A sentence the classifier is unsure about (0.25 to the threshold), for a decision model to settle: the screen, the
 * talk so far, and what the likeliest readings call for. Python sent this to Liquid's d1 with [QUESTION] and [KEEP]. */
data class Doubt(val said: String, val onScreen: Target?, val lastSlide: Target.Slide?, val earlier: List<String>, val options: List<Option>) {
    data class Option(val act: Action, val kind: Kind, val hint: String)

    companion object {
        const val QUESTION = "The presenter is sharing a screen in a live call. What should the shared screen show right after the NEW SENTENCE? " +
            "Change it only for what the presenter wants the audience to see now, not for what they will show later, showed " +
            "before, or only mention."
        const val KEEP = "Keep the screen as it is: the new sentence fits what is on screen, is small talk, or names something without asking " +
            "to see it now (a plan for later, something said or meant earlier, a passing mention)"
        val HINTS = mapOf(
            Kind.Talk to "the talk has moved on to it", Kind.Slide to "show this slide now", Kind.Next to "the next slide",
            Kind.Previous to "the previous slide", Kind.Return to "back to the slides", Kind.Open to "open it now", Kind.Search to "search for it now",
            Kind.Click to "click it on the page on screen", Kind.Control to "do it now on the page on screen", Kind.Close to "stop showing it",
        )
    }
}

/** What the agent made of the words so far. [act] is done now; [offer] waits for the presenter (one target is a
 * suggestion, several a choice); [reading] is the reading acted on, null when the words were not read. */
data class Thought(
    val said: String,
    val ended: Boolean,
    val act: Action?,
    val offer: List<Target>,
    val reason: Reason,
    val reading: Reading?,
    val byClause: Boolean = false,
    val doubt: Doubt? = null,
)

private class Decision(val act: Action? = null, val offer: List<Target> = emptyList(), val reason: Reason, val doubt: Doubt? = null)

private class Look(val sims: DoubleArray, val control: String, val query: FloatArray, val lean: Double)

/**
 * Turns what the presenter says into at most one change of the shared screen per request. Every decision during a
 * sentence is made from the screen as it was when the sentence began, so a later transcript refines an earlier one
 * instead of repeating it ("next slide" then "next slide please" is one step). A partial transcript acts only when it is
 * very likely and the previous one chose the same.
 *
 * [settle] decides the unsure band; without it, the likeliest reading that shows something is suggested, as
 * `copilot.py --router none` does.
 */
class MoleAgent(
    private val catalog: Catalog,
    private val intents: Intents,
    private val threshold: Double = 0.5,
    private val anyLanguage: Boolean = false,
    private val settle: (suspend (Doubt) -> Action?)? = null,
) : Agent {
    /** The sentences heard so far, in English unless anyLanguage. */
    val history: List<String> get() = earlier

    /** What the agent made of the last new words: why it moved, or what it is unsure about. */
    var thought: Thought? = null
        private set

    private var view = View()
    private var start = View()
    private var guess: Action? = null
    private val done = mutableListOf<Action>()
    private val earlier = mutableListOf<String>()
    private var offer = emptyList<Target>()
    private var offered = emptyList<Target>()
    private var pending = ""
    private var unfinished = ""
    private var last: Heard? = null
    private var over = false

    /**
     * The move the words so far call for, given what the audience sees. Call it with the latest transcript as often as
     * you like: the same words never move twice. A sentence ends with `heard.ended`; the next call, once the screen shows
     * its move, starts the next one.
     *
     * A request with no target here is [Move.Unsure], with [thought]'s act the [Action.Request]; a sentence that ends
     * on a request cut off before its object is [Move.Wait], and is read with the next one.
     */
    override suspend fun anythingToDo(heard: Heard, onScreen: OnScreen): Move {
        view = view.seen(catalog.canonical(onScreen.target))
        if (heard == last || heard.text.isBlank()) return Move.Nothing
        if (over) { // the next sentence starts from the screen as its first words arrive, after any tap or undo
            heard(last!!.text)
            over = false
        }
        last = heard
        val t = decide(heard.text, heard.ended)
        thought = t
        over = heard.ended
        return when (val a = t.act) {
            is Action.Show -> Move.Show(a.target)
            is Action.Control -> Move.Control(a.name)
            is Action.Request -> Move.Unsure
            null -> when {
                t.offer.isNotEmpty() -> Move.Ask(t.offer)
                t.ended && t.reading?.best == Kind.Unfinished -> Move.Wait
                else -> Move.Nothing
            }
        }
    }

    private suspend fun decide(text: String, end: Boolean): Thought {
        if (!(anyLanguage || english(text))) return Thought(text, end, null, emptyList(), Reason.NotEnglish, null)
        var said = text.takeLast(300) // the end of a long sentence is what matters
        val gate = if (end) threshold else maxOf(threshold, PARTIAL)
        var (readings, query) = read(said)
        var r = choose(readings, gate)
        if (pending.isNotEmpty() && (r.best in QUIET || r.best == Kind.Talk || r.p < threshold)) {
            // a request cut off before its object ("can you pull up, um") and the object said next are one request
            val (more, moreQuery) = read("$pending $said")
            val joined = choose(more, gate)
            if (joined.best !in QUIET && joined.best != Kind.Talk) {
                said = "$pending $said"
                readings = more
                query = moreQuery
                r = joined
            }
        }
        val control = r.probs.entries.filter { it.key.startsWith("control ") }.maxBy { it.value }.key.removePrefix("control ")
        val waits = r.best == Kind.Talk || r.best == Kind.Slide && tokens(said).lastOrNull() in setOf("slide", "page", "number") // a number may follow
        if (end) unfinished = if (r.best == Kind.Unfinished) said else ""
        val g = r.groups
        val open = g[Kind.Open] ?: 0.0
        val slide = g[Kind.Slide] ?: 0.0
        val look = Look(catalog.sims(query, said, start.last?.deck), control, query, (open - slide) / (open + slide + 1e-9))
        val d = when {
            r.p >= gate -> if (end || !waits) resolve(r.best, said, look) else Decision(reason = Reason.WaitsForTheEnd)
            end && r.p >= FALLBACK_LOW && r.best !in QUIET && r.best != Kind.Talk -> unsure(r.groups, said, look)
            else -> Decision(reason = Reason.Unsure)
        }
        var act = d.act
        if (!end) { // the transcriber still revises live words, most of all an address it spelled and no place known
            val sure = act == guess && (act !is Action.Show || act.target !is Target.Site || act.target in catalog.places.values)
            guess = act
            if (!sure) act = null
        }
        if (act == null || act in done || act is Action.Show && act.target == view.onair ||
            act is Action.Request && (!end || r.p < ASK || done.any { it is Action.Request })
        ) {
            act = null
        } else {
            done += act
        }
        if (end) offered = d.offer
        return Thought(said, end, act, if (end) d.offer else emptyList(), d.reason, r, r !== readings[0], d.doubt)
    }

    /** The sentence read as a whole, then by each of its clauses, and its vector to look things up with. */
    private suspend fun read(text: String): Pair<List<Reading>, FloatArray> {
        val vecs = catalog.embeddings.embed(prompts(text))
        val on = start.onair
        val shown = start.last != null
        val possible = mapOf(
            Kind.Next to shown, Kind.Previous to shown, Kind.Return to (shown && on != start.last), Kind.Pick to offer.isNotEmpty(),
            Kind.Control to (on is Target.Site || on is Target.File), Kind.Close to (on != null),
        )
        val out = readings(text).mapIndexed { i, t ->
            val probs = intents(vecs[i], t)
            val groups = LinkedHashMap<Kind, Double>()
            for ((label, prob) in probs) {
                val k = kindOf(label)!!
                if (possible[k] != false) groups[k] = (groups[k] ?: 0.0) + prob
            }
            val total = groups.values.sum()
            Reading(probs, groups.mapValues { it.value / total })
        }
        return out to vecs.last()
    }

    /** The reading to act on: the last clause that is surely a request, unless a clause after it puts it off; a sentence
     * that is surely a remark as a whole outweighs a less sure request inside it that is not its last clause. With no
     * clause surely a request, the sentence is read as a whole. */
    private fun choose(readings: List<Reading>, gate: Double): Reading {
        val whole = readings[0]
        val parts = readings.drop(1)
        val sure = parts.withIndex().filter { it.value.best != Kind.Talk && it.value.p >= gate }
        val (i, ask) = sure.lastOrNull { it.value.best !in QUIET } ?: return whole
        sure.firstOrNull { it.index > i && it.value.best == Kind.Later }?.let { return it.value }
        return if (i == parts.lastIndex || whole.best !in QUIET || ask.p >= whole.p) ask else whole
    }

    /** What a kind of request calls for, from the screen as the sentence began. */
    private fun resolve(kind: Kind, said: String, look: Look): Decision {
        val v = start
        val here = v.last ?: catalog.slides[0]
        val number = if (kind == Kind.Slide || kind == Kind.Open) slideNumber(said) else null
        val url = if (kind == Kind.Open && number == null) domain(said) else null
        val place = if (kind == Kind.Open && number == null && url == null) catalog.place(said) else null
        return when {
            kind == Kind.Talk -> follow(look.sims)
            kind == Kind.Pick -> pick(said, look.query)
            number != null -> {
                val deck = catalog.slides.filter { it.deck == here.deck }
                val s = if (number in 1..deck.size) deck[number - 1] else if (number == -1) deck.last() else null
                Decision(s?.let(Action::Show), reason = Reason.SlideNumber)
            }
            kind == Kind.Next || kind == Kind.Previous -> {
                if (kind == Kind.Previous && v.onair != v.last) return Decision(v.last?.let(Action::Show), reason = Reason.BackToSlides)
                val s = catalog.slide(here.deck, here.page + if (kind == Kind.Next) 1 else -1)
                Decision(s?.let(Action::Show), reason = if (kind == Kind.Next) Reason.Next else Reason.Previous)
            }
            kind == Kind.Return -> { // the last slide shown, unless the sentence names another clearly better
                val named = catalog.named(look.sims)
                val i = named.indices.maxBy { named[it] }
                val s = if (named[i] > named[catalog.slides.indexOf(here)] + MARGIN) catalog.slides[i] else here
                Decision(Action.Show(s), reason = Reason.BackToSlides)
            }
            url != null -> { // "lozac dot com" is how the transcriber spells LauzHack's address
                val host = url.removePrefix("https://").takeWhile { it !in "/?#" }.substringAfterLast('@').substringBefore(':') // urlsplit().hostname
                val known = catalog.place(host.removePrefix("www.").substringBefore('.'))
                Decision(Action.Show(known as? Target.Site ?: Target.Site(url, host)), reason = Reason.Address)
            }
            place != null -> Decision(Action.Show(place), reason = Reason.Place)
            kind == Kind.Slide || kind == Kind.Open -> show(kind, said, look)
            kind == Kind.Search || kind == Kind.Click -> Decision(Action.Request(said), reason = Reason.ForVisionModel)
            kind == Kind.Control -> Decision(Action.Control(look.control), reason = Reason.Control)
            kind == Kind.Close -> Decision(Action.Control("close"), reason = Reason.Close)
            else -> Decision(reason = Reason.Remark)
        }
    }

    /** A slide or a file, whichever fits the words better once the classifier's reading is counted: `lean` runs from -1
     * (surely a slide) to 1 (surely something outside the deck). A request outside the deck that fits no file goes to
     * the vision model. */
    private fun show(kind: Kind, said: String, look: Look): Decision {
        val sims = look.sims
        val i = sims.indices.maxBy { sims[it] }
        val named = catalog.named(sims)[i] - LEAN * look.lean // how much the slide is named, as Catalog.fits
        val d = find(look.query)
        if ((d.act != null || d.offer.isNotEmpty()) && catalog.fits(look.query).max().toDouble() + LEAN * look.lean > named) return d
        if (named < NAMED_MIN && kind == Kind.Open) return Decision(Action.Request(said), reason = Reason.ForVisionModel)
        if (named < NAMED_MIN) return Decision(Action.Show(start.last ?: catalog.slides[0]), reason = Reason.SlidesNoneNamed) // "show the slides"
        return Decision(Action.Show(catalog.slides[i]), reason = Reason.ClosestSlide)
    }

    /** The file the request describes, or the few that fit it about as well as the best, to choose from. */
    private fun find(query: FloatArray): Decision {
        if (catalog.files.isEmpty()) return Decision(reason = Reason.NoFile)
        val s = catalog.fits(query)
        val order = s.indices.sortedByDescending { s[it] }.take(3)
        // brain.py compares these float32 fits in float32: numpy casts the Python constants down
        if (s[order[0]] < NAMED_MIN.toFloat()) return Decision(reason = Reason.NoFile)
        val near = order.filter { s[it] >= s[order[0]] - CLOSE.toFloat() }.map { Target.File(catalog.files[it]) }
        return if (near.size == 1) Decision(Action.Show(near[0]), reason = Reason.File) else Decision(offer = near, reason = Reason.WhichFile)
    }

    /** One of the targets offered after the last sentence: the only one, the one named by position ("the second one",
     * "number two"), or the one the words describe ("the revised one"). */
    private fun pick(said: String, query: FloatArray): Decision {
        val w = tokens(said)
        if (offer.size == 1) return Decision(Action.Show(offer[0]), reason = Reason.Accepted)
        val n = w.firstNotNullOfOrNull { ORDINALS[it] }
            ?: w.zipWithNext().firstNotNullOfOrNull { (a, b) -> if (a == "number" || a == "option") NUMBERS[b]?.minus(1) else null }
        if (n != null && n >= -offer.size && n < offer.size) {
            return Decision(Action.Show(offer[if (n < 0) offer.size + n else n]), reason = Reason.PickedByPosition)
        }
        val scores = offer.mapIndexed { k, t -> (catalog.vec(t)?.let { dot(it, query).toDouble() } ?: -1.0) to k }
            .sortedWith(compareByDescending<Pair<Double, Int>> { it.first }.thenByDescending { it.second })
        if (scores[0].first - scores[1].first < CLOSE) return Decision(reason = Reason.NoPick) // the words describe none better
        return Decision(Action.Show(offer[scores[0].second]), reason = Reason.PickedByDescription)
    }

    /** While the slides are on air, the slide ahead in the deck that the talk has moved on to: shown when it clearly fits
     * better than the one on air, each slide skipped counting against it, and suggested when it fits a little better. */
    private fun follow(sims: DoubleArray): Decision {
        val last = start.last
        if (last == null || start.onair != last) return Decision(reason = Reason.TalkNotOnSlides)
        val slides = catalog.slides
        val i = slides.indexOf(last)
        val ahead = (1 until slides.size - i).filter { slides[i + it].deck == last.deck }.map { k -> sims[i + k] - JUMP * (k - 1) to k }
        val (score, k) = ahead.maxWithOrNull(compareBy<Pair<Double, Int>> { it.first }.thenBy { it.second }) ?: (-1.0 to 0)
        if (score > sims[i] + MARGIN) return Decision(Action.Show(slides[i + k]), reason = Reason.FollowsTheTalk)
        if (score > sims[i] + SUGGEST) return Decision(offer = listOf(slides[i + k]), reason = Reason.SuggestsAhead)
        return Decision(reason = Reason.StaysOnSlide)
    }

    /** A sentence the classifier is unsure about. [settle], when given, picks among the actions its three likeliest
     * readings call for, or keeps the screen; otherwise, or when it keeps the screen, the likeliest one that shows
     * something is suggested to the presenter. */
    private suspend fun unsure(groups: Map<Kind, Double>, said: String, look: Look): Decision {
        val v = start
        val options = LinkedHashMap<Action, Doubt.Option>()
        for (k in groups.keys.filter { it !in QUIET }.sortedByDescending { groups.getValue(it) }.take(3)) {
            val d = resolve(k, said, look)
            val a = d.act ?: d.offer.firstOrNull()?.let(Action::Show) ?: continue
            if ((a !is Action.Show || a.target != v.onair) && a !in options) options[a] = Doubt.Option(a, k, Doubt.HINTS[k] ?: k.name.lowercase())
        }
        if (options.isEmpty()) return Decision(reason = Reason.NothingToOffer)
        val doubt = Doubt(said, v.onair, v.last.takeIf { v.onair != it }, earlier.takeLast(2).map { it.takeLast(160) }, options.values.toList())
        settle?.invoke(doubt)?.let { return Decision(it, reason = Reason.Settled, doubt = doubt) }
        val shown = options.keys.filterIsInstance<Action.Show>().map { it.target }
        return Decision(offer = shown.take(1), reason = if (shown.isEmpty()) Reason.Unsure else Reason.Suggested, doubt = doubt)
    }

    /** The sentence is over: what it offered can be picked during the next one, and a request it left unfinished is
     * read together with it. */
    private fun heard(said: String) {
        if (anyLanguage || english(said)) earlier += said
        start = view
        guess = null
        done.clear()
        offer = offered
        offered = emptyList()
        pending = unfinished
        unfinished = ""
    }
}
