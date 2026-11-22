package ch.lauzhack.app.stage

import ch.lauzhack.app.OnScreen
import ch.lauzhack.app.Target
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class ThumbnailTest {
    /** A DevTools that answers everything but a screenshot, as Chrome once did for a search results tab that was
     * still loading: the presenter's close of that tab waited fifteen seconds for the phone's thumbnail. */
    @Test
    fun aCallForThePresenterDoesNotWaitForAScreenshot() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val data = createTempDirectory("mole-thumbnail").toFile()
        File(data, "wallpaper.jpg").writeBytes(ByteArray(0))
        val home = uri(File(data, "stage.html"))
        val chrome = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                get("/json/version") { call.respondText("{}") }
                get("/json/list") { call.respondText("""[{"id": "home", "type": "page", "url": "$home"}]""") }
                put("/json/new") { call.respondText("""{"id": "results", "type": "page", "url": ""}""") }
                get("/json/activate/{id}") { call.respondText("Target activated") }
                get("/json/close/{id}") { call.respondText("Target is closing") }
                webSocket("/devtools/page/{id}") {
                    for (frame in incoming) {
                        val msg = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
                        if (msg.getValue("method").jsonPrimitive.content == "Page.captureScreenshot") continue
                        send("""{"id": ${msg.getValue("id")}, "result": {"result": {"type": "string", "value": "function"}}}""")
                    }
                }
            }
        }.start()
        try {
            val desk = AgentDesktop(data, sandbox = null, data = data, hostPort = port, history = false, cache = data, files = Files { "" })
            desk.up()
            desk.show(Target.Site("https://www.google.com/search?q=warehouse+robot+grippers"))
            val shot = async { desk.thumbnail() }
            delay(300) // asked for, and not answered
            val t = TimeSource.Monotonic.markNow()
            assertEquals(OnScreen(), desk.close())
            assertTrue(t.elapsedNow() < 500.milliseconds, "the close took ${t.elapsedNow()}")
            assertNull(shot.await())
        } finally {
            chrome.stop(0, 0)
            data.deleteRecursively()
        }
    }
}
