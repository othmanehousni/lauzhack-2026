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

    private val items = (1..8).map { Item.App("App $it", Target.App("App $it")) }
    private fun axis(i: Int) = List(8) { if (it == i) 1.0 else 0.0 }
    private fun near(vararg w: Pair<Int, Double>) = List(8) { i -> w.firstOrNull { it.first == i }?.second ?: 0.0 }

    @Test
    fun offeredFirstThenTheBestOfBothQueriesUntilSix() = runBlocking {
        val q = "task: search result | query: "
        val e = Axes(items.indices.associate { Catalog.doc(items[it]) to axis(it) } + mapOf(
            "${q}the second" to near(0 to 0.9, 1 to 0.5, 2 to 0.4, 3 to 0.3, 4 to 0.2, 5 to 0.1),
            "${q}open it the second" to near(7 to 0.95, 6 to 0.3),
        ))
        val catalog = Catalog.build(items, e)
        assertEquals(items.map(Catalog::doc), e.asked)
        val got = catalog.candidates(e, "the second", "open it", first = listOf(items[2], items[5]))
        assertEquals(listOf(2, 5, 7, 0, 1, 6).map(items::get), got, "offered, then by the best cosine of either query, without repeats")
        assertEquals(listOf(0, 1, 2, 3, 4, 5).map(items::get), catalog.candidates(e, "the second", null))
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
        fun pick(score: DoubleArray, first: List<Item> = emptyList(), open: String? = null) = catalog.candidates(score, first, open)

        assertEquals(listOf(github, talk[1], talk[2], talk[0], talk[3]), pick(crowd),
            "no file leads the eighth by 0.08, a recent deck's second page waits until it is open, its first is no standout")
        val standout = crowd.copyOf().also { it[11] = .9 }
        assertEquals(listOf(files[4], github, talk[1], talk[2], talk[0], talk[3]), pick(standout))
        assertEquals(listOf(old[1], old[0], github, talk[1], talk[2], talk[0]), pick(crowd, open = "Old"), "the deck on screen is the meeting's")
        assertEquals(listOf(github, files[4], talk[1], talk[2], talk[0], talk[3]), pick(standout, first = listOf(github)), "offered first")
        val few = Catalog(all.take(4) + files.take(2), List(6) { floatArrayOf(1f) }, meeting = setOf("Talk"), recent = files.map { it.target }.toSet())
        assertEquals(listOf(talk[1], talk[2], talk[0], talk[3], files[0], files[1]), few.candidates(doubleArrayOf(.3, .5, .4, .2, .1, .05)),
            "without a crowd, what was seen lately is offered at any score")
    }

    @Test
    fun knownVectorsAreNotEmbeddedAgain() = runBlocking {
        val e = Axes(items.indices.associate { Catalog.doc(items[it]) to axis(it) })
        val first = Catalog.build(items.take(5), e)
        e.asked.clear()
        val second = Catalog.build(items, e, first.known)
        assertEquals(items.drop(5).map(Catalog::doc), e.asked)
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
        assertEquals("title: The problem | text: Acme Overview: Pickers are scarce; Labour is costly", Catalog.doc(slide))
        assertEquals("title: YouTube | text: youtube.com", Catalog.doc(made[3]))
        assertEquals("title: Notes | text: app", Catalog.doc(made[4]))
        assertEquals("title: Offer.pdf | text: Clients / Dubois", Catalog.doc(made[5]))
        val catalog = Catalog(made, List(made.size) { floatArrayOf(1f) })
        assertEquals(slide, catalog.itemOf(Target.Slide("Acme_Overview", 2)), "a slide on screen is the catalog's, whatever its title")
        assertEquals(Item.Site("example.org", "example.org/a", Target.Site("https://www.example.org/a/")), catalog.itemOf(Target.Site("https://www.example.org/a/")))
        assertEquals(made[3], catalog.named("youtube"))
        assertEquals(made[3], catalog.named("youtube.com"))
        assertEquals(made[4], catalog.named("notes"))
        assertEquals(null, catalog.named("offer.pdf"), "open names apps and bookmarks only")
    }
}
