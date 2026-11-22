package ch.lauzhack.app.link

import ch.lauzhack.app.Choice
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.session.microphoneWords
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
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
                // Some recognizers deliver words before their first ready or level callback.
                val listening = CompletableDeferred<Unit>()
                val routed = async {
                    microphoneWords(emptyFlow(), laptop.words.onSubscription { listening.complete(Unit) }, usePhone = laptop::usesPhoneMic).first()
                }
                listening.await()
                val firstCommand = Heard("next slide", ended = true)
                phone.words(firstCommand)
                assertEquals(firstCommand, routed.await())
                phone.state.first { it.phoneLeads }
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

    @Test
    fun rawPhoneAudioReachesTheDesktopTranscriber(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val packet = ByteArray(PCM_BYTES) { if (it % 2 == 0) 0 else 64 }
        val mac = MutableStateFlow<Float?>(null)
        val received = CompletableDeferred<ByteArray>()
        val server = PhoneServer(MutableStateFlow(MoleLine()), MutableStateFlow(Link.Screen()), MutableStateFlow("Audio"),
            mac, port, "127.0.0.1", beacon = false) { pcm, words ->
            pcm.collect {
                received.complete(it)
                words(Heard("next slide", ended = true))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.start()
        try {
            withTimeout(10.seconds) {
                val phone = LaptopClient(scope, HttpClient(CIO) { install(WebSockets) }, port)
                phone.state.first { it.acceptsAudio }
                val heard = async(start = CoroutineStart.UNDISPATCHED) { server.words.first() }
                phone.audio(byteArrayOf(0)) // partial PCM frames do not reach the transcriber
                phone.audio(packet)
                assertContentEquals(packet, received.await())
                assertEquals(-6.02f, pcmDecibels(packet), 0.01f)
                assertEquals(Heard("next slide", ended = true), heard.await())
                phone.state.first { it.phoneLeads }
                phone.mic(false)
                phone.state.first { !it.phoneLeads }
            }
        } finally {
            scope.cancel()
            server.stop()
        }
    }

    @Test
    fun stalledAudioFallsBackDespiteControlTrafficAndResumesWithoutReconnect(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val mac = MutableStateFlow<Float?>(-70f)
        val server = PhoneServer(MutableStateFlow(MoleLine(MoleState.Choosing, options = listOf(Choice("Slide", "", Choice.Kind.Slides)))),
            MutableStateFlow(Link.Screen()), MutableStateFlow("Walk away"), mac, port, "127.0.0.1", beacon = false) { pcm, _ ->
            pcm.collect {} // a hot recognizer, independent of microphone selection
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val packet = ByteArray(PCM_BYTES) { if (it % 2 == 0) 0 else 32 }
        server.start()
        try {
            withTimeout(10.seconds) {
                val phone = LaptopClient(scope, HttpClient(CIO) { install(WebSockets) }, port)
                phone.state.first { it.acceptsAudio }
                repeat(3) { phone.audio(packet); delay(100) }
                phone.state.first { it.phoneLeads }
                val controls = launch { repeat(20) { phone.pick(0); delay(100) } }
                try {
                    withTimeout(1200) { phone.state.first { it.connected && !it.phoneLeads } }
                    assertEquals(false, server.usesPhoneMic())
                    repeat(3) { phone.audio(packet); delay(100) }
                    phone.state.first { it.phoneLeads }
                    phone.mic(false)
                    phone.state.first { it.connected && !it.phoneLeads }
                } finally { controls.cancel() }
            }
        } finally {
            scope.cancel()
            server.stop()
        }
    }

    private fun png(width: Int, height: Int): ByteArray =
        Surface.makeRasterN32Premul(width, height).makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}
