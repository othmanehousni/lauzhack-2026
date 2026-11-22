package ch.lauzhack.app.stage

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** A visit as Chromium records it: transition is the core type and qualifiers, stored as a signed 32-bit int. */
internal data class Visit(val url: String, val title: String, val ago: Duration, val transition: Int = LINK, val hidden: Int = 0, val searched: Boolean = false)

internal const val LINK = 0x30000000  // a link, the start and the end of its redirect chain
internal const val TYPED = 0x30000001
private const val SUBFRAME = 0x30000003
private const val GENERATED = 0x30000005  // a search typed in the address bar
private const val FORM = 0x30000007
private const val REDIRECTING = 0x10000000  // a chain's start that went on to another page
private const val REDIRECTED = 0xA0000000.toInt()  // a server redirect's end

/** A History database with the tables and columns of Chromium's that History reads. */
internal fun historyDb(db: File, visits: List<Visit>, now: Instant = Instant.now()) {
    db.parentFile.mkdirs()
    fun q(s: String) = "'" + s.replace("'", "''") + "'"
    val urls = visits.distinctBy { it.url }
    val sql = buildString {
        append("CREATE TABLE urls(id INTEGER PRIMARY KEY, url LONGVARCHAR, title LONGVARCHAR, visit_count INTEGER DEFAULT 0, typed_count INTEGER DEFAULT 0, last_visit_time INTEGER, hidden INTEGER DEFAULT 0);\n")
        append("CREATE TABLE visits(id INTEGER PRIMARY KEY, url INTEGER, visit_time INTEGER, from_visit INTEGER, transition INTEGER DEFAULT 0, segment_id INTEGER, visit_duration INTEGER DEFAULT 0);\n")
        append("CREATE TABLE keyword_search_terms(keyword_id INTEGER, url_id INTEGER, term LONGVARCHAR, normalized_term LONGVARCHAR);\n")
        append("BEGIN;\n")
        urls.forEachIndexed { i, v ->
            append("INSERT INTO urls(id, url, title, hidden) VALUES (${i + 1}, ${q(v.url)}, ${q(v.title)}, ${v.hidden});\n")
            if (v.searched) append("INSERT INTO keyword_search_terms VALUES (2, ${i + 1}, 'acme', 'acme');\n")
        }
        for (v in visits) {
            val t = now.minus(v.ago).let { it.epochSecond * 1_000_000 + it.nano / 1000 + 11_644_473_600_000_000L }
            append("INSERT INTO visits(url, visit_time, from_visit, transition) VALUES (${urls.indexOfFirst { it.url == v.url } + 1}, $t, 0, ${v.transition});\n")
        }
        append("COMMIT;\n")
    }
    exec(listOf("/usr/bin/sqlite3", db.path), input = sql.toByteArray())
}

class HistoryTest {
    private val tmp = createTempDirectory("history-test").toFile()
    private val support = File(tmp, "Application Support")
    private val folder = File(tmp, "Desktop").apply { mkdirs() }
    private fun min(n: Long) = Duration.ofMinutes(n)

    @Test
    fun keepsThePagesAPresenterWouldShowMostRecentFirst() {
        val talk = File(folder, "my talk.html").apply { writeText("<title>Talk</title>") }
        val outside = File(tmp, "elsewhere.html").apply { writeText("x") }
        val now = Instant.now()
        historyDb(File(support, "Google/Chrome/Default/History"), listOf(
            Visit("https://acme.com/pricing", "Pricing", min(60)),
            Visit("https://acme.com/pricing", "Pricing", Duration.ofDays(3), TYPED),
            Visit("https://acme.com/pricing#plans", "Pricing", min(120)),
            Visit("http://bit.ly/acme", "", min(50), REDIRECTING),
            Visit("https://acme.com/blog", "Blog", min(50), REDIRECTED),
            Visit("https://www.google.com/search?q=acme", "acme - Google Search", min(55), GENERATED, searched = true),
            Visit("https://duckduckgo.com/?q=acme", "acme at DuckDuckGo", min(54), searched = true),
            Visit("https://accounts.example.com/signin?continue=https%3A%2F%2Fmail.example.com%2F", "Sign in", min(40), REDIRECTED),
            Visit("https://github.com/login?return_to=%2Fsettings", "Sign in to GitHub", min(39)),
            Visit("https://acme.com/checkout", "Checkout", min(38), FORM),
            Visit("https://ads.example.com/frame", "", min(37), SUBFRAME),
            Visit("https://hidden.example.com/", "Hidden", min(36), hidden = 1),
            Visit(uri(talk), "Talk", min(20)),
            Visit(uri(File(folder, "gone.html")), "Gone", min(21)),
            Visit(uri(outside), "Elsewhere", min(22)),
            Visit("https://acme.com/old", "Old", Duration.ofDays(20)),
            Visit("chrome://settings/", "Settings", min(10), TYPED),
            Visit("https://acme.com/team/", "", min(15)),
        ), now)
        historyDb(File(support, "Arc/User Data/Profile 1/History"), listOf(Visit("https://acme.com/blog", "Blog", min(5))), now)
        File(support, "Google/Chrome/Crashpad").mkdirs()
        historyDb(File(support, "com.operasoftware.Opera/History"), emptyList())

        val history = History(support, listOf(folder))
        assertEquals(setOf("Google/Chrome/Default", "Arc/User Data/Profile 1", "com.operasoftware.Opera"), history.profiles.keys)
        val leftovers = { File(System.getProperty("java.io.tmpdir")).list().orEmpty().count { it.startsWith("mole-history") } }
        val before = leftovers()
        val pages = history.pages(now = now)
        assertEquals(before, leftovers(), "the copies of the databases are deleted")
        assertEquals(
            listOf("https://acme.com/blog" to "Blog", "https://acme.com/team/" to "team", uri(talk) to "Talk", "https://acme.com/pricing" to "Pricing"),
            pages.map { it.url to it.title },
        )
        assertEquals(listOf(5L, 15, 20, 60), pages.map { Duration.between(it.seen, now).toMinutes() })
        assertEquals("Talk", pages[2].target.name)
    }

    @Test
    fun readsADatabaseAgainOnlyOnceItChanges() {
        val db = File(support, "Google/Chrome/Default/History")
        historyDb(db, listOf(Visit("https://acme.com/a", "A", min(5))))
        val history = History(support, listOf(folder))
        assertEquals(listOf("A"), history.pages().map { it.title })
        db.setReadable(false)  // a read now would fail, so a page means it came from memory
        assertEquals(listOf("A"), history.pages().map { it.title })
        db.setReadable(true)
        exec(listOf("/usr/bin/sqlite3", db.path, "INSERT INTO urls(id, url, title) VALUES (9, 'https://acme.com/b', 'B'); INSERT INTO visits(url, visit_time, transition) SELECT 9, MAX(visit_time) + 1, $LINK FROM visits;"))
        assertEquals(listOf("B", "A"), history.pages().map { it.title })
    }

    @Test
    fun capsTheListAtTheMostRecent() {
        historyDb(File(support, "Google/Chrome/Default/History"), (1..400).map { Visit("https://acme.com/p$it", "Page $it", min(it.toLong())) })
        val pages = History(support, listOf(folder)).pages()
        assertEquals((1..300).map { "Page $it" }, pages.map { it.title })
    }
}
