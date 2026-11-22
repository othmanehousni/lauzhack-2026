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
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The shared screen as AgentDesktop and the session change it: slides and pictures on the stage, the rest in front; an
 * announce is shown as if its window had run out. */
internal class FakeDesk(start: Target? = null) {
    private var stage: Target? = start
    private var front: Target? = null
    private val before = ArrayDeque<Pair<Target?, Target?>>()
    val onScreen get() = OnScreen(front ?: stage)

    fun apply(m: Move) {
        val t = (m as? Move.Show)?.target ?: (m as? Move.Ask)?.options?.singleOrNull()
        when {
            t != null -> {
                before += stage to front
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
    private val catalog = Catalog(listOf(slide(1), slide(2), slide(3), github, notes, photo, plan), List(7) { floatArrayOf(1f, 0f) }, meeting = setOf("Talk"))
    private fun ask(c: Item) = Move.Ask(listOf(c.target))

    /** A model that answers from a script and records what it was asked. */
    private inner class Run(start: Target? = null, val apply: Boolean = true) {
        val desk = FakeDesk(start)
        val asked = mutableListOf<Pair<String, String?>>()
        val answers = ArrayDeque<String>()
        val agent = ModelAgent(catalog, Flat, { p, g -> asked += p to g; answers.removeFirst() })
        var n = 0
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

    private fun scored(vararg s: Double) = Catalog(listOf(slide(1), slide(2), github, notes), s.map { floatArrayOf(it.toFloat(), sqrt(1 - it * it).toFloat()) }, meeting = setOf("Talk"))

    @Test
    fun aPickIsSureWhenItLeadsTheOthersAndScoresAsAMatchDoes() {
        val r = Run(apply = false)
        fun doubt(answer: String) = (r.say("that", answer).single() as Move.Ask).doubt
        r.agent.catalog = scored(0.82, 0.71, 0.77, 0.60) // candidates by score: slide 1, GitHub, slide 2, Notes
        assertEquals(listOf(0.0, 1.0, 1.0), listOf(doubt("show 1"), doubt("show 2"), doubt("search the market")), "a search has no score")
        assertEquals(listOf(0.0, 1.0), listOf(doubt("open github"), doubt("open gitub")), "a name said exactly needs no lead, one heard nearly does")
        r.agent.catalog = scored(0.80, 0.71, 0.78, 0.60)
        assertEquals(listOf(0.5, 0.5, 1.0), listOf(doubt("show 1"), doubt("show 2"), doubt("show 3")), "the best by a little, within 0.03 of it, far behind")
        r.agent.catalog = scored(0.73, 0.60, 0.62, 0.60)
        assertEquals(0.5, doubt("show 1"), "far ahead, scoring as a likely match")
        r.agent.catalog = scored(0.71, 0.60, 0.62, 0.60)
        assertEquals(1.0, doubt("show 1"), "far ahead, but scoring as anything does: a request for what is not there")
    }

    @Test
    fun aCandidateItsScoresDoNotBackIsNotShown() {
        val r = Run()
        r.agent.catalog = scored(0.69, 0.60, 0.65, 0.60)
        assertEquals(listOf(Move.Nothing, Move.Nothing, Move.Unsure), r.say("show me that one now", "show 1", "show 1", "show 1"), "not even while it is said")
        assertEquals(1.0, (r.say("open notes", "open notes").single() as Move.Ask).doubt, "the presenter asked to open it")
        r.say("which one", "ask 1 3")
        assertEquals(Move.Show(slide(1).target), r.say("the first", "show 1").last(), "a candidate offered is the presenter's choice")
        assertEquals(Move.Show(slide(2).target), r.say("next", "next").last())
    }

    @Test
    fun twoPartialsInARowActAndDoneDoesNotRepeatIt() {
        val r = Run(slide(1).target, apply = false)
        assertEquals(listOf(Move.Nothing, ask(github), Move.Nothing), r.say("can you open github please", "show 4", "show 4", "show 4"))
        assertEquals(listOf(null, null), r.asked.take(2).map { it.second }, "partials are unconstrained")
        assertEquals(grammar(State(screen = slide(1), candidates = catalog.items)), r.asked.last().second, "done is constrained")
    }

    @Test
    fun untilItsAnnounceIsOnScreenItDecidesOnEveryWordAndANoTwoPartialsAgreeOnStopsIt() = runBlocking {
        val r = Run(slide(1).target, apply = false) // the announce's window has not run out
        r.say("github", "show 4")
        r.answers += listOf("undo", "undo", "nothing")
        val w = "no no not that".split(' ')
        val moves = w.indices.map { k -> r.agent.anythingToDo(Heard(w.take(k + 1).joinToString(" "), k == w.lastIndex, r.n + 1, listOf("github")), r.desk.onScreen) }.also { r.n++ }
        assertEquals(listOf(Move.Nothing, Move.Undo, Move.Nothing, Move.Nothing), moves)
        assertEquals(listOf("no", "no no", "no no not that"), r.asked.drop(1).map { it.first.lines().first { l -> l.startsWith("now") }.substringAfter("): ") },
            "every word until the no, every two words after it")
        assertTrue("\nshown: site GitHub (github.com)\n" in r.asked[1].first)
        r.say("hmm", "nothing")
        assertTrue("\nshown: none\n" in r.prompt, "stopped, it was never shown")
    }

    /** Each piece of one utterance as the ear sends it, with the model's answer for each call that reaches it; the
     * moves, and those the rule before [Heard.quiet] would have made: the same pieces without the quiet ones. */
    private fun heard(vararg pieces: Pair<Heard, String?>): Pair<List<Move>, List<Move>> {
        fun run(ps: List<Pair<Heard, String?>>): List<Move> {
            val r = Run(slide(1).target)
            r.answers += ps.mapNotNull { it.second }
            return ps.map { (h, _) -> runBlocking { r.agent.anythingToDo(h.copy(n = 1), r.desk.onScreen).also(r.desk::apply) } }
                .also { assertTrue(r.answers.isEmpty(), "unused answers ${r.answers}") }
        }
        return run(pieces.toList()) to run(pieces.filter { !it.first.quiet || it.first.ended })
    }

    private fun live(text: String) = Heard(text)
    private fun quiet(text: String) = Heard(text, quiet = true)
    private fun end(text: String) = Heard(text, ended = true)

    @Test
    fun whenTheVoiceStopsOnWordsDecidedTheirAnswerActsWithoutAskingAgain() {
        val (now, before) = heard(live("Next slide, please") to "next", quiet("Next slide, please.") to null, end("Next slide, please.") to "next")
        assertEquals(listOf(Move.Nothing, Move.Show(slide(2).target), Move.Nothing), now, "the end does not repeat it")
        assertEquals(listOf(Move.Nothing, Move.Show(slide(2).target)), before, "before, it waited for the end")
    }

    @Test
    fun whenTheVoiceStopsOnNewWordsTheyAreDecidedAndActAtOnce() {
        val (now, _) = heard(live("Go to") to "nothing", quiet("Go to slide 3.") to "slide 3", end("Go to slide 3.") to "slide 3")
        assertEquals(listOf(Move.Nothing, Move.Show(slide(3).target), Move.Nothing), now)
        val (pick, _) = heard(quiet("So how big is this market really?") to "show 3", end("So how big is this market, really?") to "show 3")
        assertEquals(listOf(ask(slide(3)), Move.Nothing), pick, "a pick is announced as soon as the voice stops")
    }

    @Test
    fun theEndCorrectsAnEarlyAnswer() {
        val (now, _) = heard(quiet("Next slide") to "next", end("Next slide three") to "slide 3")
        assertEquals(listOf(Move.Show(slide(2).target), Move.Show(slide(3).target)), now)
    }

    @Test
    fun aQuietWaitOrNothingDoesNotAct() {
        val (now, _) = heard(quiet("Open the") to "wait", quiet("Open the.") to null, end("Open the.") to "wait")
        assertEquals(listOf(Move.Nothing, Move.Nothing, Move.Wait), now)
    }

    /** Words that go on after a command-like start, with the answers the model gave them on the lever's audio: the
     * voice stops only after the rest, so nothing acts before it is heard, as before. */
    @Test
    fun wordsThatGoOnAfterACommandLikeStartDoNotActEarly() {
        val pricing = heard(live("Next slide") to "next", live("Next slide is about") to "nothing", quiet("Next slide is about pricing") to "nothing", end("Next slide is about pricing.") to "nothing")
        assertEquals(List(4) { Move.Nothing } to List(3) { Move.Nothing }, pricing)
        val percent = heard(live("Close to") to "close", live("Close to 40% of") to "nothing", quiet("Close to 40% of our customers renew every year") to "nothing",
            end("Close to 40% of our customers renew every year") to "nothing")
        assertEquals(List(4) { Move.Nothing } to List(3) { Move.Nothing }, percent)
        // a second's pause before the number: the voice stops on "Go to slide", whose answer is not one that acts early;
        // the number comes while it is quiet again, and the analyzer then rewrites it
        val (now, before) = heard(live("Go to Slide") to "wait", quiet("Go to Slide.") to null, quiet("Go to slide. 3.") to "slide 3",
            quiet("Go to slide. Three.") to "slide 3", end("Go to slide. Three.") to "slide 3")
        assertEquals(listOf(Move.Nothing, Move.Nothing, Move.Show(slide(3).target), Move.Nothing, Move.Nothing), now)
        assertEquals(listOf(Move.Nothing, Move.Show(slide(3).target)), before)
    }

    @Test
    fun partialsDisagreeingOrOfTheWrongKindWaitForTheEnd() {
        val r = Run(slide(1).target)
        assertEquals(listOf(Move.Nothing, Move.Nothing, ask(notes)), r.say("one two three four five", "show 4", "show 5", "show 5"))
        assertEquals(listOf(Move.Nothing, Move.Nothing, Move.Ask(listOf(search("cold storage")))), r.say("look up cold storage robots please", "search cold storage", "search cold storage", "search cold storage"))
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
        assertEquals(Move.Show(photo.target), r.say("the second", "show 2").last(), "a pick from the offer is shown at once, not announced")
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
        assertEquals(ask(slide(3)), mapped("show 3"))
        assertEquals(Move.Ask(listOf(slide(1).target, slide(2).target)), mapped("ask 1 2"))
        assertEquals(Move.Ask(listOf(slide(2).target, notes.target)), mapped("ask 2 2 5"))
        assertEquals(Move.Show(slide(1).target), mapped("slide 1"))
        assertEquals(Move.Show(slide(2).target), mapped("slide 2"))
        assertEquals(Move.Show(slide(3).target), mapped("next"))
        assertEquals(Move.Show(slide(1).target), mapped("previous"))
        assertEquals(Move.Show(slide(2).target), mapped("slides"))
        assertEquals(ask(notes), mapped("open notes"))
        assertEquals(Move.Ask(listOf(Target.Site("https://example.org/a", "example.org/a"))), mapped("open example.org/a"))
        assertEquals(Move.Unsure, mapped("open nonsense"))
        assertEquals(Move.Ask(listOf(Target.Site("https://www.google.com/search?q=cold+storage+robot%27s", "cold storage robot's"))), mapped("search cold storage robot's"))
        assertEquals(Move.Control("click:sign up"), mapped("click sign up"))
        assertEquals(Move.Control("scroll down"), mapped("control scroll down"))
        assertEquals(Move.Control("close"), mapped("close"))
        assertEquals(Move.Undo, mapped("undo"))
        assertEquals(ask(github), mapped("open github", slide(1).target))
        assertEquals(ask(github), mapped("open github.com", slide(1).target))
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
    fun aSentenceCutByAPauseIsDecidedWholeIfThePauseWasAMomentAndNothingActedOnIt() = runBlocking {
        val time = TestTimeSource()
        val answers = ArrayDeque(listOf("nothing", "nothing", "wait", "nothing", "nothing", "next", "nothing"))
        val asked = mutableListOf<String>()
        val agent = ModelAgent(catalog, Flat, { p, _ -> asked += p; answers.removeFirst() }, time)
        val said = mutableListOf<String>()
        suspend fun say(text: String, after: Duration): String {
            time += after
            agent.anythingToDo(Heard(text, true, said.size + 1, said.takeLast(3)), OnScreen(slide(1).target))
            said += text
            return asked.last().lines().filter { it.startsWith("heard") || it.startsWith("now") }.joinToString(" | ")
        }
        assertEquals("now (done): The robot learns from five.", say("The robot learns from five.", 0.seconds))
        assertEquals("now (done): The robot learns from five photos", say("photos", 1.seconds))
        assertEquals("heard: The robot learns from five. | heard: photos | now (done): Open up, um", say("Open up, um", 3.seconds))
        assertEquals("heard: The robot learns from five. | heard: photos | now (done): Open up, um the plan", say("the plan", 5.seconds), "after a wait, longer")
        assertEquals("heard: photos | now (done): Open up, um the plan I mean", say("I mean", 0.seconds))
        assertEquals("heard: the plan | heard: I mean | now (done): Next slide.", say("Next slide.", 0.seconds), "on from two at most")
        assertEquals("heard: I mean | heard: Next slide. | now (done): Okay.", say("Okay.", 0.seconds), "not after a move")
    }

    @Test
    fun aSentenceWithMoreAfterItActsOnWhatItsWordsWereDecidedAndIsNeverDecidedAsDone() = runBlocking {
        val asked = mutableListOf<Pair<String, String?>>()
        val answers = ArrayDeque(listOf("next", "next", "nothing"))
        val agent = ModelAgent(catalog, Flat, { p, g -> asked += p to g; answers.removeFirst() })
        fun now() = asked.last().first.lines().first { it.startsWith("now") } to asked.last().second
        assertEquals(Move.Nothing, agent.anythingToDo(Heard("Next slide", false, 1), OnScreen(slide(1).target)))
        assertEquals(Move.Show(slide(2).target), agent.anythingToDo(Heard("Next slide.", true, 1, more = true), OnScreen(slide(1).target)))
        assertEquals("now (speaking): Next slide" to null, now(), "the model not asked again")
        assertEquals(Move.Nothing, agent.anythingToDo(Heard("Sorry, sorry.", true, 2, listOf("Next slide."), more = true), OnScreen(slide(2).target)),
            "a new answer acts only as two partials agreeing would")
        assertEquals("now (speaking): Sorry, sorry." to null, now())
        agent.anythingToDo(Heard("photos.", true, 3, listOf("Next slide.", "Sorry, sorry.")), OnScreen(slide(2).target))
        assertEquals("now (done): Sorry, sorry photos.", now().first)
    }

    @Test
    fun aCommandAfterAShowNotYetOnScreenGoesOnFromItAndDoesNotRepeatIt() {
        val r = Run(slide(1).target, apply = false)
        assertEquals(listOf(Move.Show(slide(2).target)), r.say("Next slide.", "next"))
        assertEquals(listOf(Move.Show(slide(3).target)), r.say("Next slide.", "next"))
        assertEquals(listOf(Move.Nothing), r.say("please", "slide 3"))
    }

    @Test
    fun aNewerUtteranceDropsADecisionInFlight() = runBlocking(Dispatchers.Default) {
        val hold = CompletableDeferred<String>()
        val started = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val agent = ModelAgent(catalog, Flat, { p, _ ->
            asked += p
            if ("now (done): open github" in p) started.complete(Unit).let { hold.await() } else "show 5"
        })
        val first = async { agent.anythingToDo(Heard("open github", true, 1), OnScreen()) }
        started.await()
        val second = agent.anythingToDo(Heard("open notes", true, 2, listOf("open github")), OnScreen())
        hold.complete("show 4")
        assertEquals(Move.Nothing, first.await())
        assertEquals(ask(notes), second)
        assertNull(asked.getOrNull(2))
    }
}
