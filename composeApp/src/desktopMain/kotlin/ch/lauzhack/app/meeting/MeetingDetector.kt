package ch.lauzhack.app.meeting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A call the presenter is in: its app's name ("" when started by hand), its title, whether it is only probable (a
 *  browser captures the microphone and plays sound, likely for a call), and when it began. */
data class Meeting(val app: String, val title: String, val probable: Boolean, val since: Instant)

/** Whether the presenter is in a call, as meet.swift sees it: [meeting] is null outside one. Call [run] once, for as
 *  long as the app runs. */
class MeetingDetector internal constructor(private val command: () -> List<String>, private val calls: Calls) {
    constructor() : this({ listOf(built("meet", PLIST).toString()) }, Calls())

    private val _meeting = MutableStateFlow<Meeting?>(null)
    val meeting: StateFlow<Meeting?> = _meeting.asStateFlow()
    private var overridden = false

    /** For when detection is wrong: wins until a detected call begins or ends. */
    fun start(title: String) = set(Meeting("", title, false, Clock.System.now()))

    fun stop() = set(null)

    private fun set(meeting: Meeting?) = synchronized(this) {
        overridden = true
        _meeting.value = meeting
    }

    /** Runs meet.swift, building it first if needed, and starts it again whenever it dies. Returns if it cannot be built. */
    suspend fun run() {
        val helper = try {
            withContext(Dispatchers.IO) { command() }
        } catch (e: Exception) {
            System.err.println("meeting detection is off: ${e.message}")
            return
        }
        coroutineScope {
            launch {
                while (true) {
                    delay(1.seconds)
                    update { tick(it) }
                }
            }
            while (true) {
                try {
                    val process = ProcessBuilder(helper).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                    val reading = launch(Dispatchers.IO) {
                        try {
                            process.inputStream.bufferedReader().forEachLine { text -> parse(text)?.let { line -> update { hear(line, it) } } }
                        } catch (_: IOException) {
                        }
                    }
                    try {
                        reading.join()
                    } finally {
                        process.destroy()
                    }
                } catch (e: IOException) {
                    System.err.println("meet: ${e.message}")
                }
                update { hear(null, it) }
                delay(1.seconds)
            }
        }
    }

    private fun update(change: Calls.(Instant) -> Unit) = synchronized(this) {
        val before = calls.meeting
        calls.change(Clock.System.now())
        if ((before == null) != (calls.meeting == null)) overridden = false
        if (!overridden) _meeting.value = calls.meeting
    }
}

/** One line of meet.swift. */
@Serializable
internal data class Line(val call: Boolean = false, val app: String = "", val title: String = "", val probable: Boolean = false, val mic: Boolean = false)

private val json = Json { ignoreUnknownKeys = true }

internal fun parse(text: String): Line? = try {
    json.decodeFromString<Line>(text)
} catch (_: IllegalArgumentException) {
    null
}

/** meet.swift's lines turned into the meeting in progress. A call begins once the helper has reported it with the
 *  microphone on for [start] (a microphone test is shorter). It ends once the helper has reported no call for [end] (a
 *  device switch or a helper started again is shorter), or a call without the microphone for [muted]: an app that lets
 *  go of the microphone when the presenter mutes still plays the call, but a browser may also play on after one. */
internal class Calls(private val start: Duration = 5.seconds, private val end: Duration = 10.seconds, private val muted: Duration = 5.minutes) {
    var meeting: Meeting? = null
        private set
    private var call: Line? = null
    private var since = Instant.DISTANT_PAST  // when the helper last went between no call, a call with the microphone, and one without

    /** The helper's latest line; null when it died. */
    fun hear(line: Line?, now: Instant) {
        tick(now)
        val next = line?.takeIf { it.call }
        if (next?.mic != call?.mic) since = now
        call = next
        tick(now)
    }

    fun tick(now: Instant) {
        val call = call
        val held = now - since
        meeting = when {
            call == null -> meeting.takeIf { held < end }
            !call.mic && held >= muted -> null
            meeting == null && !(call.mic && held >= start) -> null
            else -> Meeting(call.app, call.title, call.probable, meeting?.since ?: since)
        }
    }
}

internal val PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>ch.lauzhack.mole.meet</string>
<key>NSCalendarsFullAccessUsageDescription</key><string>Molière shows the title of the meeting you are in, from your calendar.</string>
</dict></plist>
"""

/** A Swift program from the app's resources, built into the cache unless it is there already from the same source and
 *  Info.plist, which names the permissions it may ask for. */
internal fun built(name: String, plist: String): Path {
    val source = MeetingDetector::class.java.getResource("/$name.swift")!!.readBytes()
    val cache = Path(System.getProperty("user.home"), "Library", "Caches", "Moliere").createDirectories()
    val exe = cache / "$name-%08x".format((source.decodeToString() + plist).hashCode())
    if (exe.exists()) return exe
    (cache / "$name.swift").writeBytes(source)
    (cache / "$name.plist").writeText(plist)
    val part = cache / "$name-${ProcessHandle.current().pid()}.part"
    val swiftc = ProcessBuilder("swiftc", "-O", "-swift-version", "5", "$name.swift", "-o", part.toString(),
        "-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", "$name.plist")
        .directory(cache.toFile()).inheritIO().start()
    check(swiftc.waitFor() == 0) { "swiftc $name.swift failed" }
    return part.moveTo(exe, ATOMIC_MOVE)
}
