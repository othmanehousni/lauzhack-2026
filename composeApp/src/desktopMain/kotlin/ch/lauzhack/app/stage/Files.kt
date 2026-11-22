package ch.lauzhack.app.stage

import ch.lauzhack.app.Target
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A file the presenter used or changed lately, and when. */
data class UsedFile(val path: String, val seen: Instant) {
    val target get() = Target.File(path)
}

/** A file as Spotlight lists it: when it was last opened (epoch ms, 0 if never) and, for a PDF, its page count and the
 * size of its first page. */
internal data class Spot(val path: String, val used: Long, val pages: Int = 0, val width: Double = 0.0, val height: Double = 0.0) {
    val name = path.substringAfterLast('/').lowercase()

    /** When the presenter last used it: opened it, or changed it if that came later. 0 once it is gone. */
    fun seen() = File(path).lastModified().let { if (it == 0L) 0L else maxOf(it, used) }
}

private const val USED = "kMDItemLastUsedDate"
private const val PAGES = "kMDItemNumberOfPages"
private const val WIDTH = "kMDItemPageWidth"
private const val HEIGHT = "kMDItemPageHeight"
private val ATTRS = listOf(USED, PAGES, WIDTH, HEIGHT)
private const val DAY = 86_400_000L
private val ATTR = Regex("""(kMDItem\w+) = (.*?)(?=   kMDItem|$)""")
private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

/** mdfind -attr's lines: a path, then "   kMDItemX = value" for each attribute asked for, "(null)" when it has none. */
internal fun spots(out: String): List<Spot> = out.lines().mapNotNull { line ->
    val at = line.indexOf("   kMDItem").takeIf { it > 0 } ?: return@mapNotNull null
    val a = ATTR.findAll(line.substring(at)).associate { it.groupValues[1] to it.groupValues[2].trim() }
    val used = a[USED]?.let { runCatching { ZonedDateTime.parse(it, DATE).toInstant().toEpochMilli() }.getOrNull() } ?: 0
    Spot(line.substring(0, at), used, a[PAGES]?.toIntOrNull() ?: 0, a[WIDTH]?.toDoubleOrNull() ?: 0.0, a[HEIGHT]?.toDoubleOrNull() ?: 0.0)
}

/** Showable files in the folders the VM mounts, so their paths are the same in the VM. Spotlight lists them in the
 * background (about 6 s for 180k files); after that a lookup is a scan of names in memory. Until then, and for files
 * newer than the list, Spotlight answers directly in about 0.1 s. `mdfind` runs mdfind with these arguments. */
class Files(private val mdfind: (List<String>) -> String = { exec(listOf("mdfind") + it, check = false) }) {
    private val places = FOLDERS.flatMap { listOf("-onlyin", File(HOME, it).path) }
    private val repos = ConcurrentHashMap<String, Boolean>()
    @Volatile private var all: CompletableFuture<List<Spot>> = CompletableFuture.supplyAsync { spotlight(QUERY) }
    @Volatile private var listed = System.currentTimeMillis()

    private fun inRepository(dir: File?): Boolean =
        dir != null && repos.getOrPut(dir.path) { File(dir, ".git").exists() || inRepository(dir.parentFile) }

    private fun outsideCode(path: String) = path.split('/').dropLast(1)
        .none { it.startsWith(".") || it in setOf("node_modules", "site-packages", "Library") || it.endsWith(".app") || it.endsWith(".framework") || it.endsWith(".bundle") }

    /** Not inside a hidden folder, a package, a library of code or a code repository, where a `.ts` file is TypeScript,
     * not a video. */
    internal fun wanted(path: String): Boolean = outsideCode(path) && !inRepository(File(path).parentFile)

    private fun spotlight(query: String, keep: (Spot) -> Boolean = { wanted(it.path) }) =
        spots(mdfind(places + ATTRS.flatMap { listOf("-attr", it) } + query)).filter(keep)

    /** The n files the presenter used most recently, for the brain to search by meaning. Waits up to a minute for the list. */
    fun newest(n: Int = 1500): List<String> =
        runCatching { all.get(60, TimeUnit.SECONDS) }.getOrDefault(emptyList())
            .map { it.path to it.seen() }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(n).map { it.first }

    /** The most recently used file whose name holds every word of the query (its first three). */
    fun find(query: String): String? {
        val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty).take(3)
        if (words.isEmpty()) return null
        val hits = all.getNow(emptyList()).filter { s -> words.all { it in s.name } }
            .ifEmpty { spotlight(words.joinToString(" && ") { "kMDItemDisplayName == \"*$it*\"cd" }) }
        return hits.map { it.path to it.seen() }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /** The files used or changed in the last `days`, most recent first. Spotlight is asked afresh for those opened and
     * those changed in the last day, which are few; the others come from the list, made again in the background once it
     * is an hour old. Asking Spotlight for 14 days of changes takes seconds on a Mac with code: it returns every file an
     * install or a build touched, before the folders of code are left out. A PDF of several pages the presenter opened
     * is theirs even in a code repository, where a developer keeps a deck; it is not wanted() as a file. */
    internal fun recent(days: Int = 14): List<Spot> {
        val now = System.currentTimeMillis()
        if (all.isDone && now - listed > DAY / 24) {
            listed = now
            CompletableFuture.supplyAsync { spotlight(QUERY) }.thenAccept { all = CompletableFuture.completedFuture(it) }
        }
        val fresh = spotlight("($QUERY) && ($USED >= \$time.now(-${days * 86_400}) || kMDItemFSContentChangeDate >= \$time.now(-86400))") {
            wanted(it.path) || it.used > 0 && it.pages >= 3 && outsideCode(it.path)
        }
        return (fresh + all.getNow(emptyList())).distinctBy { it.path }.map { it to it.seen() }
            .filter { it.second >= now - days * DAY }.sortedByDescending { it.second }.map { it.first }
    }

    companion object {
        val KINDS = listOf(
            "public.image", "public.movie", "com.adobe.pdf", "public.presentation", "public.spreadsheet", "public.composite-content",
            "public.html", "net.daringfireball.markdown", "public.rtf", "public.delimited-values-text",
        )

        // Plain text by its own type, not its type tree: source code is plain text too.
        private val QUERY = (KINDS.map { "kMDItemContentTypeTree == \"$it\"" } + "kMDItemContentType == \"public.plain-text\"").joinToString(" || ")
    }
}
