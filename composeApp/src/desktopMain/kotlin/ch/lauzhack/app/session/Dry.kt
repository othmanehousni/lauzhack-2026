package ch.lauzhack.app.session

import ch.lauzhack.app.Heard
import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import ch.lauzhack.app.log
import ch.lauzhack.app.stage.AgentDesktop
import ch.lauzhack.app.stage.Ear
import ch.lauzhack.app.stage.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** The shared screen of a replay: nothing is driven, the slide on the stage and what covers it follow the moves as on
 * AgentDesktop, and each call is logged as Laptop logs the desk's. */
internal class DryScreen : SharedScreen {
    private var stage = OnScreen()
    private var front: OnScreen? = null
    override val onScreen get() = front ?: stage

    override suspend fun show(target: Target): OnScreen {
        log("call    show $target")
        val shown = OnScreen(target, name(target))
        if (inFront(target)) front = shown else stage = shown.also { front = null }
        return shown
    }

    override suspend fun control(name: String) = log("call    $name")

    override suspend fun close(): OnScreen {
        log("call    close")
        if (front == null) stage = OnScreen() else front = null
        return onScreen
    }
}

/** The desk of a replay, for its decks and apps: the library's decks drawn apart from a running app's data, the apps
 * of a fresh Mac as in the VM, and no file or browser page of this Mac. */
internal fun dryDesk(library: File) =
    AgentDesktop(library, null, dry = true, data = File(System.getProperty("java.io.tmpdir"), "moliere-dry"), history = false, files = Files(mdfind = { "" }))

/** What an ear heard, as hear.swift's JSON lines on [input], read as the ear reads them and logged as Laptop logs them. */
fun replayed(input: InputStream): Flow<Heard> = channelFlow {
    val ear = Ear()
    launch(start = CoroutineStart.UNDISPATCHED) {
        ear.said.collect {
            log("ear     ${if (it.heard.ended) "final" else if (it.heard.quiet) "quiet" else "live "}  \"${it.heard.text}\"  unchanged ${it.ago.inWholeMilliseconds} ms")
            send(it.heard)
        }
    }
    withContext(Dispatchers.IO) { ear.read(input.bufferedReader()) }
}
