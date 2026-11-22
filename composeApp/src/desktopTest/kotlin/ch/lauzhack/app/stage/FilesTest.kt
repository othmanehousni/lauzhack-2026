package ch.lauzhack.app.stage

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private val SPOTLIGHT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z").withZone(ZoneOffset.UTC)

/** One line of `mdfind -attr kMDItemLastUsedDate ...`, as Spotlight prints it. */
internal fun spotLine(f: File, usedAgo: Duration?, vararg more: String) =
    f.path + "   kMDItemLastUsedDate = " + (usedAgo?.let { SPOTLIGHT_DATE.format(Instant.now().minus(it)) } ?: "(null)") + more.joinToString("") { "   $it" }

internal fun File.modified(ago: Duration) = apply { setLastModified(System.currentTimeMillis() - ago.toMillis()) }

/** As macOS marks a file it opened: the attribute Spotlight reads its last-used date from. */
internal fun File.opened(ago: Duration) = apply {
    val t = Instant.now().minus(ago)
    val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(t.epochSecond).putLong(t.nano.toLong())
    exec(listOf("/usr/bin/xattr", "-wx", "com.apple.lastuseddate#PS", b.array().joinToString("") { "%02x".format(it) }, path))
}

class FilesTest {
    private val dir = createTempDirectory("files-test").toFile()
    private fun file(name: String, modifiedAgo: Duration) = File(dir, name).apply { parentFile.mkdirs(); writeText(name) }.modified(modifiedAgo)
    private fun min(n: Long) = Duration.ofMinutes(n)
    private fun days(n: Long) = Duration.ofDays(n)

    @Test
    fun ordersFilesByWhenThePresenterLastUsedThem() {
        val deck = file("deck.key", days(30))
        val page = file("page.html", min(60))
        val photo = file("photo.png", days(5))
        val notes = file("notes.txt", min(1))  // changed a minute ago in an editor that does not mark it used
        val v1 = file("deck v1.key", min(90))
        val v2 = file("deck v2.key", days(3))
        val hidden = file(".cache/shot.png", min(0))
        val all = listOf(
            spotLine(deck, min(10)), spotLine(page, null), spotLine(photo, days(2)), spotLine(notes, days(40)),
            spotLine(v1, null), spotLine(v2, min(5)), spotLine(hidden, min(0)), spotLine(File(dir, "gone.pdf"), min(1)),
        ).joinToString("\n")
        val asked = mutableListOf<List<String>>()
        val files = Files { args -> synchronized(asked) { asked += args }; all }
        assertEquals(listOf(notes, v2, deck, page, v1, photo).map { it.path }, files.newest())
        assertEquals(listOf(notes, v2).map { it.path }, files.newest(2))
        assertEquals(v2.path, files.find("deck"))
        val query = asked.first().last()
        for (kind in listOf("public.html", "public.image", "public.spreadsheet", "public.presentation", "net.daringfireball.markdown", "public.composite-content"))
            assertTrue("kMDItemContentTypeTree == \"$kind\"" in query, kind)
        assertTrue("kMDItemContentType == \"public.plain-text\"" in query)
        assertTrue("-attr" in asked.first())
    }

    @Test
    fun recentFilesComeFreshFromSpotlightWithTheirPages() {
        val talk = file("Q3 talk.pdf", days(1))
        val report = file("report.pdf", min(120))
        val sheet = file("budget.xlsx", days(9))
        val older = file("older.html", days(3))  // changed, never opened: from the list
        val stale = file("stale.html", days(20))
        val list = listOf(spotLine(older, null), spotLine(stale, null), spotLine(sheet, null)).joinToString("\n")
        val recent = listOf(
            spotLine(report, null, "kMDItemNumberOfPages = 8", "kMDItemPageWidth = 595.2760000000001", "kMDItemPageHeight = 841.89"),
            spotLine(talk, min(60), "kMDItemNumberOfPages = 12", "kMDItemPageWidth = 960", "kMDItemPageHeight = 540"),
            spotLine(sheet, null, "kMDItemNumberOfPages = (null)", "kMDItemPageWidth = (null)", "kMDItemPageHeight = (null)"),
        ).joinToString("\n")
        val asked = mutableListOf<String>()
        val files = Files { args -> if ("kMDItemFSContentChangeDate >=" in args.last()) recent.also { asked += args.last() } else list }
        files.newest()  // the list is ready
        val spots = files.recent()
        assertEquals(listOf(talk, report, older, sheet).map { it.path }, spots.map { it.path })
        assertTrue("kMDItemFSContentChangeDate >= \$time.now(-86400)" in asked.single())
        assertEquals(Spot(talk.path, spots[0].used, 12, 960.0, 540.0), spots[0])
        assertEquals(listOf(12, 8, 0, 0), spots.map { it.pages })
    }

    /** A presenter's folder, with what Spotlight would answer for it: those opened in 14 days or changed in a day, and
     * the list of all. */
    private inner class Folder {
        val root = File(dir, "Desktop")
        private fun at(name: String) = File(root, name).apply { parentFile.mkdirs() }
        val notes = file("Desktop/notes.txt", min(1))
        val talk = pdf(at("talk.pdf"), slides(5)).modified(days(30)).opened(min(2))
        val repo = at("project/.git/HEAD").apply { writeText("ref") }.parentFile.parentFile
        val pitch = pdf(File(repo, "pitch.pdf"), slides(4)).modified(days(30)).opened(min(10))
        val paper = pdf(File(repo, "paper.pdf"), slides(4)).modified(min(30))  // never opened: the repository's
        val readme = file("Desktop/project/readme.md", min(1))
        val report = pdf(at("report.pdf"), slides(5, 595f, 842f)).modified(Duration.ofHours(3))
        val plan = file("Desktop/Plan.key/Index.zip", days(2)).parentFile.modified(days(2))  // a package
        val photo = file("Desktop/photo.png", days(40)).opened(days(3))
        val old = file("Desktop/old.html", days(20))
        val hidden = file("Desktop/.cache/shot.png", min(0))
        val module = file("Desktop/node_modules/pkg/readme.md", min(0))
        val script = file("Desktop/script.py", min(0))
        val recent = listOf(notes, talk, pitch, report, plan, photo)

        private val geometry = arrayOf("kMDItemNumberOfPages = 5", "kMDItemPageWidth = 960", "kMDItemPageHeight = 540")
        val fresh = listOf(
            spotLine(notes, null), spotLine(talk, min(2), *geometry), spotLine(pitch, min(10), "kMDItemNumberOfPages = 4", "kMDItemPageWidth = 960", "kMDItemPageHeight = 540"),
            spotLine(paper, null, "kMDItemNumberOfPages = 4"), spotLine(readme, null),
            spotLine(report, null, "kMDItemNumberOfPages = 5", "kMDItemPageWidth = 595", "kMDItemPageHeight = 842"), spotLine(photo, days(3)),
        ).joinToString("\n")
        val all = (fresh.lines() + listOf(spotLine(plan, null), spotLine(old, null), spotLine(hidden, null), spotLine(module, null))).joinToString("\n")
        fun spotlight(args: List<String>) = if ("kMDItemFSContentChangeDate >=" in args.last()) fresh else all
    }

    private fun Files.ready() = apply { walked.get(30, TimeUnit.SECONDS) }

    @Test
    fun theWalkFindsWhatSpotlightWouldWithoutIt() {
        val f = Folder()
        val lines = mutableListOf<String>()
        val walked = Files(listOf(f.root), note = { lines += it }) { "" }.ready()
        val spotlit = Files(mdfind = f::spotlight).ready()
        val both = Files(listOf(f.root), note = { lines += it }, mdfind = f::spotlight).ready()
        for (files in listOf(spotlit, walked, both)) {
            assertEquals(f.recent.map { it.path }, files.recent().map { it.path })
            assertEquals(listOf(f.notes, f.talk, f.report, f.plan, f.photo, f.old).map { it.path }, files.newest())
            assertEquals(f.plan.path, files.find("plan"))
        }
        val pages = walked.recent().associate { it.path.substringAfterLast('/') to Triple(it.pages, it.width, it.height) }
        assertEquals(Triple(5, 960.0, 540.0), pages["talk.pdf"])
        assertEquals(Triple(4, 960.0, 540.0), pages["pitch.pdf"])
        assertEquals(Triple(5, 595.0, 842.0), pages["report.pdf"])
        assertEquals(1, lines.size, "said once, by the Files without Spotlight")
        assertEquals("files   Spotlight lacks 3 of the 3 files used lately and answers nothing under Desktop, " +
            "so recent files and decks come from the app's own walk of Desktop", lines[0])
    }

    @Test
    fun saysWhenSpotlightFallsBehindAndCatchesUp() {
        val f = Folder()
        var answer: (List<String>) -> String = f::spotlight
        val lines = mutableListOf<String>()
        val files = Files(listOf(f.root), note = { lines += it }) { answer(it) }.ready()
        files.recent()
        assertEquals(emptyList(), lines)
        answer = { args -> f.spotlight(args).lines().filter { "photo" !in it }.joinToString("\n") }  // indexing stopped before
        repeat(2) { assertEquals(f.recent.map { it.path }, files.recent().map { it.path }) }
        assertEquals(listOf("files   Spotlight lacks 1 of the 3 files used lately, so recent files and decks come from the app's own walk of Desktop"), lines)
        answer = f::spotlight
        files.recent()
        assertEquals("files   Spotlight has every file used lately again", lines.last())
    }

    @Test
    fun aFileOpenedOrAddedSinceTheLastWalkIsInTheNext() {
        val f = Folder()
        val files = Files(listOf(f.root), note = {}) { "" }.ready()
        val added = file("Desktop/later/minutes.md", min(0))
        f.old.opened(min(0))
        val t = TimeSource.Monotonic.markNow()
        while (files.recent().map { it.path }.take(2).toSet() != setOf(added.path, f.old.path)) {
            check(t.elapsedNow().inWholeSeconds < 30) { "the next walk did not find them" }
            Thread.sleep(200)
        }
    }

    @Test
    fun recentDecksWithoutSpotlight() = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val f = Folder()
        val files = Files(listOf(f.root), note = {}) { "" }.ready()
        val desk = AgentDesktop(File(dir, "library"), sandbox = null, data = File(dir, "data"), cache = File(dir, "cache"), history = false, files = files)
        val seen = desk.settled()
        assertEquals(listOf(f.talk, f.pitch).map { it.path }, seen.decks.map { it.path })
        assertEquals((1..5).map { "talk" to it }, seen.decks[0].pages.map { it.deck to it.page })
        assertEquals(listOf(f.notes, f.report, f.plan, f.photo).map { it.path }, seen.files.map { it.path })
    }

    @Test
    fun readsWhenEachFileWasLastOpened() {
        val a = file("a.txt", days(1)).opened(Duration.ofSeconds(90))
        val b = file("b.txt", days(1))
        val c = file("c.txt", days(1)).opened(days(2))
        val used = lastUsed(listOf(a, b, c).map { it.path })
        assertEquals(setOf(a.path, c.path), used.keys)
        assertEquals(90L, (System.currentTimeMillis() - used.getValue(a.path)) / 1000)
        assertEquals(mapOf(a.path to used[a.path]), lastUsed(listOf(a.path)))
    }
}
