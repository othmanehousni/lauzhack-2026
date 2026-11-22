package ch.lauzhack.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.lauzhack.app.Choice.Kind
import ch.lauzhack.app.mole.MoleBar
import ch.lauzhack.app.mole.MoleTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

private val PNL = Choice("Acme profit and loss Q3 2026.xlsx", "Documents", Kind.Sheet)
private val PNL4 = Choice("Acme profit and loss Q4 2026.xlsx", "Documents", Kind.Sheet)

// The longest lines the session says, as it says them now that names are long.
internal val LongLines = linkedMapOf(
    "announce" to MoleLine(MoleState.Retrieving, "Showing Acme customer pipeline Q4 2026", "show the customer pipeline", listOf(Choice("Acme customer pipeline Q4 2026.xlsx", "Documents", Kind.Sheet)), timeoutMs = 2500),
    "choice-two" to MoleLine(MoleState.Choosing, heard = "open the profit and loss", options = listOf(PNL, PNL4)),
    "choice-three" to MoleLine(
        MoleState.Choosing, heard = "the comparison with the competitors please",
        options = listOf(
            Choice("Acme competitor comparison", "Acme quarterly business review, slide 12", Kind.Slides),
            Choice("Acme customer pipeline Q4 2026", "Acme quarterly business review, slide 4", Kind.Slides),
            Choice("Pricing and packaging for enterprise customers, a complete guide", "help.acme-corp.example", Kind.Site),
        ),
    ),
    "question" to MoleLine(MoleState.Choosing, "Which profit and loss, the one for Q3 or the one for Q4?", "the profit and loss", listOf(PNL, PNL4)),
    "unsure" to MoleLine(MoleState.Unsure, heard = "uh the one with the customer numbers from the last quarter please"),
)

/**
 * The floater the way the app sizes it: the bar in a window that starts at 342 x 78 and hugs the bar on every size
 * change, clipped to the window as a real one is. Pictures go to build/bar-shots/.
 */
class FloaterTest {
    @Test
    fun theWindowHoldsTheWholeBar() = holds(LongLines, Lang.En)

    // Every translation of the announce, and the one whose choice hint and question are longest.
    @Test
    fun theWindowHoldsTheLongestTranslation() {
        val name = "Acme customer pipeline Q4 2026"
        for (l in Lang.entries.drop(1)) holds(linkedMapOf("announce-${l.name}" to LongLines.getValue("announce").copy(title = l.words.showing(name))), l)
        val choice = Lang.entries.drop(1).maxBy { with(it.words) { sayOneOf(listOf("one", "two", "three")).length + titles.getValue(MoleState.Choosing).length } }
        holds(linkedMapOf("choice-three-${choice.name}" to LongLines.getValue("choice-three")), choice)
    }

    private fun holds(lines: Map<String, MoleLine>, language: Lang) {
        lang = language
        val out = File("build/bar-shots").apply { mkdirs() }
        val wrong = mutableListOf<String>()
        try {
        for ((name, line) in lines) for ((theme, suffix) in listOf(MoleTheme.Light to "", MoleTheme.Dark to "-dark")) {
            var window by mutableStateOf(IntSize(342, 78))
            var bar = IntSize.Zero
            var natural = IntSize.Zero
            val scene = ImageComposeScene(400 * 2, 380 * 2, Density(2f)) {
                Box(Modifier.fillMaxSize().background(Color(0xFFDFE6DC))) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(window.width.dp, window.height.dp).clipToBounds()) {
                        FloaterBar(line, {}, theme) { bar = it; window = hugged(it, 2.0) }
                    }
                    // The same bar with no window around it, for the size it asks for.
                    Box(Modifier.size(0.dp).clipToBounds()) { MoleBar(line, {}, Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).onSizeChanged { natural = it }, theme) }
                }
            }
            var image = scene.render(0)
            for (frame in 1..90) image = scene.render(frame * 1_000_000_000L / 60)
            scene.close()
            File(out, "$name$suffix.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            if (bar != natural || window != hugged(natural, 2.0)) wrong += "$name$suffix: bar $bar of $natural in window $window"
        }
        } finally {
            lang = Lang.En
        }
        assertEquals(emptyList(), wrong)
    }
}
