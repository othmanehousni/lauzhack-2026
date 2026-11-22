package ch.lauzhack.app.meeting

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempFile
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isExecutable
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MeetingDetectorTest {
    private val t0 = Instant.parse("2026-11-22T10:00:00Z")
    private val zoom = """{"app":"Zoom","bundle":"us.zoom.xos","call":true,"input":["us.zoom.xos"],"mic":true,"probable":false,"title":"Home insurance review"}"""
    private val zoomMuted = """{"app":"Zoom","bundle":"us.zoom.xos","call":true,"input":[],"mic":false,"probable":false,"title":"Home insurance review"}"""
    private val zoomAndHear = """{"app":"Zoom","bundle":"us.zoom.xos","call":true,"input":["ch.lauzhack.mole.hear","us.zoom.xos"],"mic":true,"probable":false,"title":"Home insurance review"}"""
    private val chrome = """{"app":"Google Chrome","bundle":"com.google.Chrome","call":true,"input":["com.google.Chrome.helper"],"mic":true,"probable":true,"title":"Google Chrome meeting"}"""
    private val chromeMuted = """{"app":"Google Chrome","bundle":"com.google.Chrome","call":true,"input":[],"mic":false,"probable":true,"title":"Google Chrome meeting"}"""
    private val none = """{"call":false,"input":[]}"""
    private val died: String? = null
    private val review = Meeting("Zoom", "Home insurance review", false, t0)

    /** The meeting at each second up to [until], with each line heard at its second (a null line: the helper died). */
    private fun play(until: Int, vararg lines: Pair<Int, String?>): List<Meeting?> {
        val calls = Calls()
        val at = lines.toMap()
        return (0..until).map { s ->
            if (s in at) calls.hear(at[s]?.let { parse(it)!! }, t0 + s.seconds) else calls.tick(t0 + s.seconds)
            calls.meeting
        }
    }

    @Test
    fun readsLines() {
        assertEquals(Line(call = true, app = "Zoom", title = "Home insurance review", probable = false, mic = true), parse(zoom))
        assertEquals(Line(), parse(none))
        assertEquals(true, parse(chrome)?.probable)
        assertNull(parse("meet: Core Audio restarted"))
    }

    @Test
    fun joinMuteForAMinuteAndLeave() {
        val seen = play(700, 0 to zoom, 60 to zoomMuted, 120 to zoom, 600 to none)
        assertEquals(List(5) { null }, seen.subList(0, 5))  // not yet: could be a microphone test
        assertEquals(List(605) { review }, seen.subList(5, 610))  // muted from 60 to 120, left at 600
        assertEquals(List(91) { null }, seen.subList(610, 701))
    }

    @Test
    fun aBlipIsNoMeeting() {
        assertEquals(List(31) { null }, play(30, 0 to zoom, 3 to none))
        assertEquals(List(31) { null }, play(30, 0 to chrome, 3 to chromeMuted))
    }

    @Test
    fun captureAloneIsNoMeeting() {
        val hear = """{"call":false,"input":["ch.lauzhack.mole.hear"]}"""
        val voiceMessage = """{"call":false,"input":["net.whatsapp.WhatsApp"]}"""
        assertEquals(List(121) { null }, play(120, 0 to hear, 10 to voiceMessage, 70 to none))
        assertEquals(review, play(5, 0 to zoom, 3 to zoomAndHear)[5])  // the mole's own listening changes nothing
    }

    @Test
    fun outlivesTheHelperDyingWhenItComesBack() {
        val restarted = play(200, 0 to zoom, 100 to died, 101 to none, 102 to zoom)
        assertEquals(List(196) { review }, restarted.subList(5, 201))
        val gone = play(200, 0 to zoom, 100 to died)
        assertEquals(review, gone[109])
        assertNull(gone[110])
    }

    @Test
    fun soundAloneLastsFiveMinutes() {
        val seen = play(800, 0 to chrome, 60 to chromeMuted, 500 to chrome, 503 to chromeMuted)
        assertEquals("Google Chrome meeting", seen[359]?.title)
        assertEquals(true, seen[359]?.probable)
        assertNull(seen[360])
        assertEquals(List(441) { null }, seen.subList(360, 801))  // nor does a voice message in the same browser bring it back
    }

    @Test
    fun buildsTheHelperOnce() {
        val exe = built("meet", PLIST)
        val time = exe.getLastModifiedTime()
        assertTrue(exe.isExecutable())
        assertEquals(exe, built("meet", PLIST))
        assertEquals(time, exe.getLastModifiedTime())
    }

    @Test
    fun startsTheHelperAgainAndYieldsToTheNextDetectedChange() = runBlocking {
        val runs = createTempFile()
        val script = """echo run >> '$runs'; echo '$zoom'; sleep 0.5; echo '$none'; sleep 0.5; echo '$zoom'; sleep 0.3"""
        val detector = MeetingDetector({ listOf("sh", "-c", script) }, Calls(start = 0.seconds, end = 0.seconds))
        val job = launch { detector.run() }
        withTimeout(10.seconds) {
            assertEquals("Home insurance review", detector.meeting.first { it != null }?.title)
            detector.stop()
            delay(200)
            assertNull(detector.meeting.value)  // the override wins while the helper still reports the call
            detector.meeting.first { it != null }  // until the call ends and a new one begins
            detector.start("Weekly sync")
            assertEquals("Weekly sync", detector.meeting.value?.title)
            while (runs.readLines().size < 2) delay(100)
        }
        job.cancel()
    }
}
