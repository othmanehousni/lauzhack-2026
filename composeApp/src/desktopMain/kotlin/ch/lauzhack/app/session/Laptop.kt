package ch.lauzhack.app.session

import ai.koog.embeddings.base.Embedder
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.Target
import ch.lauzhack.app.agent.Catalog
import ch.lauzhack.app.agent.Embeddings
import ch.lauzhack.app.agent.Intents
import ch.lauzhack.app.agent.MoleAgent
import ch.lauzhack.app.agent.SlideText
import ch.lauzhack.app.link.Link
import ch.lauzhack.app.link.PhoneServer
import ch.lauzhack.app.stage.AgentDesktop
import ch.lauzhack.app.stage.Ear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The laptop's side of the product. Asleep outside a call: no ear, no phone server, no shared screen. When [meeting]
 * gives a call's title, it brings the shared screen up, reads the decks, files and apps into the agent's catalog, and
 * runs a [Session] on the laptop's ear or the phone's, whichever leads, until the call ends; then all of it stops.
 * The floater renders [line] and sends taps to [onPick]. [downloads] (a model file, and the fraction of it on disk) is
 * collected to its end before [embedder] is first used.
 */
class Laptop(
    scope: CoroutineScope,
    embedder: Embedder,
    library: File,
    private val meeting: StateFlow<String?>,
    private val downloads: Flow<Pair<String, Float>>? = null,
) {
    private val work = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1)) // the session's state is one thread's
    private val mole = Mole(work)
    private val desk = AgentDesktop(library)
    private val embeddings = Embeddings(embedder) // kept across calls: each deck, file and example is embedded once
    private var intents: Intents? = null
    private val session = MutableStateFlow<Session?>(null)

    val line: StateFlow<MoleLine> = mole.line

    fun onPick(index: Int) {
        session.value?.pick(index)
    }

    init {
        work.launch {
            if (downloads != null) {
                try {
                    downloads.collect { (file, done) -> mole.on(Event.Setup("Downloading $file · ${(done * 100).roundToInt()}%")) }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    mole.on(Event.Failed("Couldn't download the models.", first(e)))
                }
                mole.on(Event.Sleep)
            }
            val title = meeting.map { it.orEmpty() }.stateIn(this, SharingStarted.Eagerly, "")
            meeting.map { it != null }.distinctUntilChanged().collectLatest { on -> if (on) meet(title) else mole.on(Event.Sleep) }
        }
    }

    private suspend fun meet(title: StateFlow<String>): Nothing = coroutineScope {
        val catalog = try {
            ready()
        } catch (e: Exception) {
            ensureActive()
            mole.on(Event.Failed("Couldn't get the shared screen ready.", first(e)))
            awaitCancellation()
        }
        val ear = Ear()
        launch {
            try {
                ear.listen(catalog.vocabulary)
            } catch (e: Exception) { // the phone may still hear
                ensureActive()
                mole.on(Event.Failed("Couldn't listen on this Mac.", first(e)))
            }
        }
        val laptopDb = ear.level.map { 20 * log10(maxOf(it, 1e-5f)) }.stateIn(this, SharingStarted.Eagerly, -100f)
        val screen = MutableStateFlow(Link.Screen())
        val phones = PhoneServer(mole.line, screen, title, laptopDb)
        phones.start()
        try {
            val s = Session(MoleAgent(catalog, intents!!), desk.asScreen(), mole, ear.heard, phones.words, phones.lead.map { it.phone })
            session.value = s
            launch { phones.picks.collect(s::pick) }
            launch {
                s.onScreen.collectLatest { shown -> // again once a tab that just opened has drawn
                    repeat(2) {
                        screen.value = Link.Screen(shown.name, quietly { desk.thumbnail() })
                        delay(1500.milliseconds)
                    }
                }
            }
            s.run()
        } finally {
            session.value = null
            phones.stop()
        }
    }

    /** The shared screen up, and the catalog of what can be put on it. */
    private suspend fun ready(): Catalog {
        mole.on(Event.Setup("Getting the shared screen ready…"))
        desk.up().forEach { println("shared screen: $it") }
        mole.on(Event.Setup("Reading your decks and files…"))
        val slides = desk.decks.map { SlideText(it.deck, it.page, it.title, it.text) }
        val catalog = Catalog.build(embeddings, slides, desk.apps, desk.newestFiles())
        if (intents == null) intents = Intents.train(embeddings)
        return catalog
    }
}

/**
 * The agent desktop as the session drives it. A call that fails brings the screen up afresh before the session's one
 * retry: Stage keeps a DevTools socket that died (the VM's SSH tunnel lapses, Chrome drops it) and fails every later call
 * on it, and only a new Stage connects again. A page that is not there needs no new screen.
 */
internal fun AgentDesktop.asScreen(): SharedScreen = object : SharedScreen {
    override val onScreen get() = this@asScreen.onScreen
    override suspend fun show(target: Target) = afresh { this@asScreen.show(target) }
    override suspend fun control(name: String) = afresh { this@asScreen.control(name) }
    override suspend fun close() = afresh { this@asScreen.close() }
}

private suspend fun <T> AgentDesktop.afresh(call: suspend () -> T): T = try {
    call()
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    if (e !is IllegalArgumentException) quietly { up() }
    throw e
}

private suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    null
}

private fun first(e: Exception) = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: e.toString()
