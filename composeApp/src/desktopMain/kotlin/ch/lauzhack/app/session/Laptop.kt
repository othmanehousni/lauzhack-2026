package ch.lauzhack.app.session

import ai.koog.embeddings.base.Embedder
import ch.lauzhack.app.Agent
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.Target
import ch.lauzhack.app.agent.Catalog
import ch.lauzhack.app.agent.Embeddings
import ch.lauzhack.app.agent.Intents
import ch.lauzhack.app.agent.MoleAgent
import ch.lauzhack.app.agent.SlideText
import ch.lauzhack.app.decider.ModelAgent
import ch.lauzhack.app.link.Link
import ch.lauzhack.app.link.PhoneServer
import ch.lauzhack.app.log
import ch.lauzhack.app.stage.AgentDesktop
import ch.lauzhack.app.stage.DATA
import ch.lauzhack.app.stage.DeckPage
import ch.lauzhack.app.stage.Ear
import ch.lauzhack.app.stage.HOME
import ch.lauzhack.app.stage.decks
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What decides during a call: the model, or the rules. */
interface Brain {
    /** Loads what is slow to load, before a call or while the shared screen comes up; a call waits for it. */
    suspend fun warm(library: File, desk: AgentDesktop)

    /** The agent for a call on [desk], which is up, and the names the ear is to expect, most useful first. [scope] is
     * the call's, for work that runs as long as it does. */
    suspend fun meet(desk: AgentDesktop, scope: CoroutineScope): Pair<Agent, List<String>>
}

private const val NAMES = 100 // Apple's limit for SpeechAnalyzer's contextual strings, which recognize short phrases best

/**
 * The laptop's side of the product. Asleep outside a call: no ear, no phone server, no shared screen. When [meeting]
 * gives a call's title, it brings the shared screen up while [brain] warms, has it read the decks in [library], files,
 * apps and, while [history] is on, the pages the presenter had open in a browser, and runs a [Session] of its agent on
 * the laptop's ear (hearing [audio] files instead of the microphone when given, or [typed] lines) or the phone's,
 * whichever leads, with the [window] the presenter chose before an announce is shown, until the call ends; then all of it stops. A
 * library without a PDF keeps it asleep until one is there. The floater renders [line] and sends taps to [onPick].
 * [downloads] (a model file, and the fraction of it on disk) is collected to its end before [brain] warms. Every line,
 * screen change and decision is [log]ged.
 */
class Laptop(
    scope: CoroutineScope,
    private val brain: Brain,
    private val library: StateFlow<File>,
    private val meeting: StateFlow<String?>,
    private val downloads: Flow<Pair<String, Float>>? = null,
    private val history: StateFlow<Boolean> = MutableStateFlow(true),
    private val sandbox: String? = "base",
    private val typed: Flow<Heard>? = null,
    private val audio: List<File> = emptyList(),
    private val window: StateFlow<Duration> = MutableStateFlow(ANNOUNCE),
    private val shots: File? = null, // where a checked run keeps what the audience saw after each change, as PNGs
) {
    private val work = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1)) // the session's state is one thread's
    private val mole = Mole(work)
    private var desk = AgentDesktop(library.value, sandbox, history = history.value)
    private var deskFor = library.value to history.value
    private val session = MutableStateFlow<Session?>(null)

    val line: StateFlow<MoleLine> = mole.line

    fun onPick(index: Int) {
        session.value?.pick(index)
    }

    init {
        work.launch {
            line.collect { l ->
                log("mole    ${l.state.name.lowercase()}  ${l.title.orEmpty()}  ${l.heard}" +
                    (if (l.options.isNotEmpty()) "  ${l.options.map { "${it.name} (${it.meta})" }}" else "") + (if (l.card.isNotEmpty()) "  [${l.card}]" else ""))
            }
        }
        work.launch {
            if (downloads != null) {
                try {
                    downloads.collect { (_, done) -> mole.on(Event.Setup("Downloading models, ${(done * 100).roundToInt()}%")) }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    log("download failed: $e")
                    mole.on(Event.Failed("Couldn't download the models.", first(e)))
                }
                mole.on(Event.Sleep)
            }
            val warm = async(Dispatchers.Default) { // now, so that a call starts without waiting for the models
                val t = TimeSource.Monotonic.markNow()
                try {
                    brain.warm(library.value, desk)
                    log("models ready in ${t.elapsedNow().inWholeMilliseconds} ms")
                    true
                } catch (e: Exception) { // a call tries again, and says so if it fails
                    ensureActive()
                    log("models failed: $e")
                    false
                }
            }
            if (!hasDecks(library.value)) empty(library.value)
            val title = meeting.map { it.orEmpty() }.stateIn(this, SharingStarted.Eagerly, "")
            combine(meeting.map { it != null }, library, history) { on, lib, h -> (lib to h).takeIf { on } }.distinctUntilChanged()
                .collectLatest { if (it != null) meet(title, it, warm) else mole.on(Event.Sleep) }
        }
    }

    private suspend fun empty(lib: File) {
        mole.on(Event.Setup("Put your PDF slides in ${lib.path.removePrefix("$HOME/")}."))
        delay(6.seconds)
        mole.on(Event.Sleep)
    }

    private suspend fun meet(title: StateFlow<String>, wants: Pair<File, Boolean>, warm: Deferred<Boolean>): Nothing = coroutineScope {
        val (lib, h) = wants
        log("meeting \"${title.value}\", decks in $lib, browser history ${if (h) "on" else "off"}")
        if (!hasDecks(lib)) {
            empty(lib)
            while (!hasDecks(lib)) delay(2.seconds)
        }
        if (wants != deskFor) {
            desk = AgentDesktop(lib, sandbox, history = h)
            deskFor = wants
        }
        val desk = desk
        try {
            ready(desk)
        } catch (e: Exception) {
            ensureActive()
            log("shared screen failed: $e")
            mole.on(Event.Failed("Couldn't get the shared screen ready.", first(e)))
            awaitCancellation()
        }
        val (agent, names) = try {
            if (!warm.await()) brain.warm(lib, desk)
            mole.on(Event.Setup("Reading your decks and files…"))
            brain.meet(desk, this)
        } catch (e: Exception) {
            ensureActive()
            log("models failed: $e")
            mole.on(Event.Failed("Couldn't load the models.", first(e)))
            awaitCancellation()
        }
        val vocabulary = names.filter { it.split(' ').size <= 4 }.take(NAMES)
        val ear = Ear().takeIf { typed == null }
        if (ear != null) {
            launch { ear.notes.collect { log("ear: $it") } }
            launch { ear.said.collect { log("ear     ${if (it.heard.ended) "final" else if (it.heard.quiet) "quiet" else "live "}  \"${it.heard.text}\"  unchanged ${it.ago.inWholeMilliseconds} ms") } }
            launch {
                try {
                    ear.listen(vocabulary.also { log("ear expects ${it.size} of ${names.size} names") }, audio)
                    if (audio.isNotEmpty()) log("ear: heard every file")
                } catch (e: Exception) { // the phone may still hear
                    ensureActive()
                    log("ear failed: $e")
                    mole.on(Event.Failed("Couldn't listen on this Mac.", first(e)))
                }
            }
        }
        val laptopDb = ear?.level?.inputDecibels()?.stateIn(this, SharingStarted.Eagerly, null) ?: MutableStateFlow<Float?>(null)
        val screen = MutableStateFlow(Link.Screen())
        val phones = PhoneServer(mole.line, screen, title, laptopDb, usePhoneMic = typed == null && audio.isEmpty()) { pcm, words ->
            coroutineScope {
                val phoneEar = Ear()
                val transcripts = launch(start = CoroutineStart.UNDISPATCHED) { phoneEar.heard.collect(words) }
                try { phoneEar.listen(vocabulary, pcm = pcm) } finally { transcripts.cancel() }
            }
        }
        phones.start()
        try {
            val words = microphoneWords(typed ?: ear!!.heard, phones.words, phones::usesPhoneMic)
            val s = Session(logged(agent), desk.asScreen(), mole, words, phones.lead.map { it.phone }, window = window)
            session.value = s
            launch { phones.picks.collect(s::pick) }
            launch { phones.connected.collect { log(if (it) "phone connected" else "no phone") } }
            launch {
                s.onScreen.collectLatest { shown -> // again once a tab that just opened has drawn
                    log("screen  ${shown.target ?: "the desktop"}  ${shown.name}")
                    repeat(2) {
                        val thumb = quietly { desk.thumbnail() }
                        screen.value = Link.Screen(shown.name, thumb)
                        if (it == 1 && thumb != null && shots != null) shot(thumb, shots)
                        delay(1500.milliseconds)
                    }
                }
            }
            s.run()
        } finally {
            session.value = null
            phones.stop()
            log("meeting over")
        }
    }

    private var shotCount = 0

    private fun shot(jpeg: ByteArray, dir: File) {
        dir.mkdirs()
        val file = File(dir, "%02d.png".format(++shotCount))
        ImageIO.write(ImageIO.read(jpeg.inputStream()), "png", file)
        log("shot    $file")
    }

    /** The shared screen up. */
    private suspend fun ready(desk: AgentDesktop) {
        mole.on(Event.Setup("Getting the shared screen ready…"))
        val t = TimeSource.Monotonic.markNow()
        for (note in desk.up()) {
            log("shared screen: $note")
            mole.on(Event.Setup(note.replaceFirstChar(Char::uppercase)))
            delay(3.seconds)
        }
        log("shared screen up in ${t.elapsedNow().inWholeMilliseconds} ms")
    }
}

/** The rules decide: MoleAgent over the desk's decks, apps and newest files, each embedded once across calls. */
class RulesBrain(embedder: () -> Embedder) : Brain {
    private val embeddings by lazy { Embeddings(embedder()) }
    private var intents: Intents? = null

    override suspend fun warm(library: File, desk: AgentDesktop) {
        intents = Intents.train(embeddings)
        // the first call then embeds only what changed since: 1500 files take half a minute
        val t = TimeSource.Monotonic.markNow()
        Catalog.build(embeddings, texts(decks(library, DATA)), emptyList(), desk.newestFiles())
        log("catalog warm in ${t.elapsedNow().inWholeMilliseconds} ms")
    }

    override suspend fun meet(desk: AgentDesktop, scope: CoroutineScope): Pair<Agent, List<String>> {
        val t = TimeSource.Monotonic.markNow()
        val slides = texts(desk.decks)
        val catalog = Catalog.build(embeddings, slides, desk.apps, desk.newestFiles())
        val intents = intents ?: Intents.train(embeddings).also { intents = it }
        log("catalog of ${slides.size} slides, ${catalog.files.size} files, ${desk.apps.size} apps in ${t.elapsedNow().inWholeMilliseconds} ms")
        val names = catalog.slides.map { it.deck.replace('_', ' ') } + catalog.slides.map { it.title } + catalog.vocabulary
        return MoleAgent(catalog, intents) to names.distinct()
    }
}

private fun texts(pages: List<DeckPage>) = pages.map { SlideText(it.deck, it.page, it.title, it.text) }

/** A stopped helper or sustained digital silence expires the last audible sample, even if the phone sends no levels. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<Float>.inputDecibels(): Flow<Float?> =
    filter { it.isFinite() && it > 1e-5f }.transformLatest { rms ->
        emit(20 * log10(rms))
        delay(1500.milliseconds)
        emit(null)
    }

/** Whether [dir] holds a PDF to present. */
fun hasDecks(dir: File) = !dir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }.isNullOrEmpty()

/** The agent, with one line in the log for each utterance it is asked about anew: the words, the move, how long it took. */
private fun logged(agent: Agent): Agent {
    var last: Heard? = null
    return Agent { heard, onScreen ->
        val t = TimeSource.Monotonic.markNow()
        agent.anythingToDo(heard, onScreen).also { move ->
            if (heard != last && heard.text.isNotBlank()) {
                last = heard
                val why = ((agent as? MoleAgent)?.thought?.reason ?: (agent as? ModelAgent)?.answer)?.let { " ($it)" }.orEmpty()
                log("decide  ${t.elapsedNow().inWholeMilliseconds} ms  \"${heard.text}\"${if (heard.ended) "" else " (still talking)"}  ->  $move$why")
            }
        }
    }
}

/**
 * The agent desktop as the session drives it. A call that fails brings the screen up afresh before the session's one
 * retry: Stage keeps a DevTools socket that died (the VM's SSH tunnel lapses, Chrome drops it) and fails every later call
 * on it, and only a new Stage connects again. A page that is not there needs no new screen.
 */
internal fun AgentDesktop.asScreen(): SharedScreen = object : SharedScreen {
    override val onScreen get() = this@asScreen.onScreen
    override suspend fun show(target: Target) = afresh("show $target") { this@asScreen.show(target) }
    override suspend fun control(name: String) = afresh(name) { this@asScreen.control(name) }
    override suspend fun close() = afresh("close") { this@asScreen.close() }
}

private suspend fun <T> AgentDesktop.afresh(what: String, call: suspend () -> T): T {
    val t = TimeSource.Monotonic.markNow()
    log("call    $what")
    return try {
        call().also { log("called  ${t.elapsedNow().inWholeMilliseconds} ms  $what") }
    } catch (e: Exception) {
        log("called  ${t.elapsedNow().inWholeMilliseconds} ms  $what  failed: $e")
        currentCoroutineContext().ensureActive()
        if (e !is IllegalArgumentException) quietly { up() }
        throw e
    }
}

private suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    null
}

private fun first(e: Exception) = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: e.toString()
