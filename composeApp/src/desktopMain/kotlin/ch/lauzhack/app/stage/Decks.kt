package ch.lauzhack.app.stage

import kotlinx.serialization.json.Json
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.abs

/** One PDF page: its first line of text as the title, all its lines joined as the text;
 * and its [points] as the decider is trained on a slide's text (tune/CONTRACT.md, Items): the lines after the title,
 * without bullet marks or the page number, joined by "; ". */
data class DeckPage(val deck: String, val page: Int, val title: String, val text: String, val png: File, val points: String = "")

/** A slide deck outside the library that the presenter used lately, and when. */
data class RecentDeck(val path: String, val seen: Instant, val pages: List<DeckPage>)

/** Every page of every PDF in `library`, rendered to data/decks/<deck>/<page>.png, drawn again only when the PDF changes. */
fun decks(library: File, data: File, width: Int = 1920): List<DeckPage> =
    library.listFiles { f -> f.extension.lowercase() == "pdf" }.orEmpty().sortedBy { it.name }
        .flatMap { deck(it, File(data, "decks/${it.nameWithoutExtension}"), width) }

private fun deck(pdf: File, out: File, width: Int): List<DeckPage> = Loader.loadPDF(pdf).use { doc ->
    val pages = (1..doc.numberOfPages).map { File(out, "$it.png") }
    if (pages.any { it.lastModified() < pdf.lastModified() }) {
        out.deleteRecursively()
        out.mkdirs()
        pages.forEachIndexed { i, png -> draw(doc, i, png, width) }
    }
    texts(doc).mapIndexed { i, (title, text, points) -> DeckPage(pdf.nameWithoutExtension, i + 1, title, text, pages[i], points) }
}

private val BULLET = Regex("""^[\u2022\u25e6\u25aa\u25ab\u2023\u25cf\u25cb\u25a0\u25a1\u25ba\u25b6\u2713\u2714*\u00b7\u2043\u2013\u2014-]\s*""") // escaped, as CharactersTest bans some of these marks in sources
private val FOOTER = Regex("""^(page\s*)?\d+(\s*(/|of)\s*\d+)?$""", RegexOption.IGNORE_CASE)

/** A slide's lines after its title as the decider reads them: no bullet marks, no page number, "; "-joined. */
internal fun points(lines: List<String>) = lines.drop(1).map { it.replace(BULLET, "").trim() }.filter { it.isNotEmpty() && !FOOTER.matches(it) }.joinToString("; ")

/** Each page's title, text and points. */
private fun texts(doc: PDDocument): List<List<String>> {
    val text = PDFTextStripper()
    return (1..doc.numberOfPages).map { n ->
        text.startPage = n
        text.endPage = n
        val lines = text.getText(doc).lines().map(String::trim).filter(String::isNotEmpty)
        listOf(lines.firstOrNull() ?: "page $n", lines.joinToString(" "), points(lines))
    }
}

/** Writes whole files only: the stage may load a page while another thread draws it. */
private fun whole(file: File, write: (File) -> Unit) {
    file.parentFile.mkdirs()
    val part = File.createTempFile(file.name, ".part", file.parentFile)
    write(part)
    part.renameTo(file)
}

private fun draw(doc: PDDocument, i: Int, png: File, width: Int) {
    val page = doc.getPage(i)
    val across = if (page.rotation % 180 == 0) page.cropBox.width else page.cropBox.height
    whole(png) { ImageIO.write(PDFRenderer(doc).renderImage(i, width / across, ImageType.RGB), "png", it) }
}

internal const val MOST_SLIDES = 300
internal val SCREEN = 1.3..1.8  // width over height of a slide, 4:3 to 16:9; a Letter page on its side (1.29) is a document

/** A deck's slides, not a document's pages: from 3 to MOST_SLIDES pages, each the size of the first, which is a screen's
 * shape. Sizes are (width, height) as the pages show, rotation applied. */
internal fun slideShaped(sizes: List<Pair<Float, Float>>): Boolean = sizes.size in 3..MOST_SLIDES &&
    sizes.all { (w, h) -> abs(w - sizes[0].first) < 1 && abs(h - sizes[0].second) < 1 } &&
    sizes[0].let { (w, h) -> h > 0 && (w / h).toDouble() in SCREEN }

/** Names a version of a file by its path, size and modification time. */
internal fun version(f: File): String =
    MessageDigest.getInstance("SHA-1").digest("${f.path}\n${f.length()}\n${f.lastModified()}".toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

/** The pages of a PDF outside the library if it is a deck, else null; their pictures go to `out`, drawn by drawMissing.
 * The text is read once and kept in `known`, so the next call does not open the PDF. */
internal fun recentDeck(pdf: File, out: File, known: File): List<DeckPage>? {
    val kept = if (known.isFile) Json.decodeFromString<List<List<String>>>(known.readText()).takeIf { t -> t.all { it.size == 3 } } else null
    val texts = kept ?: Loader.loadPDF(pdf).use { doc ->
        val sizes = doc.pages.map { p -> p.cropBox.let { if (p.rotation % 180 == 0) it.width to it.height else it.height to it.width } }
        if (slideShaped(sizes)) texts(doc) else emptyList()
    }.also { t -> whole(known) { it.writeText(Json.encodeToString(t)) } }
    return texts.ifEmpty { null }?.mapIndexed { i, (title, text, points) -> DeckPage(pdf.nameWithoutExtension, i + 1, title, text, File(out, "${i + 1}.png"), points) }
}

/** Draws those of the pages of `pdf` that are not drawn yet. */
internal fun drawMissing(pdf: File, pages: List<DeckPage>, width: Int = 1920) {
    val missing = pages.filterNot { it.png.exists() }.ifEmpty { return }
    Loader.loadPDF(pdf).use { doc -> for (p in missing) draw(doc, p.page - 1, p.png, width) }
}
