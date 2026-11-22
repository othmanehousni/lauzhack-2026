package ch.lauzhack.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BANNED = "•·—–" // bullet, middle dot, em dash, en dash

/** Nothing a person reads has a bullet, a middle dot or a long dash: no Kotlin string literal of the app, no Android resource. */
class CharactersTest {
    @Test
    fun noBannedCharactersInWhatAPersonReads() {
        val kotlin = (File("src").listFiles { f -> f.name.endsWith("Main") }!!.toList() + File("../androidApp/src/main/kotlin"))
            .flatMap { it.walk().filter { f -> f.extension == "kt" }.toList() }
        val hits = kotlin.flatMap { f -> literals(f.readText()).filter { s -> s.any { it in BANNED } }.map { "$f: \"$it\"" } } +
            File("../androidApp/src/main/res").walk().filter { it.extension == "xml" }
                .flatMap { f -> f.readLines().filter { l -> l.any { it in BANNED } }.map { "$f: $it" } }
        assertTrue(kotlin.size > 20, "no sources found from ${File("").absolutePath}")
        assertEquals(emptyList(), hits)
    }

    @Test
    fun literalsSkipComments() {
        val src = "// a — b\nval x = \"one \\\" · two\" /* – */ + 'c' + \"\"\"raw // not a comment\"\"\"\n"
        assertEquals(listOf("one \\\" · two", "c", "raw // not a comment"), literals(src))
    }
}

/** The string and character literals of Kotlin source, comments skipped; a template's code counts as its literal. */
internal fun literals(src: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    fun end(of: String, from: Int) = src.indexOf(of, from).let { if (it < 0) src.length else it }
    while (i < src.length) {
        when {
            src.startsWith("//", i) -> i = end("\n", i)
            src.startsWith("/*", i) -> i = end("*/", i + 2) + 2
            src.startsWith("\"\"\"", i) -> end("\"\"\"", i + 3).let { out += src.substring(i + 3, it); i = it + 3 }
            src[i] == '"' || src[i] == '\'' -> {
                var j = i + 1
                while (j < src.length && src[j] != src[i] && src[j] != '\n') j += if (src[j] == '\\') 2 else 1
                out += src.substring(i + 1, minOf(j, src.length))
                i = j + 1
            }
            else -> i++
        }
    }
    return out
}
