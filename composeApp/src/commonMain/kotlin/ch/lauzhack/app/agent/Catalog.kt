package ch.lauzhack.app.agent

import ch.lauzhack.app.Target
import kotlin.math.ln

/** One page of a deck as its text was extracted: brain.py takes the first line as the title and joins the lines. */
data class SlideText(val deck: String, val page: Int, val title: String, val text: String)

/** Everything the presenter can name: each slide and the files they may show, embedded once, and the bookmarked websites
 * and the apps on the shared screen, by name. */
class Catalog private constructor(
    internal val embeddings: Embeddings,
    val slides: List<Target.Slide>,
    private val slideVecs: List<FloatArray>,
    private val covers: List<Int>,
    private val deckVecs: List<FloatArray>,
    private val names: List<Map<String, Double>>,
    val files: List<String>,
    private val fileVecs: List<FloatArray>,
    private val slideEmpty: FloatArray,
    private val fileEmpty: FloatArray,
    private val sites: Map<String, Target.Site>,
    internal val places: Map<String, Target>,
) {
    /** The names a presenter may say, for the speech recognizer to expect: websites, apps, decks and slide titles. */
    val vocabulary: List<String> get() = (places.keys + slides.map { it.deck.replace('_', ' ') } + slides.map { it.title }).distinct()

    internal fun slide(deck: String, page: Int): Target.Slide? = slides.firstOrNull { it.deck == deck && it.page == page }

    /** The catalog's own instance of a target the screen reports, so a slide equals the one shown whatever its title. */
    internal fun canonical(t: Target?): Target? = if (t is Target.Slide) slide(t.deck, t.page) ?: t else t

    /** How close each slide is to a request naming it: by meaning, and by how much of its title and its deck's name the
     * sentence says. A deck's first slide also stands for the deck, and slides of the deck `here` are preferred. */
    internal fun sims(query: FloatArray, said: String = "", here: String? = null): DoubleArray {
        val w = stems(said)
        val s = DoubleArray(slides.size) { i ->
            val total = names[i].values.sum()
            dot(slideVecs[i], query) + NAMES * names[i].filterKeys { it in w }.values.sum() / (if (total != 0.0) total else 1.0)
        }
        covers.forEachIndexed { c, i -> s[i] = maxOf(s[i], dot(deckVecs[c], query).toDouble()) }
        for (i in s.indices) if (slides[i].deck == here) s[i] += HERE_BONUS
        return s
    }

    /** How much closer a request is to each slide than a request naming nothing is. */
    internal fun named(sims: DoubleArray): DoubleArray = DoubleArray(sims.size) { sims[it] - slideEmpty[it] }

    /** How much closer each file is to a request than a request naming nothing is. */
    internal fun fits(query: FloatArray): FloatArray = FloatArray(files.size) { dot(fileVecs[it], query) - fileEmpty[it] }

    /** The vector of a slide or file, to compare it with what the presenter says. */
    internal fun vec(t: Target): FloatArray? = when (t) {
        is Target.Slide -> slideVecs[slides.indexOf(t)]
        is Target.File -> files.indexOf(t.path).takeIf { it >= 0 }?.let { fileVecs[it] }
        else -> null
    }

    /** The website or app the sentence names. Runs of up to three words are compared without spaces, so "git hub" finds
     * GitHub; the closest name wins, and the longest of equally close ones. A one-word name written like a plain word
     * (Notes, Photos) counts only where it is used as a name: capitalized after the first word, right after "to" or
     * "open", or next to "app" or "website". */
    internal fun place(said: String): Target? {
        val raw = alnum(said)
        val w = raw.map { it.lowercase() }
        val named = raw.indices.filter { i ->
            i > 0 && (raw[i].first().isUpperCase() || w[i - 1] in GOVERNS) || w.subList(maxOf(i - 1, 0), minOf(i + 2, w.size)).any { it in NAMED }
        }.toSet()
        // by ear, "laws hack" is LauzHack, but not when a short word ("a", "to") is part of it: "open a" is no OpenAI
        val runs = (1..3).flatMap { n ->
            (0..w.size - n).map { i ->
                val part = w.subList(i, i + n)
                val run = part.joinToString("")
                Run(n, run, n > 1 || i in named, if (part.minOf { it.length } >= 3) sound(run) else "")
            }
        }.distinct()
        val close = LinkedHashMap<String, Double>()
        for (name in places.keys) {
            val parts = tokens(name)
            val plain = parts.size == 1 && name.drop(1).let { s -> s.any { it.isLowerCase() } && s.none { it.isUpperCase() || it.isTitleCase() } }
            val squashed = parts.joinToString("")
            val heard = sound(squashed)
            for (r in runs) {
                if (plain && !r.proper) continue
                // a run of more or fewer words than the name must spell it exactly, so "open a" is not OpenAI
                var ratio = if (r.n == parts.size && quickRatios(r.run, squashed) >= FUZZY) ratio(r.run, squashed) else if (r.run == squashed) 1.0 else 0.0
                if (!plain && ratio < FUZZY && heard.length >= 5 && r.sound == heard) ratio = FUZZY
                if (ratio >= FUZZY) close[name] = maxOf(ratio, close[name] ?: 0.0)
            }
        }
        val name = close.entries.fold(null as Map.Entry<String, Double>?) { best, e ->
            if (best == null || e.value > best.value || e.value == best.value && e.key.length > best.key.length) e else best
        }?.key ?: return null
        return if (name in sites && w.any { it in WEB }) sites.getValue(name) else places.getValue(name)
    }

    private data class Run(val n: Int, val run: String, val proper: Boolean, val sound: String)

    companion object {
        suspend fun build(
            embeddings: Embeddings,
            slides: List<SlideText>,
            apps: List<String>,
            files: List<String> = emptyList(),
            unfinished: List<String> = EXAMPLES.first { it.first == "unfinished" }.second,
        ): Catalog {
            // Each document says what it is: a slide by its own title, and a deck as a whole by its name, standing for
            // its first slide ("the company slides").
            val slideVecs = embeddings.remember(slides.map { "title: ${it.title} | text: ${it.text}" })
            val covers = slides.indices.filter { slides[it].page == 1 }
            val stemmed = slides.map { stems("${it.title} ${it.deck.replace('_', ' ')}") }
            val counts = stemmed.flatten().groupingBy { it }.eachCount()
            val names = stemmed.map { name -> name.associateWith { ln(stemmed.size.toDouble() / counts.getValue(it)) } }
            val deckVecs = embeddings.remember(covers.map { i ->
                val deck = slides[i].deck
                "title: ${deck.replace('_', ' ')} | text: " + slides.filter { it.deck == deck }.joinToString("; ") { it.title }
            })
            val fileVecs = embeddings.remember(files.map(::fileDoc))
            // A document that says little ("Screenshot.png") is close to every request, so what counts is how much
            // closer a request is to it than a request naming nothing is: the unfinished examples
            val empty = embeddings.remember(unfinished.map { QUERY + it }).let { v ->
                FloatArray(v[0].size) { j -> (v.sumOf { it[j].toDouble() } / v.size).toFloat() }
            }
            // "X (Twitter)" is named X and Twitter; names under three letters match too much
            val sites = LinkedHashMap<String, Target.Site>()
            for ((name, url) in SITES) for (n in name.removeSuffix(")").split(" (")) if (n.length >= 3) sites[n] = Target.Site(url, name)
            val places = LinkedHashMap<String, Target>(sites)
            for (app in apps) if (app.length >= 3) places[app] = Target.App(app)
            return Catalog(
                embeddings, slides.map { Target.Slide(it.deck, it.page, it.title) }, slideVecs, covers, deckVecs, names, files, fileVecs,
                FloatArray(slides.size) { dot(slideVecs[it], empty) }, FloatArray(files.size) { dot(fileVecs[it], empty) }, sites, places,
            )
        }
    }
}

/** difflib's real_quick_ratio and quick_ratio, the bounds SequenceMatcher checks before the full ratio. */
private fun quickRatios(a: String, b: String): Double {
    if (2.0 * minOf(a.length, b.length) / (a.length + b.length) < FUZZY) return 0.0
    val left = b.groupingBy { it }.eachCount().toMutableMap()
    var matches = 0
    for (c in a) left[c]?.let { if (it > 0) { matches++; left[c] = it - 1 } }
    return 2.0 * matches / (a.length + b.length)
}

/** difflib.SequenceMatcher(None, a, b).ratio(): twice the characters in the recursive longest matches over the total. */
private fun ratio(a: String, b: String): Double {
    val b2j = HashMap<Char, MutableList<Int>>()
    b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() } += j }
    fun matched(alo: Int, ahi: Int, blo: Int, bhi: Int): Int {
        var besti = alo
        var bestj = blo
        var best = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                next[j] = k
                if (k > best) { besti = i - k + 1; bestj = j - k + 1; best = k }
            }
            j2len = next
        }
        if (best == 0) return 0
        return best + (if (alo < besti && blo < bestj) matched(alo, besti, blo, bestj) else 0) +
            (if (besti + best < ahi && bestj + best < bhi) matched(besti + best, ahi, bestj + best, bhi) else 0)
    }
    val total = a.length + b.length
    return if (total == 0) 1.0 else 2.0 * matched(0, a.length, 0, b.length) / total
}
