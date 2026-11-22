package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.Heard
import ch.lauzhack.app.Move
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared screen as AgentDesktop and the session change it: slides and pictures on the stage, the rest in front. */
internal class FakeDesk(start: Target? = null) {
    private var stage: Target? = start
    private var front: Target? = null
    private val before = ArrayDeque<Pair<Target?, Target?>>()
    val onScreen get() = OnScreen(front ?: stage)

    fun apply(m: Move) {
        when {
            m is Move.Show -> {
                before += stage to front
                val t = m.target
                if (t is Target.Slide || t is Target.File && onStage(Catalog.file(t.path))) { stage = t; front = null } else front = t
            }
            m == Move.Control("close") -> {
                before += stage to front
                if (front != null) front = null else stage = null
            }
            m == Move.Undo -> before.removeLastOrNull()?.let { (s, f) -> stage = s; front = f }
        }
    }
}

/** Every text the same vector: candidates come in catalog order. */
internal object Flat : Embedder {
    override suspend fun embed(text: String) = Vector(listOf(1.0, 0.0))
    override fun diff(embedding1: Vector, embedding2: Vector) = 1 - embedding1.cosineSimilarity(embedding2)
}

class ModelAgentTest {
    private fun slide(p: Int) = Item.Slide("Talk", p, 3, "Title $p", "point $p", Target.Slide("Talk", p, "Title $p"))
    private val github = Item.Site("GitHub", "github.com", Target.Site("https://github.com", "GitHub"))
    private val notes = Item.App("Notes", Target.App("Notes"))
    private val plan = Catalog.file("/u/Docs/Plan.pdf")
    private val photo = Catalog.file("/u/Pictures/House.jpg")
    private val catalog = Catalog(listOf(slide(1), slide(2), slide(3), github, notes, photo, plan), List(7) { floatArrayOf(1f, 0f) })

    /** A model that answers from a script and records what it was asked. */
    private inner class Run(start: Target? = null, val apply: Boolean = true) {
        val desk = FakeDesk(start)
        val asked = mutableListOf<Pair<String, String?>>()
        val answers = ArrayDeque<String>()
        val agent = ModelAgent(catalog, Flat) { p, g -> asked += p to g; answers.removeFirst() }
        private var n = 0
        private val said = mutableListOf<String>()

        /** One utterance, two words at a time as the evals replay it; each call's move, with [answers] for the calls that reach the model. */
        fun say(text: String, vararg answer: String): List<Move> = runBlocking {
            answers += answer
            n++
            val w = text.split(' ')
            val moves = ((2 until w.size step 2) + w.size).map { k ->
                val heard = Heard(w.take(k).joinToString(" "), k == w.size, n, said.takeLast(3))
                agent.anythingToDo(heard, desk.onScreen).also { if (apply) desk.apply(it) }
                    .also { assertEquals(Move.Nothing, agent.anythingToDo(heard, desk.onScreen), "polled again with the same words") }
            }
            said += text
            assertTrue(answers.isEmpty(), "unused answers $answers")
            moves
        }

        val prompt get() = asked.last().first
    }

    @Test
    fun twoPartialsInARowActAndDoneDoesNotRepeatIt() {
        val r = Run(slide(1).target, apply = false)
        assertEquals(listOf(Move.Nothing, Move.Show(github.target), Move.Nothing), r.say("can you open github please", "show 4", "show 4", "show 4"))
        assertEquals(listOf(null, null), r.asked.take(2).map { it.second }, "partials are unconstrained")
        assertEquals(grammar(State(screen = slide(1), candidates = catalog.items.take(6))), r.asked.last().second, "done is constrained")
    }

    @Test
    fun partialsDisagreeingOrOfTheWrongKindWaitForTheEnd() {
        val r = Run(slide(1).target)
        assertEquals(listOf(Move.Nothing, Move.Nothing, Move.Show(notes.target)), r.say("one two three four five", "show 4", "show 5", "show 5"))
        assertEquals(listOf(Move.Nothing, Move.Nothing, Move.Show(search("cold storage"))), r.say("look up cold storage robots please", "search cold storage", "search cold storage", "search cold storage"))
        assertEquals(listOf(Move.Nothing, Move.Nothing, Move.Unsure), r.say("our internal dash board please", "unsure", "unsure", "unsure"))
    }

    @Test
    fun decidesEveryTwoWordsAndOnceAtTheEnd() = runBlocking {
        val r = Run()
        r.answers += listOf("nothing", "nothing", "nothing")
        val w = "so this is the plan".split(' ')
        for (k in 1..w.size) r.agent.anythingToDo(Heard(w.take(k).joinToString(" "), false, 1), r.desk.onScreen)
        r.agent.anythingToDo(Heard(w.joinToString(" "), true, 1), r.desk.onScreen)
        r.agent.anythingToDo(Heard(w.joinToString(" "), true, 1), r.desk.onScreen)
        assertEquals(listOf("so this", "so this is the", "so this is the plan"), r.asked.map { it.first.lines().first { l -> l.startsWith("now") }.substringAfter("): ") })
    }

    @Test
    fun anOfferLastsThreeUtterancesUnlessSomethingIsDone() {
        val r = Run()
        assertEquals(Move.Ask(listOf(github.target, photo.target)), r.say("show that", "ask 4 6").last())
        repeat(3) {
            r.say("hmm", "nothing")
            assertTrue("offered: 1 2" in r.prompt)
            assertEquals(listOf("1 site GitHub (github.com)", "2 file \"House.jpg\" in u / Pictures"), r.prompt.lines().filter { it[0].isDigit() }.take(2))
        }
        r.say("hmm", "nothing")
        assertTrue("offered: none" in r.prompt)

        r.say("show that", "ask 4 6")
        r.say("the second", "show 2")
        r.say("good", "nothing")
        assertTrue("offered: none" in r.prompt, "an action clears the offer")
    }

    @Test
    fun whatWasShownIsShownForTwoUtterances() {
        val r = Run(slide(1).target)
        r.say("github", "show 4")
        r.say("hmm", "nothing")
        assertTrue("shown: site GitHub (github.com)" in r.prompt && r.asked.last().second!!.endsWith("|undo"))
        r.say("hmm", "nothing")
        assertTrue("shown: site GitHub (github.com)" in r.prompt)
        r.say("hmm", "nothing")
        assertTrue("shown: none" in r.prompt && !r.asked.last().second!!.contains("undo"))
    }

    @Test
    fun aPickTappedIsShownAndEndsTheOffer() {
        val r = Run(slide(1).target)
        r.say("hmm", "nothing")
        assertTrue("shown: none" in r.prompt, "the screen the meeting starts on was not shown by anyone")
        r.say("show that", "ask 4 6")
        r.desk.apply(Move.Show(github.target))
        r.say("great", "nothing")
        assertTrue("offered: none\nshown: site GitHub (github.com)\n" in r.prompt)
    }

    @Test
    fun theDeckBehindWhatCoversIt() {
        val r = Run(slide(2).target)
        r.say("github", "show 4")
        r.say("next", "next")
        assertTrue(r.prompt.startsWith("screen: site GitHub (github.com) over slide 2 of 3 \"Title 2\" in Talk\n"))
        assertEquals(OnScreen(slide(3).target), r.desk.onScreen)
        r.say("github", "show 4")
        assertEquals(listOf(Move.Show(slide(3).target)), r.say("slides", "slides"))
        r.say("the photo", "show 6")
        r.say("hmm", "nothing")
        assertTrue(r.prompt.startsWith("screen: file \"House.jpg\" in u / Pictures\n"), "a picture replaces the deck on the stage")
    }

    /** The move [answer] makes, at the end of an utterance, with GitHub just shown over slide 2. */
    private fun mapped(answer: String, start: Target = slide(2).target): Move {
        val r = Run(start)
        if (start == slide(2).target) r.say("github", "show 4")
        return r.say("do it", answer).last()
    }

    @Test
    fun everyAnswerOfTheGrammar() {
        assertEquals(Move.Nothing, mapped("nothing"))
        assertEquals(Move.Wait, mapped("wait"))
        assertEquals(Move.Unsure, mapped("unsure"))
        assertEquals(Move.Show(slide(3).target), mapped("show 3"))
        assertEquals(Move.Ask(listOf(slide(1).target, slide(2).target)), mapped("ask 1 2"))
        assertEquals(Move.Ask(listOf(slide(2).target, notes.target)), mapped("ask 2 2 5"))
        assertEquals(Move.Show(slide(1).target), mapped("slide 1"))
        assertEquals(Move.Show(slide(2).target), mapped("slide 2"))
        assertEquals(Move.Show(slide(3).target), mapped("next"))
        assertEquals(Move.Show(slide(1).target), mapped("previous"))
        assertEquals(Move.Show(slide(2).target), mapped("slides"))
        assertEquals(Move.Show(notes.target), mapped("open notes"))
        assertEquals(Move.Show(Target.Site("https://example.org/a", "example.org/a")), mapped("open example.org/a"))
        assertEquals(Move.Unsure, mapped("open nonsense"))
        assertEquals(Move.Show(Target.Site("https://www.google.com/search?q=cold+storage+robot%27s", "cold storage robot's")), mapped("search cold storage robot's"))
        assertEquals(Move.Control("click:sign up"), mapped("click sign up"))
        assertEquals(Move.Control("scroll down"), mapped("control scroll down"))
        assertEquals(Move.Control("close"), mapped("close"))
        assertEquals(Move.Undo, mapped("undo"))
        assertEquals(Move.Show(github.target), mapped("open github", slide(1).target))
        assertEquals(Move.Show(github.target), mapped("open github.com", slide(1).target))
        assertEquals(Move.Nothing, mapped("show 1", slide(1).target), "already on screen")
    }

    @Test
    fun slideNumbersOutOfTheDeckDoNothing() {
        assertEquals(Move.Nothing, mapped("slide 4"))
        assertEquals(Move.Nothing, mapped("slide 99"))
        assertEquals(Move.Nothing, mapped("next", slide(3).target))
        assertEquals(Move.Nothing, mapped("previous", slide(1).target))
        assertEquals(Move.Show(slide(3).target), mapped("slide 3", slide(1).target))
    }

    @Test
    fun anAnswerOutsideTheGrammarIsNothing() {
        assertEquals(Move.Nothing, mapped("show 9"))
        assertEquals(Move.Nothing, mapped("click sign up", slide(1).target), "no click on a slide")
        assertEquals(Move.Nothing, mapped("undo", slide(1).target), "nothing shown to undo")
        assertEquals(Move.Nothing, mapped("hello there"))
        assertEquals(Move.Nothing, Run().say("show me the plan now", "show 6 please", "show 6 please", "nothing").last())
        assertEquals(listOf(Move.Nothing, Move.Nothing), Run().say("close it now", "close", "close"), "nothing to close on the desktop")
    }

    @Test
    fun aNewerUtteranceDropsADecisionInFlight() = runBlocking(Dispatchers.Default) {
        val hold = CompletableDeferred<String>()
        val started = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val agent = ModelAgent(catalog, Flat) { p, _ ->
            asked += p
            if ("now (done): open github" in p) started.complete(Unit).let { hold.await() } else "show 5"
        }
        val first = async { agent.anythingToDo(Heard("open github", true, 1), OnScreen()) }
        started.await()
        val second = agent.anythingToDo(Heard("open notes", true, 2, listOf("open github")), OnScreen())
        hold.complete("show 4")
        assertEquals(Move.Nothing, first.await())
        assertEquals(Move.Show(notes.target), second)
        assertNull(asked.getOrNull(2))
    }
}
