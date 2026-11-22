package ch.lauzhack.app.session

import ch.lauzhack.app.Heard
import ch.lauzhack.app.Target
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DryTest {
    @Test
    fun theEarsLinesArriveAsHeardAndTheScreenFollowsTheMoves() = runBlocking {
        val lines = """
            {"text": "Next", "kind": "live", "ago": 0}
            {"level": 0.02}
            {"text": "Next slide.", "kind": "live", "ago": 0.5, "quiet": true}
            {"text": "Next slide.", "kind": "final", "ago": 0.8}
        """.trimIndent()
        assertEquals(listOf(Heard("Next"), Heard("Next slide.", quiet = true), Heard("Next slide.", ended = true)), replayed(lines.byteInputStream()).take(3).toList())

        val s = DryScreen()
        val slide = Target.Slide("Talk", 2, "Title 2")
        val github = Target.Site("https://github.com", "GitHub")
        s.show(slide)
        assertEquals(github, s.show(github).target)
        assertEquals(slide, s.close().target, "a tab comes off the slide")
        assertNull(s.close().target, "then the slide comes off the stage")
        s.show(slide)
        s.show(Target.App("Notes"))
        assertEquals(slide, s.close().target, "an app comes off the slide")
        val photo = Target.File("/u/Pictures/House.jpg")
        s.show(photo)
        assertNull(s.close().target, "a picture replaces the slide on the stage")
    }
}
