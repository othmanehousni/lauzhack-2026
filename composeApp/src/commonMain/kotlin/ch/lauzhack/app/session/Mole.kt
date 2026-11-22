package ch.lauzhack.app.session

import ch.lauzhack.app.Choice
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal val WAIT = 8.seconds   // how long the mole waits for the object of a request cut off before it
internal val OFFER = 10.seconds // how long an offer stays up when nobody takes it

/** What really happened, for the mole to say. */
internal sealed interface Event {
    data object Sleep : Event                                       // no call: nothing to show
    data class Setup(val step: String) : Event                      // getting ready before the mole can listen
    data class Hello(val title: String) : Event                     // it listens now: a call started, or the phone took the lead
    data class Words(val said: String) : Event                      // what the presenter is saying
    data class Thinking(val said: String) : Event                   // the agent is taking long over new words
    data object Answered : Event                                    // the agent answered
    data class Wait(val title: String?, val said: String) : Event   // a request whose object is still to come
    data class Unsure(val said: String) : Event                     // a request nothing fits
    data class Offer(val options: List<Choice>, val title: String?, val said: String) : Event
    data object Dismiss : Event                                     // the offer was turned down
    data class Start(val change: Change, val said: String) : Event  // a change of the screen is decided and under way
    data object Retry : Event                                       // the screen failed it once, the one retry runs
    data object Done : Event                                        // the screen did it
    data class Failed(val title: String, val reason: String) : Event
    data object Expire : Event                                      // the line has been up as long as it stays by itself
}

/** What the mole says about one change of the shared screen, from the moment it is decided until it is up. */
internal data class Change(
    val working: String,        // while the screen is still at it
    val retrying: String,       // while the one retry runs
    val ready: String?,         // once it is up; null hands straight back
    val failed: String,
    val first: String? = null,  // the title it starts with; null is heard's own, "I've got it."
    val card: String = "",      // the number on the slide card
    val pick: Int? = null,      // the offered row it was picked from
)

private data class Machine(val line: MoleLine, val change: Change? = null)

private fun just(state: MoleState, title: String? = null, heard: String = "") = Machine(MoleLine(state, title, heard))

/** The line an event leads to, or null when the event changes nothing in this state. */
private fun step(m: Machine, e: Event): Machine? {
    val l = m.line
    val c = m.change
    return when (e) {
        Event.Sleep -> just(MoleState.Hidden)
        is Event.Setup -> just(MoleState.Tunneling, e.step)
        is Event.Hello -> just(MoleState.Hello, e.title)
        is Event.Words -> when (l.state) {
            MoleState.Hidden, MoleState.Idle, MoleState.Back -> just(MoleState.Idle, heard = e.said)
            MoleState.Thinking, MoleState.Waiting, MoleState.Choosing -> m.copy(line = l.copy(heard = e.said))
            else -> null // the line is about a move: its words are the ones that made it
        }
        is Event.Thinking -> if (l.state in setOf(MoleState.Hidden, MoleState.Idle, MoleState.Back)) just(MoleState.Thinking, heard = e.said) else null
        Event.Answered -> if (l.state == MoleState.Thinking) just(MoleState.Idle, heard = l.heard) else null
        is Event.Wait -> Machine(MoleLine(MoleState.Waiting, e.title, e.said, timeoutMs = WAIT.inWholeMilliseconds))
        is Event.Unsure -> just(MoleState.Unsure, heard = e.said)
        is Event.Offer -> Machine(MoleLine(MoleState.Choosing, e.title, e.said, e.options))
        Event.Dismiss -> if (l.state == MoleState.Choosing) just(MoleState.Hidden) else null
        is Event.Start -> Machine(
            if (e.change.pick != null) MoleLine(MoleState.Picked, e.change.first, e.said, l.options, e.change.pick)
            else MoleLine(MoleState.Heard, e.change.first, e.said, card = e.change.card),
            e.change,
        )
        Event.Retry -> c?.let { Machine(MoleLine(MoleState.Retrieving, it.retrying, l.heard, card = it.card), it) }
        Event.Done -> c?.let { Machine(it.ready?.let { t -> MoleLine(MoleState.Ready, t, l.heard, card = it.card) } ?: MoleLine(MoleState.Back)) }
        is Event.Failed -> just(MoleState.Failed, e.title, e.reason)
        Event.Expire -> when (l.state) {
            MoleState.Heard, MoleState.Picked -> c?.let { Machine(MoleLine(MoleState.Working, it.working, l.heard, card = it.card), it) }
            MoleState.Ready, MoleState.Unsure, MoleState.Failed -> just(MoleState.Back)
            MoleState.Back, MoleState.Waiting, MoleState.Choosing -> just(MoleState.Hidden)
            MoleState.Hello -> just(MoleState.Idle)
            else -> null
        }
    }
}

/** How long a line stays up before another may replace it, so it can be read. */
private fun read(state: MoleState): Duration = when (state) {
    MoleState.Hidden, MoleState.Idle, MoleState.Choosing -> Duration.ZERO
    MoleState.Heard, MoleState.Picked -> 700.milliseconds
    MoleState.Ready -> 1500.milliseconds
    MoleState.Hello -> 1800.milliseconds
    MoleState.Unsure -> 2.seconds
    MoleState.Failed -> 2500.milliseconds
    else -> 600.milliseconds
}

/** How long a line stays up before it gives way by itself, for the lines that do. */
private fun hold(l: MoleLine): Duration? = when (l.state) {
    MoleState.Heard, MoleState.Picked -> 700.milliseconds // then working, if the screen is still at it
    MoleState.Ready, MoleState.Hello -> 1800.milliseconds
    MoleState.Back -> 1500.milliseconds
    MoleState.Unsure -> 2.seconds
    MoleState.Failed -> 3.seconds
    MoleState.Waiting -> WAIT
    MoleState.Choosing -> OFFER
    else -> null
}

/**
 * The mole's line, which the bar and the phone render, as a state machine: [on] takes what really happened, [step] turns
 * it into the next line, and the line on screen gives way to it once it has been up long enough to read. Events come as
 * the work happens and nothing waits for the line, so the narration may trail the screen but never holds it back.
 * Call [on] from [scope]'s thread.
 */
class Mole(private val scope: CoroutineScope, private val time: TimeSource = TimeSource.Monotonic) {
    private val shown = MutableStateFlow(MoleLine(MoleState.Hidden))
    val line: StateFlow<MoleLine> = shown.asStateFlow()
    private var want = Machine(shown.value) // where the events have led, shown once the line up now has been read
    private var since = time.markNow()      // when the line up now came up
    private var timer: Job? = null

    internal fun on(e: Event) {
        want = step(want, e) ?: return
        settle(new = e !is Event.Words && e !is Event.Setup) // more words, or the next step of the setup, show at once
    }

    private fun settle(new: Boolean) {
        timer?.cancel()
        if (new || want.line.state != shown.value.state) {
            val left = read(shown.value.state) - since.elapsedNow()
            if (left > Duration.ZERO) {
                timer = scope.launch { delay(left); settle(new = true) }
                return
            }
            since = time.markNow()
        }
        shown.value = want.line
        hold(want.line)?.let { h -> timer = scope.launch { delay(h - since.elapsedNow()); on(Event.Expire) } }
    }
}
