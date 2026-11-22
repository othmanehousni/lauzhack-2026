package ch.lauzhack.app.mole

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [MoleColors] is the :root block of mole/mole.css and the phone's tokens in index.html, both sides of every light-dark(). */
class MoleColorsTest {
    private val roles: Map<String, (MoleColors) -> Color> = mapOf(
        "--paper" to MoleColors::paper, "--ink" to MoleColors::ink, "--figure" to MoleColors::figure, "--lens" to MoleColors::lens,
        "--stack" to MoleColors::stack, "--rim" to MoleColors::rim, "--bridge" to MoleColors::bridge, "--tie" to MoleColors::tie,
        "--tie-fold" to MoleColors::tieFold, "--tie-knot" to MoleColors::tieKnot, "--ember" to MoleColors::ember, "--sage" to MoleColors::sage,
        "--plum" to MoleColors::plum, "--hairline" to MoleColors::hairline, "--rule" to MoleColors::rule, "--wash" to MoleColors::wash,
        "--hover" to MoleColors::hover, "--hint" to MoleColors::hint, "--heard" to MoleColors::heard,
    )
    private val phone: Map<String, (MoleColors) -> Color> = mapOf(
        "--page" to MoleColors::page, "--fill" to MoleColors::fill, "--muted" to MoleColors::muted, "--device" to MoleColors::device,
        "--outline" to MoleColors::outline, "--deck" to { _ -> Deck },
    )

    @Test
    fun matchesTheCss() {
        val css = tokens(File("../mole/mole.css").readText())
        val page = tokens(File("../mole/index.html").readText())
        val all = css + page
        assertEquals(css.keys - "--ease-out" - "--slow", roles.keys, "every colour token of mole.css has a role in MoleColors")
        for ((name, role) in roles + phone) for ((dark, colors) in listOf(false to MoleColors.Light, true to MoleColors.Dark)) {
            assertEquals(resolve(all.getValue(name), dark, all), role(colors), "$name, ${if (dark) "dark" else "light"}")
        }
    }

    private fun tokens(text: String) = Regex("""(--[\w-]+):\s*([^;]+);""")
        .findAll(text.substringAfter(":root {").substringBefore("}").replace(Regex("""(?s)/\*.*?\*/"""), ""))
        .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun resolve(value: String, dark: Boolean, all: Map<String, String>): Color {
        val v = value.trim()
        val args = v.substringAfter("(").substringBeforeLast(")")
        return when {
            v.startsWith("light-dark(") -> {
                var depth = 0
                val comma = args.indices.first { i -> args[i].also { if (it == '(') depth++ else if (it == ')') depth-- } == ',' && depth == 0 }
                resolve(if (dark) args.substring(comma + 1) else args.substring(0, comma), dark, all)
            }
            v.startsWith("var(") -> resolve(all.getValue(args), dark, all)
            v.startsWith("oklch(from ") -> {
                val (base, alpha) = Regex("""from (var\([^)]+\)) l c h / ([\d.]+)""").matchEntire(args)!!.destructured
                resolve(base, dark, all).copy(alpha = alpha.toFloat())
            }
            v.startsWith("oklch(") -> {
                val parts = args.split("/")
                val (l, c, h) = parts[0].trim().split(Regex("\\s+")).map { it.toDouble() }
                oklch(l, c, h).copy(alpha = parts.getOrNull(1)?.trim()?.toFloat() ?: 1f)
            }
            else -> error("not a colour: $v")
        }
    }

    // OKLCH → OKLab → linear sRGB with Björn Ottosson's matrices, then the sRGB transfer curve, rounded to 8 bits as
    // Chrome paints it.
    private fun oklch(l: Double, c: Double, hue: Double): Color {
        val a = c * cos(Math.toRadians(hue))
        val b = c * sin(Math.toRadians(hue))
        val lms = listOf(
            l + 0.3963377774 * a + 0.2158037573 * b,
            l - 0.1055613458 * a - 0.0638541728 * b,
            l - 0.0894841775 * a - 1.2914855480 * b,
        ).map { it.pow(3) }
        val linear = listOf(
            listOf(4.0767416621, -3.3077115913, 0.2309699292),
            listOf(-1.2684380046, 2.6097574011, -0.3413193965),
            listOf(-0.0041960863, -0.7034186147, 1.7076147010),
        ).map { row -> row.indices.sumOf { row[it] * lms[it] } }
        val rgb = linear.map { if (it <= 0.0031308) 12.92 * it else 1.055 * it.pow(1 / 2.4) - 0.055 }.map { (it * 255).roundToInt() }
        assertTrue(rgb.all { it in 0..255 }, "oklch($l $c $hue) is outside sRGB: $rgb")
        return Color(rgb[0], rgb[1], rgb[2])
    }
}
