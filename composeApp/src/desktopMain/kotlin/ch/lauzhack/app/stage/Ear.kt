package ch.lauzhack.app.stage

import ch.lauzhack.app.Heard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the presenter said, `ago` how long the words had been unchanged when it arrived, and `stopped` when that was:
 * the log's "text Nms" is `ago`, its "N ms after you stopped" is `stopped.elapsedNow()` once the screen changed. */
data class Said(val heard: Heard, val ago: Duration, val stopped: TimeSource.Monotonic.ValueTimeMark)

private const val PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>ch.lauzhack.moliere.hear</string>
<key>NSMicrophoneUsageDescription</key><string>Molière listens to what you say to put the right thing on the shared screen.</string>
<key>NSSpeechRecognitionUsageDescription</key><string>Molière turns what you say into text, on this Mac.</string>
</dict></plist>
"""

/** Speech to text with Apple's on-device SpeechTranscriber (hear.swift, macOS 26), outside this process. */
class Ear(private val data: File = DATA) {
    private val saidFlow = MutableSharedFlow<Said>(extraBufferCapacity = 64)
    private val levelFlow = MutableSharedFlow<Float>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val notesFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val said: SharedFlow<Said> = saidFlow
    val heard: Flow<Heard> = said.map { it.heard }

    /** The microphone's RMS loudness, ten times a second. */
    val level: SharedFlow<Float> = levelFlow

    /** What the helper says about itself, such as "getting Apple's English speech model ready". */
    val notes: SharedFlow<String> = notesFlow

    /** hear.swift as a program in the app's data, built again when the source changes. Its Info.plist names why it
     * needs the microphone. */
    fun built(): File {
        val source = File(data, "hear.swift")
        val exe = File(data, "hear")
        val swift = Ear::class.java.getResource("/stage/hear.swift")!!.readText()
        val plist = File(data, "hear.plist")
        if (source.exists() && source.readText() == swift && plist.exists() && plist.readText() == PLIST && exe.lastModified() >= source.lastModified()) return exe
        if (exec(listOf("xcode-select", "-p"), check = false).isBlank()) error("the ear needs Xcode's command line tools: xcode-select --install")
        data.mkdirs()
        source.writeText(swift)
        plist.writeText(PLIST)
        exec(listOf("swiftc", "-O", "-swift-version", "5", "-parse-as-library", source.path, "-o", exe.path,
            "-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", plist.path),
            what = "building hear.swift")
        return exe
    }

    /** Listens, listening for `names` (apps, websites, slide titles) in particular, until the helper stops or this is
     * cancelled. With `files`, it hears those instead of the microphone, at the pace they would be spoken. */
    suspend fun listen(names: List<String>, files: List<File> = emptyList()) = withContext(Dispatchers.IO) {
        val log = File(data, "hear.log")
        val p = ProcessBuilder(listOf(built().path) + files.map { it.path }).redirectError(log).start()
        p.outputStream.bufferedWriter().use { w -> names.map(String::trim).filter(String::isNotEmpty).forEach { w.write(it + "\n") }; w.write("\n") }
        val code = try {
            read(p.inputStream.bufferedReader())
            p.waitFor()
        } finally {
            p.destroy()
        }
        if (code != 0) error("hear.swift stopped with code $code: ${log.readText().trim()}")
    }

    internal suspend fun read(lines: BufferedReader) {
        for (line in lines.lineSequence()) {
            currentCoroutineContext().ensureActive()  // levels arrive ten times a second, so a cancelled listen stops soon
            val e = Json.parseToJsonElement(line).jsonObject
            when {
                "level" in e -> levelFlow.tryEmit(e["level"]!!.jsonPrimitive.double.toFloat())
                "note" in e -> notesFlow.emit(e["note"]!!.jsonPrimitive.content)
                else -> {
                    val ago = e["ago"]!!.jsonPrimitive.double.seconds
                    val heard = Heard(e["text"]!!.jsonPrimitive.content, ended = e["kind"]!!.jsonPrimitive.content == "final")
                    saidFlow.emit(Said(heard, ago, TimeSource.Monotonic.markNow() - ago))
                }
            }
        }
    }
}

internal val WORD = 400.milliseconds  // 150 words a minute
internal val PAUSE = 700.milliseconds // the silence after which the recognizer finishes an utterance

/** Typed lines as what the presenter said, each one a whole utterance: for tests and dry runs. [paced], a line comes as
 * speech does: word by word at a speaking rate, then finished after a pause. */
fun typed(input: InputStream = System.`in`, paced: Boolean = false): Flow<Said> = flow {
    for (line in input.bufferedReader().lineSequence()) {
        if (line.isBlank()) continue
        val words = line.trim().split(Regex("\\s+"))
        if (paced) {
            for (k in 1..words.size) {
                delay(WORD)
                emit(Said(Heard(words.take(k).joinToString(" ")), Duration.ZERO, TimeSource.Monotonic.markNow()))
            }
            delay(PAUSE)
        }
        emit(Said(Heard(words.joinToString(" "), ended = true), Duration.ZERO, TimeSource.Monotonic.markNow()))
    }
}.flowOn(Dispatchers.IO)
