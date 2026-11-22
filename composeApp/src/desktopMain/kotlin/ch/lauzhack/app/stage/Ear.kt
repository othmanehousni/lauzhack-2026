package ch.lauzhack.app.stage

import ch.lauzhack.app.Heard
import ch.lauzhack.app.LOGS
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    fun built(): File = synchronized(Ear::class.java) {
        val source = File(data, "hear.swift")
        val exe = File(data, "hear")
        val swift = Ear::class.java.getResource("/stage/hear.swift")!!.readText()
        val plist = File(data, "hear.plist")
        if (source.exists() && source.readText() == swift && plist.exists() && plist.readText() == PLIST && exe.lastModified() >= source.lastModified()) return@synchronized exe
        if (exec(listOf("xcode-select", "-p"), check = false).isBlank()) error("the ear needs Xcode's command line tools: xcode-select --install")
        data.mkdirs()
        source.writeText(swift)
        plist.writeText(PLIST)
        exec(listOf("swiftc", "-O", "-swift-version", "5", "-parse-as-library", source.path, "-o", exe.path,
            "-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", plist.path),
            what = "building hear.swift")
        exe
    }

    /** Listens, listening for `names` (apps, websites, slide titles) in particular, until the helper stops or this is
     * cancelled. [pcm] supplies live phone audio. With [files], it hears recorded audio at speaking pace. */
    suspend fun listen(names: List<String>, files: List<File> = emptyList(), pcm: Flow<ByteArray>? = null) = withContext(Dispatchers.IO) {
        val log = File(data, if (pcm == null) "hear.log" else "hear-phone.log")
        val args = if (pcm == null) files.map { it.path } else listOf("--phone-pcm") + names
        // Only the laptop's own audio is kept: the phone's helper would otherwise inherit HEAR_KEEP and write the same file.
        val keep = if (pcm != null) null else System.getenv("HEAR_KEEP")?.let(::File) ?: if (files.isEmpty()) recording() else null
        val p = ProcessBuilder(listOf(built().path) + args).redirectError(log)
            .apply { if (keep != null) environment()["HEAR_KEEP"] = keep.path else environment().remove("HEAR_KEEP") }.start()
        // A cancelled meeting must unblock both the pipe reader and the writer, even during model startup.
        val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { p.destroyForcibly() }
        }
        val writer = launch {
            try {
                p.outputStream.use { out ->
                    if (pcm != null) pcm.collect { out.write(it); out.flush() }
                    else out.bufferedWriter().use { w ->
                        names.map(String::trim).filter(String::isNotEmpty).forEach { w.write(it + "\n") }
                        w.write("\n")
                    }
                }
            } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            }
        }
        val code = try {
            p.inputStream.bufferedReader().use { read(it) }
            p.waitFor()
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cleanup.cancel()
            writer.cancel()
            if (keep?.isFile == true) ch.lauzhack.app.log("ear: recorded ${keep.name}, ${exec(listOf("afinfo", keep.path), check = false).substringAfter("estimated duration: ").substringBefore(" ")} s")
        }
        currentCoroutineContext().ensureActive()
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
                    val heard = Heard(e["text"]!!.jsonPrimitive.content, ended = e["kind"]!!.jsonPrimitive.content == "final",
                        quiet = e["quiet"]?.jsonPrimitive?.booleanOrNull == true)
                    saidFlow.emit(Said(heard, ago, TimeSource.Monotonic.markNow() - ago))
                }
            }
        }
    }
}

/** A new file in [dir] for what the microphone hears, named by the time, once the oldest beyond [budget] bytes are deleted. */
internal fun recording(dir: File = File(LOGS, "audio"), budget: Long = 500_000_000): File {
    dir.mkdirs()
    var total = 0L
    for (f in dir.listFiles().orEmpty().sortedByDescending { it.name }) { total += f.length(); if (total > budget) f.delete() }
    return File(dir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".caf")
}

internal val WORD = 400.milliseconds  // 150 words a minute
internal val PAUSE = 700.milliseconds // the silence after which the recognizer finishes an utterance
internal val QUIET = 500.milliseconds // the silence after which the ear says the voice stopped (hear.swift's QUIET)

/** Typed lines as what the presenter said, each one a whole utterance: for tests and dry runs. [paced], a line comes as
 * speech does: word by word at a speaking rate, quiet after a moment, then finished after a pause. */
fun typed(input: InputStream = System.`in`, paced: Boolean = false): Flow<Said> = flow {
    for (line in input.bufferedReader().lineSequence()) {
        if (line.isBlank()) continue
        val words = line.trim().split(Regex("\\s+"))
        if (paced) {
            for (k in 1..words.size) {
                delay(WORD)
                emit(Said(Heard(words.take(k).joinToString(" ")), Duration.ZERO, TimeSource.Monotonic.markNow()))
            }
            delay(QUIET)
            emit(Said(Heard(words.joinToString(" "), quiet = true), QUIET, TimeSource.Monotonic.markNow() - QUIET))
            delay(PAUSE - QUIET)
        }
        emit(Said(Heard(words.joinToString(" "), ended = true), Duration.ZERO, TimeSource.Monotonic.markNow()))
    }
}.flowOn(Dispatchers.IO)
