package ch.lauzhack.app.link

import androidx.compose.ui.graphics.ImageBitmap
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What the phone shows, as last heard from the laptop. */
data class PhoneState(
    val line: MoleLine = MoleLine(),
    val onScreenName: String = "",
    val thumbnail: ImageBitmap? = null,
    val phoneLeads: Boolean = false,
    val metres: Float = SEATED_M,
    val meeting: String = "",
    val connected: Boolean = false,
    val trouble: String? = null, // why the phone cannot reach the laptop, in words the presenter can act on
)

/**
 * The phone's end of the link. Tries 127.0.0.1 first (`adb reverse` over USB), then the first laptop [beacon] hears
 * ("host:port"), then [manual], and starts over whenever the link drops. [http] needs the WebSockets plugin with a ping,
 * so a laptop that vanishes is noticed in seconds.
 */
class LaptopClient(
    scope: CoroutineScope,
    private val http: HttpClient,
    private val port: Int = PORT,
    private val beacon: suspend () -> String? = { null },
) {
    @Volatile var manual: String? = null // "host" or "host:port"

    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    @Volatile private var session: DefaultClientWebSocketSession? = null

    init {
        scope.launch {
            while (true) {
                if (talk("127.0.0.1")) continue
                val heard = beacon()
                if (heard != null && talk(heard)) continue
                val manual = manual
                if (manual != null && talk(manual)) continue
                _state.update { it.copy(trouble = trouble(heard, manual)) }
                delay(1.seconds)
            }
        }
    }

    fun pick(index: Int) = send(Link.Pick(index))
    fun words(heard: Heard) = send(Link.Words(heard))
    fun level(db: Float, speech: Boolean) = send(Link.Level(db, speech))

    private fun send(message: Link) {
        session?.outgoing?.trySend(Frame.Text(message.encode()))
    }

    /** False if it could not connect, or the laptop closed without a word, as it does to a second phone; true once a
     * session the laptop spoke in has ended. Connected means spoken to, so a refused phone never reads as connected. */
    private suspend fun talk(address: String): Boolean {
        val ws = try {
            withTimeout(3.seconds) {
                http.webSocketSession(host = address.substringBefore(':'), port = address.substringAfter(':', "").toIntOrNull() ?: port, path = PATH)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return false
        }
        session = ws
        var spoke = false
        try {
            for (frame in ws.incoming) if (frame is Frame.Text) decode(frame.readText())?.let {
                if (!spoke) _state.update { s -> s.copy(connected = true, trouble = null) }
                spoke = true
                show(it)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
        } finally {
            session = null
            ws.cancel()
            _state.value = PhoneState() // nothing the laptop said holds once it is gone
        }
        return spoke
    }

    private fun show(message: Link) {
        val thumbnail = (message as? Link.Screen)?.thumbnail?.let(::decodeImage)
        _state.update {
            when (message) {
                is Link.Line -> it.copy(line = message.line)
                is Link.Screen -> it.copy(onScreenName = message.name, thumbnail = thumbnail)
                is Link.Lead -> it.copy(phoneLeads = message.phone, metres = message.metres)
                is Link.Meeting -> it.copy(meeting = message.title)
                is Link.Level, is Link.Words, is Link.Pick -> it
            }
        }
    }
}

private fun trouble(heard: String?, manual: String?) = when {
    heard != null -> "Found the laptop at ${heard.substringBefore(':')} but could not connect. This Wi-Fi may keep devices apart: try a phone hotspot or USB."
    manual != null -> "Could not reach the laptop at $manual, and none answered on this Wi-Fi."
    else -> "No laptop found on this Wi-Fi. Venue networks often keep devices apart: try a phone hotspot or USB."
}

internal expect fun decodeImage(bytes: ByteArray): ImageBitmap?
