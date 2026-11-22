package ch.lauzhack.app.mole

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lauzhack.app.Choice
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test

@Serializable
private class Step(val words: String, val ms: Int)

@Serializable
private class Shot(val name: String, val phone: Boolean = false, val mic: String = "laptop", val steps: List<Step>)

private val WALL = Color(0xFFDFE6DC) // index.html's sage wallpaper, so the bar's hairline shows
private val DECK_INK = Color(0xFF1F1C1A)

// A stand-in for the live copy of the shared frame: a slide with the market-size chart.
private val THUMB = ImageBitmap(176, 99).also {
    val canvas = Canvas(it)
    canvas.drawRect(0f, 0f, 176f, 99f, Paint().apply { color = Deck })
    listOf(30f, 42f, 56f, 74f).forEachIndexed { i, h ->
        canvas.drawRect(16f + i * 24f, 90f - h, 32f + i * 24f, 90f, Paint().apply { color = if (i == 3) MoleColors.Light.ember else DECK_INK.copy(alpha = .16f) })
    }
}

private fun words(key: String): MoleLine = when (key) {
    "choosing-two" -> Choices[0].second
    "choosing-three" -> Choices[1].second
    "picked" -> Choices[2].second
    "choosing-kinds" -> MoleLine(
        MoleState.Choosing,
        heard = "show them the launch",
        options = listOf(Choice("lauzhack.ch", "Site", Choice.Kind.Site), Choice("Team photo.jpg", "Image", Choice.Kind.Image), Choice("Notes.txt", "File", Choice.Kind.Other)),
    )
    else -> Samples.getValue(MoleState.entries.first { it.name.equals(key, ignoreCase = true) })
}

/**
 * Renders every shot in src/desktopTest/shots/shots.json, light as <name> and dark as <name>-dark, to
 * build/mole-shots/compose/. Where web.sh has rendered the same shot from mole/index.html, writes
 * build/mole-shots/compare/<name>.png (web | compose | difference) and the mean difference per theme to
 * build/mole-shots/report.txt.
 */
class MoleShots {
    @Test
    fun render() {
        val out = File("build/mole-shots")
        val shots = Json.decodeFromString<List<Shot>>(File("src/desktopTest/shots/shots.json").readText())
        val report = StringBuilder("%-22s %7s %7s\n".format("", "light", "dark"))
        for (shot in shots) {
            val scores = listOf(MoleTheme.Light to "", MoleTheme.Dark to "-dark").map { (theme, suffix) ->
                val name = shot.name + suffix
                val png = File(out, "compose/$name.png").apply { parentFile.mkdirs() }
                png.writeBytes(shoot(shot, theme))
                val web = File(out, "web/$name.png")
                if (web.exists()) "%.4f".format(compare(web, png, File(out, "compare/$name.png"))) else "-"
            }
            report.appendLine("%-22s %7s %7s".format(shot.name, scores[0], scores[1]))
        }
        File(out, "report.txt").writeText(report.toString())
        println(report)
    }

    private fun shoot(shot: Shot, theme: MoleTheme): ByteArray {
        val (w, h) = if (shot.phone) 284 to 634 else 364 to 280
        var line by mutableStateOf(words(shot.steps.first().words))
        val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) {
            if (shot.phone) Column(Modifier.fillMaxSize().background(if (theme == MoleTheme.Dark) MoleColors.Dark.paper else MoleColors.Light.paper)) {
                Spacer(Modifier.height(36.dp)) // the mock's status bar; the app gets real insets instead
                MolePhone(line, "Market size", THUMB, shot.mic == "phone", "Home insurance review", {}, theme = theme)
            } else Box(Modifier.fillMaxSize().background(WALL)) {
                MoleBar(line, {}, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp), theme)
            }
        }
        var frame = 0L
        var image = scene.render(0)
        shot.steps.forEachIndexed { i, step ->
            if (i > 0) {
                line = words(step.words)
                Snapshot.sendApplyNotifications()
            }
            // Two frames more than web.js steps: Compose composes a change on the next frame and its animations read their
            // start time on the one after, where the web starts them as set() returns.
            repeat(step.ms * 60 / 1000 + 2) { image = scene.render(++frame * 1_000_000_000L / 60) }
        }
        scene.close()
        return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun compare(web: File, compose: File, to: File): Double {
        val a = ImageIO.read(web)
        val b = ImageIO.read(compose)
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val sheet = BufferedImage(w * 3, h, BufferedImage.TYPE_INT_RGB)
        sheet.graphics.apply { drawImage(a, 0, 0, null); drawImage(b, w, 0, null) }
        var sum = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            val d = (0..2).map { abs((p shr (it * 8) and 255) - (q shr (it * 8) and 255)) }
            sum += d.sum() / 765.0
            val v = (d.max() * 3).coerceAtMost(255)
            sheet.setRGB(2 * w + x, y, (v shl 16) or (v shl 8) or v)
        }
        to.parentFile.mkdirs()
        ImageIO.write(sheet, "png", to)
        return sum / (w * h)
    }
}
