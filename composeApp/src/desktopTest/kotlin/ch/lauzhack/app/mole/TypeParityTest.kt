package ch.lauzhack.app.mole

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** [type] sets a line as wide as Chrome 148 sets it in mole/fonts' Inter, with its optical size, weight and tracking. */
class TypeParityTest {
    @Test
    fun widthsMatchChrome() {
        val text = "Home offer revised.pdf Sent 12 Mar Which one? pull up the offer"
        // getBoundingClientRect().width of that line in a white-space: nowrap span, by size, weight and letter-spacing (px).
        val chrome = listOf(
            listOf(12f, 400f, 0f, 364.219f), listOf(12f, 500f, 0f, 367.5f), listOf(13f, 400f, -.039f, 392.109f),
            listOf(13f, 500f, -.084f, 392.828f), listOf(14f, 400f, -.084f, 419.625f), listOf(15f, 500f, -.135f, 448.875f),
            listOf(15f, 600f, -.135f, 453.203f), listOf(15.5f, 600f, -.31f, 456.656f), listOf(22f, 600f, -.396f, 634f),
        )
        var got = emptyList<Float>()
        ImageComposeScene(1, 1) {
            val measurer = rememberTextMeasurer()
            got = chrome.map { (size, weight, tracking) ->
                measurer.measure(text, type(size, size, weight.toInt(), tracking, Color.Black)).multiParagraph.intrinsics.maxIntrinsicWidth
            }
        }.apply { render(); close() }
        for ((case, width) in chrome.zip(got)) {
            assertTrue(abs(width - case[3]) < 1, "${case[0]} px / ${case[1].toInt()}: Compose $width, Chrome ${case[3]}")
        }
    }
}
