package ch.lauzhack.app.mole

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** [type] sets a line as wide as Chrome 148 on macOS sets it in -apple-system (SFNS.ttf, with its optical size and trak). */
class TypeParityTest {
    @Test
    fun widthsMatchChrome() {
        if (!File("/System/Library/Fonts/SFNS.ttf").exists()) return
        val text = "Home offer — revised.pdf Sent 12 Mar Which one? pull up the offer"
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        // getBoundingClientRect().width of that line in a white-space: nowrap span, by size and weight.
        val chrome = listOf(
            Triple(12f, 400, 377.734), Triple(12f, 500, 385.203), Triple(12.5f, 500, 398.875), Triple(13f, 500, 412.359),
            Triple(14f, 500, 439.641), Triple(15f, 500, 466.281), Triple(15f, 600, 475.563), Triple(15.5f, 700, 499.188),
            Triple(22f, 600, 660.422),
        )
        for ((size, weight, want) in chrome) {
            val got = measurer.measure(text, type(size, size, weight, color = Color.Black)).multiParagraph.intrinsics.maxIntrinsicWidth
            assertTrue(abs(got - want) < 1, "$size px / $weight: Compose $got, Chrome $want")
        }
    }
}
