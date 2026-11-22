package ch.lauzhack.app.link

import ch.lauzhack.app.Choice
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

class LoopbackTest {
    @Test
    fun laptopAndPhoneHearEachOtherAcrossAReconnect() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val line = MutableStateFlow(MoleLine())
        val screen = MutableStateFlow(Link.Screen())
        val meeting = MutableStateFlow("Home insurance review")
        val laptopDb = MutableStateFlow<Float?>(-30f)
        // Loopback only, no beacon: nothing here reaches the network or asks the firewall.
        fun laptop() = PhoneServer(line, screen, meeting, laptopDb, port = port, host = "127.0.0.1", beacon = false).apply { start() }
        var laptop = laptop()
        val phoneScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val phone = LaptopClient(phoneScope, HttpClient(CIO) { install(WebSockets) { pingIntervalMillis = 1000 } }, port = port)
        try {
            withTimeout(20.seconds) {
                phone.state.first { it.connected && it.meeting == "Home insurance review" }

                val offers = listOf(Choice("Home offer — March.pdf", "Sent 12 Mar", Choice.Kind.Pdf), Choice("Home offer — revised.pdf", "Sent 2 Apr", Choice.Kind.Pdf))
                line.value = MoleLine(MoleState.Choosing, heard = "pull up the offer we sent them", options = offers)
                screen.value = Link.Screen("Market size", png(16, 9))
                val seen = phone.state.first { it.line.state == MoleState.Choosing && it.onScreenName == "Market size" && it.thumbnail != null }
                assertEquals(offers, seen.line.options)
                assertEquals(16 to 9, seen.thumbnail!!.width to seen.thumbnail.height)

                val pick = async(start = CoroutineStart.UNDISPATCHED) { laptop.picks.first() }
                val words = async(start = CoroutineStart.UNDISPATCHED) { laptop.words.first() }
                phone.words(Heard("the second one", ended = true))
                phone.pick(5) // not on offer: dropped
                phone.pick(1)
                assertEquals(1, pick.await())
                assertEquals(Heard("the second one", ended = true), words.await())

                // The phone's Stop on an announce reaches the laptop.
                val choice = line.value
                line.value = MoleLine(MoleState.Retrieving, "Showing slide 7", options = offers.take(1))
                phone.state.first { it.line.state == MoleState.Retrieving }
                val stop = async(start = CoroutineStart.UNDISPATCHED) { laptop.picks.first() }
                phone.pick(-1)
                assertEquals(-1, stop.await())
                line.value = choice

                // A live connection can outlast either microphone. Keep the controls connected during handoff.
                laptopDb.value = null
                phone.mic(true)
                phone.state.first { it.phoneLeads }
                phone.mic(false)
                phone.state.first { it.connected && !it.phoneLeads }
                laptopDb.value = -30f
                phone.mic(true)

                // Calibrate at the laptop, then sound 20 dB closer on the phone than on the laptop: ten times as far.
                val calibrated = async(start = CoroutineStart.UNDISPATCHED) { laptop.levels.take(CALIBRATION).count() }
                repeat(CALIBRATION) { phone.level(-10f, speech = true) }
                calibrated.await()
                laptopDb.value = -50f
                repeat(30) { phone.level(-10f, speech = true) }
                phone.state.first { it.phoneLeads && it.metres > 4f }

                laptop.stop()
                val gone = phone.state.first { !it.connected && it.trouble != null }
                assertEquals(PhoneState(trouble = gone.trouble), gone) // no line, screen, meeting or lead left over as if live
                laptop = laptop()
                assertEquals(MoleState.Choosing, phone.state.first { it.connected && it.line.state == MoleState.Choosing }.line.state)
                val again = async(start = CoroutineStart.UNDISPATCHED) { laptop.picks.first() }
                phone.pick(0)
                assertEquals(0, again.await())
            }
        } finally {
            phoneScope.cancel()
            laptop.stop()
        }
    }

    private fun png(width: Int, height: Int): ByteArray =
        Surface.makeRasterN32Premul(width, height).makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}
