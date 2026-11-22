package ch.lauzhack.app.decider

import ch.lauzhack.app.Heard
import ch.lauzhack.app.Move
import ch.lauzhack.app.Target
import ch.lauzhack.app.stage.AgentDesktop
import ch.lauzhack.app.stage.Files
import ch.lauzhack.app.stage.Visit
import ch.lauzhack.app.stage.historyDb
import ch.lauzhack.app.stage.modified
import ch.lauzhack.app.stage.pdf
import ch.lauzhack.app.stage.settled
import ch.lauzhack.app.stage.slides
import ch.lauzhack.app.stage.spotLine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the presenter saw lately as the decider's candidates, from a fixture Mac: a browser history, Spotlight's answer
 * and a deck on the Desktop. RecentsLeverTest shows these same targets on a headless Chrome. */
class RecentsTest {
    @Test
    fun aRecentPageFileAndDeckPageAreCandidatesAndEachCanBeShown() = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val tmp = createTempDirectory("mole-recent-candidates").toFile().canonicalFile
        try {
            val desktop = File(tmp, "Desktop")
            val browsers = File(tmp, "support")
            historyDb(File(browsers, "Google/Chrome/Default/History"), listOf(Visit("https://acme.com/pricing?ref=mail#plans", "Acme pricing", Duration.ofMinutes(2))))
            val roadmap = pdf(File(desktop, "roadmap.pdf"), slides(4)).modified(Duration.ofHours(1))
            val notes = File(desktop, "notes.txt").apply { writeText("plan, price\n") }.modified(Duration.ofHours(1))
            val spotlight = listOf(
                spotLine(roadmap, Duration.ofMinutes(20), "kMDItemNumberOfPages = 4", "kMDItemPageWidth = 960", "kMDItemPageHeight = 540"),
                spotLine(notes, Duration.ofMinutes(5)),
            ).joinToString("\n")
            fun desk(history: Boolean) = AgentDesktop(File(tmp, "library"), sandbox = null, data = File(tmp, "data"), cache = File(tmp, "cache"),
                history = history, files = Files { if ("kMDItemFSContentChangeDate >=" in it.last()) spotlight else "" }, browsers = browsers)

            val recents = desk(history = true).settled()
            assertEquals(1, recents.pages.size)
            assertEquals(emptyList(), desk(history = false).settled().pages, "with history off, the same Mac gives no page")

            val items = items(emptyList(), emptyList(), emptyList(), sites = emptyList(), recents = recents)
            assertEquals(listOf("roadmap", "Slide 1", "Slide 2", "Slide 3", "Slide 4", "Acme pricing", "notes"), vocabulary(items))
            val answers = ArrayDeque<String>()
            val prompts = mutableListOf<String>()
            val agent = ModelAgent(Catalog.build(items, Flat), Flat, { p, _ -> prompts += p; answers.removeFirst() })
            val screen = FakeDesk()
            var n = 0
            suspend fun say(text: String, answer: String): Move {
                answers += answer
                return agent.anythingToDo(Heard(text, true, ++n), screen.onScreen).also(screen::apply)
            }

            val page = recents.pages.single().target
            assertEquals(Move.Ask(listOf(page)), say("the pricing page we saw", "show 2"))
            val candidates = prompts.last().lines().filter { it.first().isDigit() }
            assertEquals(3, candidates.size, "a recent deck is one candidate, its first slide, until it is on screen")
            assertEquals("1 slide 1 of 4 \"Slide 1\" in roadmap: about pricing", candidates[0])
            assertEquals("2 site Acme pricing (acme.com/pricing)", candidates[1], "an address without its query or fragment")
            assertTrue(candidates[2].startsWith("3 file \"notes.txt\" in "), candidates[2])
            assertEquals(page, screen.onScreen.target)

            assertEquals(Move.Ask(listOf(Target.File(notes.path))), say("and my notes", "show 3"))
            assertEquals(Target.File(notes.path), screen.onScreen.target)

            val first = recents.decks.single().pages[0]
            assertEquals(Move.Ask(listOf(Target.Slide(first.deck, 1, first.title))), say("the roadmap deck", "show 1"))
            assertEquals(Move.Show(Target.Slide("roadmap", 2, "Slide 2")), say("next slide", "next"), "a recent deck is a deck")
            assertEquals(Move.Ask(listOf(Target.Slide("roadmap", 4, "Slide 4"))), say("its last slide", "show 4"), "on screen, all its slides are candidates")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
