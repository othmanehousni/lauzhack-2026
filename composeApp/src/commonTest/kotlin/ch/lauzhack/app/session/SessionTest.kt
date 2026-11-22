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
internal class FakeScreen(private val scope: TestScope, shows: Target?) : SharedScreen {
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
internal class Rig(private val scope: TestScope, shows: Target? = null, script: suspend (Heard, OnScreen) -> Move) {
    val laptop = MutableSharedFlow<Heard>(extraBufferCapacity = 64) // like the ear's: nobody listening, nobody hears
    val phone = MutableSharedFlow<Heard>(extraBufferCapacity = 64)
    val phoneLeads = MutableStateFlow(false)
    val asked = mutableListOf<Pair<Long, Heard>>()
    val screen = FakeScreen(scope, shows)
    val mole = Mole(scope.backgroundScope, scope.testTimeSource)
    val window = MutableStateFlow(ANNOUNCE)
    val session = Session(Agent { h, s -> asked += scope.currentTime to h; script(h, s) }, screen, mole, laptop, phone, phoneLeads, scope.testTimeSource, window)
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

    /** Says [text], which the agent announces, and gives when: the moment it was decided. */
    suspend fun announced(text: String, to: MutableSharedFlow<Heard> = laptop): Long {
        say(text, to)
        until(MoleState.Retrieving)
        return decided(text)
    }

    fun states() = lines.map { it.second.state }.fold(listOf<MoleState>()) { s, x -> if (s.lastOrNull() == x) s else s + x }

    fun time(state: MoleState, after: Long = 0) = lines.first { it.first >= after && it.second.state == state }.first

    fun line(state: MoleState) = lines.last { it.second.state == state }.second

    /** When the agent was first asked about the end of the last utterance of [text]. */
    fun decided(text: String): Long {
        val n = asked.last { it.second.text == text && it.second.ended }.second.n
        return asked.first { it.second.n == n && it.second.ended }.first
    }
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
        assertEquals(2, rig.asked.count { it.second.text == "next slide" }, "the agent was asked about each \"next slide\" once, as it ended")
        assertEquals(listOf(1, 2, 3, 4), rig.asked.map { it.second.n }.filter { it > 0 }.distinct())
        assertEquals(listOf("can you show slide seven please", "show slide seven", "next slide"), rig.asked.last().second.earlier)
    }

    /** index.html's story, beat by beat, with the wiring's setup step first, until every one of the 15 states has come
     * from something that really happened. */
    @Test
    fun aChangeOfLeadEndsWhatTheOtherMicrophoneLeftOpen() = runTest {
        val rig = Rig(this) { _, _ -> Move.Nothing }
        rig.start()
        delay(1.seconds) // the session listens
        rig.laptop.emit(Heard("Next slide. And then")) // one sentence ended, the next still open
        delay(500.milliseconds)
        rig.phoneLeads.value = true
        delay(500.milliseconds)
        rig.say("show the roadmap", to = rig.phone)
        delay(500.milliseconds)
        assertEquals(listOf("Next slide.", "And then", "show the roadmap"), rig.asked.filter { it.second.ended }.map { it.second.text }.distinct())
    }

    @Test
    fun pickingWhatIsAlreadyUpEndsTheChoice() = runTest {
        val rig = Rig(this, march) { h, _ -> if (h.ended && h.text == "pull up the offer") Move.Ask(listOf(march, revised)) else Move.Nothing }
        rig.start()
        rig.say("pull up the offer")
        rig.until(MoleState.Choosing)
        rig.session.pick(0) // the March offer, on screen already
        rig.until(MoleState.Ready)
        assertTrue(rig.screen.calls.isEmpty(), "nothing to show: it is up")
        rig.until(MoleState.Hidden)
    }

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
                "which brings us to the margin" -> Move.Ask(listOf(slide(9)))
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
        assertEquals("Home offer — revised is on screen.", rig.until(MoleState.Ready).title)
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
        assertEquals(null to 8000L, waiting.title to waiting.timeoutMs) // the state's own title: no word list reads the request
        rig.screen.latency = 1.seconds
        rig.screen.fails = 2
        val calls = rig.screen.calls.size
        rig.say("the roadmap")
        assertEquals(MoleState.Working, rig.mole.line.first { it.title == "Trying slide 10 again…" }.state)
        assertEquals(MoleLine(MoleState.Failed, "Couldn't show slide 10.", "The shared screen didn't answer."), rig.until(MoleState.Failed))
        assertEquals(listOf("show slide 10", "show slide 10"), rig.screen.calls.drop(calls).map { it.second })
        assertEquals(youtube, rig.session.onScreen.value.target)
        rig.until(MoleState.Hidden)
        rig.screen.latency = 30.milliseconds

        rig.say("next slide")
        assertEquals("Slide 8 is ready.", rig.until(MoleState.Ready).title)
        rig.until(MoleState.Hidden)
        assertEquals(slide(8), rig.session.onScreen.value.target)

        // the talk moves on to slide 9: the mole says it is coming, and it comes unless the presenter stops it
        rig.say("which brings us to the margin")
        assertEquals("Showing slide 9", rig.until(MoleState.Retrieving).title)
        assertEquals("Opening slide 9…", rig.until(MoleState.Picked).title)
        rig.until(MoleState.Hidden)
        assertEquals(rig.time(MoleState.Retrieving) + ANNOUNCE.inWholeMilliseconds, rig.screen.calls.last().first)
        assertEquals(slide(9), rig.session.onScreen.value.target)

        assertEquals(
            "Hidden Tunneling Hello Idle Heard Ready Back Hidden Hello Idle Choosing Picked Ready Back Hidden Hello Idle Thinking Heard " +
                "Working Ready Back Hidden Idle Unsure Back Hidden Idle Waiting Heard Working Failed Back Hidden Idle Heard Ready Back Hidden " +
                "Idle Retrieving Picked Ready Back Hidden",
            rig.states().joinToString(" "),
        )
        assertEquals(MoleState.entries.toSet(), rig.states().toSet())
        val buzzes = rig.states().filter { it in setOf(MoleState.Choosing, MoleState.Retrieving, MoleState.Failed) }
        assertEquals(listOf(MoleState.Choosing, MoleState.Failed, MoleState.Retrieving), buzzes, "the phone buzzes for a pick, a failure and an announce, once each")
    }

    @Test
    fun anOfferIsTakenByVoiceOrByStayingOnItsTopic() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "pull up the offer" -> Move.Ask(listOf(march, revised))
                "the second one" -> Move.Show(revised)
                "what comes after this" -> Move.Ask(listOf(slide(8))) // the agent announces it
                "and the margin keeps growing year on year" -> Move.Show(slide(8)) // and the talk moves on to it before the window ends
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

        val announced = rig.announced("what comes after this")
        assertEquals("Showing slide 8", rig.mole.line.value.title)
        assertEquals(1, rig.screen.calls.size)
        rig.say("and the margin keeps growing year on year")
        assertEquals("Opening slide 8…" to 0, rig.until(MoleState.Picked).let { it.title to it.pick })
        rig.until(MoleState.Hidden)
        val taken = rig.decided("and the margin keeps growing year on year")
        assertTrue(taken < announced + ANNOUNCE.inWholeMilliseconds, "taken before the window ends")
        assertEquals(listOf("show Home offer — revised", "show slide 8"), rig.screen.calls.map { it.second })
        assertEquals(taken, rig.screen.calls.last().first)
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

    @Test
    fun anAnnounceIsShownWhenItsWindowEndsWhileThePresenterTalksOn() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)))
                "and open up, um" -> Move.Wait
                "the thing from the offsite" -> Move.Unsure
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("which brings us to the margin")
        val coming = rig.until(MoleState.Retrieving)
        val at = rig.decided("which brings us to the margin")
        assertEquals(
            MoleLine(MoleState.Retrieving, "Showing slide 8", "which brings us to the margin", listOf(choice(slide(8))), card = "08", timeoutMs = 2500),
            coming,
        )
        assertEquals(at, rig.time(MoleState.Retrieving), "announced the moment it is decided")
        rig.say("so margins went up") // a presenter who keeps talking consents
        rig.say("and open up, um")
        rig.say("the thing from the offsite")
        rig.until(MoleState.Hidden)
        assertTrue(rig.decided("the thing from the offsite") < at + 2500, "all of it said inside the window")
        assertEquals(listOf(at + 2500 to "show slide 8"), rig.screen.calls)
        assertEquals(listOf(MoleState.Retrieving, MoleState.Picked, MoleState.Ready, MoleState.Back, MoleState.Hidden), rig.states().dropWhile { it != MoleState.Retrieving })
        assertEquals(MoleLine(MoleState.Picked, "Opening slide 8…", "which brings us to the margin", coming.options, pick = 0), rig.line(MoleState.Picked))
        assertEquals(at + 2500, rig.time(MoleState.Picked))
        assertEquals(at + 3200 to "Slide 8 is ready.", rig.time(MoleState.Ready) to rig.line(MoleState.Ready).title)
        assertEquals(slide(8), rig.session.onScreen.value.target)
    }

    @Test
    fun aNoStopsAnAnnounceUntilItsLastMillisecondAndTakesItBackAfter() = runTest {
        var answer = 0L // when the agent hears the "no"
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)))
                "and the costs" -> Move.Ask(listOf(slide(9)))
                "here is the robot" -> Move.Ask(listOf(youtube))
                "no", "no, not now", "no, not that", "no, take it off" -> { delay(answer - currentTime); Move.Undo }
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)

        // at once
        val first = rig.announced("which brings us to the margin")
        rig.say("no")
        assertEquals(MoleLine(MoleState.Back, "Okay, not shown."), rig.until(MoleState.Back))
        assertEquals(first + 140, rig.decided("no"))
        assertEquals(first + 140, rig.time(MoleState.Back, after = first))
        delay(3.seconds)
        assertEquals(emptyList(), rig.screen.calls)

        // at its last millisecond
        val last = rig.announced("and the costs")
        answer = last + 2499
        rig.say("no, not now")
        rig.until(MoleState.Back)
        assertEquals(last + 2499, rig.time(MoleState.Back, after = last))
        delay(3.seconds)
        assertEquals(emptyList(), rig.screen.calls)
        assertEquals(slide(7), rig.session.onScreen.value.target)

        // a millisecond late: it is up, and the no takes it back off at once
        val late = rig.announced("here is the robot")
        answer = late + 2500
        rig.say("no, not that")
        assertEquals("Slide 7 is back.", rig.until(MoleState.Ready).title)
        rig.until(MoleState.Hidden)
        assertEquals(listOf(late + 2500 to "show Robot gripper demo", late + 2530 to "close"), rig.screen.calls)

        // seconds later, within the undo
        val again = rig.announced("here is the robot")
        rig.until(MoleState.Ready)
        delay(3.seconds)
        answer = 0
        rig.say("no, take it off")
        rig.until(MoleState.Hidden)
        assertEquals(listOf(again + 2500 to "show Robot gripper demo", rig.decided("no, take it off") to "close"), rig.screen.calls.drop(2))
        assertEquals(slide(7), rig.session.onScreen.value.target)
    }

    @Test
    fun aTapStopsOrTakesAnAnnounceAndSoDoesAYes() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)))
                "and the costs" -> Move.Ask(listOf(slide(9)))
                "here is the robot" -> Move.Ask(listOf(youtube))
                "yes, show the robot" -> Move.Show(youtube)
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)

        // dismissed on the bar in the very millisecond it appears
        rig.laptop.emit(Heard("which brings us to the margin", ended = true))
        rig.until(MoleState.Retrieving)
        val dismissed = currentTime
        assertEquals(rig.decided("which brings us to the margin"), dismissed)
        rig.session.pick(-1)
        assertEquals(MoleLine(MoleState.Back, "Okay, not shown."), rig.until(MoleState.Back))
        assertEquals(dismissed, rig.time(MoleState.Back, after = dismissed))
        rig.session.pick(0) // a tap after it is gone does nothing
        rig.session.pick(-1)
        delay(4.seconds)
        assertEquals(emptyList(), rig.screen.calls)
        assertEquals(listOf(MoleState.Retrieving, MoleState.Back, MoleState.Hidden), rig.states().dropWhile { it != MoleState.Retrieving })

        // taken by a tap before its window ends
        val costs = rig.announced("and the costs")
        val options = rig.mole.line.value.options
        val tapped = costs + 700
        delay((tapped - currentTime).milliseconds)
        rig.session.pick(0)
        assertEquals(MoleLine(MoleState.Picked, "Opening slide 9…", "", options, pick = 0), rig.until(MoleState.Picked))
        assertEquals(tapped, rig.time(MoleState.Picked, after = tapped))
        delay(4.seconds)
        assertEquals(listOf(tapped to "show slide 9"), rig.screen.calls)

        // taken by the agent showing the same thing
        val robot = rig.announced("here is the robot")
        rig.say("yes, show the robot")
        assertEquals("Opening Robot gripper demo…", rig.until(MoleState.Picked).title)
        delay(4.seconds)
        val yes = rig.decided("yes, show the robot")
        assertTrue(yes < robot + 2500)
        assertEquals(listOf(tapped to "show slide 9", yes to "show Robot gripper demo"), rig.screen.calls)
    }

    @Test
    fun theSameAskKeepsItsWindowAndAnotherStartsAFreshOne() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin", "the margin again" -> Move.Ask(listOf(slide(8)))
                "and the costs" -> Move.Ask(listOf(slide(9)))
                "or rather the risks" -> Move.Ask(listOf(slide(10)))
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        val first = rig.announced("which brings us to the margin")
        rig.say("the margin again")
        rig.until(MoleState.Picked)
        assertTrue(rig.decided("the margin again") < first + 2500)
        assertEquals(1, rig.lines.count { it.second.state == MoleState.Retrieving }, "announced once")
        assertEquals(listOf(first + 2500 to "show slide 8"), rig.screen.calls)
        rig.until(MoleState.Hidden)

        rig.say("and the costs")
        rig.until(MoleState.Retrieving)
        rig.say("or rather the risks")
        val risks = rig.decided("or rather the risks")
        assertEquals("Showing slide 10", rig.lines.first { it.first == risks && it.second.state == MoleState.Retrieving }.second.title)
        rig.until(MoleState.Picked)
        rig.until(MoleState.Hidden)
        assertEquals(listOf(first + 2500 to "show slide 8", risks + 2500 to "show slide 10"), rig.screen.calls, "slide 9 never")
    }

    @Test
    fun aChoiceAnAnnounceAndAShowEachReplaceWhatIsInPlay() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "pull up the offer" -> Move.Ask(listOf(march, revised))
                "here is the robot" -> Move.Ask(listOf(youtube))
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)))
                "and the other offer" -> Move.Ask(listOf(march, revised))
                "and the costs" -> Move.Ask(listOf(slide(9)))
                "show the roadmap" -> Move.Show(slide(10))
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)

        // an announce during a choice replaces it
        rig.say("pull up the offer")
        rig.until(MoleState.Choosing)
        val robot = rig.announced("here is the robot")
        rig.session.pick(1) // the choice is gone: this takes the announce's only row, which has no second
        rig.until(MoleState.Hidden)
        assertEquals(listOf(robot + 2500 to "show Robot gripper demo"), rig.screen.calls)

        // a choice during an announce stops it: nothing is shown until a pick
        val margin = rig.announced("which brings us to the margin")
        rig.say("and the other offer")
        val choosing = rig.until(MoleState.Choosing)
        assertEquals(listOf("Home offer — March.pdf", "Home offer — revised.pdf"), choosing.options.map { it.name })
        delay((margin + 4000 - currentTime).milliseconds)
        assertEquals(1, rig.screen.calls.size, "slide 8 never")
        val picked = currentTime
        rig.session.pick(1)
        rig.until(MoleState.Hidden)
        assertEquals(picked to "show Home offer — revised", rig.screen.calls.last())

        // a show of something else acts at once, and the announce is dropped
        val costs = rig.announced("and the costs")
        rig.say("show the roadmap")
        rig.until(MoleState.Heard)
        delay((costs + 4000 - currentTime).milliseconds)
        assertEquals(listOf(rig.decided("show the roadmap") to "show slide 10"), rig.screen.calls.drop(2))
        assertTrue(rig.decided("show the roadmap") < costs + 2500)
        assertEquals(slide(10), rig.session.onScreen.value.target)
    }

    @Test
    fun nothingReachesTheScreenOnceTheCallHasEnded() = runTest {
        val rig = Rig(this, slide(7)) { h, _ -> if (h.ended && h.text == "which brings us to the margin") Move.Ask(listOf(slide(8))) else Move.Nothing }
        val call = rig.start()
        delay(3.seconds)
        val at = rig.announced("which brings us to the margin")
        delay((at + 2499 - currentTime).milliseconds)
        call.cancel()
        delay(10.seconds)
        assertEquals(emptyList(), rig.screen.calls)
    }

    @Test
    fun aScreenThatFailsWhenTheWindowEndsIsTriedOnceMore() = runTest {
        val rig = Rig(this, slide(7)) { h, _ -> if (h.ended && h.text == "which brings us to the margin") Move.Ask(listOf(slide(8))) else Move.Nothing }
        rig.start()
        delay(3.seconds)
        rig.screen.latency = 1.seconds
        rig.screen.fails = 2
        val at = rig.announced("which brings us to the margin")
        assertEquals(MoleLine(MoleState.Failed, "Couldn't show slide 8.", "The shared screen didn't answer."), rig.until(MoleState.Failed))
        assertEquals(listOf(at + 2500 to "show slide 8", at + 3500 to "show slide 8"), rig.screen.calls)
        assertEquals(
            listOf(MoleState.Retrieving to "Showing slide 8", MoleState.Picked to "Opening slide 8…", MoleState.Working to "Finding slide 8…",
                MoleState.Working to "Trying slide 8 again…", MoleState.Failed to "Couldn't show slide 8."),
            rig.lines.map { it.second.state to it.second.title }.dropWhile { it.first != MoleState.Retrieving },
        )
        assertEquals(at + 4500, rig.time(MoleState.Failed))
        assertEquals(slide(7), rig.session.onScreen.value.target)
    }

    @Test
    fun announcesBackToBackAndAgainWhenAskedAfterAStop() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)))
                "and the costs" -> Move.Ask(listOf(slide(9)))
                "and the risks", "the risks again", "back to the risks" -> Move.Ask(listOf(slide(10)))
                "no" -> Move.Undo
                "show the roadmap" -> Move.Show(slide(11))
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        val margin = rig.announced("which brings us to the margin")
        rig.until(MoleState.Picked)
        val costs = rig.announced("and the costs") // while slide 8's lines still run
        assertEquals(costs, rig.time(MoleState.Retrieving, after = margin + 1), "the second announce does not wait for the first one's lines")
        assertTrue(rig.lines.none { it.second.title == "Slide 8 is ready." })
        rig.until(MoleState.Hidden)
        assertEquals(listOf(margin + 2500 to "show slide 8", costs + 2500 to "show slide 9"), rig.screen.calls)

        rig.say("and the risks")
        rig.until(MoleState.Retrieving)
        rig.say("no")
        rig.until(MoleState.Hidden)
        val risks = rig.announced("the risks again") // turned down, then asked for anew: announced again
        rig.until(MoleState.Hidden)
        assertEquals(listOf("show slide 8", "show slide 9", "show slide 10"), rig.screen.calls.map { it.second })
        assertEquals(risks + 2500, rig.screen.calls.last().first)
    }

    @Test
    fun thePhoneLeadsAnAnnounceAndStopsIt() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)))
                "and the costs" -> Move.Ask(listOf(slide(9)))
                "show them the hail damage photos" -> Move.Show(march)
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.phoneLeads.value = true
        rig.until(MoleState.Hello)
        rig.until(MoleState.Idle)
        val margin = rig.announced("which brings us to the margin", to = rig.phone)
        rig.say("show them the hail damage photos", to = rig.laptop) // not the one leading: not heard
        rig.phoneLeads.value = false // the lead changes hands inside the window: the announce keeps the line
        rig.until(MoleState.Picked)
        assertEquals(listOf(margin + 2500 to "show slide 8"), rig.screen.calls)
        val window = rig.lines.dropWhile { it.second.state != MoleState.Retrieving }.takeWhile { it.first < margin + 2500 }
        assertTrue(window.all { it.second.state == MoleState.Retrieving }, "no hello over the announce")
        rig.until(MoleState.Hidden)

        rig.phoneLeads.value = true
        rig.until(MoleState.Idle)
        rig.say("and the costs", to = rig.phone)
        rig.until(MoleState.Retrieving)
        delay(1.seconds)
        rig.session.pick(-1) // the phone's stop comes as a pick, like its rows
        assertEquals("Okay, not shown.", rig.until(MoleState.Back).title)
        delay(4.seconds)
        assertEquals(listOf(margin + 2500 to "show slide 8"), rig.screen.calls)
        assertTrue(rig.asked.none { "hail" in it.second.text })
    }

    @Test
    fun eachPieceIsAskedAboutWhenItIsHeardAndTheMoveGoesUpInTheAgentsTime() = runTest {
        val rig = Rig(this, slide(6)) { h, s ->
            delay(if (h.ended) 250 else 100)
            if (h.ended && h.text == "next slide please") Move.Show(slide((s.target as Target.Slide).page + 1)) else Move.Nothing
        }
        rig.start()
        delay(3.seconds)
        val start = currentTime
        rig.say("next slide please", every = 400.milliseconds)
        rig.until(MoleState.Hidden)
        assertEquals(listOf(start, start + 400, start + 800), rig.asked.map { it.first }, "each piece at once, and once")
        assertEquals(listOf(start + 800 + 250 to "show slide 7"), rig.screen.calls)
    }

    @Test
    fun rapidCommandsEachGoUpInTheAgentsTimeWithoutQueueing() = runTest {
        val rig = Rig(this, slide(1)) { h, s ->
            if (!h.ended) return@Rig Move.Nothing
            delay(250)
            if (h.text == "Next slide.") Move.Show(slide((s.target as Target.Slide).page + 1)) else Move.Nothing
        }
        rig.start()
        delay(3.seconds)
        val start = currentTime
        repeat(4) {
            rig.laptop.emit(Heard("Next slide.", ended = true))
            delay(600)
        }
        rig.until(MoleState.Hidden)
        assertEquals((0..3).map { start + it * 600 + 250 to "show slide ${it + 2}" }, rig.screen.calls)
        assertEquals(slide(5), rig.session.onScreen.value.target)
    }

    @Test
    fun aSentenceTheRecognizerEndedAndWordsAfterAMoveOnAPauseAreUtterancesOfTheirOwn() = runTest {
        val rig = Rig(this, slide(1)) { h, _ ->
            if (h.text.lowercase().trimEnd('.') in setOf("next slide", "previous slide") && (h.ended || h.quiet)) Move.Show(slide(10 + h.n)) else Move.Nothing
        }
        rig.start()
        delay(3.seconds)
        for (piece in listOf(Heard("Next slide. Next"), Heard("Next slide. Next slide", quiet = true), Heard("Next slide. Next slide.", ended = true))) {
            rig.laptop.emit(piece)
            delay(600)
        }
        delay(2.seconds)
        for (piece in listOf(Heard("Previous slide", quiet = true), Heard("Previous slide previous"), Heard("Previous slide previous slide.", ended = true))) {
            rig.laptop.emit(piece)
            delay(600)
        }
        delay(2.seconds)
        assertEquals(listOf("Next slide." to true, "Next slide." to false, "Previous slide" to true, "previous slide." to false),
            rig.asked.filter { it.second.ended }.map { it.second.text to it.second.more })
        assertEquals(listOf("show slide 11", "show slide 12", "show slide 13", "show slide 14"), rig.screen.calls.map { it.second })
    }

    @Test
    fun aSlowScreenKeepsThePresenterHeardAndANoOrANewCommandMeanwhileIsNotLost() = runTest {
        val rig = Rig(this, slide(6)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "open the gripper video" -> Move.Show(youtube)
                "no, not that" -> Move.Undo
                "show slide nine" -> Move.Show(slide(9))
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)

        // a call that answers after 3 s: the no said during it takes it back the moment it is up
        rig.screen.latency = 3.seconds
        val open = currentTime
        rig.laptop.emit(Heard("open the gripper video", ended = true))
        delay(1.seconds)
        rig.screen.latency = 30.milliseconds
        rig.laptop.emit(Heard("no, not that", ended = true))
        rig.mole.line.first { it.title == "Slide 6 is back." }
        assertEquals(open + 1000, rig.decided("no, not that"), "heard and decided while the call runs")
        rig.until(MoleState.Hidden)
        assertEquals(listOf(open to "show Robot gripper demo", open + 3000 to "close"), rig.screen.calls)
        assertEquals(slide(6), rig.session.onScreen.value.target)

        // a call that takes 10 s gives up after STALL; the no said during it has nothing to take back, and the
        // command said after it goes up at once
        rig.screen.latency = 10.seconds
        val slow = currentTime
        rig.laptop.emit(Heard("open the gripper video", ended = true))
        delay(2.seconds)
        rig.screen.latency = 30.milliseconds
        rig.laptop.emit(Heard("no, not that", ended = true))
        delay(1.seconds)
        rig.laptop.emit(Heard("show slide nine", ended = true))
        assertEquals(MoleLine(MoleState.Failed, "Couldn't show Robot gripper demo.", "The shared screen didn't answer."), rig.until(MoleState.Failed))
        assertEquals(slow + STALL.inWholeMilliseconds, currentTime)
        assertEquals(slow + 2000, rig.decided("no, not that"))
        assertEquals(slow + 3000, rig.decided("show slide nine"))
        rig.until(MoleState.Hidden)
        assertEquals(listOf(slow to "show Robot gripper demo", slow + STALL.inWholeMilliseconds to "show slide 9"), rig.screen.calls.drop(2))
        assertEquals(slide(9), rig.session.onScreen.value.target)
        assertEquals("Slide 9 is ready.", rig.line(MoleState.Ready).title)
    }

    @Test
    fun aCallThatNeverAnswersFailsAfterTheStallAndTheNextOneGoesUp() = runTest {
        val rig = Rig(this, slide(6)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "close that" -> Move.Control("close")
                "show slide nine" -> Move.Show(slide(9))
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.screen.latency = Duration.INFINITE
        val close = currentTime
        rig.laptop.emit(Heard("close that", ended = true))
        assertEquals(MoleLine(MoleState.Failed, "Couldn't close.", "The shared screen didn't answer."), rig.until(MoleState.Failed))
        assertEquals(close + STALL.inWholeMilliseconds, currentTime)
        assertEquals(
            listOf(MoleState.Heard to null, MoleState.Working to "On it…", MoleState.Failed to "Couldn't close."),
            rig.lines.map { it.second.state to it.second.title }.dropWhile { it.first != MoleState.Heard },
            "not tried again: it stalled",
        )
        assertEquals(slide(6), rig.session.onScreen.value.target)
        rig.screen.latency = 30.milliseconds
        rig.until(MoleState.Hidden)
        rig.say("show slide nine")
        rig.until(MoleState.Hidden)
        assertEquals(listOf(close to "close", rig.decided("show slide nine") to "show slide 9"), rig.screen.calls)
        assertEquals(slide(9), rig.session.onScreen.value.target)
    }

    @Test
    fun anAnnounceWaitsAsLongAsThePresenterChose() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)))
                "and the costs" -> Move.Ask(listOf(slide(9)))
                "and the risks" -> Move.Ask(listOf(slide(10)))
                "no, not that" -> Move.Undo
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        for ((said, ms) in listOf("which brings us to the margin" to 2500L, "and the costs" to 1500L)) {
            rig.window.value = ms.milliseconds
            val at = rig.announced(said)
            assertEquals(ms, rig.mole.line.value.timeoutMs, "the bar's and the phone's Stop drain for as long")
            rig.until(MoleState.Hidden)
            assertEquals(at + ms, rig.screen.calls.last().first)
        }
        rig.window.value = Duration.ZERO // none: shown at once, and a no takes it back after
        rig.say("and the risks")
        rig.until(MoleState.Ready)
        rig.say("no, not that")
        rig.until(MoleState.Hidden)
        val risks = rig.decided("and the risks")
        assertEquals(listOf(risks to "show slide 10", rig.decided("no, not that") to "show slide 9"), rig.screen.calls.drop(2))
        assertEquals(2, rig.lines.count { it.second.state == MoleState.Retrieving }, "no announce without a window")
        assertEquals(slide(9), rig.session.onScreen.value.target)
    }

    @Test
    fun aPickWaitsAsLongAsTheAgentDoubtsItAndASureOneGoesUpAtOnce() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "which brings us to the margin" -> Move.Ask(listOf(slide(8)), doubt = 0.0)
                "and the costs" -> Move.Ask(listOf(slide(9)), doubt = 0.5)
                "and the risks" -> Move.Ask(listOf(slide(10)), doubt = 1.0)
                "and the team" -> Move.Ask(listOf(slide(11)), doubt = 0.5)
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("which brings us to the margin")
        rig.until(MoleState.Hidden)
        assertEquals(listOf(rig.decided("which brings us to the margin") to "show slide 8"), rig.screen.calls, "sure: at once, like a command")
        assertEquals(listOf(MoleState.Heard, MoleState.Ready, MoleState.Back, MoleState.Hidden), rig.states().dropWhile { it != MoleState.Heard })
        assertEquals("Showing slide 8" to "08", rig.line(MoleState.Heard).let { it.title to it.card }, "the bar says what went up")

        for ((said, ms) in listOf("and the costs" to 1250L, "and the risks" to 2500L)) {
            val at = rig.announced(said)
            assertEquals(ms, rig.mole.line.value.timeoutMs, "the bar's and the phone's Stop drain over the wait itself")
            rig.until(MoleState.Hidden)
            assertEquals(at + ms, rig.screen.calls.last().first)
        }
        rig.window.value = 1500.milliseconds
        val team = rig.announced("and the team")
        assertEquals(1000L, rig.mole.line.value.timeoutMs, "half of 1.5 s is too short to stop: the floor")
        rig.until(MoleState.Hidden)
        assertEquals(team + 1000, rig.screen.calls.last().first)
    }

    @Test
    fun aSurePickSaysWhatWentUpAndANoTakesItBack() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "here is the robot" -> Move.Ask(listOf(youtube), doubt = 0.0)
                "no, go back" -> Move.Undo
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.say("here is the robot")
        rig.until(MoleState.Hidden)
        assertEquals(listOf("Showing Robot gripper demo", "Robot gripper demo is on screen."),
            rig.lines.filter { it.second.state == MoleState.Heard || it.second.state == MoleState.Ready }.map { it.second.title })
        delay(10.seconds)
        rig.say("no, go back")
        assertEquals("Slide 7 is back.", rig.until(MoleState.Ready).title)
        assertEquals(listOf("show Robot gripper demo", "close"), rig.screen.calls.map { it.second })
        assertEquals(slide(7), rig.session.onScreen.value.target)
    }

    @Test
    fun aPickTurnedDownIsAnnouncedAgainHoweverSure() = runTest {
        val rig = Rig(this, slide(7)) { h, _ ->
            if (!h.ended) return@Rig Move.Nothing
            when (h.text) {
                "and the risks" -> Move.Ask(listOf(slide(10)))
                "the risks again" -> Move.Ask(listOf(slide(10)), doubt = 0.0)
                "here is the robot" -> Move.Ask(listOf(youtube), doubt = 0.0)
                "no", "no, go back" -> Move.Undo
                else -> Move.Nothing
            }
        }
        rig.start()
        delay(3.seconds)
        rig.announced("and the risks")
        rig.say("no")
        rig.until(MoleState.Hidden)
        val again = rig.announced("the risks again") // stopped a moment ago: the whole window
        assertEquals(2500L, rig.mole.line.value.timeoutMs)
        rig.until(MoleState.Hidden)

        rig.say("here is the robot")
        rig.until(MoleState.Hidden)
        val first = rig.decided("here is the robot")
        rig.say("no, go back")
        rig.until(MoleState.Hidden)
        val taken = rig.decided("no, go back")
        val robot = rig.announced("here is the robot") // taken back a moment ago: the whole window too
        assertEquals(2500L, rig.mole.line.value.timeoutMs)
        rig.until(MoleState.Hidden)
        rig.say("no, go back")
        rig.until(MoleState.Hidden)
        delay(UNDO)
        rig.say("here is the robot") // long enough ago: sure again
        rig.until(MoleState.Hidden)
        assertEquals(
            listOf(again + 2500 to "show slide 10", first to "show Robot gripper demo", taken to "close", robot + 2500 to "show Robot gripper demo",
                rig.decided("no, go back") to "close", rig.decided("here is the robot") to "show Robot gripper demo"),
            rig.screen.calls,
        )
    }
}
