package ch.lauzhack.app.stage

import ch.lauzhack.app.Heard
import ch.lauzhack.app.session.inputDecibels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class EarTest {
    /** hear-sample.jsonl is the output of hear.swift's own emit(), JSONSerialization with its escaped slashes, long
     * decimals and keys in any order. */
    @Test
    fun readsTheHelpersLines() = runBlocking {
        val ear = Ear(Files.createTempDirectory("ear").toFile())
        val said = async(start = CoroutineStart.UNDISPATCHED) { ear.said.take(4).toList() }
        val levels = async(start = CoroutineStart.UNDISPATCHED) { ear.level.take(5).toList() }
        val notes = async(start = CoroutineStart.UNDISPATCHED) { ear.notes.take(2).toList() }
        ear.read(EarTest::class.java.getResource("/hear-sample.jsonl")!!.openStream().bufferedReader())
        assertEquals(
            listOf(Heard("can you"), Heard("can you open github.com/lauzhack"), Heard("can you open github.com/lauzhack", quiet = true),
                Heard("Can you open github.com/lauzhack \"now\", café?", ended = true)),
            said.await().map { it.heard },
        )
        assertEquals(listOf(0, 300, 500, 1400), said.await().map { it.ago.inWholeMilliseconds.toInt() })
        assertEquals(true, said.await().last().stopped.elapsedNow() >= 1400.milliseconds)
        assertEquals(listOf(0f, 0.0031622776f, 0.08f, 0.21f, 1e-5f), levels.await())
        assertEquals(listOf("getting Apple's English speech model ready", "listening... (ctrl-c to stop)"), notes.await())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun aMutedMacStillDeliveringSamplesExpiresAndRecovers() = runTest {
        val rms = MutableSharedFlow<Float>()
        val db = rms.inputDecibels().stateIn(backgroundScope, SharingStarted.Eagerly, null)
        runCurrent()
        rms.emit(0.1f)
        runCurrent()
        assertEquals(-20f, db.value)
        repeat(10) { rms.emit(0f); advanceTimeBy(100) }
        assertEquals(-20f, db.value)
        repeat(6) { rms.emit(1e-6f); advanceTimeBy(100) }
        assertNull(db.value)
        rms.emit(0.01f)
        runCurrent()
        assertEquals(-40f, db.value)
    }

    @Test
    fun typedLinesAreWholeUtterances() = runBlocking {
        val said = typed("open the roadmap\n\n   next slide  \n".byteInputStream()).toList()
        assertEquals(listOf(Heard("open the roadmap", ended = true), Heard("next slide", ended = true)), said.map { it.heard })
    }

    @Test
    fun quotesLikeShlex() {
        assertEquals("--remote-debugging-port=9222", quote("--remote-debugging-port=9222"))
        assertEquals("'Google Chrome'", quote("Google Chrome"))
        assertEquals("''", quote(""))
        assertEquals("'it'\"'\"'s'", quote("it's"))
        assertEquals("open -a 'Google Chrome' '\$HOME'", quote(listOf("open", "-a", "Google Chrome", "\$HOME")))
    }
}
