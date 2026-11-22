package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.Target
import ch.lauzhack.app.stage.DeckPage
import ch.lauzhack.app.stage.points
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CatalogTest {
    /** Vectors by text: each item one axis, so a query's cosine with an item is its value on that axis. */
    private class Axes(val vectors: Map<String, List<Double>>) : Embedder {
        val asked = mutableListOf<String>()
        override suspend fun embed(text: String) = Vector(vectors[text] ?: error("unexpected \"$text\"")).also { asked += text }
        override fun diff(embedding1: Vector, embedding2: Vector) = 1 - embedding1.cosineSimilarity(embedding2)
    }

    private val items = (1..10).map { Item.App("App $it", Target.App("App $it")) }
    private fun axis(i: Int) = List(12) { if (it == i) 1.0 else 0.0 }
    private fun near(vararg w: Pair<Int, Double>) = List(12) { i -> w.firstOrNull { it.first == i }?.second ?: 0.0 }
    private fun byAxis(items: List<Item>) = items.indices.flatMap { i -> Catalog.docs(items[i]).map { it to axis(i) } }.toMap()

    @Test
    fun offeredFirstThenTheBestOfAnyDocumentAndQueryUntilEight() = runBlocking {
        val q = "task: search result | query: "
        val now = "please could you open the second one now"
        val e = Axes(byAxis(items) + mapOf(
            "title: App 9 | text: App 9 app" to axis(10),
            "$q$now" to near(0 to 0.9, 1 to 0.5, 2 to 0.4, 3 to 0.3, 4 to 0.2, 5 to 0.1),
            "${q}open it $now" to near(7 to 0.95, 6 to 0.35),
            "${q}you open the second one now" to near(10 to 0.8, 9 to 0.6),
        ))
        val catalog = Catalog.build(items, e)
        assertEquals(items.flatMap(Catalog::docs), e.asked)
        val got = catalog.candidates(e, now, "open it", first = listOf(items[2], items[5]))
        assertEquals(listOf(2, 5, 7, 8, 0, 9, 1, 6).map(items::get), got, "offered, then by the best cosine of any document and query, without repeats")

        fun slide(deck: String) = Item.Slide(deck, 2, 2, "Plan", "", Target.Slide(deck, 2))
        val decks = Catalog(listOf(slide("A"), slide("B")), List(2) { floatArrayOf(1f) })
        val flat = Axes(mapOf("${q}x" to listOf(1.0)))
        assertContentEquals(doubleArrayOf(1.0, 1.04), decks.scores(flat, "x", null, open = "B"), "0.04 for the deck on screen")
    }

    @Test
    fun theMeetingsSlidesAlwaysAndWhatWasSeenLatelyOnlyWhenItStandsOutOfTheCrowd() {
        fun slide(deck: String, p: Int, of: Int) = Item.Slide(deck, p, of, "$deck $p", "", Target.Slide(deck, p))
        val talk = (1..4).map { slide("Talk", it, 4) }                     // 0..3, the meeting's deck
        val old = (1..3).map { slide("Old", it, 3) }                       // 4..6, a deck seen lately
        val files = (1..10).map { Catalog.file("/u/Docs/f$it.pdf") }       // 7..16, files seen lately
        val github = Catalog.site(Target.Site("https://github.com", "GitHub")) // 17
        val all = talk + old + files + github
        val catalog = Catalog(all, all.map { floatArrayOf(1f) }, meeting = setOf("Talk"), recent = files.map { it.target }.toSet())
        val crowd = doubleArrayOf(.3, .5, .4, .2, .6, .9, .1) + DoubleArray(10) { .75 - it * .01 } + .55
        fun pick(score: DoubleArray, first: List<Item> = emptyList(), open: String? = null) = catalog.candidates(score, first, open, k = 6)

        assertEquals(listOf(github, talk[1], talk[2], talk[0], talk[3]), pick(crowd),
            "no file leads the eighth by 0.08, a recent deck's second page waits until it is open, its first is no standout")
        val standout = crowd.copyOf().also { it[11] = .9 }
        assertEquals(listOf(files[4], github, talk[1], talk[2], talk[0], talk[3]), pick(standout))
        assertEquals(listOf(old[1], old[0], github, talk[1], talk[2], talk[0]), pick(crowd, open = "Old"), "the deck on screen is the meeting's")
        val alone = crowd.copyOf().also { for (i in 8..16) it[i] = .74 - (i - 8) * .002; it[7] = .785 }
        assertEquals(listOf(files[0], github, talk[1], talk[2], talk[0], talk[3]), pick(alone), "the best leads the eighth by 0.057 only, but the second by 0.045")
        assertEquals(listOf(github, files[4], talk[1], talk[2], talk[0], talk[3]), pick(standout, first = listOf(github)), "offered first")
        val few = Catalog(all.take(4) + files.take(2), List(6) { floatArrayOf(1f) }, meeting = setOf("Talk"), recent = files.map { it.target }.toSet())
        assertEquals(listOf(talk[1], talk[2], talk[0], talk[3], files[0], files[1]), few.candidates(doubleArrayOf(.3, .5, .4, .2, .1, .05)),
            "without a crowd, what was seen lately is offered at any score")
    }

    @Test
    fun knownVectorsAreNotEmbeddedAgain() = runBlocking {
        val e = Axes(byAxis(items))
        val first = Catalog.build(items.take(5), e)
        e.asked.clear()
        val second = Catalog.build(items, e, first.known)
        assertEquals(items.drop(5).flatMap(Catalog::docs), e.asked)
        val file = File.createTempFile("vectors", ".bin").apply { deleteOnExit() }
        saveVectors(file, second.known)
        val back = loadVectors(file)
        assertEquals(second.known.keys, back.keys)
        second.known.forEach { (doc, v) -> assertContentEquals(v, back.getValue(doc)) }
        assertEquals(emptyMap(), loadVectors(File(file.path + ".missing")))
    }

    @Test
    fun documentsAndItemsAsTheContractWritesThem() {
        val lines = listOf("The problem", "•  Pickers are scarce", "- Labour is costly", "2 / 3")
        assertEquals("Pickers are scarce; Labour is costly", points(lines), "bullets as training joins them, without their marks or the page number")
        val page = DeckPage("Acme_Overview", 2, "The problem", lines.joinToString(" "), File("2.png"), points(lines))
        val made = items(listOf(page, page.copy(page = 1), page.copy(page = 3)), listOf("Notes"), listOf("/Users/a/Documents/Clients/Dubois/Offer.pdf"), BOOKMARKS.take(1))
        val slide = made[0] as Item.Slide
        assertEquals(Item.Slide("Acme Overview", 2, 3, "The problem", "Pickers are scarce; Labour is costly", Target.Slide("Acme_Overview", 2, "The problem")), slide)
        assertEquals(listOf("title: The problem | text: Pickers are scarce; Labour is costly", "title: The problem | text: none"), Catalog.docs(slide))
        assertEquals(listOf("title: The problem | text: Acme Overview; Pickers are scarce; Labour is costly", "title: The problem | text: none",
            "title: Acme Overview | text: presentation slides"), Catalog.docs(made[1]), "a deck's first slide also by the deck's name")
        assertEquals(listOf("title: YouTube | text: youtube.com", "title: YouTube | text: YouTube website, youtube.com"), Catalog.docs(made[3]))
        assertEquals(listOf("title: Notes | text: app", "title: Notes | text: Notes app"), Catalog.docs(made[4]))
        assertEquals(listOf("title: Offer.pdf | text: Clients / Dubois", "title: Offer.pdf | text: none"), Catalog.docs(made[5]))
        val catalog = Catalog(made, List(made.size) { floatArrayOf(1f) })
        assertEquals(slide, catalog.itemOf(Target.Slide("Acme_Overview", 2)), "a slide on screen is the catalog's, whatever its title")
        assertEquals(Item.Site("example.org", "example.org/a", Target.Site("https://www.example.org/a/")), catalog.itemOf(Target.Site("https://www.example.org/a/")))
        assertEquals(made[3], catalog.named("youtube"))
        assertEquals(made[3], catalog.named("youtube.com"))
        assertEquals(made[4], catalog.named("notes"))
        assertEquals(null, catalog.named("offer.pdf"), "open names apps and bookmarks only")
        val x = Catalog.site(Target.Site("https://x.com", "X (Twitter)"))
        assertEquals(x, Catalog(listOf(x), listOf(floatArrayOf(1f))).named("x twitter"), "a name's other characters are spaces, collapsed")
    }

    @Test
    fun aNameMisheardIsTheOneItSpellsClosest() {
        val made = items(emptyList(), listOf("Calculator", "Notes", "Chess", "Photos"), emptyList())
        val catalog = Catalog(made, List(made.size) { floatArrayOf(1f) })
        fun named(words: String) = (catalog.named(words) as? Item.Site)?.name ?: (catalog.named(words) as? Item.App)?.name
        assertEquals("GitHub", named("gitub"))
        assertEquals("GitHub", named("get hub"))
        assertEquals("YouTube", named("you tube"))
        assertEquals("Calculator", named("calculater"))
        for (absent in listOf("google meet", "zorblax", "chess club", "the photos", "key note", "dentist", "jidhub"))
            assertEquals(null, named(absent), "\"$absent\" is too far from any name")
        assertEquals(null, named("gitub.com"), "an address is taken as spelled")
    }
}
