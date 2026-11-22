package ch.lauzhack.app.session

import ch.lauzhack.app.Agent
import ch.lauzhack.app.Choice
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.Move
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import ch.lauzhack.app.agent.ON_STAGE
import ch.lauzhack.app.agent.suffix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The shared screen as the session drives it: the agent desktop on the laptop, a fake in tests. */
interface SharedScreen {
    val onScreen: OnScreen
    suspend fun show(target: Target): OnScreen
    suspend fun control(name: String)
    suspend fun close(): OnScreen
}

internal val THINK = 1.seconds // an agent slower than this over new words shows the mole thinking
internal val UNDO = 20.seconds // how long "no, not that" takes back what just went up: room to notice, say so and be heard
internal val ANNOUNCE = 2500.milliseconds // how long an announce can be stopped before it is shown, unless the presenter chose otherwise
internal val STALL = 5.seconds // a screen call that has not answered by then has failed
private const val EARLIER = 3  // utterances handed to the agent with each one

/**
 * One meeting. Each time the words change it asks [given] whether there is anything to do about what the presenter is
 * saying and what the audience sees, and carries the move out on [agentDesktop] at once, telling [mole] what really
 * happens. Words come from [laptop], or from [phone] while [phoneLeads]. Moves reach the screen one at a time, in the
 * order they were decided, each call failing after [STALL]; the agent keeps deciding meanwhile. An [Move.Ask] of one
 * target is announced and shown once [window] has run, unless the presenter stops it ([Move.Undo], or [pick] of a
 * negative index), or at once with no window; several are a choice. It keeps what is on screen and what was there
 * before, the offer in play, the presenter's picks, and the last few utterances. Run it on one thread: its state
 * changes between suspensions.
 */
class Session(
    private val given: Agent,
    private val agentDesktop: SharedScreen,
    private val mole: Mole,
    private val laptop: Flow<Heard>,
    private val phone: Flow<Heard> = emptyFlow(),
    private val phoneLeads: Flow<Boolean> = flowOf(false),
    private val time: TimeSource = TimeSource.Monotonic,
    private val window: StateFlow<Duration> = MutableStateFlow(ANNOUNCE),
) {
    private class Coming(val target: Target, val said: String) // by identity: announced again, it is a fresh window

    private val _heard = MutableStateFlow(Heard(ended = true))
    private val _onScreen = MutableStateFlow(agentDesktop.onScreen)

    /** The utterance the agent is asked about: the next one waits until the agent has seen this one end. */
    val heard: StateFlow<Heard> = _heard.asStateFlow()
    val onScreen: StateFlow<OnScreen> = _onScreen.asStateFlow()

    private val later = ArrayDeque<Heard>()            // utterances begun since, the last one possibly still growing
    private var asked = Heard()                        // the words the agent was last asked about
    private var actedOn = Heard()                      // the last words a move was carried out for
    private val acted = mutableListOf<Move>()          // the moves carried out for utterance actedOn.n
    private var before: Pair<OnScreen, TimeMark>? = null // what the last change replaced, and when
    private var offer = emptyList<Target>()            // a choice, in play while the mole shows it
    private var coming: Coming? = null                 // announced, shown when its window ends
    private val picks = mutableMapOf<Set<Target>, Target>() // the presenter's answer to each choice offered
    private val taps = Channel<Int>(Channel.UNLIMITED)
    private val turn = Mutex()                         // one move at a time, in order, from the loop, a tap or a window ending

    /** A tap on row [index] of the options, on the bar or the phone; a negative index turns them down. */
    fun pick(index: Int) {
        taps.trySend(index)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(): Nothing = coroutineScope {
        mole.on(Event.Hello("Hi. I'm listening."))
        val leads = phoneLeads.distinctUntilChanged()
        launch { leads.drop(1).collect { mole.on(Event.Hello(if (it) "On your phone now." else "Back on the laptop.")) } }
        launch { leads.flatMapLatest { if (it) phone else laptop }.collect(::hear) }
        launch { for (i in taps) turn.withLock { if (i < 0) stop() else offered().getOrNull(i)?.let { show(it, said = "", pick = i) } } }
        launch {
            while (true) {
                val h = heard.first { it != asked && it.text.isNotBlank() }
                val move = decide(h)
                if (fresh(move)) launch { turn.withLock { act(move, h.text) } } // the screen may be slow: the next words are decided meanwhile
                if (h.ended && heard.value == h) later.removeFirstOrNull()?.let { _heard.value = it }
            }
        }
        awaitCancellation()
    }

    private suspend fun CoroutineScope.decide(heard: Heard): Move {
        val slow = launch { delay(THINK); mole.on(Event.Thinking(heard.text)) }
        asked = heard
        return try {
            given.anythingToDo(heard, onScreen.value)
        } catch (e: Exception) { // a model that failed once must not end the meeting
            currentCoroutineContext().ensureActive()
            println("the agent failed on \"${heard.text}\": $e")
            Move.Nothing
        } finally {
            slow.cancel()
            mole.on(Event.Answered)
        }
    }

    private suspend fun CoroutineScope.act(move: Move, said: String) = when (move) {
        is Move.Show -> show(move.target, said)
        is Move.Ask -> ask(move.options, said)
        is Move.Control -> control(move.name, said)
        Move.Wait -> mole.on(Event.Wait(waiting(said), said))
        Move.Unsure -> mole.on(Event.Unsure(said))
        Move.Undo -> undo(said)
        Move.Nothing -> Unit
    }

    private fun hear(piece: Heard) {
        if (piece.text.isBlank()) return
        mole.on(Event.Words(piece.text))
        val last = later.lastOrNull() ?: heard.value
        if (last.ended) {
            val next = Heard(piece.text, piece.ended, last.n + 1, (last.earlier + last.text).filter(String::isNotBlank).takeLast(EARLIER))
            if (later.isEmpty() && (asked == heard.value || heard.value.text.isEmpty())) _heard.value = next else later += next
        } else {
            val grown = last.copy(text = piece.text, ended = piece.ended)
            if (later.isEmpty()) _heard.value = grown else later[later.lastIndex] = grown
        }
    }

    /** Never a move twice for the same words, nor the same move twice in one utterance as its pieces grow. */
    private fun fresh(move: Move): Boolean {
        if (move == Move.Nothing || asked == actedOn || asked.n == actedOn.n && move in acted) return false
        if (asked.n != actedOn.n) acted.clear()
        actedOn = asked
        acted += move
        return true
    }

    private fun offered(): List<Target> =
        coming?.let { listOf(it.target) } ?: if (mole.line.value.state == MoleState.Choosing) offer else emptyList()

    private suspend fun CoroutineScope.show(target: Target, said: String, pick: Int? = null, first: String? = null) {
        if (same(target, onScreen.value.target)) return
        val options = offered()
        val i = pick ?: options.indexOfFirst { same(it, target) }.takeIf { it >= 0 } // by tap, by voice, or by staying on it
        if (i != null && options.size > 1) picks[options.toSet()] = options[i]
        change(showing(target, i, options.size, first), said, undoable = true) { agentDesktop.show(target) }
    }

    private suspend fun CoroutineScope.ask(options: List<Target>, said: String) {
        picks[options.toSet()]?.let { return show(it, said, first = "Same as before: ${name(it)}.") }
        if (options.size > 1) {
            if (offered() == options) return
            coming = null
            offer = options
            return mole.on(Event.Offer(options.map(::choice), said))
        }
        val t = options[0]
        if (same(t, coming?.target) || same(t, onScreen.value.target)) return
        val w = window.value
        if (w <= Duration.ZERO) return show(t, said)
        val c = Coming(t, said)
        coming = c
        offer = emptyList()
        mole.on(Event.Coming(choice(t), "Showing ${name(t)}", c.said, card(t), w.inWholeMilliseconds))
        launch {
            delay(w)
            turn.withLock { if (coming === c) show(t, c.said) }
        }
    }

    /** Turns down the choice or the announce in play: nothing is shown. */
    private fun stop() {
        if (offered().isEmpty()) return
        coming = null
        offer = emptyList()
        mole.on(Event.Dismiss)
    }

    private suspend fun CoroutineScope.control(name: String, said: String) =
        if (name == "close") change(controlling(name), said, undoable = true) { agentDesktop.close() }
        else change(controlling(name), said, undoable = false) { agentDesktop.control(name); null }

    private suspend fun CoroutineScope.undo(said: String) {
        if (offered().isNotEmpty()) return stop()
        val (was, at) = before ?: return
        if (at.elapsedNow() > UNDO) return
        before = null
        val now = onScreen.value.target
        change(undoing(was.target), said, undoable = false) {
            // a tab or app comes off to bare what is under it; what is on the stage itself is replaced
            val under = if (was.target == null || inFront(now)) agentDesktop.close() else null
            if (was.target == null || same(under?.target, was.target)) under else agentDesktop.show(was.target)
        }
    }

    /** Runs a change of the screen, once more if it fails but not if it stalls, and tells the mole each step as it
     * happens. A change that fails leaves nothing for a "no" to take back. */
    private suspend fun CoroutineScope.change(c: Change, said: String, undoable: Boolean, call: suspend () -> OnScreen?) {
        val was = onScreen.value
        offer = emptyList()
        coming = null
        mole.on(Event.Start(c, said))
        repeat(2) { attempt ->
            try {
                bounded(call)?.let { _onScreen.value = it }
                if (undoable) before = was to time.markNow()
                return mole.on(Event.Done)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt == 0 && e !is TimeoutCancellationException) {
                    mole.on(Event.Retry)
                } else {
                    _onScreen.value = agentDesktop.onScreen
                    before = null
                    return mole.on(Event.Failed(c.failed, plain(e)))
                }
            }
        }
    }

    /** [call], given up after [STALL]. It runs outside this scope, so a call stuck where it cannot be cancelled is not
     * waited for; once it ends, the screen is read again. */
    private suspend fun <T> CoroutineScope.bounded(call: suspend () -> T): T {
        val c = CoroutineScope(currentCoroutineContext().minusKey(Job)).async(start = CoroutineStart.UNDISPATCHED) { call() }
        try {
            return withTimeout(STALL) { c.await() }
        } catch (e: CancellationException) {
            c.cancel()
            if (e is TimeoutCancellationException) launch { c.join(); _onScreen.value = agentDesktop.onScreen }
            throw e
        }
    }
}

private fun plain(e: Exception): String =
    (e as? IllegalArgumentException)?.message?.let { it.replaceFirstChar(Char::uppercase) + "." } ?: "The shared screen didn't answer."

private fun same(a: Target?, b: Target?) = if (a is Target.Slide && b is Target.Slide) a.deck == b.deck && a.page == b.page else a == b

/** A tab or app over the stage, which close takes off; slides, pictures and videos are on the stage itself. */
internal fun inFront(t: Target?) = t != null && t !is Target.Slide && !(t is Target.File && suffix(t.path).lowercase() in ON_STAGE)

internal fun name(t: Target): String = when (t) {
    is Target.Slide -> "slide ${t.page}"
    is Target.File -> t.path.substringAfterLast('/').substringBeforeLast('.')
    is Target.Site -> t.name.ifEmpty { host(t.url) }
    is Target.App -> t.name
}

private fun host(url: String) = url.substringAfter("://").substringBefore('/')

private fun String.cap() = replaceFirstChar(Char::uppercase)

private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth")

private fun showing(t: Target, pick: Int?, of: Int, first: String?) = Change(
    working = if (t is Target.Slide) "Finding slide ${t.page}…" else "Opening ${name(t)}…",
    retrying = "Trying ${name(t)} again…",
    ready = if (t is Target.Slide) "Slide ${t.page} is ready." else "It's on screen.",
    failed = "Couldn't show ${name(t)}.",
    first = first ?: pick?.let { if (of > 1 && it < ORDINALS.size) "Opening the ${ORDINALS[it]} one…" else "Opening ${name(t)}…" },
    card = card(t),
    pick = pick,
)

private fun card(t: Target) = if (t is Target.Slide) t.page.toString().padStart(2, '0') else ""

private fun undoing(back: Target?) = Change(
    working = back?.let { "Putting ${name(it)} back…" } ?: "Taking it down…",
    retrying = "Trying again…",
    ready = "${back?.let { name(it).cap() } ?: "The desktop"} is back.",
    failed = "Couldn't take it back.",
    first = "Okay, taking it back.",
)

private fun controlling(name: String) = Change("On it…", "Trying again…", ready = null, failed = "Couldn't ${name.replace(':', ' ')}.")

private val VERBS = Regex("\\b(open|show|pull up|bring up|put up|play|find|search for|go to)\\b", RegexOption.IGNORE_CASE)

/** "Open… what?" for "open up, um". */
private fun waiting(said: String) = VERBS.findAll(said).lastOrNull()?.value?.lowercase()?.cap()?.let { "$it… what?" }

/** A row of the choosing panel: what the target is called, where it is, and its kind. */
internal fun choice(t: Target): Choice = when (t) {
    is Target.Slide -> Choice(t.title.ifEmpty { "Slide ${t.page}" }, "${t.deck.replace('_', ' ')}, slide ${t.page}", Choice.Kind.Slides)
    is Target.File -> Choice(t.path.substringAfterLast('/'), t.path.substringBeforeLast('/', "").substringAfterLast('/'), kind(t.path))
    is Target.Site -> Choice(t.name.ifEmpty { host(t.url) }, host(t.url), Choice.Kind.Site)
    is Target.App -> Choice(t.name, "App", Choice.Kind.Other)
}

private fun kind(path: String) = when (suffix(path).lowercase()) {
    ".pdf" -> Choice.Kind.Pdf
    ".pptx", ".ppt", ".key" -> Choice.Kind.Slides
    ".xlsx", ".xls", ".csv", ".numbers" -> Choice.Kind.Sheet
    ".png", ".jpg", ".jpeg", ".gif", ".heic", ".webp" -> Choice.Kind.Image
    ".html", ".htm" -> Choice.Kind.Site
    else -> Choice.Kind.Other
}
