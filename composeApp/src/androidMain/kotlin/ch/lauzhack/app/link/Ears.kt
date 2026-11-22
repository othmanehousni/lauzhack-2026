package ch.lauzhack.app.link

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val TAG = "Mole"

/** Called only while the companion is visible, connected and has microphone permission. */
@SuppressLint("MissingPermission")
internal suspend fun streamMicrophone(client: LaptopClient, issue: (String?) -> Unit) = withContext(Dispatchers.IO) {
    val format = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            val minimum = AudioRecord.getMinBufferSize(PCM_RATE, format, encoding)
            check(minimum > 0) { "Unsupported microphone format ($minimum)" }
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, PCM_RATE, format, encoding,
                maxOf(minimum, PCM_BYTES * 2))
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                issue(null)
                var packet = ByteArray(PCM_BYTES)
                var offset = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = recorder.read(packet, offset, packet.size - offset, AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "Microphone read failed ($n)" }
                    offset += n
                    if (offset == packet.size) {
                        client.audio(packet)
                        packet = ByteArray(PCM_BYTES)
                        offset = 0
                    }
                    if (n == 0) delay(10)
                }
            } finally {
                recorder.release()
                client.mic(false)
            }
        } catch (e: SecurityException) {
            issue("Allow microphone access for Molière to use the phone mic.")
            return@withContext
        } catch (e: RuntimeException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Could not capture phone audio", e)
            issue("Could not open the phone microphone. Retrying…")
            delay(1500)
        }
    }
}
