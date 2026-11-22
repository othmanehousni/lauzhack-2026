package ch.lauzhack.app.stage

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** A PDF with a page of each size, "Slide n" and "about pricing" written on each. */
internal fun pdf(file: File, sizes: List<Pair<Float, Float>>, rotation: Int = 0): File {
    PDDocument().use { doc ->
        sizes.forEachIndexed { i, (w, h) ->
            val page = PDPage(PDRectangle(w, h)).also { it.rotation = rotation }
            doc.addPage(page)
            PDPageContentStream(doc, page).use {
                it.beginText()
                it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                it.newLineAtOffset(40f, h - 60f)
                it.showText("Slide ${i + 1}")
                it.newLineAtOffset(0f, -30f)
                it.showText("about pricing")
                it.endText()
            }
        }
        file.parentFile.mkdirs()
        doc.save(file)
    }
    return file
}

internal fun slides(n: Int, w: Float = 960f, h: Float = 540f) = List(n) { w to h }

/** recents() once nothing is left to read or draw in the background. */
internal suspend fun AgentDesktop.settled(): Recents {
    val start = TimeSource.Monotonic.markNow()
    while (true) {
        val idle = queued.isEmpty()
        val seen = recents()
        if (idle && queued.isEmpty()) return seen
        check(start.elapsedNow().inWholeSeconds < 60) { "the decks were not read in the background" }
        delay(100)
    }
}

class DecksTest {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    private val tmp = createTempDirectory("decks-test").toFile()

    private fun deckOf(name: String, sizes: List<Pair<Float, Float>>, rotation: Int = 0) =
        recentDeck(pdf(File(tmp, "$name.pdf"), sizes, rotation), File(tmp, "out/$name"), File(tmp, "known/$name.json"))

    @Test
    fun tellsDecksFromDocumentsByTheirPages() {
        val decks = listOf(
            Triple("16 by 9", slides(5), 0), Triple("4 by 3", slides(3, 720f, 540f), 0), Triple("A4 on its side", slides(4, 842f, 595f), 0),
            Triple("portrait pages turned", slides(4, 540f, 960f), 90),
        )
        val documents = mapOf(
            "A4" to slides(5, 595f, 842f), "Letter on its side" to slides(5, 792f, 612f), "two slides" to slides(2),
            "a banner" to slides(4, 1800f, 600f), "mixed" to listOf(960f to 540f, 595f to 842f, 960f to 540f),
            "too long" to slides(MOST_SLIDES + 1),
        )
        for ((name, sizes, rotation) in decks) assertEquals(sizes.size, deckOf(name, sizes, rotation)?.size, name)
        for ((name, sizes) in documents) assertNull(deckOf(name, sizes), name)
    }

    @Test
    fun readsADecksTextOnceAndDrawsItLater() {
        val pdf = pdf(File(tmp, "Q3_roadmap.pdf"), slides(3))
        val out = File(tmp, "out")
        val known = File(tmp, "known.json")
        val pages = recentDeck(pdf, out, known)!!
        assertEquals(listOf("Slide 1", "Slide 2", "Slide 3"), pages.map { it.title })
        assertEquals("Slide 2 about pricing", pages[1].text)
        assertEquals(DeckPage("Q3_roadmap", 3, "Slide 3", "Slide 3 about pricing", File(out, "3.png"), "about pricing"), pages[2])
        assertTrue(pages.none { it.png.exists() })

        drawMissing(pdf, pages)
        assertEquals(listOf(1920, 1920, 1920), pages.map { ImageIO.read(it.png).width })
        assertEquals(1080, ImageIO.read(pages[0].png).height)
        val drawn = pages.map { it.png.lastModified() }
        pages[1].png.delete()
        drawMissing(pdf, pages)
        assertEquals(drawn[0], pages[0].png.lastModified())
        assertTrue(pages[1].png.exists())
        assertEquals(emptyList(), out.list()!!.filter { it.endsWith(".part") })

        val version = version(pdf)
        pdf.renameTo(File(tmp, "elsewhere.pdf"))
        assertEquals(pages, recentDeck(pdf, out, known), "the second time, the text comes from `known`, not the PDF")
        File(tmp, "elsewhere.pdf").renameTo(pdf)
        pdf.modified(Duration.ofMinutes(1))
        assertNotEquals(version, version(pdf))

        assertNull(recentDeck(pdf(File(tmp, "report.pdf"), slides(5, 595f, 842f)), out, File(tmp, "report.json")))
        File(tmp, "report.pdf").delete()
        assertNull(recentDeck(File(tmp, "report.pdf"), out, File(tmp, "report.json")))
    }

    @Test
    fun theLibraryStillDrawsEveryPage() {
        val library = File(tmp, "library")
        pdf(File(library, "intro.pdf"), slides(2))
        val pages = decks(library, File(tmp, "data"))
        assertEquals(listOf("intro" to 1, "intro" to 2), pages.map { it.deck to it.page })
        assertTrue(pages.all { ImageIO.read(it.png).width == 1920 })
    }

    @Test
    fun recentDecksOutsideTheLibraryWithoutSetup() = runBlocking {
        val library = File(tmp, "library")
        pdf(File(library, "intro.pdf"), slides(3))
        val desktop = File(tmp, "Desktop")
        val day = Duration.ofDays(1)
        val roadmap = pdf(File(desktop, "roadmap.pdf"), slides(4)).modified(day)
        val copy = pdf(File(desktop, "intro.pdf"), slides(3)).modified(day)
        val mixed = pdf(File(desktop, "mixed.pdf"), listOf(960f to 540f, 595f to 842f, 960f to 540f)).modified(day)
        val notes = File(desktop, "notes.html").apply { writeText("<title>notes</title>") }.modified(day)
        val repo = File(desktop, "project").apply { File(this, ".git").mkdirs() }
        val pitch = pdf(File(repo, "pitch.pdf"), slides(5)).modified(day)
        val paper = pdf(File(repo, "paper.pdf"), slides(4, 595f, 842f)).modified(day)
        val geometry = arrayOf("kMDItemNumberOfPages = 4", "kMDItemPageWidth = 960", "kMDItemPageHeight = 540")
        val recent = listOf(
            spotLine(notes, Duration.ofMinutes(30)), spotLine(roadmap, Duration.ofMinutes(60), *geometry),
            spotLine(pitch, Duration.ofMinutes(90), *geometry), spotLine(paper, Duration.ofMinutes(100), "kMDItemNumberOfPages = 4"),
            spotLine(copy, Duration.ofMinutes(120), *geometry), spotLine(mixed, Duration.ofMinutes(180), *geometry),
        ).joinToString("\n")
        val data = File(tmp, "data")
        val stale = File(data, "decks/.recent/0123456789abcdef").apply { mkdirs() }
        val desk = AgentDesktop(library, sandbox = null, data = data, cache = File(tmp, "cache"), history = false,
            files = Files { args -> if ("kMDItemFSContentChangeDate >=" in args.last()) recent else "" })
        val seen = desk.settled()
        assertEquals(listOf(roadmap.path, pitch.path), seen.decks.map { it.path })
        assertEquals((1..4).map { "roadmap" to it }, seen.decks[0].pages.map { it.deck to it.page })
        assertTrue(seen.decks[0].pages.all { it.png.exists() && it.png.startsWith(File(data, "decks")) })
        assertEquals(listOf(notes, copy, mixed).map { it.path }, seen.files.map { it.path })
        assertTrue(seen.pages.isEmpty())
        assertTrue(!stale.exists())
    }
}
