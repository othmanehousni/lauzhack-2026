package ch.lauzhack.app.link

import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeadTest {
    // A presenter `r` metres from the laptop, the phone 0.3 m from their mouth. The voice varies by ±6 dB, which both
    // microphones hear; each also adds ±2 dB of its own noise.
    private class Room(val laptopGain: Float, val phoneGain: Float, seed: Int = 1) {
        private val random = Random(seed)
        private fun noise(db: Float) = (random.nextFloat() * 2 - 1) * db
        var walk = Walk()
        fun speak(r: Float, samples: Int = 1): Walk {
            repeat(samples) {
                val voice = 70 + noise(6f)
                walk = walk.next(voice - 20 * log10(r) + laptopGain + noise(2f), voice - 20 * log10(0.3f) + phoneGain + noise(2f))
            }
            return walk
        }
        fun calibrated() = apply { speak(SEATED_M, CALIBRATION) }
    }

    @Test
    fun calibratesAtTheLaptopWhateverTheGains() {
        for ((laptop, phone) in listOf(-60f to -50f, -20f to -75f, 0f to 0f)) {
            val room = Room(laptop, phone)
            room.speak(SEATED_M, CALIBRATION - 1)
            assertEquals(Link.Lead(), room.walk.lead, "the laptop leads while calibrating")
            room.speak(SEATED_M)
            room.speak(1.5f, 40)
            assertTrue(abs(room.walk.lead.metres - 1.5f) < 0.3f, "gains $laptop/$phone read ${room.walk.lead.metres} m at 1.5 m")
            assertFalse(room.walk.lead.phone)
            assertTrue(abs(room.speak(4f, 40).lead.metres - 4f) < 0.8f, "gains $laptop/$phone read ${room.walk.lead.metres} m at 4 m")
        }
    }

    @Test
    fun walkingAwayHandsToThePhonePastThreeMetres() {
        val room = Room(-40f, -55f).calibrated()
        var r = SEATED_M
        while (r < 2.4f) {
            assertFalse(room.speak(r).lead.phone, "the phone leads at $r m")
            r += 0.02f
        }
        while (r < 3.6f) { room.speak(r); r += 0.02f }
        assertTrue(room.walk.lead.phone, "the laptop still leads at 3.6 m")
    }

    @Test
    fun comingBackHandsToTheLaptopUnderTwoMetres() {
        val room = Room(-40f, -55f).calibrated()
        room.speak(5f, 40)
        var r = 5f
        while (r > 2.4f) {
            assertTrue(room.speak(r).lead.phone, "the laptop leads at $r m")
            r -= 0.02f
        }
        while (r > 1.6f) { room.speak(r); r -= 0.02f }
        assertFalse(room.walk.lead.phone, "the phone still leads at 1.6 m")
    }

    @Test
    fun standingAtTheEdgeDoesNotFlap() {
        for (seed in 1..5) {
            val room = Room(-40f, -55f, seed).calibrated()
            var switches = 0
            var phone = false
            repeat(600) { // a minute of talk at 3 m
                if (room.speak(EDGE_M).lead.phone != phone) { switches++; phone = !phone }
            }
            assertTrue(switches <= 1, "seed $seed switched $switches times")
        }
    }
}
