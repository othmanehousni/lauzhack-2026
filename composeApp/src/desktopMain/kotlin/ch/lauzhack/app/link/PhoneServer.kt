package ch.lauzhack.app.link

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds
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
 * now. [lead] says whose words to follow; it falls back to the laptop while no phone is connected.
 */
class PhoneServer(
    line: StateFlow<MoleLine>,
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
    private val _words = MutableSharedFlow<Heard>(extraBufferCapacity = 64)
    private val _levels = MutableSharedFlow<Link.Level>(extraBufferCapacity = 64)
    private val _picks = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    val words: SharedFlow<Heard> = _words
    val levels: SharedFlow<Link.Level> = _levels
    val picks: SharedFlow<Int> = _picks
    val connected: StateFlow<Boolean> = phones.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)
    val lead: StateFlow<Link.Lead> = combine(walk, phones) { w, n -> if (n > 0) w.lead else Link.Lead() }.stateIn(scope, SharingStarted.Eagerly, Link.Lead())

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
        phones.update { it + 1 }
        val push = launch { pushes.collect { send(Frame.Text(it.encode())) } }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val message = decode(frame.readText())) {
                    is Link.Words -> _words.emit(message.heard)
                    is Link.Pick -> _picks.emit(message.index)
                    is Link.Level -> {
                        if (message.speech) walk.update { it.next(laptopDb.value, message.db) }
                        _levels.emit(message)
                    }
                    else -> Unit
                }
            }
        } finally {
            push.cancel()
            phones.update { it - 1 }
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

// Each interface's own broadcast address, since 255.255.255.255 leaves by the default route only.
private fun broadcasts(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.interfaceAddresses }
    .mapNotNull { it.broadcast }

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
