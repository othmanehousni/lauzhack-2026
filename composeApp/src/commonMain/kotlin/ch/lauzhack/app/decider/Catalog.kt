package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ch.lauzhack.app.Target
import kotlin.math.sqrt

private const val QUERY = "task: search result | query: "
private val ON_STAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "mp4", "mov", "m4v", "webm") // stage.ON_STAGE

/** What the presenter can ask for, each item embedded once (tune/CONTRACT.md, Candidates). */
class Catalog(val items: List<Item>, private val vectors: List<FloatArray>) {
    private val slides = items.filterIsInstance<Item.Slide>()

    /** Each item's vector by its document, for a cache to keep. */
    val known: Map<String, FloatArray> get() = items.indices.associate { doc(items[it]) to vectors[it] }

    val firstSlide: Item.Slide? get() = slides.firstOrNull()

    fun slide(deck: String, page: Int): Item.Slide? = slides.firstOrNull { it.target.deck == deck && it.page == page }

    /** [first], then the items closest to [now] or to [before] and [now] together, until there are [k]. */
    suspend fun candidates(embedder: Embedder, now: String, before: String?, first: List<Item> = emptyList(), k: Int = 6): List<Item> {
        val queries = listOfNotNull(now, before?.let { "$it $now" }).map { unit(embedder, QUERY + it) }
        val score = DoubleArray(items.size) { i -> queries.maxOf { dot(it, vectors[i]) } }
        val out = first.distinct().toMutableList()
        for (i in items.indices.sortedByDescending { score[it] }) {
            if (out.size >= k) break
            if (items[i] !in out) out += items[i]
        }
        return out
    }

    /** The item a target on screen is, made from the target when the catalog lacks it. */
    fun itemOf(t: Target): Item = items.firstOrNull { same(it.target, t) } ?: when (t) {
        is Target.Slide -> Item.Slide(t.deck.replace('_', ' '), t.page, slides.count { it.target.deck == t.deck }.coerceAtLeast(t.page), t.title, "", t)
        is Target.File -> file(t.path)
        is Target.Site -> site(t)
        is Target.App -> Item.App(t.name, t)
    }

    /** The app or bookmark the decider named in `open <words>`, by its name or address. */
    fun named(words: String): Item? = items.firstOrNull {
        it is Item.App && spelled(it.name) == words || it is Item.Site && (spelled(it.name) == words || it.url == words)
    }

    companion object {
        fun doc(c: Item): String = when (c) {
            is Item.Slide -> "title: ${c.title} | text: ${c.deck}: ${c.text}"
            is Item.File -> "title: ${c.name} | text: ${c.folder}"
            is Item.Site -> "title: ${c.name} | text: ${c.url}"
            is Item.App -> "title: ${c.name} | text: app"
        }

        /** Embeds each item's document unless [known] has it. */
        suspend fun build(items: List<Item>, embedder: Embedder, known: Map<String, FloatArray> = emptyMap()): Catalog =
            Catalog(items, items.map { known[doc(it)] ?: unit(embedder, doc(it)) })

        fun file(path: String): Item.File {
            val parts = path.split('/')
            return Item.File(parts.last(), parts.dropLast(1).takeLast(2).joinToString(" / "), Target.File(path))
        }

        fun site(t: Target.Site): Item.Site {
            val url = t.url.substringAfter("://").removePrefix("www.").removeSuffix("/")
            return Item.Site(t.name.ifEmpty { url.substringBefore('/') }, url, t)
        }
    }
}

internal fun same(a: Target?, b: Target?) = if (a is Target.Slide && b is Target.Slide) a.deck == b.deck && a.page == b.page else a == b

/** A picture or video replaces the deck on the stage instead of covering it. */
internal fun onStage(c: Item) = c is Item.File && c.name.substringAfterLast('.', "").lowercase() in ON_STAGE

/** A name as the decider writes it in `open <words>` (tune/build.py, fam_site). */
private fun spelled(name: String) = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in " .-'" }.trim()

private suspend fun unit(embedder: Embedder, text: String): FloatArray {
    val v = embedder.embed(text).values
    val norm = sqrt(v.sumOf { it * it })
    return FloatArray(v.size) { (v[it] / norm).toFloat() }
}

private fun dot(a: FloatArray, b: FloatArray): Double {
    var s = 0.0
    for (i in a.indices) s += a[i].toDouble() * b[i]
    return s
}
