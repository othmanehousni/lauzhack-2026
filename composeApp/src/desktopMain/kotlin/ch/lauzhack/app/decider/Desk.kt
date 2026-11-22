package ch.lauzhack.app.decider

import ch.lauzhack.app.Target
import ch.lauzhack.app.engine.EmbeddingGemma
import ch.lauzhack.app.engine.Engines
import ch.lauzhack.app.stage.AgentDesktop
import ch.lauzhack.app.stage.DeckPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.time.Duration.Companion.minutes

/** The presenter's bookmarks: the websites brain.py offered. */
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

/** The catalog's items: every deck page, the bookmarks, the apps and the files. */
fun items(decks: List<DeckPage>, apps: List<String>, files: List<String>, sites: List<Target.Site> = BOOKMARKS): List<Item> {
    val pages = decks.groupingBy { it.deck }.eachCount()
    return decks.map {
        Item.Slide(it.deck.replace('_', ' '), it.page, pages.getValue(it.deck), it.title, it.text.removePrefix(it.title).trim(), Target.Slide(it.deck, it.page, it.title))
    } + sites.map(Catalog::site) + apps.map { Item.App(it, Target.App(it)) } + files.map(Catalog::file)
}

/**
 * The model agent over [desk]'s decks and apps, the bookmarks and the files the stage lists, or null while
 * `decider.litertlm` is not in the models folder: use MoleAgent then. It starts without files; they are embedded in
 * [scope] once the stage has listed them, and the catalog is rebuilt every ten minutes. Vectors are kept on disk by
 * document, so a restart or a rebuild embeds only what is new.
 */
suspend fun modelAgent(engines: Engines, desk: AgentDesktop, scope: CoroutineScope): ModelAgent? {
    val client = engines.client(DECIDER) ?: return null
    val embedder = engines.embedder()
    val cache = File(engines.cacheDir, "decider-vectors-${EmbeddingGemma.name}.bin")
    var known = withContext(Dispatchers.IO) { loadVectors(cache) }
    val agent = ModelAgent(Catalog.build(items(desk.decks, desk.apps, emptyList()), embedder, known), embedder, decider(client))
    scope.launch {
        while (true) {
            val catalog = Catalog.build(items(desk.decks, desk.apps, desk.newestFiles()), embedder, known)
            agent.catalog = catalog
            known = catalog.known
            withContext(Dispatchers.IO) { saveVectors(cache, known) }
            delay(10.minutes)
        }
    }
    return agent
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
