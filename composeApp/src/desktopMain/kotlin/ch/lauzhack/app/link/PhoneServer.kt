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
    private val line: StateFlow<MoleLine>,
    screen: StateFlow<Link.Screen>,
    meeting: StateFlow<String>,
    private val laptopDb: StateFlow<Float?>,
    private val port: Int = PORT,
    private val host: String = "0.0.0.0",
    private val beacon: Boolean = true,
    private val usePhoneMic: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = MutableStateFlow(0)
    private data class Input(val socket: DefaultWebSocketServerSession, val walk: Walk)
    private val input = MutableStateFlow<Input?>(null)
    private val _words = MutableSharedFlow<Heard>(extraBufferCapacity = 64)
    private val _levels = MutableSharedFlow<Link.Level>(extraBufferCapacity = 64)
    private val _picks = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    val words: SharedFlow<Heard> = _words
    val levels: SharedFlow<Link.Level> = _levels
    val picks: SharedFlow<Int> = _picks
    val connected: StateFlow<Boolean> = phones.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)
    val lead: StateFlow<Link.Lead> = combine(input, laptopDb) { active, db ->
        when {
            active == null || !usePhoneMic -> Link.Lead()
            db == null -> active.walk.lead.copy(phone = true)
            else -> active.walk.lead
        }
    }.stateIn(scope, SharingStarted.Eagerly, Link.Lead())

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
        var walk = Walk()
        fun report(ready: Boolean) {
            input.update { active ->
                when {
                    active != null && active.socket !== this -> active
                    ready -> Input(this, walk)
                    else -> null
                }
            }
        }
        val push = launch { pushes.collect { send(Frame.Text(it.encode())) } }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val message = decode(frame.readText())) {
                    is Link.Mic -> report(message.ready)
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
                        val db = laptopDb.value
                        if (message.speech && db != null) walk = walk.next(db, message.db)
                        report(true)
                        _levels.emit(message)
                    }
                    else -> Unit
                }
            }
        } finally {
            report(false)
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
