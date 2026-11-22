package ch.lauzhack.app.link

import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeadTest {
    // Voice varies by ±6 dB on both microphones, with independent ±2 dB of measurement noise.
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
    fun calibratesInASecondWhateverTheDeviceGains() {
        for ((laptop, phone) in listOf(-60f to -50f, -20f to -75f, 0f to 0f)) {
            val room = Room(laptop, phone)
            room.speak(SEATED_M, CALIBRATION - 1)
            assertEquals(Link.Lead(), room.walk.lead)
            room.speak(SEATED_M)
            room.speak(1f, 30)
            assertTrue(abs(room.walk.lead.metres - 1f) < 0.3f)
            assertFalse(room.walk.lead.phone)
            assertTrue(abs(room.speak(4f, 30).lead.metres - 4f) < 1.2f, "gains $laptop/$phone estimate ${room.walk.lead.metres} m")
            assertTrue(room.walk.lead.phone)
        }
    }

    @Test
    fun walkingAwaySwitchesEarlyAndReturningRequiresSustainedEvidence() {
        val room = Room(-40f, -55f).calibrated()
        var r = SEATED_M
        while (r < 1.1f) {
            assertFalse(room.speak(r).lead.phone, "phone took over at $r m")
            r += 0.02f
        }
        while (r < 2f) { room.speak(r); r += 0.02f }
        assertTrue(room.walk.lead.phone, "Mac still leading at 2 m")
        room.speak(SEATED_M, 5)
        assertTrue(room.walk.lead.phone, "a brief return must not bounce the microphones")
        room.speak(SEATED_M, 20)
        assertFalse(room.walk.lead.phone)
    }

    @Test
    fun pairingAwayFromTheDeskTakesOnlyThreeFrames() {
        var walk = Walk()
        repeat(2) { walk = walk.next(-75f, -28f) }
        assertFalse(walk.lead.phone)
        walk = walk.next(-75f, -28f)
        assertTrue(walk.lead.phone)
        assertEquals(null, walk.gain)
        repeat(CALIBRATION + 2) { walk = walk.next(-30f, -28f) }
        assertFalse(walk.lead.phone)
        assertEquals(2f, walk.gain)
    }

    @Test
    fun weakMacFallbackStillWorksAfterCalibrationAndPhonePlacementChanges() {
        var walk = Walk()
        repeat(CALIBRATION) { walk = walk.next(-30f, -25f) }
        // The phone went into a pocket: the ratio no longer indicates distance, but the Mac cannot hear well.
        repeat(3) { walk = walk.next(-65f, -56f) }
        assertTrue(walk.lead.phone)
        assertTrue(walk.lead.metres < EDGE_M, "absolute fallback must work independently of the distance estimate")
    }

    @Test
    fun aRecognizerWithoutRmsCanHandOffInBothDirections() {
        var walk = Walk()
        repeat(2) { walk = walk.next(-70f, null) }
        assertFalse(walk.lead.phone)
        walk = walk.next(-70f, null)
        assertTrue(walk.lead.phone)
        repeat(20) { walk = walk.next(-48f, null) }
        assertTrue(walk.lead.phone)
        repeat(9) { walk = walk.next(-30f, null) }
        assertTrue(walk.lead.phone)
        walk = walk.next(-30f, null)
        assertFalse(walk.lead.phone)
    }

    @Test
    fun pausesAndOneFrameSpikesDoNotSwitchMicrophones() {
        var walk = Walk()
        repeat(CALIBRATION) { walk = walk.next(-30f, -25f) }
        walk = walk.next(-70f, -25f)
        assertFalse(walk.lead.phone)
        repeat(20) { walk = walk.next(-70f, -72f) }
        assertFalse(walk.lead.phone, "room noise is not phone speech")
        repeat(3) { walk = walk.next(-65f, -25f) }
        assertTrue(walk.lead.phone)
        repeat(20) { walk = walk.next(-70f, -72f) }
        assertTrue(walk.lead.phone, "a shared pause holds the previous microphone")
        repeat(10) { walk = walk.next(-100f, -100f) }
        assertTrue(walk.lead.phone, "digital silence on both microphones also holds the choice")
    }

    @Test
    fun aLoudButNoisyMacDoesNotBlockThePhone() {
        var walk = Walk()
        repeat(100) { walk = walk.next(-48f, -70f, speech = false) }
        repeat(3) { walk = walk.next(-47f, -25f) }
        assertTrue(walk.lead.phone, "Mac audio barely above its noise floor is not useful speech")
    }

    @Test
    fun digitalMuteAndMissingMacHaveAFallback() {
        var walk = Walk()
        repeat(3) { walk = walk.next(null, -30f) }
        assertTrue(walk.lead.phone)
        walk = walk.next(-30f, -100f)
        assertFalse(walk.lead.phone)
        assertFalse(walk.phoneAudible)
        assertEquals(walk, walk.next(Float.NaN, -30f))
        assertEquals(walk, walk.next(-30f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun standingAtTheEdgeDoesNotFlap() {
        for (seed in 1..5) {
            val room = Room(-40f, -55f, seed).calibrated()
            var switches = 0
            var phone = false
            repeat(600) {
                if (room.speak(EDGE_M).lead.phone != phone) { switches++; phone = !phone }
            }
            assertTrue(switches <= 1, "seed $seed switched $switches times")
        }
    }
}
