package ch.lauzhack.app.stage

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import javax.imageio.ImageIO

/** One PDF page as brain.py's Catalog reads it: its first line of text as the title, all its lines joined as the text. */
data class DeckPage(val deck: String, val page: Int, val title: String, val text: String, val png: File)

/** Every page of every PDF in `library`, rendered to data/decks/<deck>/<page>.png, drawn again only when the PDF changes. */
fun decks(library: File, data: File, width: Int = 1920): List<DeckPage> =
    library.listFiles { f -> f.extension.lowercase() == "pdf" }.orEmpty().sortedBy { it.name }
        .flatMap { deck(it, File(data, "decks/${it.nameWithoutExtension}"), width) }

private fun deck(pdf: File, out: File, width: Int): List<DeckPage> = Loader.loadPDF(pdf).use { doc ->
    val pages = (1..doc.numberOfPages).map { File(out, "$it.png") }
    if (pages.any { it.lastModified() < pdf.lastModified() }) {
        out.deleteRecursively()
        out.mkdirs()
        val renderer = PDFRenderer(doc)
        for ((i, png) in pages.withIndex()) {
            val page = doc.getPage(i)
            val across = if (page.rotation % 180 == 0) page.cropBox.width else page.cropBox.height
            ImageIO.write(renderer.renderImage(i, width / across, ImageType.RGB), "png", png)
        }
    }
    val text = PDFTextStripper()
    pages.mapIndexed { i, png ->
        text.startPage = i + 1
        text.endPage = i + 1
        val lines = text.getText(doc).lines().map(String::trim).filter(String::isNotEmpty)
        DeckPage(pdf.nameWithoutExtension, i + 1, lines.firstOrNull() ?: "page ${i + 1}", lines.joinToString(" "), png)
    }
}
