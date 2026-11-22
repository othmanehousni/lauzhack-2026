package ch.lauzhack.app.mole

import ch.lauzhack.app.Choice
import ch.lauzhack.app.Choice.Kind
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import ch.lauzhack.app.MoleState.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// The sample lines of mole/index.html (SAMPLES, OFFERS, Q3, CHOICES), so the app and the web design show the same words.
private const val LINE = "so let's look at the market size"

internal val Offers = listOf(Choice("Home offer March.pdf", "Sent 12 Mar", Kind.Pdf), Choice("Home offer revised.pdf", "Sent 2 Apr", Kind.Pdf))
internal val Q3 = listOf(
    Choice("Q3 review.pptx", "Slide 7", Kind.Slides),
    Choice("Q3 numbers.xlsx", "Edited today", Kind.Sheet),
    Choice("Q3 board memo.pdf", "4 pages", Kind.Pdf),
)
internal val Choices = listOf(
    "Two versions of one file" to MoleLine(Choosing, heard = "pull up the offer we sent them", options = Offers),
    "Three kinds of file" to MoleLine(Choosing, heard = "show them the Q3 numbers", options = Q3),
    "Picked by voice" to MoleLine(Picked, "Opening the revised offer…", "the second one", Offers, pick = 1),
)
internal val Samples: Map<MoleState, MoleLine> = mapOf(
    Hidden to MoleLine(Hidden),
    Idle to MoleLine(Idle, card = "07"),
    Heard to MoleLine(Heard, "I've got it.", LINE, card = "07"),
    Working to MoleLine(Working, "Finding slide 7…", LINE, card = "07"),
    Retrieving to MoleLine(Retrieving, "I found slide 7.", LINE, card = "07"),
    Ready to MoleLine(Ready, "Slide 7 is ready.", LINE, card = "07"),
    Back to MoleLine(Back, "Back to you.", card = "07"),
    Unsure to MoleLine(Unsure, heard = "open the, um, thing from the offsite", card = "07"),
    Thinking to MoleLine(Thinking, "Thinking…", "show them how the warehouse robot picks", card = "07"),
    Waiting to MoleLine(Waiting, "Open… what?", "open up, um", card = "07", timeoutMs = 8000),
    Tunneling to MoleLine(Tunneling, "Step 2, clicking Search", "search robot grippers on youtube", card = "07"),
    Failed to MoleLine(Failed, "Couldn't find “roadmap”.", "pull up the roadmap", card = "07"),
    Hello to MoleLine(Hello, "Hi. I'm listening.", card = "07"),
    Choosing to Choices[0].second,
    Picked to Choices[2].second,
)

/** index.html's ANNOUNCE: retrieving with the one thing it is about to show, shown when the time is up unless stopped. */
internal val Announce = MoleLine(Retrieving, "Showing slide 7", LINE, listOf(Choice("Market size", "Slide 7", Kind.Slides)), card = "07", timeoutMs = 2500)

/** Plays index.html's story on a loop, and its pickLive when a row is picked, until the agent drives the mole. */
internal class Demo(private val scope: CoroutineScope) {
    private val line = MutableStateFlow(MoleLine(Hidden))
    val lines: StateFlow<MoleLine> = line
    private var job: Job? = null
    private var options = Offers

    fun show(next: MoleLine) {
        job?.cancel()
        if (next.options.isNotEmpty()) options = next.options
        line.value = next
    }

    fun play() = restart { while (true) story() }

    // Picking a row plays the rest: it opens, lands on screen, and hands back. Stop (-1) on an announce hands back with
    // nothing shown.
    fun pick(i: Int) = restart {
        val card = if (i < 0) "07" else "${i + 1}"
        if (i >= 0) {
            beat(MoleLine(Picked, "Opening the ${listOf("first", "second", "third").getOrElse(i) { "chosen" }} one…", "", options, i, card), 1100)
            beat(MoleLine(Ready, "It's on screen.", card = card), 1600)
        }
        beat(MoleLine(Back, card = card), 1500)
        beat(MoleLine(Idle, card = card), 2000)
        while (true) story()
    }

    private fun restart(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private suspend fun beat(next: MoleLine, ms: Long) {
        if (next.options.isNotEmpty()) options = next.options
        line.value = next
        delay(ms)
    }

    private suspend fun stream(text: String) {
        val words = text.split(" ")
        for (i in 1..words.size) beat(MoleLine(Idle, heard = words.take(i).joinToString(" "), card = "07"), 140)
    }

    private suspend fun story() {
        val market = "okay so the next thing I want to show you is the market size"
        beat(MoleLine(Idle, card = "07"), 500)
        stream(market)
        beat(MoleLine(Heard, "I've got it.", market, card = "07"), 1000)
        beat(MoleLine(Working, "Finding slide 7…", market, card = "07"), 2000)
        beat(Announce.copy(heard = market), Announce.timeoutMs!!)
        beat(MoleLine(Ready, "It's on screen.", market, card = "07"), 1700)
        beat(MoleLine(Back, card = "07"), 1500)
        beat(MoleLine(Hidden), 2700)
        beat(MoleLine(Hello, "On your phone now."), 1800)
        beat(MoleLine(Idle), 400)
        val offer = "and can you pull up the offer we sent them"
        stream(offer)
        beat(MoleLine(Choosing, heard = offer, options = Offers), 2600)
        beat(MoleLine(Picked, "Opening the revised offer…", "the second one", Offers, pick = 1, card = "2"), 1300)
        beat(MoleLine(Ready, "It's on screen.", "the second one", card = "2"), 1500)
        beat(MoleLine(Back, card = "2"), 1500)
        beat(MoleLine(Hidden), 2700)
        beat(MoleLine(Hello), 1600)
        beat(MoleLine(Idle), 600)
        val robot = "show them how the warehouse robot picks"
        beat(MoleLine(Thinking, heard = robot), 1400)
        beat(MoleLine(Tunneling, "Step 1, opening YouTube", robot), 1500)
        beat(MoleLine(Tunneling, "Step 2, clicking Search", robot), 1500)
        beat(MoleLine(Ready, "It's on screen.", robot), 1500)
        beat(MoleLine(Back), 1300)
        beat(MoleLine(Hidden), 1500)
        beat(MoleLine(Idle), 400)
        val offsite = "and can you open the, um, thing from the offsite"
        stream(offsite)
        beat(MoleLine(Unsure, heard = offsite), 2000)
        beat(MoleLine(Back), 1300)
        beat(MoleLine(Hidden), 2000)
    }
}
