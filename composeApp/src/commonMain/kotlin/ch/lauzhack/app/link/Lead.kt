package ch.lauzhack.app.link

import kotlin.math.log10
import kotlin.math.pow

internal const val SEATED_M = 0.5f
internal const val EDGE_M = 1.5f
internal const val BACK_M = 0.9f
internal const val CALIBRATION = 10 // one second of speech at 10 Hz
private const val TAKE_PHONE = 3 // 300 ms of evidence, including before calibration
private const val TAKE_LAPTOP = 10 // a slower return avoids bouncing between microphones

/** A slowly rising lower envelope learns room noise without treating a syllable as the noise floor. */
internal data class Signal(
    val noise: Float = -70f,
    val recent: List<Float> = emptyList(),
    val snr: Float = 0f,
) {
    fun next(db: Float): Signal {
        val samples = (recent + db).takeLast(50)
        val floor = minOf(noise + 0.15f, samples.sorted()[samples.size / 5])
        return Signal(floor, samples, db - floor)
    }
}

/**
 * Both recognizers stay live. Choose the phone after three useful frames when the Mac is weak or the phone/Mac
 * ratio has grown since speaking at the desk. Absolute level and signal/noise checks still apply after calibration,
 * so pairing away from the desk, a muted Mac or a changed phone position cannot trap the lead on a poor microphone.
 * Silence holds the choice; a return needs a full second of good Mac audio and a smaller ratio. Metres are only an
 * estimate: automatic gain, room reflections and carrying the phone differently all change the ratio.
 */
internal data class Walk(
    val lead: Link.Lead = Link.Lead(),
    val seen: List<Float> = emptyList(),
    val gain: Float? = null,
    val weakSamples: Int = 0,
    val db: Float = 0f,
    val laptop: Signal = Signal(),
    val phone: Signal = Signal(),
    val phoneAudible: Boolean = true, // legacy recognizers may report readiness before levels
)

/** Call on every 100 ms frame, including silence, so room noise and mute detection keep updating. */
internal fun Walk.next(laptopDb: Float?, phoneDb: Float?, speech: Boolean? = null): Walk {
    if (laptopDb?.isFinite() == false || phoneDb?.isFinite() == false) return this
    val mac = laptopDb?.let(laptop::next) ?: laptop
    val mobile = phoneDb?.let(phone::next) ?: phone
    val phoneSpeech = speech ?: (phoneDb == null || phoneDb > -62f && mobile.snr >= 8f)
    val macSpeech = laptopDb != null && laptopDb > -60f && mac.snr >= 8f
    val ratio = if (phoneDb != null && laptopDb != null) phoneDb - laptopDb else null
    val samples = if (gain == null && phoneSpeech && macSpeech && laptopDb > -44f && ratio != null) seen + ratio else seen
    val calibrated = gain ?: samples.takeIf { it.size >= CALIBRATION }?.sorted()?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
    val delta = if (ratio != null && calibrated != null) ratio - calibrated else null
    val smoothed = delta?.let { db + 0.25f * (it - db) } ?: db
    val metres = if (calibrated != null) (SEATED_M * 10f.pow(smoothed / 20)).coerceIn(0.1f, 50f) else lead.metres

    val weakMac = laptopDb == null || laptopDb < -50f || !macSpeech
    val away = delta != null && delta > 20 * log10(EDGE_M / SEATED_M)
    val near = delta == null || smoothed < 20 * log10(BACK_M / SEATED_M)
    val mutedPhone = phoneDb != null && phoneDb <= -85f
    val wantPhone = phoneSpeech && (weakMac || away)
    val wantMac = macSpeech && (mutedPhone || !phoneSpeech || laptopDb > -44f && near)
    val evidence = when {
        !lead.phone && wantPhone -> (weakSamples.coerceAtLeast(0) + 1).coerceAtMost(TAKE_PHONE)
        lead.phone && wantMac -> (weakSamples.coerceAtMost(0) - 1).coerceAtLeast(-TAKE_LAPTOP)
        else -> 0
    }
    val usePhone = when {
        mutedPhone && macSpeech -> false
        evidence == TAKE_PHONE -> true
        evidence == -TAKE_LAPTOP -> false
        else -> lead.phone
    }
    return copy(
        lead = Link.Lead(usePhone, metres),
        seen = if (calibrated == null) samples else emptyList(),
        gain = calibrated,
        weakSamples = if (usePhone != lead.phone) 0 else evidence,
        db = smoothed,
        laptop = mac,
        phone = mobile,
        phoneAudible = !mutedPhone,
    )
}
