package ch.lauzhack.app.mole

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lauzhack.app.Choice
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import ch.lauzhack.app.meeting.Meeting
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private const val MARKET = "So how big is this market really?"
private const val LONG_HEARD = "So how big is this market really, and how fast has it grown since we last looked at it in the spring?"
private const val LONG_FOLDER = "Quarterly business reviews and board meeting decks for the whole of 2026"

/**
 * Renders the window to build/home-shots/: asleep and in a meeting, light and dark, at the window's default size and its
 * smallest, each as the window shows it (<name>.png) and as one long page (<name>-page.png); a long folder name and a
 * long line heard at the smallest size; and contact.png of the window-sized ones.
 */
class HomeShots {
    @Test
    fun render() {
        assumeTrue("set MOLE_SHOTS to render the window", System.getenv("MOLE_SHOTS") != null)
        val out = File("build/home-shots").apply { deleteRecursively(); mkdirs() }
        val sheet = mutableListOf<Pair<String, BufferedImage>>()
        for ((w, h) in listOf(720 to 780, 480 to 600)) for (meeting in listOf(false, true)) for (theme in listOf(MoleTheme.Light, MoleTheme.Dark)) {
            val name = "${if (meeting) "meeting" else "asleep"}-${w}x$h-${theme.name.lowercase()}"
            sheet += name to save(File(out, "$name.png"), shoot(w, h, theme, meeting, long = false))
            save(File(out, "$name-page.png"), shoot(w, if (w > 600) 1480 else 1760, theme, meeting, long = false))
        }
        for (theme in listOf(MoleTheme.Light, MoleTheme.Dark)) {
            val name = "long-480x600-${theme.name.lowercase()}"
            sheet += name to save(File(out, "$name.png"), shoot(480, 600, theme, meeting = true, long = true))
            save(File(out, "$name-page.png"), shoot(480, 1760, theme, meeting = true, long = true))
            val open = "choosing-480x600-${theme.name.lowercase()}" // the sample at its tallest, beside the panel's label
            sheet += open to save(File(out, "$open.png"), shoot(480, 600, theme, meeting = false, long = false, sample = Choices[0].second))
        }
        ImageIO.write(contact(sheet), "png", File(out, "contact.png"))
    }

    private fun save(file: File, png: ByteArray): BufferedImage {
        file.writeBytes(png)
        return ImageIO.read(file)
    }

    private fun shoot(w: Int, h: Int, theme: MoleTheme, meeting: Boolean, long: Boolean, sample: MoleLine = Announce): ByteArray {
        val lib = File(Files.createTempDirectory("home").toFile(), if (long) LONG_FOLDER else "library").apply { mkdirs() }
        for (deck in if (long) (1..12).map { "Deck $it" } else listOf("Company_Overview", "Q3_2026_Financials", "Product_Roadmap")) File(lib, "$deck.pdf").writeText("")
        val settings = Settings(MemoryPrefs(), MutableStateFlow(lib), MutableStateFlow(true), MutableStateFlow(2500.milliseconds), WAITS, mutableStateOf(theme))
        val call = MutableStateFlow(if (meeting) Meeting("", "Meeting", false, Clock.System.now()) else null)
        val said = if (long) LONG_HEARD else MARKET
        val line = MutableStateFlow(
            if (meeting) MoleLine(MoleState.Retrieving, "Showing slide 5", said, listOf(Choice("Market", "Slide 5", Choice.Kind.Slides)), card = "05", timeoutMs = 2500)
            else MoleLine(MoleState.Hidden),
        )
        val screen = MutableStateFlow(if (meeting) OnScreen(Target.Slide("Company_Overview", 7, "Traction"), "Company Overview") else OnScreen())
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) {
            Column(Modifier.fillMaxSize().background(theme.colors().page)) {
                Spacer(Modifier.height(28.dp)) // HomeWindow's clear title bar
                Home(call, {}, {}, line, {}, MutableStateFlow(sample), {}, screen, MutableStateFlow(meeting), settings, { null })
            }
        }
        var image = scene.render(0)
        repeat(72) { image = scene.render((it + 1) * 1_000_000_000L / 60) } // 1.2 s: the stop has drained about half
        scene.close()
        lib.parentFile.deleteRecursively()
        return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun contact(shots: List<Pair<String, BufferedImage>>): BufferedImage {
        val cols = 4
        val cell = 720 + 24
        val rows = (shots.size + cols - 1) / cols
        val sheet = BufferedImage(cols * cell + 24, rows * (780 + 56) + 24, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x88, 0x88, 0x88)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
        shots.forEachIndexed { i, (name, img) ->
            val x = 24 + (i % cols) * cell
            val y = 24 + (i / cols) * (780 + 56)
            g.color = Color.WHITE
            g.drawString(name, x, y + 18)
            g.drawImage(img, x, y + 32, img.width / 2, img.height / 2, null)
        }
        g.dispose()
        return sheet
    }
}
