package ch.lauzhack.app.link

import android.content.Context
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ch.lauzhack.app.MoleState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone as the Android screen sees it: [state] for MolePhone, [onPick] for its taps. Hears with the on-device
 * recognizer while [listen] is on (the activity is started and the microphone granted), and buzzes once each time
 * the mole starts choosing. Build it on the main thread.
 */
class Phone(context: Context, scope: CoroutineScope) {
    private val app = context.applicationContext
    private val client = LaptopClient(
        scope,
        HttpClient(OkHttp) {
            engine { config { pingInterval(2, TimeUnit.SECONDS) } } // the WebSockets plugin's own ping does not apply to OkHttp
            install(WebSockets)
        },
        beacon = { withContext(Dispatchers.IO) { hearBeacon(app) } },
    )
    private var levelAt = 0L
    private val ears = Ears(app, client::words) { db, speech ->
        val now = SystemClock.elapsedRealtime()
        if (now - levelAt >= 100) { // 10 Hz, the rate the lead policy is tuned for
            levelAt = now
            client.level(db, speech)
        }
    }

    val state: StateFlow<PhoneState> = client.state
    var manual: String? by client::manual // "host" or "host:port", tried when nothing else answers

    fun onPick(index: Int) = client.pick(index)
    fun listen(on: Boolean) = if (on) ears.start() else ears.stop()

    init {
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        val buzz = VibrationEffect.createWaveform(longArrayOf(0, 12, 60, 12), -1)
        scope.launch {
            state.map { it.line.state }.distinctUntilChanged().collect { if (it == MoleState.Choosing) vibrator.vibrate(buzz) }
        }
    }
}

/** The first laptop beacon heard within 2.5 s, as "host:port". */
private fun hearBeacon(context: Context): String? {
    // Some phones drop broadcast packets unless a multicast lock is held.
    val lock = context.getSystemService(WifiManager::class.java)?.createMulticastLock(TAG)?.apply { acquire() }
    try {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 2500
            socket.bind(InetSocketAddress(PORT))
            while (true) {
                val packet = DatagramPacket(ByteArray(64), 64)
                socket.receive(packet)
                val text = packet.data.decodeToString(0, packet.length)
                val port = text.removePrefix("$BEACON ").toIntOrNull()
                if (text.startsWith("$BEACON ") && port != null) return "${packet.address.hostAddress}:$port"
            }
        }
    } catch (e: IOException) {
        return null
    } finally {
        lock?.release()
    }
}

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
