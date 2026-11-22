package ch.lauzhack.app.link

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import ch.lauzhack.app.Heard

internal const val TAG = "Mole"

/**
 * The on-device recognizer, started again after every utterance and error so it keeps listening; words said in the
 * moment between two starts are lost. Its loudness comes from onRmsChanged, so nothing else opens the microphone.
 * Main thread only, as SpeechRecognizer requires.
 */
internal class Ears(
    private val context: Context,
    private val heard: (Heard) -> Unit,
    private val level: (db: Float, speech: Boolean) -> Unit,
) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var speaking = false
    private var downloadAsked = false
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US") // the mole reads English, whatever the phone's language
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    fun start() {
        if (recognizer != null) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            Log.w(TAG, "No on-device speech recognizer on this phone; the laptop's microphone will have to do")
            return
        }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            it.startListening(intent)
        }
        Log.i(TAG, "Recognizer started")
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        speaking = false
    }

    override fun onBeginningOfSpeech() { speaking = true }
    override fun onEndOfSpeech() { speaking = false }
    override fun onRmsChanged(rmsdB: Float) = level(rmsdB, speaking)

    override fun onPartialResults(partialResults: Bundle) {
        val text = best(partialResults) ?: return
        speaking = true
        heard(Heard(text))
    }

    override fun onResults(results: Bundle) {
        speaking = false
        best(results)?.let { heard(Heard(it, ended = true)) }
        recognizer?.startListening(intent)
    }

    override fun onError(error: Int) {
        speaking = false
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            recognizer?.startListening(intent) // silence
            return
        }
        Log.w(TAG, "Recognizer error $error, starting again")
        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && !downloadAsked && Build.VERSION.SDK_INT >= 34) {
            downloadAsked = true
            recognizer?.triggerModelDownload(intent)
        }
        stop()
        main.postDelayed(::start, 1000)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun best(results: Bundle) = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
