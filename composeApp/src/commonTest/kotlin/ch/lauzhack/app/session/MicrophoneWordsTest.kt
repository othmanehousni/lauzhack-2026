@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ch.lauzhack.app.session

import ch.lauzhack.app.Heard
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals

class MicrophoneWordsTest {
    @Test
    fun switchingMidSentenceUsesTheWarmRecognizersFullTranscript() = runTest {
        val laptop = MutableSharedFlow<Heard>()
        val phone = MutableSharedFlow<Heard>()
        var phoneLeads = false
        val heard = mutableListOf<Heard>()
        backgroundScope.launch { microphoneWords(laptop, phone, testTimeSource) { phoneLeads }.collect { heard += it } }
        runCurrent()
        laptop.emit(Heard("show the"))
        phone.emit(Heard("show")) // the alternate recognizer stays subscribed while the laptop leads
        runCurrent()
        phoneLeads = true
        laptop.emit(Heard("show the wrong slide", ended = true))
        phone.emit(Heard("show the roadmap", ended = true))
        runCurrent()
        assertEquals(listOf(Heard("show the"), Heard("show the roadmap", ended = true)), heard)
    }

    @Test
    fun aLateCopyFromTheOtherRecognizerDoesNotRepeatACommand() = runTest {
        val laptop = MutableSharedFlow<Heard>()
        val phone = MutableSharedFlow<Heard>()
        var phoneLeads = false
        val heard = mutableListOf<Heard>()
        backgroundScope.launch { microphoneWords(laptop, phone, testTimeSource) { phoneLeads }.collect { heard += it } }
        runCurrent()
        laptop.emit(Heard("Next slide!", ended = true))
        runCurrent()
        phoneLeads = true
        phone.emit(Heard("next"))
        phone.emit(Heard("next slide", ended = true))
        phone.emit(Heard("next slide please", ended = true))
        phone.emit(Heard("open roadmap", ended = true))
        runCurrent()
        assertEquals(listOf("Next slide!", "open roadmap"), heard.map { it.text })
        phone.emit(Heard("open roadmap", ended = true)) // repeating on the same mic is intentional
        runCurrent()
        assertEquals(3, heard.size)
        advanceTimeBy(2001)
        phoneLeads = false
        laptop.emit(Heard("open roadmap", ended = true))
        runCurrent()
        assertEquals(4, heard.size, "the guard expires, so later commands work on either mic")
    }
}
