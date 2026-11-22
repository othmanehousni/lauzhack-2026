package ch.lauzhack.app.mole

import ch.lauzhack.app.MoleState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

// Drives mole/mole.js's rig in node through 300 random state changes (reduced motion, timeouts and frame rates mixed
// in), then replays the same calls on the Kotlin rig and compares every drawn number.
private const val SCRIPT = """
const ctx = { window: {}, matchMedia: () => ({ matches: false }) };
require("vm").runInNewContext(require("fs").readFileSync(process.argv[1], "utf8"), ctx);
const { createRig, states } = ctx.window.Mole;
const mulberry32 = a => () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
const random = mulberry32(11), rig = createRig(7);
const flat = p => [p.body.x, p.body.y, p.body.rot, p.life.x, p.life.y, p.life.rot, p.life.sx, p.life.sy, p.head, p.sway, p.yaw, p.nose,
  p.specs.x, p.specs.y, p.card, p.cardAt.x, p.cardAt.y, p.cardAt.rot, p.fan, p.stack, p.tip.x, p.tip.y, ...p.scent, p.tie,
  p.mound.x, p.mound.y, p.ground, ...p.specks.flatMap(s => [s.x, s.y, s.r]), rig.asleep ? 1 : 0];
let state = "hidden";
const script = [], poses = [];
for (let i = 0; i < 300; i++) {
  const others = states.filter(s => s !== state);
  const next = others[Math.floor(random() * others.length)];
  const reduce = random() < 0.15, delay = state === "hidden" ? 0.09 : 0, timeout = 500 + Math.floor(random() * 3000);
  const dt = random() < 0.5 ? 1 / 240 : 1 / 60, steps = 1 + Math.floor(random() * 120);
  rig.set(next, { reduce, delay, timeout });
  for (let n = 0; n < steps; n++) rig.step(dt);
  state = next;
  script.push([next, reduce, delay, timeout, dt, steps]);
  poses.push(flat(rig.pose()));
}
console.log(JSON.stringify({ script, poses }));
"""

private fun flat(p: Pose, asleep: Boolean) = listOf(
    p.bodyX, p.bodyY, p.bodyRot, p.lifeX, p.lifeY, p.lifeRot, p.lifeSx, p.lifeSy, p.head, p.sway, p.yaw, p.nose,
    p.specsX, p.specsY, p.card, p.cardX, p.cardY, p.cardRot, p.fan, p.stack, p.tipX, p.tipY, *p.scent.toTypedArray(), p.tie,
    p.moundX, p.moundY, p.ground, *p.specks.flatMap { listOf(it.x, it.y, it.r) }.toTypedArray(), if (asleep) 1.0 else 0.0,
)

class RigParityTest {
    @Test
    fun drawsTheSameFramesAsMoleJs() {
        val node = try {
            ProcessBuilder("node", "-e", SCRIPT, File("../mole/mole.js").absolutePath).start()
        } catch (_: IOException) {
            return println("RigParityTest skipped: node is not on PATH")
        }
        val text = node.inputStream.bufferedReader().readText()
        check(node.waitFor() == 0) { node.errorStream.bufferedReader().readText() }
        val run = Json.parseToJsonElement(text).jsonObject
        val poses = run.getValue("poses").jsonArray
        val rig = Rig(7)
        run.getValue("script").jsonArray.forEachIndexed { i, call ->
            val c = call.jsonArray.map { it.jsonPrimitive }
            rig.set(MoleState.entries.first { it.name.equals(c[0].content, ignoreCase = true) }, c[1].boolean, c[2].double, c[3].double)
            repeat(c[5].int) { rig.step(c[4].double) }
            val want = poses[i].jsonArray.map { it.jsonPrimitive.double }
            val got = flat(rig.pose(), rig.asleep)
            for (j in want.indices) assertTrue(abs(want[j] - got[j]) < 1e-6, "call $i (${c[0].content}), number $j: mole.js ${want[j]}, Kotlin ${got[j]}")
        }
    }
}
