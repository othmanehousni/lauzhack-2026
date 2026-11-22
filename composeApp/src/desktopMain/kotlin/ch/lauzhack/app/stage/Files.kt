package ch.lauzhack.app.stage

import ch.lauzhack.app.Target
import ch.lauzhack.app.log
import org.apache.pdfbox.Loader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files as Nio

/** A file the presenter used or changed lately, and when. */
data class UsedFile(val path: String, val seen: Instant) {
    val target get() = Target.File(path)
}

/** A file as Spotlight or the walk lists it: when it was last opened (epoch ms, 0 if never) and, for a PDF, its page
 * count and the size of its first page. */
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
private const val GRACE = 5 * 60_000L  // a working Spotlight has a file opened or changed this long ago
private const val IDLE = 5 * 60_000L  // the walk rests once nothing asked for this long
private val ATTR = Regex("""(kMDItem\w+) = (.*?)(?=   kMDItem|$)""")
private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
private val SPOTLIGHT: (List<String>) -> String = { exec(listOf("mdfind") + it, check = false) }
private val WALKER = Executors.newSingleThreadScheduledExecutor { Thread(it, "files walk").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

/** mdfind -attr's lines: a path, then "   kMDItemX = value" for each attribute asked for, "(null)" when it has none. */
internal fun spots(out: String): List<Spot> = out.lines().mapNotNull { line ->
    val at = line.indexOf("   kMDItem").takeIf { it > 0 } ?: return@mapNotNull null
    val a = ATTR.findAll(line.substring(at)).associate { it.groupValues[1] to it.groupValues[2].trim() }
    val used = a[USED]?.let { runCatching { ZonedDateTime.parse(it, DATE).toInstant().toEpochMilli() }.getOrNull() } ?: 0
    Spot(line.substring(0, at), used, a[PAGES]?.toIntOrNull() ?: 0, a[WIDTH]?.toDoubleOrNull() ?: 0.0, a[HEIGHT]?.toDoubleOrNull() ?: 0.0)
}

/** When macOS last saw each file opened, from the attribute it sets on the file (`com.apple.lastuseddate#PS`: seconds
 * then nanoseconds, little-endian), as epoch ms; files without it are left out. */
internal fun lastUsed(paths: List<String>): Map<String, Long> = paths.filter { '\n' !in it }.chunked(200).flatMap { batch ->
    val out = exec(listOf("/usr/bin/xattr", "-p", "-x", "com.apple.lastuseddate#PS") + batch, check = false).lines()
    // for one file xattr prints the value alone; for more, "path: " and the value on the next line
    val pairs = if (batch.size == 1) listOf(batch[0] to out.firstOrNull().orEmpty()) else
        out.zipWithNext().filter { (a, _) -> a.endsWith(": ") }.map { (a, b) -> a.removeSuffix(": ") to b }
    pairs.mapNotNull { (path, hex) ->
        val bytes = hex.trim().split(' ').mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray().takeIf { it.size >= 16 } ?: return@mapNotNull null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        path to b.getLong(0) * 1000 + b.getLong(8) / 1_000_000
    }
}.toMap()

/** A folder as the walk last listed it: its subfolders to walk and its files of a showable kind. */
private class Folder(val modified: Long, val folders: List<String>, val files: List<String>, val repo: Boolean)

/** Showable files in the folders the VM mounts, so their paths are the same in the VM. Spotlight lists them in the
 * background (about 6 s for 180k files); after that a lookup is a scan of names in memory. Until then, and for files
 * newer than the list, Spotlight answers directly in about 0.1 s. `mdfind` runs mdfind with these arguments.
 *
 * Spotlight may stop answering or indexing, on a Mac where it otherwise works. So the app also walks `roots` itself, in
 * the background every 20 s while asked; the files it finds join Spotlight's, and it says once when Spotlight lacks
 * some. A walk stops after `budget` ms. */
class Files(
    private val roots: List<File> = emptyList(),
    private val budget: Long = 10_000,
    private val note: (String) -> Unit = ::log,
    private val mdfind: (List<String>) -> String = SPOTLIGHT,
) {
    private val places = FOLDERS.flatMap { listOf("-onlyin", File(HOME, it).path) }
    private val repos = ConcurrentHashMap<String, Boolean>()
    @Volatile private var all: CompletableFuture<List<Spot>> = CompletableFuture.supplyAsync { spotlight(QUERY) }
    @Volatile private var listed = System.currentTimeMillis()
    @Volatile private var asked = System.currentTimeMillis()
    @Volatile private var onDisk: List<Spot> = emptyList()
    @Volatile private var behind = false
    private val folders = HashMap<String, Folder>()  // the walk's own, as the next two
    private val opened = HashMap<String, Pair<Long, Long>>()  // a file's last use by its status change time
    private val paged = HashMap<String, Spot>()  // a PDF's pages by its version

    /** Done once the first walk is. */
    internal val walked = CompletableFuture<Unit>()

    init {
        WALKER.scheduleWithFixedDelay({
            if (!walked.isDone || System.currentTimeMillis() - asked < IDLE) runCatching { onDisk = walk() }
            walked.complete(Unit)
        }, 0, 20, TimeUnit.SECONDS)
    }

    private fun inRepository(dir: File?): Boolean =
        dir != null && repos.getOrPut(dir.path) { File(dir, ".git").exists() || inRepository(dir.parentFile) }

    private fun code(folder: String) = folder.startsWith(".") || folder in setOf("node_modules", "site-packages", "Library") ||
        folder.endsWith(".app") || folder.endsWith(".framework") || folder.endsWith(".bundle")

    private fun outsideCode(path: String) = path.split('/').dropLast(1).none(::code)

    /** Not inside a hidden folder, a package, a library of code or a code repository, where a `.ts` file is TypeScript,
     * not a video. */
    internal fun wanted(path: String): Boolean = outsideCode(path) && !inRepository(File(path).parentFile)

    /** What recent() keeps. A PDF of several pages the presenter opened is theirs even in a code repository, where a
     * developer keeps a deck; it is not wanted() as a file. */
    private fun kept(s: Spot) = wanted(s.path) || s.used > 0 && s.pages >= 3 && outsideCode(s.path)

    private fun spotlight(query: String, keep: (Spot) -> Boolean = { wanted(it.path) }) =
        spots(mdfind(places + ATTRS.flatMap { listOf("-attr", it) } + query)).filter(keep)

    /** The n files the presenter used most recently, for the brain to search by meaning. Waits up to a minute for the
     * lists. */
    fun newest(n: Int = 1500): List<String> {
        asked = System.currentTimeMillis()
        val listed = runCatching { all.get(60, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        runCatching { walked.get(60, TimeUnit.SECONDS) }
        return joined(listed, onDisk.filter { wanted(it.path) })
            .map { it.path to it.seen() }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(n).map { it.first }
    }

    /** The most recently used file whose name holds every word of the query (its first three). */
    fun find(query: String): String? {
        asked = System.currentTimeMillis()
        val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty).take(3)
        if (words.isEmpty()) return null
        val hits = (all.getNow(emptyList()) + onDisk.filter { wanted(it.path) }).filter { s -> words.all { it in s.name } }
            .ifEmpty { spotlight(words.joinToString(" && ") { "kMDItemDisplayName == \"*$it*\"cd" }) }
        return hits.map { it.path to it.seen() }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /** The files used or changed in the last `days`, most recent first. Spotlight is asked afresh for those opened and
     * those changed in the last day, which are few; the others come from the list, made again in the background once it
     * is an hour old, and from the last walk. Asking Spotlight for 14 days of changes takes seconds on a Mac with code:
     * it returns every file an install or a build touched, before the folders of code are left out. */
    internal fun recent(days: Int = 14): List<Spot> {
        val now = System.currentTimeMillis()
        asked = now
        if (all.isDone && now - listed > DAY / 24) {
            listed = now
            CompletableFuture.supplyAsync { spotlight(QUERY) }.thenAccept { all = CompletableFuture.completedFuture(it) }
        }
        val fresh = spotlight("($QUERY) && ($USED >= \$time.now(-${days * 86_400}) || kMDItemFSContentChangeDate >= \$time.now(-86400))", ::kept)
        val disk = onDisk.filter(::kept)
        val dated = joined(fresh + all.getNow(emptyList()), disk).map { it to it.seen() }
        if (walked.isDone) compare(fresh, disk.mapTo(HashSet()) { it.path }, dated, now, days)
        return dated.filter { it.second >= now - days * DAY }.sortedByDescending { it.second }.map { it.first }
    }

    /** Spotlight's spots then the walk's, one per file: Spotlight's pages unless it has none, the later last use. */
    private fun joined(spotlight: List<Spot>, disk: List<Spot>): List<Spot> {
        val byPath = disk.associateBy { it.path }
        val known = HashSet<String>()
        return spotlight.filter { known.add(it.path) }.map { s ->
            byPath[s.path]?.let { d -> (if (s.pages > 0) s else d).copy(used = maxOf(s.used, d.used)) } ?: s
        } + disk.filter { it.path !in known }
    }

    /** Says once that Spotlight cannot be trusted when its fresh answer lacks a file the walk found opened in the last
     * `days` or changed in the last day, more than GRACE ago: Spotlight is off, answers nothing, or stopped indexing. */
    private fun compare(fresh: List<Spot>, disk: Set<String>, dated: List<Pair<Spot, Long>>, now: Long, days: Int) {
        val answered = fresh.mapTo(HashSet()) { it.path }
        val lately = dated.filter { (s, seen) -> s.path in disk && (s.used in now - days * DAY..now - GRACE || seen in now - DAY..now - GRACE) }.map { it.first }
        val lacking = lately.filter { it.path !in answered }
        if (lacking.isNotEmpty() == behind) return
        behind = lacking.isNotEmpty()
        if (!behind) return note("files   Spotlight has every file used lately again")
        fun under(r: File, s: Spot) = s.path.startsWith(r.path + "/")
        val silent = roots.filter { r -> fresh.none { under(r, it) } && lacking.any { under(r, it) } }.map { it.name }
        note("files   Spotlight lacks ${lacking.size} of the ${lately.size} files used lately" +
            (if (silent.isEmpty()) "" else " and answers nothing under ${silent.joinToString()}") +
            ", so recent files and decks come from the app's own walk of ${roots.joinToString { it.name }}")
    }

    /** One walk of the roots: the files of a showable kind that wanted() keeps and, in code repositories, the PDFs, each
     * with its last use and, for a recent PDF, its pages. A folder is listed again only once it changed (a file added,
     * removed or renamed in it), so a walk after the first costs a stat of each folder and file. Folders outside code
     * repositories go first; past `budget`, what is left waits for the next walk. */
    internal fun walk(): List<Spot> {
        val start = System.currentTimeMillis()
        val outside = ArrayDeque<File>()
        val inside = ArrayDeque<File>()
        roots.forEach { (if (inRepository(it)) inside else outside).add(it) }
        val found = mutableListOf<String>()
        val visited = HashSet<String>()
        while (System.currentTimeMillis() - start < budget) {
            val inRepo = outside.isEmpty()
            val dir = outside.removeFirstOrNull() ?: inside.removeFirstOrNull() ?: break
            val modified = dir.lastModified()
            val f = folders[dir.path]?.takeIf { it.modified == modified } ?: list(dir, modified, inRepo) ?: continue
            folders[dir.path] = f
            visited += dir.path
            for (n in f.folders) (if (f.repo) inside else outside).add(File(dir, n))
            f.files.mapTo(found) { "${dir.path}/$it" }
        }
        if (outside.isEmpty() && inside.isEmpty()) folders.keys.retainAll(visited)
        val times = found.mapNotNull { p ->
            val a = runCatching { Nio.readAttributes(File(p).toPath(), "unix:lastModifiedTime,ctime", LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            a?.let { p to ((it["lastModifiedTime"] as FileTime).toMillis() to (it["ctime"] as FileTime).toMillis()) }
        }
        // Setting the last-use attribute changes a file's status time; a file whose status did not change after its
        // content was last used no later than that.
        val ask = times.filter { (p, t) -> t.second > t.first + 2000 && opened[p]?.first != t.second }
        val got = lastUsed(ask.map { it.first })
        for ((p, t) in ask) opened[p] = t.second to (got[p] ?: 0L)
        val since = start - 14 * DAY
        val dated = times.map { (p, t) -> Spot(p, opened[p]?.takeIf { it.first == t.second }?.second ?: 0L).let { it to maxOf(t.first, it.used) } }
        return dated.sortedByDescending { it.second }.map { (s, seen) ->
            if (!s.path.endsWith(".pdf", ignoreCase = true) || seen < since || s.used == 0L && !wanted(s.path)) return@map s
            val key = version(File(s.path))
            paged[key]?.copy(used = s.used) ?: if (System.currentTimeMillis() - start < budget) pages(s).also { paged[key] = it } else s
        }
    }

    private fun list(dir: File, modified: Long, inRepo: Boolean): Folder? {
        val names = dir.list() ?: return null
        val repo = inRepo || ".git" in names
        val folders = mutableListOf<String>()
        val files = mutableListOf<String>()
        for (n in names) {
            if (n.startsWith(".")) continue
            val ext = n.substringAfterLast('.', "").lowercase()
            val shown = if (repo) ext == "pdf" else ext in EXTENSIONS
            val a = runCatching { Nio.readAttributes(File(dir, n).toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: continue
            when {
                a.isDirectory && shown -> files += n  // a package: a Keynote, Pages or Numbers document, an RTFD
                a.isDirectory -> if (!code(n)) folders += n
                a.isRegularFile && shown -> files += n
            }
        }
        return Folder(modified, folders, files, repo)
    }

    /** A PDF's page count and the size of its first page, as Spotlight gives them. */
    private fun pages(s: Spot): Spot = runCatching {
        Loader.loadPDF(File(s.path)).use { doc ->
            val p = doc.getPage(0)
            val (w, h) = p.cropBox.let { if (p.rotation % 180 == 0) it.width to it.height else it.height to it.width }
            s.copy(pages = doc.numberOfPages, width = w.toDouble(), height = h.toDouble())
        }
    }.getOrDefault(s)

    companion object {
        val KINDS = listOf(
            "public.image", "public.movie", "com.adobe.pdf", "public.presentation", "public.spreadsheet", "public.composite-content",
            "public.html", "net.daringfireball.markdown", "public.rtf", "public.delimited-values-text",
        )

        // Plain text by its own type, not its type tree: source code is plain text too.
        private val QUERY = (KINDS.map { "kMDItemContentTypeTree == \"$it\"" } + "kMDItemContentType == \"public.plain-text\"").joinToString(" || ")

        /** The common extensions of KINDS and plain text, for the walk; `ts` is left out, mostly TypeScript. */
        internal val EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "heic", "heif", "tif", "tiff", "bmp", "webp", "avif", "svg",
            "mov", "mp4", "m4v", "mts", "webm", "avi", "mpg", "mpeg", "pdf",
            "key", "pptx", "ppt", "odp", "numbers", "xlsx", "xls", "ods", "pages", "docx", "doc", "odt", "rtfd", "epub",
            "html", "htm", "md", "markdown", "rtf", "csv", "tsv", "txt",
        )
    }
}
