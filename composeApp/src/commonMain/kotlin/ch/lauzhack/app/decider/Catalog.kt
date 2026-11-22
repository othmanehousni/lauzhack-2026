package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ch.lauzhack.app.Target
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val QUERY = "task: search result | query: "
const val K = 8                // candidates
private const val BONUS = 0.04 // for a slide of the deck on screen (tune/CONTRACT.md, Candidates)
private const val OWN = 3      // places kept for the meeting's slides (rehearsal/rule.py)
private const val LEAD = 0.08  // how far something seen lately must lead the eighth best of them (rehearsal/rule.py)
private const val ALONE = 0.035 // or how far the best of them must lead the second: a request singles one out
private val ON_STAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "mp4", "mov", "m4v", "webm") // stage.ON_STAGE

/** What the presenter can ask for, each item embedded once by each of its documents (tune/CONTRACT.md, Candidates): the
 * slides of the [meeting]'s decks, what the presenter saw [recent]ly, and the rest. [vectors] are those of the items'
 * main documents, [more] those of their other documents. */
class Catalog(
    val items: List<Item>,
    private val vectors: List<FloatArray>,
    private val meeting: Set<String> = emptySet(),
    private val recent: Set<Target> = emptySet(),
    private val more: List<List<FloatArray>> = emptyList(),
) {
    private val slides = items.filterIsInstance<Item.Slide>()
    private val index = items.withIndex().associate { it.value to it.index }
    private fun all(i: Int) = listOf(vectors[i]) + more.getOrElse(i) { emptyList() }

    /** Each vector by its document, for a cache to keep. */
    val known: Map<String, FloatArray> get() = items.indices.flatMap { docs(items[it]).zip(all(it)) }.toMap()

    val firstSlide: Item.Slide? get() = slides.firstOrNull()

    fun slide(deck: String, page: Int): Item.Slide? = slides.firstOrNull { it.target.deck == deck && it.page == page }

    /** Each item's best cosine over its documents and the queries: [now], [before] with [now], and the last six words of
     * [now]; plus [BONUS] for a slide of the deck [open] on screen. */
    suspend fun scores(embedder: Embedder, now: String, before: String?, open: String? = null): DoubleArray {
        val six = now.split(' ', '\t', '\r', '\n').filter(String::isNotEmpty).takeLast(6).joinToString(" ")
        val queries = listOfNotNull(now, before?.let { "$it $now" }, six).distinct().map { unit(embedder, QUERY + it) }
        return DoubleArray(items.size) { i ->
            val c = items[i]
            all(i).maxOf { v -> queries.maxOf { dot(it, v) } } + if (c is Item.Slide && c.target.deck == open) BONUS else 0.0
        }
    }

    suspend fun candidates(embedder: Embedder, now: String, before: String?, first: List<Item> = emptyList(), open: String? = null, k: Int = K): List<Item> =
        candidates(scores(embedder, now, before, open), first, open, k)

    /**
     * Candidates at real scale (tune/CONTRACT.md): [first]; then the best [OWN] slides of the meeting's decks and of the
     * deck [open] on screen; then by [score] the rest of those slides, the bookmarks and apps, and what was seen lately
     * (recent files and pages, the first slide of a recent deck) when it leads the eighth best of those by [LEAD] or is
     * the best of them and leads the second by [ALONE], until
     * there are [k]; all of it after [first] in descending score. Other pages of a recent deck wait until it is open.
     */
    fun candidates(score: DoubleArray, first: List<Item> = emptyList(), open: String? = null, k: Int = K): List<Item> {
        fun own(c: Item) = c is Item.Slide && (c.target.deck in meeting || c.target.deck == open)
        fun lately(c: Item) = if (c is Item.Slide) c.page == 1 && !own(c) else c.target in recent
        val order = items.indices.sortedByDescending { score[it] }
        val seen = order.filter { lately(items[it]) }
        val bar = if (seen.size > 8) score[seen[7]] + LEAD else Double.NEGATIVE_INFINITY // a crowd: only a standout
        val alone = seen.takeIf { it.size > 8 && score[it[0]] - score[it[1]] >= ALONE }?.first()
        fun offered(i: Int) = items[i].let { own(it) || if (lately(it)) score[i] >= bar || i == alone else it !is Item.Slide }
        val out = first.distinct().toMutableList()
        val chosen = order.filter { own(items[it]) && items[it] !in out }.take(OWN).toMutableList()
        for (i in order) {
            if (out.size + chosen.size >= k) break
            if (items[i] !in out && i !in chosen && offered(i)) chosen += i
        }
        return out + chosen.sortedByDescending { score[it] }.map(items::get)
    }

    /** [c]'s entry in [score], or null when [c] is not in the catalog. */
    fun score(c: Item, score: DoubleArray): Double? = index[c]?.let { score[it] }

    /** [c] as the log names it, with its [score]: kind, a short name and the cosine. */
    fun label(c: Item, score: DoubleArray): String = when (c) {
        is Item.Slide -> "slide ${c.target.deck} ${c.page}"
        is Item.File -> "file ${c.name}"
        is Item.Site -> "site ${c.url}"
        is Item.App -> "app ${c.name}"
    } + " " + (score(c, score)?.let { (it * 1000).roundToInt() / 1000.0 } ?: "-")

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

    /** The app or bookmark the decider named in `open <words>`, by its name or address; else the one whose name, or
     * address for words with a dot, has letters closest to theirs, at [NEAR] or more: the recognizer mishears names. */
    fun named(words: String): Item? = exactly(words) ?: near(letters(words), address = '.' in words)

    /** The app or bookmark the decider named in `open <words>` by its very name or address. */
    fun exactly(words: String): Item? = items.firstOrNull {
        it is Item.App && spelled(it.name) == words || it is Item.Site && (spelled(it.name) == words || it.url == words)
    }

    private fun near(heard: String, address: Boolean): Item? {
        val names = items.mapNotNull { c -> (if (address) (c as? Item.Site)?.url else (c as? Item.App)?.name ?: (c as? Item.Site)?.name)?.let { letters(it) to c } }
        val close = names.map { it.first }.distinct().associateWith { similarity(heard, it) }
        val (best, score) = close.maxByOrNull { it.value } ?: return null
        if (score < NEAR || close.any { it.key != best && it.value == score }) return null
        return names.first { it.first == best }.second
    }

    companion object {
        /** [c]'s main document, then those by its name alone. */
        fun docs(c: Item): List<String> = when (c) {
            is Item.Slide -> listOf("title: ${c.title} | text: " + (if (c.page == 1) "${c.deck}; " else "") + c.text, "title: ${c.title} | text: none") +
                if (c.page == 1) listOf("title: ${c.deck} | text: presentation slides") else emptyList()
            is Item.File -> listOf("title: ${c.name} | text: ${c.folder}", "title: ${c.name} | text: none")
            is Item.Site -> listOf("title: ${c.name} | text: ${c.url}", "title: ${c.name} | text: ${c.name} website, ${c.url}")
            is Item.App -> listOf("title: ${c.name} | text: app", "title: ${c.name} | text: ${c.name} app")
        }

        /** Embeds each document of each item unless [known] has it. */
        suspend fun build(
            items: List<Item>, embedder: Embedder, known: Map<String, FloatArray> = emptyMap(), meeting: Set<String> = emptySet(), recent: Set<Target> = emptySet(),
        ): Catalog {
            val vectors = items.map { c -> docs(c).map { known[it] ?: unit(embedder, it) } }
            return Catalog(items, vectors.map { it.first() }, meeting, recent, vectors.map { it.drop(1) })
        }

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

/** A name as the decider writes it in `open <words>` (tune/prompt.py, spoken). */
private fun spelled(name: String) =
    name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it in " .-'") it else ' ' }.joinToString("").split(' ').filter(String::isNotEmpty).joinToString(" ")

private const val NEAR = 0.8 // "gitub" is GitHub at 0.83; "google meet" is Google Sheets at only 0.75

private fun letters(name: String) = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

/** One less the edits from [a] to [b] over the longer's length. */
private fun similarity(a: String, b: String): Double {
    var row = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val next = IntArray(b.length + 1).also { it[0] = i + 1 }
        for (j in b.indices) next[j + 1] = minOf(row[j + 1] + 1, next[j] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
        row = next
    }
    return 1 - row[b.length].toDouble() / maxOf(a.length, b.length, 1)
}

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
