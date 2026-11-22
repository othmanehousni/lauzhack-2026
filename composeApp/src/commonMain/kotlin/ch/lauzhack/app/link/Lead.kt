package ch.lauzhack.app.link

import kotlin.math.pow

internal const val SEATED_M = 0.5f // the presenter's distance to the laptop while calibrating
internal const val EDGE_M = 3f // the phone leads past this
internal const val BACK_M = 2f // and hands back under this: 3.5 dB of hysteresis
internal const val CALIBRATION = 30 // samples of speech, 3 s at the phone's 10 Hz
private const val SMOOTHING = 0.15f // about 0.6 s at 10 Hz

/**
 * Which microphone leads, stepped with [next] on each pair of levels taken while the presenter speaks.
 *
 * The phone travels with the presenter at a roughly constant distance from the mouth, so its level follows how loud
 * they speak, while the laptop's also falls 6 dB per doubling of distance. The phone's dB minus the laptop's is then
 * 20·log10(distance) plus a constant for the two gains and the phone's distance to the mouth; the median over the first
 * [CALIBRATION] samples, taken while the presenter is still [SEATED_M] from the laptop, measures that constant.
 * It reads wrong when the phone moves on the body after calibration (in a pocket it loses 10-20 dB and the presenter
 * reads several times closer, so the laptop keeps the lead), when echo dominates the laptop's microphone (past 1-2 m
 * in a hard room its level stops falling, so far reads short and the edge comes late), when automatic gain control or
 * Android's clamped rmsdB flattens either level, when the presenter turns their back to the laptop (reads farther),
 * when someone else talks (the phone hears speech, not whose), and when pairing happens away from the laptop.
 */
internal data class Walk(
    val lead: Link.Lead = Link.Lead(),
    val seen: List<Float> = emptyList(),
    val gain: Float? = null,
    val weakSamples: Int = 0,
    val db: Float = 0f, // smoothed dB above the calibrated ratio
)

// Mac levels are dBFS. While the phone hears speech, eight consecutive weak Mac samples
// are enough to use the phone even when pairing away from the desk or Android omits RMS.
internal fun Walk.next(laptopDb: Float, phoneDb: Float?): Walk {
    if (!laptopDb.isFinite() || phoneDb?.isFinite() == false) return this
    if (gain == null || phoneDb == null) {
        val weak = when {
            laptopDb < -52f -> (weakSamples.coerceAtLeast(0) + 1).coerceAtMost(8)
            laptopDb > -44f -> (weakSamples.coerceAtMost(0) - 1).coerceAtLeast(-8)
            else -> 0
        }
        val phone = when (weak) {
            8 -> true
            -8 -> false
            else -> lead.phone
        }
        val next = copy(lead = lead.copy(phone = phone), weakSamples = weak)
        if (gain != null || phoneDb == null || laptopDb <= -44f) return next
        val samples = seen + (phoneDb - laptopDb)
        return if (samples.size < CALIBRATION) next.copy(seen = samples)
        else next.copy(seen = emptyList(), gain = samples.sorted()[samples.size / 2])
    }
    val db = db + SMOOTHING * (phoneDb - laptopDb - gain - db)
    val metres = SEATED_M * 10f.pow(db / 20)
    return copy(db = db, weakSamples = 0, lead = Link.Lead(phone = metres > if (lead.phone) BACK_M else EDGE_M, metres = metres))
}
