package ch.lauzhack.app.stage

import ch.lauzhack.app.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.copyTo
import kotlin.io.path.createTempDirectory

/** A page the presenter had open in a browser, and when they last did. */
data class Page(val title: String, val url: String, val seen: Instant) {
    val target get() = Target.Site(url, title)
}

/** The Chromium browsers' folders under Application Support, each with a folder per profile. Opera keeps its one
 * profile in the folder itself. */
private val CHROMIUM = listOf(
    "Google/Chrome", "Google/Chrome Beta", "Google/Chrome Dev", "Google/Chrome Canary", "Chromium", "Arc/User Data",
    "BraveSoftware/Brave-Browser", "Microsoft Edge", "Microsoft Edge Beta", "Vivaldi", "com.operasoftware.Opera",
    "Dia/User Data", "Comet",
)

private const val WEBKIT_EPOCH = 11_644_473_600_000_000L  // Chromium counts microseconds from 1601

// Visits where the browser stayed (a redirect chain's end), outside frames, not a search typed in the address bar or a
// keyword, not a form's result, to pages no search engine produced (keyword_search_terms lists those it did).
private const val VISITS = """SELECT u.url, u.title, MAX(v.visit_time) AS t FROM visits v JOIN urls u ON u.id = v.url
WHERE v.visit_time >= %d AND (v.transition & 0x20000000) != 0 AND (v.transition & 255) NOT IN (3, 4, 5, 7, 9, 10)
  AND u.hidden = 0 AND u.id NOT IN (SELECT url_id FROM keyword_search_terms)
  AND (u.url LIKE 'http://%%' OR u.url LIKE 'https://%%' OR u.url LIKE 'file://%%')
GROUP BY u.id ORDER BY t DESC LIMIT 2000"""

/** The pages the presenter had open lately, from the history of each profile of the Chromium browsers on this Mac.
 * Safari's history needs Full Disk Access, so it is not read. A database is copied to a private temporary folder, read
 * with the sqlite3 macOS ships and deleted; its pages stay in memory until it changes, and nothing of them is written. */
class History(
    private val support: File = File(HOME, "Library/Application Support"),
    private val folders: List<File> = FOLDERS.map { File(HOME, it) },
) {
    private val read = ConcurrentHashMap<File, Pair<List<Long>, List<Page>>>()

    /** Each profile's History database, by its folder under Application Support, such as "Arc/User Data/Default". */
    val profiles: Map<String, File>
        get() = CHROMIUM.map { File(support, it) }.flatMap { listOf(it) + it.listFiles(File::isDirectory).orEmpty() }
            .map { File(it, "History") }.filter(File::isFile).associateBy { it.parentFile.relativeTo(support).path }

    /** The pages of the last `days` of every profile, most recent first, one per address (its fragment aside). */
    fun pages(days: Long = 14, max: Int = 300, now: Instant = Instant.now()): List<Page> {
        val since = now.minus(Duration.ofDays(days))
        return profiles.values.flatMap { db ->
            val stamp = listOf("", "-wal", "-journal").map { File(db.path + it) }.flatMap { listOf(it.lastModified(), it.length()) }
            read[db]?.takeIf { it.first == stamp }?.second
                ?: runCatching { chromium(db, since) }.getOrDefault(emptyList()).also { read[db] = stamp to it }
        }.filter { it.seen >= since && presentable(it.url) }
            .sortedByDescending { it.seen }.distinctBy { it.url.substringBefore('#') }.take(max)
    }

    /** A sign-in, consent or hand-off page carries the address to go back to in its query (continue=https://...,
     * return_to=/settings). A local page counts while its file is in a folder the shared screen reads. */
    private fun presentable(url: String): Boolean {
        val query = url.substringBefore('#').substringAfter('?', "").split('&').map { decode(it.substringAfter('=', "")) }
        if (query.any { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") }) return false
        if (!url.startsWith("file:")) return true
        val file = runCatching { File(URI(url.substringBefore('#').substringBefore('?'))) }.getOrNull() ?: return false
        return file.isFile && folders.any { file.startsWith(it) }
    }

    /** The pages of one History database visited since then, the most recent 2000. */
    internal fun chromium(db: File, since: Instant): List<Page> {
        val dir = createTempDirectory("mole-history")  // readable by this user only
        try {
            for (suffix in listOf("", "-wal", "-journal")) File(db.path + suffix).takeIf(File::isFile)?.toPath()?.copyTo(dir.resolve("History$suffix"))
            val micros = since.epochSecond * 1_000_000 + since.nano / 1000 + WEBKIT_EPOCH
            val out = exec(listOf("/usr/bin/sqlite3", "-json", dir.resolve("History").toString(), VISITS.format(micros)), what = "reading a browser history")
            if (out.isBlank()) return emptyList()
            return Json.parseToJsonElement(out).jsonArray.map { it.jsonObject }.map { row ->
                val url = row["url"]!!.jsonPrimitive.content
                val seen = Instant.EPOCH.plus(row["t"]!!.jsonPrimitive.long - WEBKIT_EPOCH, ChronoUnit.MICROS)
                Page(row["title"]?.jsonPrimitive?.content.orEmpty().trim().ifEmpty { name(url) }, url, seen)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

private fun decode(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

/** For a page without a title: the last part of its path, else its host. */
private fun name(url: String) = url.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')
    .let { decode(it.substringAfterLast('/')).ifEmpty { it } }
