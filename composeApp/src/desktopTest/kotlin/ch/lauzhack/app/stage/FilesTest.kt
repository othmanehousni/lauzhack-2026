package ch.lauzhack.app.stage

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SPOTLIGHT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z").withZone(ZoneOffset.UTC)

/** One line of `mdfind -attr kMDItemLastUsedDate ...`, as Spotlight prints it. */
internal fun spotLine(f: File, usedAgo: Duration?, vararg more: String) =
    f.path + "   kMDItemLastUsedDate = " + (usedAgo?.let { SPOTLIGHT_DATE.format(Instant.now().minus(it)) } ?: "(null)") + more.joinToString("") { "   $it" }

internal fun File.modified(ago: Duration) = apply { setLastModified(System.currentTimeMillis() - ago.toMillis()) }

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
}
