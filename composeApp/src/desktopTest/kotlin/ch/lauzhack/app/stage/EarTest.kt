package ch.lauzhack.app.stage

import ch.lauzhack.app.Heard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun keepsTheNewestRecordingsUpToTheBudget() {
        val dir = Files.createTempDirectory("audio").toFile()
        listOf("2026-11-20_10-00-00", "2026-11-21_10-00-00", "2026-11-22_10-00-00").forEach { File(dir, "$it.caf").writeBytes(ByteArray(40)) }
        val next = recording(dir, budget = 100)
        assertEquals(listOf("2026-11-21_10-00-00.caf", "2026-11-22_10-00-00.caf"), dir.list()!!.sorted())
        assertEquals(dir, next.parentFile)
    }
}
