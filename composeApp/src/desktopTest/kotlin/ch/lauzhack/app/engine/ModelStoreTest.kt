package ch.lauzhack.app.engine

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStoreTest {
    private val data = Random(7).nextBytes(3_000_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val ranges = mutableListOf<String?>()
    private var cutAfter: Int? = null   // the next response stops after this many bytes
    private var honorRange = true

    private val server = embeddedServer(CIO, port = 0) {
        routing {
            get("/m.bin") {
                val range = call.request.headers[HttpHeaders.Range].also { ranges += it }
                val from = if (honorRange) range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0 else 0
                if (from > 0) call.response.header(HttpHeaders.ContentRange, "bytes $from-${data.size - 1}/${data.size}")
                val status = if (from > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK
                call.respondBytesWriter(contentLength = (data.size - from).toLong(), status = status) {
                    val cut = cutAfter.also { cutAfter = null }
                    writeFully(data, from, if (cut != null) from + cut else data.size)
                    if (cut != null) { flush(); throw IOException("cut") }
                }
            }
        }
    }.start()
    private val url = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}/m.bin"
    private val dir = Files.createTempDirectory("models").toFile()
    private val store = ModelStore(dir.path)

    @AfterTest fun stop() { server.stop(0, 0); dir.deleteRecursively() }

    private fun fetch(file: ModelFile) = runBlocking { store.fetch(listOf(file)).toList() }
    private fun part(file: ModelFile) = File(dir, file.name + ".part")

    @Test fun downloadsAndVerifies() {
        val file = ModelFile("m.bin", url, data.size.toLong(), sha)
        val progress = fetch(file)
        assertContentEquals(data, File(store.path(file)).readBytes())
        assertTrue(store.has(file))
        assertFalse(part(file).exists())
        assertEquals(Progress("m.bin", file.size, file.size), progress.last())
        assertTrue(progress.size > 2)
        fetch(file)
        assertEquals(1, ranges.size, "a file already there is not downloaded again")
    }

    @Test fun wrongHashDeletesThePart() {
        val file = ModelFile("m.bin", url, data.size.toLong(), "0".repeat(64))
        assertTrue(assertFailsWith<IllegalStateException> { fetch(file) }.message!!.contains("sha256"))
        assertFalse(part(file).exists())
        assertFalse(File(store.path(file)).exists())
    }

    @Test fun moreBytesThanExpectedDeletesThePart() {
        val file = ModelFile("m.bin", url, data.size - 10L, sha)
        assertTrue(assertFailsWith<IllegalStateException> { fetch(file) }.message!!.contains("larger"))
        assertFalse(part(file).exists())
    }

    @Test fun fewerBytesThanExpectedKeepsThePart() {
        val file = ModelFile("m.bin", url, data.size + 10L, sha)
        assertTrue(assertFailsWith<IllegalStateException> { fetch(file) }.message!!.contains("stopped at"))
        assertEquals(data.size.toLong(), part(file).length())
    }

    @Test fun resumesAnInterruptedDownload() {
        val file = ModelFile("m.bin", url, data.size.toLong(), sha)
        cutAfter = 1_500_000
        assertFailsWith<Throwable> { fetch(file) }
        val kept = part(file).length()
        assertTrue(kept in 1..<data.size, "kept $kept bytes")
        fetch(file)
        assertEquals(listOf(null, "bytes=$kept-"), ranges)
        assertContentEquals(data, File(store.path(file)).readBytes())
    }

    @Test fun startsOverWhenTheServerIgnoresTheRange() {
        val file = ModelFile("m.bin", url, data.size.toLong(), sha)
        cutAfter = 1_000_000
        assertFailsWith<Throwable> { fetch(file) }
        honorRange = false
        fetch(file)
        assertContentEquals(data, File(store.path(file)).readBytes())
    }
}
