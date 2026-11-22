package ch.lauzhack.app.mole

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontSynthesis
import ch.lauzhack.app.MoleState
import kotlinx.coroutines.channels.Channel
import kotlin.math.min

internal fun svg(d: String): Path = PathParser().parsePathString(d).toPath()

// The figure of mole/mole.js FIGURE, in its mole-space units: the body's base at (0, 0) on the ground line, snout to +x.
private val BODY = svg("M-49 60L-49 -8C-49 -38 -36.5 -61 -27 -73C-17 -86 3 -100 17 -100L26 -99.5L30 -80L37.7 -64C40 -52 49.5 -35 49.7 -14L49 0L49 60Z")
private val TIE = svg("M-4 -3.5C-8 -7 -13 -10 -16 -8.5C-19 -7 -18 -2 -17.2 0C-18 2 -19 7 -16 8.5C-13 10 -8 7 -4 3.5ZM4 -3.5C8 -7 13 -10 16 -8.5C19 -7 18 -2 17.2 0C18 2 19 7 16 8.5C13 10 8 7 4 3.5Z")
private val FOLD = svg("M-5 0C-8.5 -2.2 -12.5 -2.2 -15.5 0C-12.5 2.2 -8.5 2.2 -5 0ZM5 0C8.5 -2.2 12.5 -2.2 15.5 0C12.5 2.2 8.5 2.2 5 0Z")
private val SNOUT = svg("M4 -19.5C14 -21 26 -23.6 35.5 -25C39 -25.5 40.8 -23.5 39.5 -21C32 -12 19 -2 13 13L0 0Z")
private val SCENT = listOf(svg("M0 -4.5Q5 -8 10 -5"), svg("M1.5 0.5Q6.5 -3 11.5 0"))
private val ORIGIN = Offset.Zero
private val PAW = Offset(-19f, 14f) // both cards pivot near the bottom-left corner, where a paw would hold them

private class Puppet {
    val rig = Rig(1)
    val wake = Channel<Unit>(Channel.CONFLATED)
    var shown = MoleState.Hidden
    var frame by mutableIntStateOf(0)
}

/** The paper puppet, posed by [Rig] for [state]. [front] and [back] are the numbers on the cards it holds. */
@Composable
internal fun MolePuppet(state: MoleState, timeoutMs: Long?, front: String, back: String, modifier: Modifier) {
    val reduce = reduceMotion()
    val slow by rememberUpdatedState(LocalSlowMotion.current)
    val p = remember { Puppet() }
    LaunchedEffect(state) {
        if (state == p.shown) return@LaunchedEffect
        p.rig.set(state, reduce, delay = if (p.shown == MoleState.Hidden) 0.09 else 0.0, timeout = (timeoutMs ?: 8000L).toDouble())
        p.shown = state
        p.frame++
        p.wake.trySend(Unit)
    }
    LaunchedEffect(p) {
        while (true) {
            p.wake.receive()
            var last = -1L
            while (!p.rig.asleep) withFrameNanos { now ->
                if (last >= 0) p.rig.step((now - last) / 1e9 / slow)
                last = now
                p.frame++
            }
        }
    }
    val c = LocalMoleColors.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // .m-num: 600 15.5px, letter-spacing -0.02em, tabular, in px so it can be drawn in mole space like the SVG text.
    val family = inter(15.5f, 600)
    val num = remember(density, c.rim, family) {
        with(density) {
            TextStyle(
                color = c.rim,
                fontWeight = FontWeight.SemiBold,
                fontFamily = family,
                fontSynthesis = FontSynthesis.None,
                letterSpacing = (-.31f).toSp(),
                fontSize = 15.5f.toSp(),
                fontFeatureSettings = "tnum",
            )
        }
    }
    val frontText = remember(front, num) { measurer.measure(front, num) }
    val backText = remember(back, num) { measurer.measure(back, num) }
    Canvas(modifier) {
        p.frame
        val pose = p.rig.pose()
        // The SVG's viewBox (-54 -114 154 114.8), fitted like preserveAspectRatio meet, clipped like overflow: hidden.
        val s = min(size.width / 154f, size.height / 114.8f)
        clipRect {
            withTransform({
                translate((size.width - 154f * s) / 2, (size.height - 114.8f * s) / 2)
                scale(s, s, ORIGIN)
                translate(54f, 114f)
            }) { figure(pose, frontText, backText, c) }
        }
    }
}

private val Double.f get() = toFloat()
private fun unit(n: Double) = n.coerceIn(0.0, 1.0).toFloat()

private fun DrawScope.figure(p: Pose, front: TextLayoutResult, back: TextLayoutResult, c: MoleColors) {
    withTransform({
        translate(p.bodyX.f, p.bodyY.f)
        rotate(p.bodyRot.f, ORIGIN)
        translate(p.lifeX.f, p.lifeY.f)
        rotate(p.lifeRot.f, ORIGIN)
        scale(p.lifeSx.f, p.lifeSy.f, ORIGIN)
    }) {
        drawPath(BODY, c.figure)
        withTransform({ translate(18f, -64f); scale(.86f, .86f, ORIGIN); rotate(p.tie.f, ORIGIN) }) {
            drawPath(TIE, c.tie)
            drawPath(FOLD, c.tieFold)
            drawRoundRect(c.tieKnot, Offset(-4.5f, -4.5f), Size(9f, 9f), CornerRadius(2f))
        }
        // A profile can't turn its head, so the failed shake foreshortens the snout instead (yaw).
        withTransform({ translate(22f, -80f); rotate((p.head + p.sway).f, ORIGIN); scale(p.yaw.f, 1f, ORIGIN); rotate(p.nose.f, ORIGIN) }) {
            drawCircle(c.figure, 18.5f, ORIGIN)
            drawPath(SNOUT, c.figure)
            translate(p.specsX.f, p.specsY.f) {
                drawLine(c.bridge, Offset(2.9f, -16.3f), Offset(5.3f, -17f), 2.8f, StrokeCap.Round)
                lens(Offset(-5.8f, -13.9f), 9f, c)
                lens(Offset(12f, -18.8f), 7f, c)
            }
        }
    }
    translate(0f, (14 * (1 - p.stack)).f) {
        sheet(6f, -6f, 68f, c)
        sheet(11f, -12f, 58f, c)
    }
    val k = unit(p.card)
    val fan = p.fan.f
    withTransform({ translate(p.cardX.f, p.cardY.f); rotate(p.cardRot.f, ORIGIN); scale(0.9f + 0.1f * k, 0.9f + 0.1f * k, ORIGIN) }) {
        layer(k) {
            withTransform({ rotate(16 * fan, PAW); translate(3 * fan, 0f) }) { layer(unit(fan * 4.0)) { card(back, c) } }
            rotate(-12 * fan, PAW) { card(front, c) }
        }
    }
    translate((p.tipX + 6).f, (p.tipY - 1).f) {
        SCENT.forEachIndexed { i, mark -> drawPath(mark, c.ember, unit(p.scent[i]), Stroke(2f, cap = StrokeCap.Round)) }
    }
    for (speck in p.specks) if (speck.r > 0) drawCircle(c.figure, speck.r.f, Offset(speck.x.f, speck.y.f))
    drawOval(c.figure, Offset((p.moundX - 20).f, (p.moundY - 12).f), Size(40f, 24f))
    // The countdown runs on the stretch of ground right of the mole; the part under it is always hidden.
    drawLine(c.figure, Offset(-54f, 0f), Offset((40 + 60 * p.ground).f, 0f), 1.4f, StrokeCap.Round)
}

private fun DrawScope.lens(center: Offset, r: Float, c: MoleColors) {
    drawCircle(c.lens, r, center)
    drawCircle(c.rim, r, center, style = Stroke(1.4f))
}

private fun DrawScope.sheet(x: Float, y: Float, w: Float, c: MoleColors) {
    drawRoundRect(c.stack, Offset(x, y), Size(w, 6f), CornerRadius(1f))
    drawRoundRect(c.rim, Offset(x, y), Size(w, 6f), CornerRadius(1f), Stroke(1.2f))
}

// The slide card: white paper, ink edge, a two-bar chart and the slide number. 52 × 31, centred on its origin.
private fun DrawScope.card(number: TextLayoutResult, c: MoleColors) {
    drawRoundRect(c.lens, Offset(-26f, -15.5f), Size(52f, 31f), CornerRadius(1.8f))
    drawRoundRect(c.rim, Offset(-26f, -15.5f), Size(52f, 31f), CornerRadius(1.8f), Stroke(1.3f))
    drawRect(c.ember, Offset(-18f, -1f), Size(6.5f, 9.5f))
    drawRect(c.ember, Offset(-9.5f, -10.5f), Size(6.5f, 19f))
    drawText(number, topLeft = Offset(11f - number.size.width / 2f, 5.6f - number.firstBaseline))
}

// SVG group opacity: the group is composited once, so the cards' overlapping parts don't show through each other.
private inline fun DrawScope.layer(alpha: Float, block: DrawScope.() -> Unit) {
    if (alpha <= 0f) return
    if (alpha >= 1f) return block()
    drawIntoCanvas { it.saveLayer(Rect(-60f, -60f, 60f, 60f), Paint().apply { this.alpha = alpha }) }
    block()
    drawIntoCanvas { it.restore() }
}
