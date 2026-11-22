package ch.lauzhack.app.decider

import ai.koog.embeddings.base.Embedder
import ch.lauzhack.app.Agent
import ch.lauzhack.app.Target
import ch.lauzhack.app.engine.EmbeddingGemma
import ch.lauzhack.app.engine.Engines
import ch.lauzhack.app.engine.LiteRTClientConfig
import ch.lauzhack.app.engine.LiteRTLLMClient
import ch.lauzhack.app.log
import ch.lauzhack.app.stage.AgentDesktop
import ch.lauzhack.app.stage.DeckPage
import ch.lauzhack.app.stage.Page
import ch.lauzhack.app.stage.Recents
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** The presenter's bookmarks: well-known websites, always in the catalog. */
val BOOKMARKS = listOf(
    "YouTube" to "https://www.youtube.com", "GitHub" to "https://github.com", "Google" to "https://www.google.com",
    "Gmail" to "https://mail.google.com", "Google Maps" to "https://www.google.com/maps", "Google Drive" to "https://drive.google.com",
    "Google Docs" to "https://docs.google.com", "Google Sheets" to "https://sheets.google.com", "LinkedIn" to "https://www.linkedin.com",
    "X (Twitter)" to "https://x.com", "Facebook" to "https://www.facebook.com", "Instagram" to "https://www.instagram.com",
    "Reddit" to "https://www.reddit.com", "Wikipedia" to "https://en.wikipedia.org", "Amazon" to "https://www.amazon.com",
    "Netflix" to "https://www.netflix.com", "Spotify" to "https://open.spotify.com", "Notion" to "https://www.notion.so",
    "Figma" to "https://www.figma.com", "Slack" to "https://app.slack.com", "Discord" to "https://discord.com/app",
    "ChatGPT" to "https://chatgpt.com", "Claude" to "https://claude.ai", "Hacker News" to "https://news.ycombinator.com",
    "Product Hunt" to "https://www.producthunt.com", "Stack Overflow" to "https://stackoverflow.com", "Devpost" to "https://devpost.com",
    "Hugging Face" to "https://huggingface.co", "arXiv" to "https://arxiv.org", "TikTok" to "https://www.tiktok.com",
    "Twitch" to "https://www.twitch.tv", "Medium" to "https://medium.com", "Apple" to "https://www.apple.com", "Vercel" to "https://vercel.com",
    "LauzHack" to "https://lauzhack.com", "EPFL" to "https://www.epfl.ch", "OpenAI" to "https://openai.com", "Anthropic" to "https://www.anthropic.com",
).map { (name, url) -> Target.Site(url, name) }

/** The catalog's items: every deck page, the bookmarks, the apps and the files, and what the presenter saw lately: the
 * pages of [recents]' decks as slides, its browser pages as sites and its files, most recent first, before [files]. */
fun items(decks: List<DeckPage>, apps: List<String>, files: List<String>, sites: List<Target.Site> = BOOKMARKS, recents: Recents? = null): List<Item> {
    val slides = decks + recents?.decks.orEmpty().flatMap { it.pages }
    val pages = slides.groupingBy { it.deck }.eachCount()
    return slides.map {
        Item.Slide(it.deck.replace('_', ' '), it.page, pages.getValue(it.deck), it.title, it.points, Target.Slide(it.deck, it.page, it.title))
    } + (sites.map(Catalog::site) + recents?.pages.orEmpty().map(::page)).distinctBy { Catalog.site(it.target).url } +
        apps.map { Item.App(it, Target.App(it)) } + (recents?.files.orEmpty().map { it.path } + files).distinct().map(Catalog::file)
}

/** A browser page as the decider reads it: its address without the query or fragment, noise to it, and long. */
fun page(p: Page): Item.Site = Catalog.site(p.target).let { it.copy(url = it.url.substringBefore('?').substringBefore('#')) }

/** The names the ear is to expect, most useful first: decks, slide titles, sites and apps, then files without extension. */
fun vocabulary(items: List<Item>): List<String> {
    val slides = items.filterIsInstance<Item.Slide>()
    val names = slides.map { it.deck } + slides.map { it.title } + items.mapNotNull { (it as? Item.Site)?.name ?: (it as? Item.App)?.name } +
        items.filterIsInstance<Item.File>().map { it.name.substringBeforeLast('.') }
    return names.map(::clean).filter(String::isNotEmpty).distinct()
}

private val REBUILD = 1.minutes  // a page opened a minute ago is a candidate

/**
 * The model decides: [ModelAgent] over the desk's decks, documents and apps, the bookmarks and what the presenter saw lately
 * (AgentDesktop.recents), of which only the files used or changed in the last 14 days, not the Mac's newest 1500
 * (tune/CONTRACT.md, Candidates at real scale). A call starts on what is quick to have: the library, the apps, the bookmarks and whatever was
 * embedded before; the rest is embedded in the background, and the catalog is rebuilt every [REBUILD] of the call.
 * Vectors are kept on disk by document, so a restart or a rebuild embeds only what is new, except those of the browser's
 * pages, which stay in memory as the history does.
 */
class ModelBrain(private val engines: Engines) {
    private val cache = File(engines.cacheDir, "decider-vectors-${EmbeddingGemma.name}.bin")
    private lateinit var embedder: Embedder
    private lateinit var model: suspend (String, String?) -> String
    @Volatile private var known = emptyMap<String, FloatArray>()
    private var saved = emptySet<String>()
    private val browsed = ConcurrentHashMap.newKeySet<String>() // documents of browser pages, never written

    /** Loads both models and the vectors kept on disk, before a call or while the shared screen comes up; a call waits
     * for it. Each model is kept as soon as it loads, so a retry after a failure loads only what is missing. */
    suspend fun warm() = coroutineScope {
        val decider = File(engines.modelsDir, DECIDER)
        check(decider.isFile) { "No decider: put $DECIDER in ${decider.parent}." }
        val vectors = async(Dispatchers.IO) { loadVectors(cache) }
        val decide = async(Dispatchers.IO) {
            // on CPU, as the contract has it: on the GPU, LLGuidance lets <pad> through and the constrained answer fails
            if (!::model.isInitialized) model = ahead(decider(LiteRTLLMClient(LiteRTClientConfig(engines.modelsDir, engines.cacheDir, backends = listOf(Backend.CPU())))))
        }
        if (!::embedder.isInitialized) withContext(Dispatchers.IO) {
            embedder = engines.embedder().also { it.embed("task: search result | query: hello") } // its first call takes longer
        }
        known = vectors.await().also { saved = it.keys }
        decide.await()
    }

    /** The agent for a call on [desk], which is up, and the names the ear is to expect, most useful first. [scope] is
     * the call's, for work that runs as long as it does. */
    suspend fun meet(desk: AgentDesktop, scope: CoroutineScope): Pair<Agent, List<String>> {
        val recents = desk.recents()
        val all = items(desk.decks, desk.apps, desk.documents, recents = recents)
        val base = items(desk.decks, desk.apps, desk.documents)
        val quick = base.toSet()
        val meeting = desk.decks.map { it.deck }.toSet()
        val agent = ModelAgent(build(all.filter { it in quick || Catalog.docs(it).all(known::containsKey) }, meeting, recents, whole = false), embedder, model, note = ::log)
        scope.launch(Dispatchers.Default) {
            var seen = recents
            while (true) {
                agent.catalog = build(items(desk.decks, desk.apps, desk.documents, recents = seen), meeting, seen, whole = true)
                delay(REBUILD)
                seen = desk.recents()
            }
        }
        return agent to (vocabulary(base) + vocabulary(all)).distinct() // the library's before recent decks' hundreds of titles
    }

    /** The catalog of [items], embedding only what is new; a [whole] one is all there is to keep. */
    private suspend fun build(items: List<Item>, meeting: Set<String>, recents: Recents, whole: Boolean): Catalog {
        val t = TimeSource.Monotonic.markNow()
        val recent = (recents.files.map { it.target } + recents.pages.map { it.target }).toSet()
        val catalog = Catalog.build(items, embedder, known, meeting, recent)
        val new = catalog.known.keys.count { it !in known }
        known = if (whole) catalog.known else known + catalog.known
        recents.pages.flatMapTo(browsed) { Catalog.docs(page(it)) }
        val keep = known.filterKeys { it !in browsed }
        if (keep.keys != saved) {
            withContext(Dispatchers.IO) { saveVectors(cache, keep) }
            saved = keep.keys
        }
        log("catalog of ${items.count { it is Item.Slide }} slides, ${items.count { it is Item.File }} files, ${items.count { it is Item.Site }} sites " +
            "(${recents.pages.size} browser pages), " +
            "${items.count { it is Item.App }} apps; $new embedded, in ${t.elapsedNow().inWholeMilliseconds} ms")
        return catalog
    }
}

/** [decide] with its engine loaded and a conversation of each kind made ahead, so that the first utterance waits for neither. */
private suspend fun ahead(decide: suspend (String, String?) -> String) = decide.also {
    val s = State()
    it(prompt(s), null)
    it(prompt(s), grammar(s))
}

fun loadVectors(file: File): Map<String, FloatArray> = runCatching {
    DataInputStream(file.inputStream().buffered()).use { input ->
        HashMap<String, FloatArray>().apply {
            repeat(input.readInt()) {
                val doc = ByteArray(input.readInt()).also(input::readFully).decodeToString()
                put(doc, FloatArray(input.readInt()) { input.readFloat() })
            }
        }
    }
}.getOrDefault(emptyMap())

fun saveVectors(file: File, vectors: Map<String, FloatArray>) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    DataOutputStream(tmp.outputStream().buffered()).use { out ->
        out.writeInt(vectors.size)
        for ((doc, v) in vectors) {
            val bytes = doc.encodeToByteArray()
            out.writeInt(bytes.size)
            out.write(bytes)
            out.writeInt(v.size)
            v.forEach(out::writeFloat)
        }
    }
    tmp.renameTo(file)
}
