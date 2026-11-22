package ch.lauzhack.app.link

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.math.log10
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

/**
 * The laptop's end of the link. Serves phones on [port], announces itself once a second on every local network, and
 * runs the lead policy on every phone audio frame and level against [laptopDb], the laptop microphone's level in dB
 * now. [lead] says whose words to follow; it falls back to the laptop while no phone is connected, and while the
 * connected one sends no levels, which it does about ten times a second while its recognizer listens: a phone in a
 * pocket with its screen off, or whose recognizer died, must not keep the lead and leave the meeting deaf.
 *
 * One phone at a time: anything on the Wi-Fi can connect, so a second one is refused while the first is connected, and
 * a pick counts only for an option on offer. The demo runs on the phone's own hotspot, which limits who can try.
 */
class PhoneServer(
    private val line: StateFlow<MoleLine>,
    screen: StateFlow<Link.Screen>,
    meeting: StateFlow<String>,
    private val laptopDb: StateFlow<Float?>,
    private val port: Int = PORT,
    private val host: String = "0.0.0.0",
    private val beacon: Boolean = true,
    private val usePhoneMic: Boolean = true,
    private val transcribe: (suspend (Flow<ByteArray>, suspend (Heard) -> Unit) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = MutableStateFlow(0)
    private data class Input(val socket: DefaultWebSocketServerSession, val walk: Walk)
    private val input = MutableStateFlow<Input?>(null)
    private val hearing = MutableStateFlow(false) // the connected phone's levels, audio or words keep arriving
    private val _words = MutableSharedFlow<Heard>(extraBufferCapacity = 64)
    private val _levels = MutableSharedFlow<Link.Level>(extraBufferCapacity = 64)
    private val _picks = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    val words: SharedFlow<Heard> = _words
    val levels: SharedFlow<Link.Level> = _levels
    val picks: SharedFlow<Int> = _picks
    val connected: StateFlow<Boolean> = phones.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)
    private fun select(active: Input?, db: Float?, heard: Boolean): Link.Lead =
        when {
            active == null || !usePhoneMic || !heard -> Link.Lead()
            db == null -> active.walk.lead.copy(phone = active.walk.phoneAudible)
            else -> active.walk.lead
        }

    internal fun usesPhoneMic(): Boolean = select(input.value, laptopDb.value, hearing.value).phone
    val lead: StateFlow<Link.Lead> = combine(input, laptopDb, hearing, ::select).stateIn(scope, SharingStarted.Eagerly, Link.Lead())

    private val pushes = merge(line.map { Link.Line(it) }, screen, lead, meeting.map { Link.Meeting(it) })

    private val server = embeddedServer(CIO, configure = {
        connector {
            host = this@PhoneServer.host
            port = this@PhoneServer.port
        }
        reuseAddress = true // a restarted laptop app gets its port back at once
    }) {
        install(WebSockets) {
            pingPeriod = 2.seconds
            timeout = 5.seconds
            maxFrameSize = 64 * 1024L // a phone sends words, picks and levels, all small
        }
        routing { webSocket(PATH) { serve() } }
    }

    fun start() {
        server.start()
        if (beacon) scope.launch { announce() }
    }

    fun stop() {
        server.stop(100, 1000)
        scope.cancel()
    }

    private suspend fun DefaultWebSocketServerSession.serve() {
        if (!phones.compareAndSet(0, 1)) return close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "one phone at a time"))
        var walk = Walk()
        val heardAt = MutableStateFlow(TimeSource.Monotonic.markNow())
        val lastAudio = MutableStateFlow<TimeMark?>(null)
        fun report(ready: Boolean) {
            lastAudio.value = if (ready) TimeSource.Monotonic.markNow() else null
            if (ready) {
                heardAt.value = TimeSource.Monotonic.markNow()
                hearing.value = true
            }
            input.update { active ->
                when {
                    active != null && active.socket !== this -> active
                    ready -> Input(this, walk)
                    else -> null
                }
            }
        }
        val audio = transcribe?.takeIf { usePhoneMic }?.let { transcriber ->
            PcmStream(this, transcriber, onWords = { words ->
                if (input.value?.socket === this) _words.emit(words)
            }, onFailure = { report(false) })
        }
        fun stopAudio() {
            audio?.stop()
            report(false)
            walk = Walk() // a resumed capture may have different gain or phone placement
        }
        // Controls and WebSocket pings do not prove that microphone audio is still arriving.
        val watchdog = launch {
            lastAudio.collectLatest { last ->
                if (last != null) {
                    delay(700.milliseconds - last.elapsedNow())
                    if (lastAudio.value == last) stopAudio()
                }
            }
        }
        val push = launch { pushes.collect { send(Frame.Text(it.encode())) } }
        val watch = launch { while (true) { hearing.value = heardAt.value.elapsedNow() < HEARING; delay(250.milliseconds) } }
        try {
            if (audio != null) send(Frame.Text(Link.AcceptAudio.encode()))
            while (true) {
                val received = incoming.receiveCatching()
                val frame = received.getOrNull() ?: break
                if (frame is Frame.Binary) {
                    val pcm = frame.data
                    if (audio == null || pcm.size != PCM_BYTES) continue
                    if (input.value?.let { it.socket !== this } == true) continue
                    val db = pcmDecibels(pcm)
                    val mac = laptopDb.value
                    walk = walk.next(mac, db)
                    // Establish ownership before the recognizer can deliver its first result.
                    report(true)
                    if (!audio.offer(pcm)) report(false)
                    continue
                }
                if (frame !is Frame.Text) continue
                when (val message = decode(frame.readText())) {
                    is Link.Mic -> if (message.ready) report(true) else stopAudio()
                    is Link.Words -> {
                        report(true)
                        if (input.value?.socket === this) _words.emit(message.heard)
                    }
                    // Anything on the Wi-Fi can connect, so a pick counts only for an option on offer now, and a stop (-1)
                    // only while a choice or an announce is up.
                    is Link.Pick -> line.value.let { l ->
                        if ((l.state == MoleState.Choosing || l.state == MoleState.Retrieving) && message.index in -1 until l.options.size) _picks.emit(message.index)
                    }
                    is Link.Level -> {
                        walk = walk.next(laptopDb.value, message.db, message.speech)
                        report(true)
                        _levels.emit(message)
                    }
                    else -> Unit
                }
            }
        } finally {
            stopAudio()
            watchdog.cancel()
            push.cancel()
            watch.cancel()
            hearing.value = false
            phones.value = 0
        }
    }

    private suspend fun announce() = DatagramSocket().use { socket ->
        socket.broadcast = true
        val hello = "$BEACON $port".encodeToByteArray()
        while (true) {
            for (to in broadcasts()) runCatching { socket.send(DatagramPacket(hello, hello.size, to, PORT)) }
            delay(1.seconds)
        }
    }
}

private val HEARING = 2.seconds // the phone leads only while it sent a level this recently

/** One recognizer per active capture. Keep at most eight seconds of audio while the helper builds and the model starts. */
private class PcmStream(
    private val scope: CoroutineScope,
    private val transcribe: suspend (Flow<ByteArray>, suspend (Heard) -> Unit) -> Unit,
    private val onWords: suspend (Heard) -> Unit,
    private val onFailure: () -> Unit,
) {
    private var packets: Channel<ByteArray>? = null
    private var worker: Job? = null

    fun offer(bytes: ByteArray): Boolean {
        if (worker?.isActive != true) {
            val channel = Channel<ByteArray>(80, BufferOverflow.DROP_OLDEST)
            packets = channel
            worker = scope.launch {
                try {
                    transcribe(channel.receiveAsFlow(), onWords)
                } catch (e: Exception) {
                    ensureActive()
                    channel.cancel()
                    onFailure()
                    log("phone speech recognition failed: ${e.message}")
                    delay(2.seconds)
                }
            }
        }
        return packets?.trySend(bytes)?.isSuccess == true
    }

    fun stop() {
        worker?.cancel()
        packets?.cancel()
        worker = null
        packets = null
    }
}

/** RMS in dBFS for one 100 ms mono PCM16LE frame. */
internal fun pcmDecibels(pcm: ByteArray): Float {
    var energy = 0.0
    for (i in pcm.indices step 2) {
        val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)) / 32768.0
        energy += sample * sample
    }
    return (10 * log10(maxOf(1e-10, energy / (pcm.size / 2)))).toFloat()
}

// Each interface's own broadcast address, since 255.255.255.255 leaves by the default route only.
private fun broadcasts(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.interfaceAddresses }
    .mapNotNull { it.broadcast }

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
