package ch.lauzhack.app.link

import ch.lauzhack.app.Choice
import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import ch.lauzhack.app.MoleState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkTest {
    @Test
    fun everyMessageRoundTrips() {
        val offers = listOf(Choice("Home offer — March.pdf", "Sent 12 Mar", Choice.Kind.Pdf), Choice("Home offer — revised.pdf", "Sent 2 Apr", Choice.Kind.Pdf))
        val messages = listOf(
            Link.Line(MoleLine(MoleState.Choosing, heard = "pull up the offer we sent them", options = offers)),
            Link.Lead(phone = true, metres = 4.2f),
            Link.Meeting("Home insurance review"),
            Link.Level(db = 6.5f, speech = true),
            Link.Words(Heard("the second one", ended = true)),
            Link.Pick(1),
        )
        for (message in messages) assertEquals(message, decode(message.encode()))

        val screen = decode(Link.Screen("Market size", byteArrayOf(-119, 80, 78, 71, 0, 127)).encode()) as Link.Screen
        assertEquals("Market size", screen.name)
        assertContentEquals(byteArrayOf(-119, 80, 78, 71, 0, 127), screen.thumbnail)
        assertNull((decode(Link.Screen("Desktop").encode()) as Link.Screen).thumbnail)
    }

    @Test
    fun wireIsShortAndUnknownFramesAreDropped() {
        assertEquals("""{"type":"pick","index":1}""", Link.Pick(1).encode())
        assertNull(decode("""{"type":"teleport"}"""))
        assertNull(decode("not json"))
    }
}
