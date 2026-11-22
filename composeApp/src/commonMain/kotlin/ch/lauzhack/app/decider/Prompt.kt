package ch.lauzhack.app.decider

import ch.lauzhack.app.Target

/** Something the presenter may ask for, as the decider reads it (tune/CONTRACT.md, Items), and what shows it. */
sealed interface Item {
    val target: Target

    data class Slide(val deck: String, val page: Int, val pages: Int, val title: String, val text: String, override val target: Target.Slide) : Item
    data class File(val name: String, val folder: String, override val target: Target.File) : Item
    data class Site(val name: String, val url: String, override val target: Target.Site) : Item
    data class App(val name: String, override val target: Target.App) : Item
}

/** The input of one decision (tune/CONTRACT.md, State). */
data class State(
    val screen: Item? = null,
    val behind: Item.Slide? = null,
    val next: Item.Slide? = null,
    val offered: List<Int> = emptyList(),
    val shown: Item? = null,
    val heard: List<String> = emptyList(),
    val now: String = "",
    val done: Boolean = false,
    val candidates: List<Item> = emptyList(),
)

val CONTROLS = listOf(
    "scroll down", "scroll up", "scroll to the top", "scroll to the bottom", "zoom in", "zoom out", "reset the zoom",
    "go back a page", "go forward a page", "play the video", "pause the video", "full screen", "exit full screen",
)
private const val TEXT = 90
private const val NOW = 300
private const val HEARD = 200

internal fun clean(s: String): String {
    val out = StringBuilder()
    var gap = false
    for (c in s) {
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            gap = true
        } else {
            if (gap && out.isNotEmpty()) out.append(' ')
            gap = false
            out.append(c)
        }
    }
    return out.toString()
}

internal fun head(s: String, n: Int) = if (s.length <= n) s else s.take(n).substringBeforeLast(' ') + "…"

internal fun tail(s: String, n: Int) = if (s.length <= n) s else s.takeLast(n).substringAfter(' ')

internal fun line(c: Item, text: Boolean = true): String = when (c) {
    is Item.Slide -> "slide ${c.page} of ${c.pages} \"${clean(c.title)}\" in ${clean(c.deck)}" +
        (clean(c.text).takeIf { text && it.isNotEmpty() }?.let { ": ${head(it, TEXT)}" } ?: "")
    is Item.File -> "file \"${clean(c.name)}\"" + (clean(c.folder).takeIf { it.isNotEmpty() }?.let { " in $it" } ?: "")
    is Item.Site -> "site ${clean(c.name)} (${c.url})"
    is Item.App -> "app ${clean(c.name)}"
}

fun prompt(s: State): String = buildList {
    add("screen: " + (s.screen?.let { line(it) } ?: "desktop") + (s.behind?.let { " over ${line(it, false)}" } ?: ""))
    s.next?.let { add("next: ${line(it)}") }
    add("offered: " + s.offered.joinToString(" ").ifEmpty { "none" })
    add("shown: " + (s.shown?.let { line(it, false) } ?: "none"))
    for (h in s.heard.takeLast(2)) add("heard: ${tail(clean(h), HEARD)}")
    add("now (${if (s.done) "done" else "speaking"}): ${tail(clean(s.now), NOW)}")
    s.candidates.forEachIndexed { i, c -> add("${i + 1} ${line(c)}") }
}.joinToString("\n")

/** The answers [s] allows, as one regular expression. */
fun grammar(s: State): String {
    val n = s.candidates.size
    val on = s.screen
    val alts = mutableListOf("nothing", "wait", "unsure", "slide [1-9][0-9]?", "open [a-z0-9][a-z0-9 .\\-/']{0,40}", "search [a-z0-9][a-z0-9 .\\-']{0,60}")
    if (n >= 1) alts += "show [1-$n]"
    if (n >= 2) alts += "ask [1-$n] [1-$n]( [1-$n])?"
    if (on is Item.Slide || s.behind != null) alts += listOf("next", "previous")
    if (on !is Item.Slide) alts += "slides"
    if (on is Item.Site || on is Item.File) alts += listOf("click [a-z0-9][a-z0-9 .\\-']{0,40}", "control (${CONTROLS.joinToString("|")})")
    if (on != null) alts += "close"
    if (s.shown != null) alts += "undo"
    return alts.joinToString("|")
}
