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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
internal val UNDO = 8.seconds  // how long "no, not that" takes back what just went up
private const val EARLIER = 3  // utterances handed to the agent with each one

/**
 * One meeting. Twice a second it asks [given] whether there is anything to do about what the presenter is saying and
 * what the audience sees, carries the move out on [agentDesktop] at once, and tells [mole] what really happens. Words
 * come from [laptop], or from [phone] while [phoneLeads]. It keeps what is on screen and what was there before, the offer
 * in play, the presenter's picks, and the last few utterances. Run it on one thread: its state changes between suspensions.
 */
class Session(
    private val given: Agent,
    private val agentDesktop: SharedScreen,
    private val mole: Mole,
    private val laptop: Flow<Heard>,
    private val phone: Flow<Heard> = emptyFlow(),
    private val phoneLeads: Flow<Boolean> = flowOf(false),
    private val time: TimeSource = TimeSource.Monotonic,
) {
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
    private var offer = emptyList<Target>()            // in play while the mole shows it
    private val picks = mutableMapOf<Set<Target>, Target>() // the presenter's answer to each choice offered
    private val taps = Channel<Int>(Channel.UNLIMITED)
    private val turn = Mutex()                         // one move at a time, from the loop or a tap

    /** A tap on row [index] of the options, on the bar or the phone. */
    fun pick(index: Int) {
        taps.trySend(index)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(): Nothing = coroutineScope {
        mole.on(Event.Hello("Hi. I'm listening."))
        val leads = phoneLeads.distinctUntilChanged()
        launch { leads.drop(1).collect { mole.on(Event.Hello(if (it) "On your phone now." else "Back on the laptop.")) } }
        launch { leads.flatMapLatest { if (it) phone else laptop }.collect(::hear) }
        launch { for (i in taps) turn.withLock { offered().getOrNull(i)?.let { show(it, said = "", pick = i) } } }
        val agent = Agent { heard, onScreen ->
            val slow = if (heard != asked) launch { delay(THINK); mole.on(Event.Thinking(heard.text)) } else null
            asked = heard
            try {
                given.anythingToDo(heard, onScreen)
            } catch (e: Exception) { // a model that failed once must not end the meeting
                currentCoroutineContext().ensureActive()
                println("the agent failed on \"${heard.text}\": $e")
                Move.Nothing
            } finally {
                slow?.cancel()
                mole.on(Event.Answered)
            }
        }
        flow { while (true) { emit(agent.anythingToDo(heard.value, onScreen.value)); delay(500.milliseconds) } }
            .collect { move ->
                turn.withLock {
                    if (fresh(move)) when (move) {
                        is Move.Show -> show(move.target)
                        is Move.Ask -> ask(move.options)
                        is Move.Control -> control(move.name)
                        Move.Wait -> mole.on(Event.Wait(waiting(asked.text), asked.text))
                        Move.Unsure -> mole.on(Event.Unsure(asked.text))
                        Move.Undo -> undo()
                        Move.Nothing -> Unit
                    }
                    if (heard.value.ended) later.removeFirstOrNull()?.let { _heard.value = it }
                }
            }
        awaitCancellation()
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

    private fun offered(): List<Target> = if (mole.line.value.state == MoleState.Choosing) offer else emptyList()

    private suspend fun show(target: Target, said: String = asked.text, pick: Int? = null, first: String? = null) {
        if (same(target, onScreen.value.target)) return
        val options = offered()
        val i = pick ?: options.indexOfFirst { same(it, target) }.takeIf { it >= 0 } // by tap, by voice, or by staying on it
        if (i != null && options.size > 1) picks[options.toSet()] = options[i]
        offer = emptyList()
        change(showing(target, i, options.size, first), said, undoable = true) { agentDesktop.show(target) }
    }

    private suspend fun ask(options: List<Target>) {
        picks[options.toSet()]?.let { return show(it, first = "Same as before: ${name(it)}.") }
        if (offered() == options) return
        offer = options
        mole.on(Event.Offer(options.map(::choice), if (options.size == 1) "Show ${name(options[0])}?" else null, asked.text))
    }

    private suspend fun control(name: String) =
        if (name == "close") change(controlling(name), asked.text, undoable = true) { agentDesktop.close() }
        else change(controlling(name), asked.text, undoable = false) { agentDesktop.control(name); null }

    private suspend fun undo() {
        if (offered().isNotEmpty()) {
            offer = emptyList()
            return mole.on(Event.Dismiss)
        }
        val (was, at) = before ?: return
        if (at.elapsedNow() > UNDO) return
        before = null
        val now = onScreen.value.target
        change(undoing(was.target), asked.text, undoable = false) {
            // a tab or app comes off to bare what is under it; what is on the stage itself is replaced
            val under = if (was.target == null || inFront(now)) agentDesktop.close() else null
            if (was.target == null || same(under?.target, was.target)) under else agentDesktop.show(was.target)
        }
    }

    /** Runs a change of the screen, once more if it fails, and tells the mole each step as it happens. */
    private suspend fun change(c: Change, said: String, undoable: Boolean, call: suspend () -> OnScreen?) {
        val was = onScreen.value
        mole.on(Event.Start(c, said))
        repeat(2) { attempt ->
            try {
                call()?.let { _onScreen.value = it }
                if (undoable) before = was to time.markNow()
                return mole.on(Event.Done)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt == 0) {
                    mole.on(Event.Retry)
                } else {
                    _onScreen.value = agentDesktop.onScreen
                    mole.on(Event.Failed(c.failed, plain(e)))
                }
            }
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
    card = if (t is Target.Slide) t.page.toString().padStart(2, '0') else "",
    pick = pick,
)

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
