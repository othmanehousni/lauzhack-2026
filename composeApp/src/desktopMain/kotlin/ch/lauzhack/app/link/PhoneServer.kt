package ch.lauzhack.app.link

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

/**
 * The laptop's end of the link. Serves phones on [port], announces itself once a second on every local network, and
 * runs the lead policy on each level the phone hears speech in against [laptopDb], the laptop microphone's level in dB
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
    private val laptopDb: StateFlow<Float>,
    private val port: Int = PORT,
    private val host: String = "0.0.0.0",
    private val beacon: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = MutableStateFlow(0)
    private val walk = MutableStateFlow(Walk()) // kept across reconnects: the gains do not change
    private val hearing = MutableStateFlow(false) // the connected phone's levels keep arriving
    private val _words = MutableSharedFlow<Heard>(extraBufferCapacity = 64)
    private val _levels = MutableSharedFlow<Link.Level>(extraBufferCapacity = 64)
    private val _picks = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    val words: SharedFlow<Heard> = _words
    val levels: SharedFlow<Link.Level> = _levels
    val picks: SharedFlow<Int> = _picks
    val connected: StateFlow<Boolean> = phones.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)
    val lead: StateFlow<Link.Lead> = combine(walk, phones, hearing) { w, n, h -> if (n > 0 && h) w.lead else Link.Lead() }
        .stateIn(scope, SharingStarted.Eagerly, Link.Lead())

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
        val push = launch { pushes.collect { send(Frame.Text(it.encode())) } }
        val heardAt = MutableStateFlow(TimeSource.Monotonic.markNow())
        val watch = launch { while (true) { hearing.value = heardAt.value.elapsedNow() < HEARING; delay(250.milliseconds) } }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val message = decode(frame.readText())) {
                    is Link.Words -> _words.emit(message.heard)
                    // Anything on the Wi-Fi can connect, so a pick counts only for an option on offer now, and a stop (-1)
                    // only while a choice or an announce is up.
                    is Link.Pick -> line.value.let { l ->
                        if ((l.state == MoleState.Choosing || l.state == MoleState.Retrieving) && message.index in -1 until l.options.size) _picks.emit(message.index)
                    }
                    is Link.Level -> {
                        heardAt.value = TimeSource.Monotonic.markNow()
                        hearing.value = true
                        if (message.speech) walk.update { it.next(laptopDb.value, message.db) }
                        _levels.emit(message)
                    }
                    else -> Unit
                }
            }
        } finally {
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

// Each interface's own broadcast address, since 255.255.255.255 leaves by the default route only.
private fun broadcasts(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.interfaceAddresses }
    .mapNotNull { it.broadcast }

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
