package ch.lauzhack.app

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractTest {
    @Test
    fun roundTrips() {
        val line = MoleLine(MoleState.Choosing, options = listOf(Choice("Q3 deck", "slides · 12 pages", Choice.Kind.Slides)), pick = 0)
        assertEquals(line, Json.decodeFromString<MoleLine>(Json.encodeToString(line)))
        val screen = OnScreen(Target.Slide("q3.pdf", 7, "Market size"), "Q3 deck")
        assertEquals(screen, Json.decodeFromString<OnScreen>(Json.encodeToString(screen)))
    }
}
