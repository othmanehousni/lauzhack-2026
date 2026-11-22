package ch.lauzhack.app.link

import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.mole.Announce
import ch.lauzhack.app.mole.Choices
import ch.lauzhack.app.mole.Samples
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.w3c.dom.Element

private const val APP = "ch.lauzhack.app"
private const val SIDE_PORT = 47475 // not the laptop app's, so one running on this Mac does not answer instead
private const val LOOKING = "Looking for your laptop…"
private const val MIC = "android.permission.RECORD_AUDIO"
private val ADB = System.getenv("ADB") ?: "adb"
private val FORBIDDEN = listOf("•", "·", "—", "–")

private class Node(val text: String, val desc: String, val id: String, val clickable: Boolean, val box: List<Int>, val kids: List<Node>) {
    fun all(): Sequence<Node> = sequenceOf(this) + kids.asSequence().flatMap { it.all() }
    val words get() = all().map { it.text.ifEmpty { it.desc } }.filter(String::isNotEmpty).joinToString(" ")
    val w get() = box[2] - box[0]
    val h get() = box[3] - box[1]
}

/**
 * The phone app on a running emulator, against a scripted laptop: a real [PhoneServer] fed the sample lines. Every state
 * in light and dark, taps that must reach the laptop, the buzz for each moment, the laptop going and coming back, the
 * network dropping, rotation, the background, a display cutout and the microphone denied. Runs only with
 * PHONE_CHECK=<dir> and the app installed (androidApp/check.sh does both); screenshots, sheet.png and report.txt go there.
 */
class PhoneCheck {
    private val line = MutableStateFlow(Samples.getValue(MoleState.Hello))
    private val screen = MutableStateFlow(Link.Screen())
    private val meeting = MutableStateFlow("Home insurance review")
    private var laptop: PhoneServer? = null
    private val report = StringBuilder()
    private var fails = 0
    private var dp = 2.625f
    private val shots = mutableListOf<Pair<String, File>>()
    private lateinit var dir: File

    @Test
    fun walk() = runBlocking {
        dir = File(System.getenv("PHONE_CHECK") ?: return@runBlocking).apply { mkdirs() }
        dir.listFiles { f -> f.extension == "png" }?.forEach { it.delete() }
        dp = adb("shell", "wm", "density").trim().substringAfterLast(' ').toFloat() / 160
        adb("shell", "wm", "user-rotation", "lock", "0")
        adb("reverse", "--remove-all")
        try {
            pass(dark = false)
            pass(dark = true)
            survive()
        } finally {
            laptop?.stop()
            adb("reverse", "--remove-all")
            adb("shell", "svc", "wifi", "enable")
            adb("shell", "svc", "data", "enable")
            adb("shell", "wm", "user-rotation", "lock", "0")
            adb("shell", "cmd", "uimode", "night", "no")
            adb("shell", "cmd", "overlay", "disable", "com.android.internal.display.cutout.emulation.tall")
            quietly()
            adb("shell", "am", "force-stop", APP)
            sheet()
            File(dir, "report.txt").writeText(report.toString())
        }
        println(report)
        assertEquals(0, fails, "$fails checks failed, see ${File(dir, "report.txt")}")
    }

    /** Every state once, in one theme: the microphone asked and denied in light, granted in dark. */
    private suspend fun pass(dark: Boolean) = coroutineScope {
        val t = if (dark) "dark" else "light"
        laptop?.stop() // no call yet
        adb("shell", "cmd", "uimode", "night", if (dark) "yes" else "no")
        if (dark) adb("shell", "pm", "grant", APP, MIC) else {
            adb("shell", "pm", "revoke", APP, MIC)
            adb("shell", "pm", "clear-permission-flags", APP, MIC, "user-set", "user-fixed")
        }
        delay(1500) // a revoke kills the app a moment later, which must not be the run about to start
        adb("shell", "am", "force-stop", APP)
        adb("logcat", "-c")
        line.value = Samples.getValue(MoleState.Hello)
        screen.value = Link.Screen()
        val since = deviceTime()
        start("10.0.2.2:$SIDE_PORT")
        if (!dark) {
            val deny = waitFor { n -> n.all().any { it.id.endsWith("permission_deny_button") } }?.all()?.first { it.id.endsWith("permission_deny_button") }
            check("$t: the microphone is asked for on first start", deny != null)
            shot("$t mic asked")
            if (deny != null) tap(deny)
        }
        val before = waitFor { LOOKING in it.words }
        settle("$t before a call", before)
        check("$t before a call: only the mole looking, nothing stale", before != null && listOf("Presenting", "On screen", "Laptop").none { it in before.words }, before?.words.orEmpty())
        if (!dark) check("$t microphone denied: the app goes on", before != null)

        val up = TimeSource.Monotonic.markNow()
        laptop = server()
        check("$t connects to the laptop", connected(), "in ${up.elapsedNow().inWholeMilliseconds} ms")
        state("$t hello", line.value, "Hi. I'm listening.")
        state("$t idle", MoleLine(MoleState.Idle, card = "07"), "You're driving.")
        state("$t hearing", MoleLine(MoleState.Idle, heard = "next slide", card = "07"), "“next slide”")
        state("$t heard", MoleLine(MoleState.Heard, heard = "next slide", card = "08"), "I've got it.")
        val announce = state("$t announce", Announce, "Showing slide 7")
        tapFor("$t Stop reaches the laptop", announce?.all()?.firstOrNull { it.clickable && "Stop" in it.words }, -1)
        val two = state("$t choice of two", Choices[0].second, "Which one?")
        tapFor("$t second row reaches the laptop", row(two, Choices[0].second.options[1].name), 1)
        val three = state("$t choice of three", Choices[1].second, "Which one?")
        tapFor("$t third row reaches the laptop", row(three, Choices[1].second.options[2].name), 2)
        state("$t picked", Choices[2].second, "Opening the revised offer…")
        screen.value = Link.Screen("Market size", slide())
        state("$t ready", Samples.getValue(MoleState.Ready), "Market size")
        state("$t unsure", Samples.getValue(MoleState.Unsure), "Didn't catch that.")
        state("$t failed", Samples.getValue(MoleState.Failed), "Couldn't find “roadmap”.")
        line.value = MoleLine(MoleState.Hidden)

        laptop!!.stop()
        val down = TimeSource.Monotonic.markNow()
        val gone = waitFor(20.seconds) { LOOKING in it.words }
        check("$t laptop gone: noticed", gone != null, "in ${down.elapsedNow().inWholeMilliseconds} ms (one screen dump, about 1 s, is the resolution)")
        settle("$t laptop gone", gone)
        check("$t laptop gone: nothing stale", gone != null && listOf("Market size", "Presenting", "Home insurance review").none { it in gone.words }, gone?.words.orEmpty())
        val back = TimeSource.Monotonic.markNow()
        laptop = server()
        check("$t laptop back: reconnects", connected(), "in ${back.elapsedNow().inWholeMilliseconds} ms")
        settle("$t laptop back", waitFor { "You're driving." in it.words })

        val buzzes = buzzes(since)
        check("$t buzzes: a tick for the announce, a double click for each choice, two pulses for the failure, nothing else", buzzes == listOf("tick", "double click", "double click", "pulses"), buzzes.joinToString())
        val mic = adb("logcat", "-d", "-s", "Mole").lines().filter { "Recognizer" in it || "recognizer" in it }.map { it.substringAfter(": ").trim() }.distinct()
        info("$t phone's ear: ${mic.ifEmpty { listOf(if (dark) "nothing logged" else "not started, the microphone is denied") }.joinToString(" | ")}")
    }

    /** What could go wrong in a talk: rotation, a cutout, the background, the network, a new address, adb reverse. */
    private suspend fun survive() = coroutineScope {
        adb("shell", "cmd", "uimode", "night", "no")
        val three = Choices[1].second
        adb("shell", "wm", "user-rotation", "lock", "1")
        val land = state("turned on its side", three, "Which one?")
        // Upright still, every row whole, on screen, and clear of the words around it.
        val rows = three.options.map { row(land, it.name) }
        val others = land?.all()?.filter { t -> t.text.isNotEmpty() && rows.none { r -> r != null && t in r.all() } }?.toList().orEmpty()
        val clash = rows.filterNotNull().flatMap { r -> others.filter { it.box[1] < r.box[3] && it.box[3] > r.box[1] && it.box[0] < r.box[2] && it.box[2] > r.box[0] }.map { "${r.words} / ${it.text}" } }
        check("turned on its side: stays upright, every row whole, on screen and clear", land != null && land.h > land.box[2] - land.box[0] && clash.isEmpty() && rows.all { it != null && it.box[1] >= 0 && it.box[3] <= land.box[3] && it.h >= 48 * dp }, clash.joinToString())
        adb("shell", "cmd", "overlay", "enable", "com.android.internal.display.cutout.emulation.tall")
        state("on its side, tall cutout", three, "Which one?")
        adb("shell", "wm", "user-rotation", "lock", "0")
        state("portrait tall cutout", three, "Which one?")
        adb("shell", "cmd", "overlay", "disable", "com.android.internal.display.cutout.emulation.tall")

        line.value = MoleLine(MoleState.Idle, card = "07")
        adb("shell", "settings", "put", "system", "haptic_feedback_enabled", "0")
        adb("shell", "dumpsys", "battery", "unplug")
        adb("shell", "cmd", "power", "set-mode", "1")
        waitFor { "You're driving." in it.words } // an overlay change recreates the activity, which connects anew
        val quiet = deviceTime()
        line.value = Choices[1].second
        waitFor { "Which one?" in it.words }
        delay(500)
        val saver = buzzes(quiet)
        check("a choice buzzes with touch feedback off and battery saver on", saver == listOf("double click"), saver.joinToString())
        quietly()

        line.value = MoleLine(MoleState.Idle, card = "07")
        delay(1000)
        val since = deviceTime()
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        delay(1500)
        line.value = Choices[0].second
        delay(1500)
        val away = buzzes(since)
        info("a choice while the app is in the background buzzes: ${away.ifEmpty { listOf("nothing asked") }.joinToString()}")
        check("a choice while the app is in the background still buzzes", away == listOf("double click"), away.joinToString())
        screen.value = Link.Screen("Q3 numbers.xlsx", slide())
        line.value = MoleLine(MoleState.Ready, "It's on screen.", "the second one")
        adb("shell", "am", "start", "-n", "$APP/.MainActivity")
        val front = waitFor { "It's on screen." in it.words }
        settle("back from the background", front)
        check("back from the background: the live line, not the one left behind", front != null && "Which one?" !in front.words && "Q3 numbers.xlsx" in front.words)

        line.value = MoleLine(MoleState.Idle, card = "07")
        val off = TimeSource.Monotonic.markNow()
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        val offline = waitFor(30.seconds) { LOOKING in it.words }
        check("network off: noticed", offline != null, "in ${off.elapsedNow().inWholeMilliseconds} ms")
        settle("network off", offline)
        val on = TimeSource.Monotonic.markNow()
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
        check("network back: reconnects", connected(60.seconds), "in ${on.elapsedNow().inWholeMilliseconds} ms")
        settle("network back", waitFor { "You're driving." in it.words })

        adb("shell", "am", "force-stop", APP)
        dropped()
        start("10.0.2.2:1")
        delay(4000)
        val moved = TimeSource.Monotonic.markNow()
        start("10.0.2.2:$SIDE_PORT")
        check("a new address reaches the running app", connected(), "in ${moved.elapsedNow().inWholeMilliseconds} ms")

        adb("shell", "am", "force-stop", APP)
        dropped()
        adb("reverse", "tcp:$PORT", "tcp:$SIDE_PORT")
        val usb = TimeSource.Monotonic.markNow()
        start(null)
        check("adb reverse, no address given: connects through 127.0.0.1", connected(), "in ${usb.elapsedNow().inWholeMilliseconds} ms")
        adb("reverse", "--remove-all")
    }

    /** Touch feedback on, battery saver off, the battery as it really is. */
    private fun quietly() {
        adb("shell", "settings", "put", "system", "haptic_feedback_enabled", "1")
        adb("shell", "cmd", "power", "set-mode", "0")
        adb("shell", "dumpsys", "battery", "reset")
    }

    private fun server() = PhoneServer(line, screen, meeting, MutableStateFlow(-100f), port = SIDE_PORT, host = "127.0.0.1", beacon = false).apply { start() }

    private fun start(laptop: String?) {
        adb("shell", "am", "start", "-W", "-n", "$APP/.MainActivity", *(if (laptop != null) arrayOf("-e", "laptop", laptop) else emptyArray()))
    }

    /** Puts [l] on the laptop, waits for [expect] on the phone and for it to settle, and checks the design on it. */
    private suspend fun state(name: String, l: MoleLine, expect: String): Node? {
        line.value = l
        return settle(name, waitFor { expect in it.words }).also { check("$name: shows “$expect”", it != null) }
    }

    private suspend fun settle(name: String, seen: Node?): Node? {
        if (seen == null) return null.also { shot(name) }
        delay(1200) // the title's swap and the rows' opening
        val n = dump() ?: seen
        shot(name)
        val texts = n.all().map { it.text.ifEmpty { it.desc } }.filter(String::isNotEmpty).toList()
        val bad = texts.filter { t -> FORBIDDEN.any { it in t } }
        val controls = n.all().filter { it.clickable }.toList()
        val small = controls.filter { it.h < 48 * dp || it.w < 48 * dp }
        val unnamed = controls.filter { it.words.isEmpty() }
        val off = n.all().filter { it.text.isNotEmpty() && (it.box[0] < n.box[0] || it.box[1] < n.box[1] || it.box[2] > n.box[2] || it.box[3] > n.box[3]) }.toList()
        if (bad.isNotEmpty() || small.isNotEmpty() || unnamed.isNotEmpty() || off.isNotEmpty()) {
            check("$name: design", false, "dashes or dots $bad, small targets ${small.map { "${it.words} ${(it.h / dp).toInt()} dp" }}, unnamed ${unnamed.size}, off screen ${off.map { it.text }}")
        }
        return n
    }

    private suspend fun tapFor(name: String, target: Node?, want: Int) = coroutineScope {
        if (target == null) return@coroutineScope check(name, false, "no such control on screen")
        val got = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(5.seconds) { laptop!!.picks.first() } }
        tap(target)
        val pick = got.await()
        check(name, pick == want, "laptop got ${pick ?: "nothing"}, the control is ${(target.h / dp).toInt()} dp tall and reads “${target.words}”")
    }

    private fun row(n: Node?, name: String) = n?.all()?.firstOrNull { it.clickable && name in it.words }

    private fun tap(n: Node) {
        adb("shell", "input", "tap", "${(n.box[0] + n.box[2]) / 2}", "${(n.box[1] + n.box[3]) / 2}")
    }

    private suspend fun waitFor(timeout: Duration = 15.seconds, ok: (Node) -> Boolean): Node? = withTimeoutOrNull(timeout) {
        var n = dump()
        while (n == null || !ok(n)) {
            delay(200)
            n = dump()
        }
        n
    }

    private suspend fun connected(timeout: Duration = 20.seconds) = withTimeoutOrNull(timeout) { laptop!!.connected.first { it } } != null

    private suspend fun dropped() = withTimeoutOrNull(10.seconds) { laptop!!.connected.first { !it } }

    private fun dump(): Node? {
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        val xml = ProcessBuilder(ADB, "exec-out", "cat", "/sdcard/ui.xml").start().inputStream.readBytes()
        val doc = runCatching { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream()) }.getOrNull() ?: return null
        fun node(e: Element): Node = Node(
            e.getAttribute("text"), e.getAttribute("content-desc"), e.getAttribute("resource-id"), e.getAttribute("clickable") == "true",
            Regex("\\d+").findAll(e.getAttribute("bounds")).map { it.value.toInt() }.toList(),
            (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().map(::node),
        )
        return (0 until doc.documentElement.childNodes.length).map { doc.documentElement.childNodes.item(it) }.filterIsInstance<Element>().firstOrNull()?.let(::node)
    }

    private fun shot(name: String) {
        val file = File(dir, "%02d %s.png".format(shots.size + 1, name))
        file.writeBytes(ProcessBuilder(ADB, "exec-out", "screencap", "-p").start().inputStream.readBytes())
        shots += name to file
    }

    private fun deviceTime() = adb("shell", "date '+%m-%d %H:%M:%S'").trim()

    /** What the app asked the vibrator for since [since] (device time), oldest first, with how it went if not played. */
    private fun buzzes(since: String): List<String> = adb("shell", "dumpsys", "vibrator_manager").lines()
        .filter { "opPkg=$APP" in it && "createTime: " in it }
        .map { it.substringAfter("createTime: ").take(18) to it }
        .filter { it.first >= since }
        .sortedBy { it.first }
        .map { (_, l) ->
            val kind = when {
                "effect=TICK" in l -> "tick"
                "effect=DOUBLE_CLICK" in l -> "double click"
                "Step" in l || "Waveform" in l -> "pulses"
                else -> "other"
            }
            val status = l.substringAfter("status: ").substringBefore(',')
            if (status == "finished" || status == "running") kind else "$kind ($status)"
        }

    private fun slide(): ByteArray {
        val s = Surface.makeRasterN32Premul(320, 180)
        s.canvas.clear(0xFFFFFFFF.toInt())
        listOf(54, 76, 102, 134).forEachIndexed { i, h ->
            s.canvas.drawRect(Rect.makeXYWH(30f + i * 44f, 164f - h, 30f, h.toFloat()), Paint().apply { color = if (i == 3) 0xFFE8111F.toInt() else 0x29161616 })
        }
        return s.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    /** Every screenshot, a row per theme and one for the rest, labelled. */
    private fun sheet() {
        if (shots.isEmpty()) return
        val rows = listOf("light", "dark").map { t -> shots.filter { it.first.startsWith(t) } } + listOf(shots.filter { !it.first.startsWith("light") && !it.first.startsWith("dark") })
        val (w, h, label) = Triple(216, 480, 40)
        val cols = rows.maxOf { it.size }
        val out = BufferedImage(cols * (w + 8) + 8, rows.size * (h + label + 8) + 8, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color(0x888888)
        g.fillRect(0, 0, out.width, out.height)
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, (name, file) ->
                val img = runCatching { ImageIO.read(file) }.getOrNull() ?: return@forEachIndexed
                val x = 8 + c * (w + 8)
                val y = 8 + r * (h + label + 8)
                val scale = minOf(w.toDouble() / img.width, h.toDouble() / img.height)
                g.drawImage(img, x, y, (img.width * scale).toInt(), (img.height * scale).toInt(), null)
                g.color = Color.BLACK
                g.drawString(name, x, y + h + 24)
            }
        }
        ImageIO.write(out, "png", File(dir, "sheet.png"))
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) fails++
        report.appendLine("${if (ok) "PASS" else "FAIL"}  $name${if (detail.isNotEmpty()) "  ($detail)" else ""}")
    }

    private fun info(text: String) {
        report.appendLine("INFO  $text")
    }
}

private fun adb(vararg args: String): String {
    val p = ProcessBuilder(listOf(ADB) + args).redirectErrorStream(true).start()
    return p.inputStream.readBytes().decodeToString().also { p.waitFor() }
}
