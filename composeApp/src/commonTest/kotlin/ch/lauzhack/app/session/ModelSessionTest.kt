@file:OptIn(ExperimentalCoroutinesApi::class)

package ch.lauzhack.app.session

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.Target
import ch.lauzhack.app.decider.Catalog
import ch.lauzhack.app.decider.Item
import ch.lauzhack.app.decider.ModelAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun page(p: Int, title: String) = Item.Slide("Talk", p, 4, title, "", Target.Slide("Talk", p, title))
private val pages = listOf(page(1, "Intro"), page(2, "The problem"), page(3, "Roadmap"), page(4, "Costs"))
private val PACE = 400.milliseconds // 150 words a minute

/** Every text the same vector: the candidates are the slides in order. */
private object Same : Embedder {
    override suspend fun embed(text: String) = Vector(listOf(1.0, 0.0))
    override fun diff(embedding1: Vector, embedding2: Vector) = 1 - embedding1.cosineSimilarity(embedding2)
}

/** A session of the model agent on virtual time, its model scripted: 200 ms an answer, "show 3" once it hears of the
 * roadmap, "show 4" of the costs, "next" for the next slide, and "undo" for a no only when what was shown is the screen,
 * as the decider does. */
private class Talk(scope: TestScope) {
    val prompts = mutableListOf<String>()
    val agent = ModelAgent(Catalog(pages, List(4) { floatArrayOf(1f, 0f) }), Same, { p, _ ->
        delay(200)
        prompts += p
        val now = p.lines().first { it.startsWith("now (") }.substringAfter("): ")
        val (screen, shown) = listOf("screen: ", "shown: ").map { k -> p.lines().first { it.startsWith(k) }.substringAfter(k) }
        when {
            now.substringBefore(' ').trimEnd(',') == "no" -> if (shown == screen) "undo" else "nothing"
            "costs" in now -> "show 4"
            "roadmap" in now -> "show 3"
            "next slide" in now -> "next"
            else -> "nothing"
        }
    }, scope.testTimeSource)
    val rig = Rig(scope, pages[0].target) { h, s -> agent.anythingToDo(h, s) }

    fun prompt(said: String) = prompts.last { "): $said" in it }
}

class ModelSessionTest {
    @Test
    fun aPickIsAnnouncedWhileThePresenterSpeaksAndShownOnceItsWindowEnds() = runTest {
        val t = Talk(this)
        t.rig.start()
        delay(3.seconds)
        val said = "so let us look at the roadmap we drew for the coming year"
        val start = currentTime
        t.rig.say(said, every = PACE)
        val lastWord = start + (said.split(' ').size - 1) * PACE.inWholeMilliseconds
        t.rig.until(MoleState.Hidden)
        val announced = t.rig.time(MoleState.Retrieving)
        assertTrue(announced < lastWord, "announced while the presenter still speaks, on two partial decisions that agree")
        assertEquals("Showing slide 3", t.rig.line(MoleState.Retrieving).title)
        assertEquals(1, t.rig.lines.count { it.second.state == MoleState.Retrieving }, "the same pick at the end is not announced again")
        assertEquals(listOf(announced + ANNOUNCE.inWholeMilliseconds to "show slide 3"), t.rig.screen.calls)
    }

    @Test
    fun aNoAsTheNextUtteranceStopsTheAnnounce() = runTest {
        val t = Talk(this)
        t.rig.start()
        delay(3.seconds)
        t.rig.say("show the roadmap")
        t.rig.until(MoleState.Retrieving)
        t.rig.say("no")
        assertEquals("Okay, not shown.", t.rig.until(MoleState.Back).title)
        val roadmap = "slide 3 of 4 \"Roadmap\" in Talk"
        assertTrue(t.prompt("no").startsWith("screen: $roadmap\noffered: none\nshown: $roadmap\n"), "the announce is on screen as far as the agent knows")
        delay(5.seconds)
        assertEquals(emptyList(), t.rig.screen.calls)
    }

    @Test
    fun aNoThatTwoPartialsAgreeOnStopsTheAnnounceMidSentence() = runTest {
        val t = Talk(this)
        t.rig.start()
        delay(3.seconds)
        t.rig.say("show the roadmap")
        t.rig.until(MoleState.Retrieving)
        val announced = t.rig.time(MoleState.Retrieving)
        val start = currentTime
        t.rig.say("no no wait not that one", every = PACE)
        val stopped = t.rig.time(MoleState.Back)
        assertTrue(stopped < start + 5 * PACE.inWholeMilliseconds, "stopped before the last word")
        assertTrue(stopped < announced + ANNOUNCE.inWholeMilliseconds)
        delay(5.seconds)
        assertEquals(emptyList(), t.rig.screen.calls)
    }

    @Test
    fun aNoOnceItIsShownTakesItBack() = runTest {
        val t = Talk(this)
        t.rig.start()
        delay(3.seconds)
        t.rig.say("show the roadmap")
        t.rig.until(MoleState.Ready)
        t.rig.until(MoleState.Hidden)
        t.rig.say("no not that")
        assertEquals("Slide 1 is back.", t.rig.until(MoleState.Ready).title)
        assertEquals(listOf("show slide 3", "show slide 1"), t.rig.screen.calls.map { it.second })
        assertEquals(pages[0].target, t.rig.session.onScreen.value.target)
    }

    @Test
    fun navigationIsImmediate() = runTest {
        val t = Talk(this)
        t.rig.start()
        delay(3.seconds)
        t.rig.say("next slide please")
        t.rig.until(MoleState.Hidden)
        assertEquals(listOf(t.rig.decided("next slide please") + 200 to "show slide 2"), t.rig.screen.calls)
        assertTrue(t.rig.lines.none { it.second.state == MoleState.Retrieving })
    }

    @Test
    fun aDifferentPickAtTheEndReplacesTheAnnounce() = runTest {
        val t = Talk(this)
        t.rig.start()
        delay(3.seconds)
        val said = "so let us look at the roadmap or rather at the costs"
        val start = currentTime
        t.rig.say(said, every = PACE)
        t.rig.until(MoleState.Hidden)
        val announces = t.rig.lines.filter { it.second.state == MoleState.Retrieving }
        assertEquals(listOf("Showing slide 3", "Showing slide 4"), announces.map { it.second.title })
        assertTrue(announces[0].first < start + (said.split(' ').size - 1) * PACE.inWholeMilliseconds, "the first while speaking")
        assertEquals(listOf(announces[1].first + ANNOUNCE.inWholeMilliseconds to "show slide 4"), t.rig.screen.calls, "slide 3 never")
    }

    @Test
    fun anAnnounceStoppedOnTheBarIsNotWhatTheAgentLastShowed() = runTest {
        val t = Talk(this)
        t.rig.start()
        delay(3.seconds)
        t.rig.say("next slide please")
        t.rig.until(MoleState.Heard)
        val next = currentTime
        t.rig.say("show the roadmap")
        t.rig.until(MoleState.Retrieving)
        t.rig.session.pick(-1) // the screen never changes
        t.rig.until(MoleState.Back)
        delay(4500.milliseconds)
        t.rig.say("no not that") // inside the undo of the next slide, which a "no" about the roadmap must not take back
        delay(3.seconds)
        assertTrue(t.rig.decided("no not that") < next + UNDO.inWholeMilliseconds)
        assertEquals(listOf("show slide 2"), t.rig.screen.calls.map { it.second })
        val p = t.prompt("no not that")
        assertTrue(p.startsWith("screen: slide 2 of 4 \"The problem\" in Talk\noffered: none\nshown: none\n"), p)
    }
}
