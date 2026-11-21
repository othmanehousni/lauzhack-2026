package ch.lauzhack.app

import kotlinx.serialization.Serializable

/** The mole's fifteen moments, named as in mole/mole.js TITLES. */
@Serializable enum class MoleState { Hidden, Idle, Heard, Working, Retrieving, Ready, Back, Unsure, Thinking, Waiting, Tunneling, Failed, Hello, Choosing, Picked }

/** Something the agent can put on the shared screen. */
@Serializable sealed interface Target {
    @Serializable data class Slide(val deck: String, val page: Int, val title: String = "") : Target   // page is 1-based
    @Serializable data class File(val path: String) : Target
    @Serializable data class Site(val url: String, val name: String = "") : Target
    @Serializable data class App(val name: String) : Target
}

/** One row of the choosing panel, as mole.js draws it. */
@Serializable data class Choice(val name: String, val meta: String, val kind: Kind) {
    @Serializable enum class Kind { Pdf, Slides, Sheet, Site, Image, Other }
}

/** What the mole shows. Mirrors mole.js set(state, { title, heard, timeout, options, pick, card }); the bar and the phone render exactly this. */
@Serializable data class MoleLine(
    val state: MoleState = MoleState.Idle,
    val title: String? = null,          // null: the state's default title
    val heard: String = "",
    val options: List<Choice> = emptyList(),
    val pick: Int? = null,
    val card: String = "",              // the number on the slide card the mole holds
    val timeoutMs: Long? = null,
)

/** What the presenter is saying: the utterance so far, and whether they have stopped. */
@Serializable data class Heard(val text: String = "", val ended: Boolean = false)

/** What the audience sees right now. target == null is the bare agent desktop. */
@Serializable data class OnScreen(val target: Target? = null, val name: String = "")

/** The agent's answer to "is there anything to do?". */
sealed interface Move {
    data class Show(val target: Target) : Move
    data class Ask(val options: List<Target>) : Move
    data class Control(val name: String) : Move   // scroll, zoom, play, pause, close… on what is on screen
    data object Nothing : Move
}
