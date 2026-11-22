package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ch.lauzhack.app.Target
import ch.lauzhack.app.stage.DeckPage
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
        val page = DeckPage("Acme_Overview", 2, "The problem", "The problem Pickers are scarce Labour is costly 2 / 3", File("2.png"))
        val made = items(listOf(page, page.copy(page = 1), page.copy(page = 3)), listOf("Notes"), listOf("/Users/a/Documents/Clients/Dubois/Offer.pdf"), BOOKMARKS.take(1))
        val slide = made[0] as Item.Slide
        assertEquals(Item.Slide("Acme Overview", 2, 3, "The problem", "Pickers are scarce Labour is costly 2 / 3", Target.Slide("Acme_Overview", 2, "The problem")), slide)
        assertEquals("title: The problem | text: Acme Overview: Pickers are scarce Labour is costly 2 / 3", Catalog.doc(slide))
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
