@file:OptIn(ExperimentalCoroutinesApi::class)

package ch.lauzhack.app.session

import ch.lauzhack.app.Agent
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.Move
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun slide(page: Int) = Target.Slide("Home_Insurance_Review", page)
private val march = Target.File("/Users/hamid/Documents/Clients/Dubois/Home offer — March.pdf")
private val revised = Target.File("/Users/hamid/Documents/Clients/Dubois/Home offer — revised.pdf")
private val youtube = Target.Site("https://www.youtube.com/watch?v=grip", "Robot gripper demo")

/** The shared screen: a stage with a tab or app over it, the calls it got at which virtual time, failures on demand. */
private class FakeScreen(private val scope: TestScope, shows: Target?) : SharedScreen {
    val calls = mutableListOf<Pair<Long, String>>()
    var fails = 0
    var latency = 30.milliseconds
    private var stage = OnScreen(shows, shows?.let(::name).orEmpty())
    private var front: OnScreen? = null
    override val onScreen get() = front ?: stage

    override suspend fun show(target: Target) = act("show ${name(target)}") {
        val s = OnScreen(target, name(target))
        if (inFront(target)) front = s else { stage = s; front = null }
    }

    override suspend fun close() = act("close") { if (front != null) front = null else stage = OnScreen() }

    override suspend fun control(name: String) { act(name) {} }

    private suspend fun act(what: String, change: () -> Unit): OnScreen {
        calls += scope.currentTime to what
        delay(latency)
        if (fails > 0) {
            fails--
            error("HTTP 500 from DevTools")
        }
        change()
        return onScreen
    }
}

/** A session with a scripted agent, a fake screen and both microphones, on virtual time; [lines] is every line the
 * mole showed, with when. */
private class Rig(private val scope: TestScope, shows: Target? = null, script: suspend (Heard, OnScreen) -> Move) {
    val laptop = MutableSharedFlow<Heard>(extraBufferCapacity = 64) // like the ear's: nobody listening, nobody hears
    val phone = MutableSharedFlow<Heard>(extraBufferCapacity = 64)
    val phoneLeads = MutableStateFlow(false)
    val asked = mutableListOf<Pair<Long, Heard>>()
    val screen = FakeScreen(scope, shows)
    val mole = Mole(scope.backgroundScope, scope.testTimeSource)
    val session = Session(Agent { h, s -> asked += scope.currentTime to h; script(h, s) }, screen, mole, laptop, phone, phoneLeads, scope.testTimeSource)
    val lines = mutableListOf<Pair<Long, MoleLine>>()

    init {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { mole.line.collect { lines += scope.currentTime to it } }
    }

    fun start() = scope.backgroundScope.launch { session.run() }

    /** The words one at a time, as an ear streams them; the last piece ends the utterance. */
    suspend fun say(text: String, to: MutableSharedFlow<Heard> = laptop, every: Duration = 140.milliseconds) {
        val w = text.split(" ")
        for (i in 1..w.size) {
            to.emit(Heard(w.take(i).joinToString(" "), ended = i == w.size))
            delay(every)
        }
    }

    suspend fun until(state: MoleState) = mole.line.first { it.state == state }

    fun states() = lines.map { it.second.state }.fold(listOf<MoleState>()) { s, x -> if (s.lastOrNull() == x) s else s + x }

    fun time(state: MoleState, after: Long = 0) = lines.first { it.first >= after && it.second.state == state }.first

    fun line(state: MoleState) = lines.last { it.second.state == state }.second

    /** When the agent was first asked about the end of [text]. */
    fun decided(text: String) = asked.first { it.second.text == text && it.second.ended }.first
}

class SessionTest {
    @Test
    fun eachMoveIsCarriedOutOnceAndWhatIsUpIsNotShownAgain() = runTest {
        val rig = Rig(this, slide(6)) { h, s ->
            when {
                "slide seven" in h.text -> Move.Show(slide(7)) // for every growing piece, at every poll
                h.text == "next slide" -> Move.Show(slide(((s.target as Target.Slide).page) + 1)) // what is up now, plus one
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(2.seconds)
        rig.say("can you show slide seven please")
        delay(3.seconds)
        assertEquals(listOf("show slide 7"), rig.screen.calls.map { it.second })
        rig.say("show slide seven") // a new utterance, for what is up already
        delay(3.seconds)
        assertEquals(listOf("show slide 7"), rig.screen.calls.map { it.second })
        rig.say("next slide")
        delay(3.seconds)
        rig.say("next slide") // the same words again: a second utterance
        delay(3.seconds)
        assertEquals(listOf("show slide 7", "show slide 8", "show slide 9"), rig.screen.calls.map { it.second })
        assertTrue(rig.asked.count { it.second.text == "next slide" } >= 6, "the agent was asked about each \"next slide\" again and again")
        assertEquals(listOf(1, 2, 3, 4), rig.asked.map { it.second.n }.filter { it > 0 }.distinct())
        assertEquals(listOf("can you show slide seven please", "show slide seven", "next slide"), rig.asked.last().second.earlier)
    }

    /** index.html's story, beat by beat, with the wiring's setup step first, until every one of the 15 states has come
     * from something that really happened. */
    @Test
    fun theStory() = runTest {
        val rig = Rig(this, slide(6)) { h, s ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "okay so the next thing I want to show you is the market size" -> Move.Show(slide(7))
                "and can you pull up the offer we sent them" -> Move.Ask(listOf(march, revised))
                "show them how the warehouse robot picks" -> { delay(1500.milliseconds); Move.Show(youtube) }
                "can you open the, um, thing from the offsite" -> Move.Unsure
                "open up, um" -> Move.Wait
                "the roadmap" -> Move.Show(slide(10))
                "next slide" -> Move.Show(slide(((s.target as? Target.Slide)?.page ?: 7) + 1))
                else -> Move.Nothing
            }
        }
        rig.mole.on(Event.Setup("Getting the shared screen ready…"))
        delay(1.seconds)
        rig.start()
        assertEquals(MoleLine(MoleState.Hello, "Hi. I'm listening."), rig.until(MoleState.Hello))
        rig.until(MoleState.Idle)

        // "the market size": the slide goes up the moment it is decided; the line follows the real work
        rig.say("okay so the next thing I want to show you is the market size")
        rig.until(MoleState.Hidden)
        val decided = rig.decided("okay so the next thing I want to show you is the market size")
        assertEquals(listOf(decided to "show slide 7"), rig.screen.calls)
        assertEquals(decided, rig.time(MoleState.Heard))
        assertEquals(MoleLine(MoleState.Heard, heard = "okay so the next thing I want to show you is the market size", card = "07"), rig.line(MoleState.Heard))
        assertEquals("Slide 7 is ready.", rig.line(MoleState.Ready).title)
        assertEquals(decided + 700, rig.time(MoleState.Ready))
        assertEquals(slide(7), rig.session.onScreen.value.target)

        // walking away: the phone leads, and only its words count
        rig.phoneLeads.value = true
        assertEquals("On your phone now.", rig.until(MoleState.Hello).title)
        rig.until(MoleState.Idle)
        rig.say("show them the hail damage photos", to = rig.laptop)
        rig.say("and can you pull up the offer we sent them", to = rig.phone)
        val choosing = rig.until(MoleState.Choosing)
        assertEquals(listOf("Home offer — March.pdf", "Home offer — revised.pdf"), choosing.options.map { it.name })
        assertEquals(listOf("Dubois"), choosing.options.map { it.meta }.distinct())
        assertTrue(rig.asked.none { "hail" in it.second.text })
        delay(2.seconds)
        assertEquals(1, rig.screen.calls.size, "nothing is shared until the presenter picks")
        rig.session.pick(1)
        val picked = rig.until(MoleState.Picked)
        assertEquals(MoleLine(MoleState.Picked, "Opening the second one…", "", choosing.options, pick = 1), picked)
        assertEquals(currentTime, rig.screen.calls.last().first)
        assertEquals("show Home offer — revised", rig.screen.calls.last().second)
        assertEquals("It's on screen.", rig.until(MoleState.Ready).title)
        rig.until(MoleState.Hidden)

        // back at the laptop: a request the agent takes long over, on a slow screen
        rig.phoneLeads.value = false
        assertEquals("Back on the laptop.", rig.until(MoleState.Hello).title)
        rig.until(MoleState.Idle)
        rig.screen.latency = 1500.milliseconds
        rig.say("show them how the warehouse robot picks")
        rig.until(MoleState.Thinking)
        val asked = rig.decided("show them how the warehouse robot picks")
        assertEquals(asked + 1000, currentTime, "thinking once the agent has taken a second over new words")
        assertEquals("Opening Robot gripper demo…", rig.until(MoleState.Working).title)
        assertEquals(asked + 1500, rig.screen.calls.last().first, "the screen starts as soon as the agent answers")
        rig.until(MoleState.Ready)
        assertEquals(asked + 3000, currentTime, "ready when it is up")
        rig.until(MoleState.Hidden)
        rig.screen.latency = 30.milliseconds

        rig.say("can you open the, um, thing from the offsite")
        assertEquals(MoleLine(MoleState.Unsure, heard = "can you open the, um, thing from the offsite"), rig.until(MoleState.Unsure))
        rig.until(MoleState.Hidden)

        // a request cut off before its object, then a screen that fails it twice: one retry, then failed
        rig.say("open up, um")
        val waiting = rig.until(MoleState.Waiting)
        assertEquals("Open… what?" to 8000L, waiting.title to waiting.timeoutMs)
        rig.screen.latency = 1.seconds
        rig.screen.fails = 2
        val calls = rig.screen.calls.size
        rig.say("the roadmap")
        assertEquals("Trying slide 10 again…", rig.until(MoleState.Retrieving).title)
        assertEquals(MoleLine(MoleState.Failed, "Couldn't show slide 10.", "The shared screen didn't answer."), rig.until(MoleState.Failed))
        assertEquals(listOf("show slide 10", "show slide 10"), rig.screen.calls.drop(calls).map { it.second })
        assertEquals(youtube, rig.session.onScreen.value.target)
        rig.until(MoleState.Hidden)
        rig.screen.latency = 30.milliseconds

        rig.say("next slide")
        assertEquals("Slide 8 is ready.", rig.until(MoleState.Ready).title)
        rig.until(MoleState.Hidden)
        assertEquals(slide(8), rig.session.onScreen.value.target)

        assertEquals(
            "Hidden Tunneling Hello Idle Heard Ready Back Hidden Hello Idle Choosing Picked Ready Back Hidden Hello Idle Thinking Heard " +
                "Working Ready Back Hidden Idle Unsure Back Hidden Idle Waiting Heard Working Retrieving Failed Back Hidden Idle Heard Ready Back Hidden",
            rig.states().joinToString(" "),
        )
        assertEquals(MoleState.entries.toSet(), rig.states().toSet())
        assertEquals(1, rig.states().count { it == MoleState.Choosing }, "one buzz")
    }

    @Test
    fun anOfferIsTakenByVoiceOrByStayingOnItsTopic() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "pull up the offer" -> Move.Ask(listOf(march, revised))
                "the second one" -> Move.Show(revised)
                "what comes after this" -> Move.Ask(listOf(slide(8))) // the agent only suggests it
                "and the margin keeps growing year on year" -> Move.Show(slide(8)) // and the talk moves on to it
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("pull up the offer")
        rig.until(MoleState.Choosing)
        rig.say("the second one")
        assertEquals(MoleLine(MoleState.Picked, "Opening the second one…", "the second one", rig.line(MoleState.Choosing).options, pick = 1), rig.until(MoleState.Picked))
        rig.until(MoleState.Hidden)
        assertEquals(revised, rig.session.onScreen.value.target)

        rig.say("what comes after this")
        assertEquals("Show slide 8?", rig.until(MoleState.Choosing).title)
        assertEquals(1, rig.screen.calls.size)
        rig.say("and the margin keeps growing year on year")
        assertEquals("Opening slide 8…" to 0, rig.until(MoleState.Picked).let { it.title to it.pick })
        rig.until(MoleState.Hidden)
        assertEquals(listOf("show Home offer — revised", "show slide 8"), rig.screen.calls.map { it.second })
    }

    @Test
    fun anIgnoredOfferFadesAndAChoiceIsNotAskedTwice() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "pull up the offer" -> Move.Ask(listOf(march, revised))
                "show slide three" -> Move.Show(slide(3))
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("pull up the offer")
        rig.until(MoleState.Choosing)
        rig.until(MoleState.Hidden)
        assertEquals(rig.time(MoleState.Choosing) + OFFER.inWholeMilliseconds, currentTime, "it fades by itself")
        assertEquals(listOf(MoleState.Choosing, MoleState.Hidden), rig.states().takeLast(2), "quietly, without handing back")
        rig.session.pick(1)
        delay(2.seconds)
        assertEquals(emptyList(), rig.screen.calls, "a tap after it faded does nothing")

        rig.say("pull up the offer")
        rig.until(MoleState.Choosing)
        rig.session.pick(0)
        rig.until(MoleState.Hidden)
        rig.say("show slide three")
        rig.until(MoleState.Hidden)
        rig.say("pull up the offer")
        assertEquals("Same as before: Home offer — March.", rig.until(MoleState.Heard).title)
        rig.until(MoleState.Hidden)
        assertEquals(listOf("show Home offer — March", "show slide 3", "show Home offer — March"), rig.screen.calls.map { it.second })
        assertEquals(2, rig.states().count { it == MoleState.Choosing })
    }

    @Test
    fun undoPutsBackWhatWasThere() = runTest {
        val rig = Rig(this, slide(6)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "show slide seven" -> Move.Show(slide(7))
                "open the gripper video" -> Move.Show(youtube)
                "no, not that, go back" -> Move.Undo
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("show slide seven")
        rig.until(MoleState.Hidden)
        rig.say("no, not that, go back")
        assertEquals("Okay, taking it back.", rig.until(MoleState.Heard).title)
        assertEquals("Slide 6 is back.", rig.until(MoleState.Ready).title)
        assertEquals(slide(6), rig.session.onScreen.value.target)
        rig.until(MoleState.Hidden)

        rig.say("open the gripper video") // a tab over the slide
        rig.until(MoleState.Ready)
        rig.say("no, not that, go back")
        rig.until(MoleState.Hidden)
        assertEquals(listOf("show slide 7", "show slide 6", "show Robot gripper demo", "close"), rig.screen.calls.map { it.second })
        assertEquals(slide(6), rig.session.onScreen.value.target)

        rig.say("show slide seven")
        rig.until(MoleState.Hidden)
        delay(UNDO)
        rig.say("no, not that, go back") // too late: it stays
        delay(3.seconds)
        assertEquals("show slide 7", rig.screen.calls.last().second)
    }

    @Test
    fun undoOfTheFirstThingShownBaresTheDesktop() = runTest {
        val rig = Rig(this) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "show slide seven" -> Move.Show(slide(7))
                "no, not that" -> Move.Undo
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("show slide seven")
        rig.until(MoleState.Ready)
        rig.say("no, not that") // while the mole still holds slide 7 up
        rig.until(MoleState.Hidden)
        assertEquals(listOf("show slide 7", "close"), rig.screen.calls.map { it.second })
        assertEquals(OnScreen(), rig.session.onScreen.value)
        assertEquals(listOf("Slide 7 is ready.", "The desktop is back."), rig.lines.filter { it.second.state == MoleState.Ready }.map { it.second.title })
    }

    @Test
    fun theNarrationNeverHoldsTheScreenBack() = runTest {
        val rig = Rig(this, slide(6)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "show slide seven" -> Move.Show(slide(7))
                "and eight" -> Move.Show(slide(8))
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("show slide seven")
        rig.until(MoleState.Ready)
        rig.say("and eight") // while slide 7 is still being announced
        rig.until(MoleState.Hidden)
        val seven = rig.decided("show slide seven")
        val eight = rig.decided("and eight")
        assertEquals(listOf(seven to "show slide 7", eight to "show slide 8"), rig.screen.calls, "each at the moment it was decided")
        val ready = rig.time(MoleState.Ready)
        assertEquals(seven + 700, ready)
        assertEquals("Slide 7 is ready.", rig.lines.last { it.first <= eight + 30 }.second.title, "slide 8 went up while the line still read slide 7's")
        assertEquals(ready + 1500, rig.lines.first { it.second.title == "Slide 8 is ready." }.first, "and the line caught up once that had been read")
        assertEquals(
            listOf(MoleState.Heard to null, MoleState.Ready to "Slide 7 is ready.", MoleState.Ready to "Slide 8 is ready.", MoleState.Back to null, MoleState.Hidden to null),
            rig.lines.map { it.second.state to it.second.title }.dropWhile { it.first != MoleState.Heard },
        )
    }
}
