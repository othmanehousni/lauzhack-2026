package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ch.lauzhack.app.Target
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val QUERY = "task: search result | query: "
const val K = 6                // candidates
private const val OWN = 3      // places kept for the meeting's slides (rehearsal/rule.py)
private const val LEAD = 0.08  // how far something seen lately must lead the eighth best of them (rehearsal/rule.py)
private val ON_STAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "mp4", "mov", "m4v", "webm") // stage.ON_STAGE

/** What the presenter can ask for, each item embedded once (tune/CONTRACT.md, Candidates): the slides of the [meeting]'s
 * decks, what the presenter saw [recent]ly, and the rest. */
class Catalog(
    val items: List<Item>,
    private val vectors: List<FloatArray>,
    private val meeting: Set<String> = emptySet(),
    private val recent: Set<Target> = emptySet(),
) {
    private val slides = items.filterIsInstance<Item.Slide>()
    private val index = items.withIndex().associate { it.value to it.index }

    /** Each item's vector by its document, for a cache to keep. */
    val known: Map<String, FloatArray> get() = items.indices.associate { doc(items[it]) to vectors[it] }

    val firstSlide: Item.Slide? get() = slides.firstOrNull()

    fun slide(deck: String, page: Int): Item.Slide? = slides.firstOrNull { it.target.deck == deck && it.page == page }

    /** Each item's cosine with [now] or with [before] and [now] together, whichever is closer. */
    suspend fun scores(embedder: Embedder, now: String, before: String?): DoubleArray {
        val queries = listOfNotNull(now, before?.let { "$it $now" }).map { unit(embedder, QUERY + it) }
        return DoubleArray(items.size) { i -> queries.maxOf { dot(it, vectors[i]) } }
    }

    suspend fun candidates(embedder: Embedder, now: String, before: String?, first: List<Item> = emptyList(), open: String? = null, k: Int = K): List<Item> =
        candidates(scores(embedder, now, before), first, open, k)

    /**
     * Candidates at real scale (tune/CONTRACT.md): [first]; then the best [OWN] slides of the meeting's decks and of the
     * deck [open] on screen; then by [score] the rest of those slides, the bookmarks and apps, and what was seen lately
     * (recent files and pages, the first slide of a recent deck) when it leads the eighth best of those by [LEAD], until
     * there are [k]; all of it after [first] in descending score. Other pages of a recent deck wait until it is open.
     */
    fun candidates(score: DoubleArray, first: List<Item> = emptyList(), open: String? = null, k: Int = K): List<Item> {
        fun own(c: Item) = c is Item.Slide && (c.target.deck in meeting || c.target.deck == open)
        fun lately(c: Item) = if (c is Item.Slide) c.page == 1 && !own(c) else c.target in recent
        val order = items.indices.sortedByDescending { score[it] }
        val seen = order.filter { lately(items[it]) }
        val bar = if (seen.size > 8) score[seen[7]] + LEAD else Double.NEGATIVE_INFINITY // a crowd: only a standout
        fun offered(i: Int) = items[i].let { own(it) || if (lately(it)) score[i] >= bar else it !is Item.Slide }
        val out = first.distinct().toMutableList()
        val chosen = order.filter { own(items[it]) && items[it] !in out }.take(OWN).toMutableList()
        for (i in order) {
            if (out.size + chosen.size >= k) break
            if (items[i] !in out && i !in chosen && offered(i)) chosen += i
        }
        return out + chosen.sortedByDescending { score[it] }.map(items::get)
    }

    /** [c] as the log names it, with its [score]: kind, a short name and the cosine. */
    fun label(c: Item, score: DoubleArray): String = when (c) {
        is Item.Slide -> "slide ${c.target.deck} ${c.page}"
        is Item.File -> "file ${c.name}"
        is Item.Site -> "site ${c.url}"
        is Item.App -> "app ${c.name}"
    } + " " + (index[c]?.let { (score[it] * 1000).roundToInt() / 1000.0 } ?: "-")

    /** The best [n] of each tier by [score], one log line each, for rehearsal/check.py to tell what was not retrieved. */
    fun pool(score: DoubleArray, n: Int = 8): List<String> = items.indices.groupBy { tier(items[it]) }.map { (tier, ix) ->
        "pool    $tier  " + ix.sortedByDescending { score[it] }.take(n).joinToString(" | ") { label(items[it], score) }
    }

    private fun tier(c: Item) = when {
        c is Item.Slide -> if (c.target.deck in meeting) "meeting" else if (c.page == 1) "firsts" else "decks"
        c.target in recent -> "recent"
        c is Item.File -> "files"
        c is Item.Site -> "sites"
        else -> "apps"
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
        suspend fun build(
            items: List<Item>, embedder: Embedder, known: Map<String, FloatArray> = emptyMap(), meeting: Set<String> = emptySet(), recent: Set<Target> = emptySet(),
        ): Catalog = Catalog(items, items.map { known[doc(it)] ?: unit(embedder, doc(it)) }, meeting, recent)

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
