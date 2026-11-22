package ch.lauzhack.app.engine

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.Digest
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** A model file the app downloads on first run. [sha256] is lowercase hex. */
data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

/** [bytes] of [file] are on disk, out of [total]. */
data class Progress(val file: String, val bytes: Long, val total: Long)

/** Model files in [dir]. A download goes to `<name>.part`, resumes from there after a crash or a lost connection, and is renamed once its size and sha256 match. */
class ModelStore(val dir: String) {
    fun path(file: ModelFile): String = Path(dir, file.name).toString()

    fun has(file: ModelFile): Boolean = SystemFileSystem.metadataOrNull(Path(dir, file.name))?.size == file.size

    /** Downloads the files that are missing, one after the other; completes when all are in [dir]. A wrong size or hash deletes the `.part` and fails the flow. */
    fun fetch(files: List<ModelFile>): Flow<Progress> = flow {
        SystemFileSystem.createDirectories(Path(dir))
        // HttpTimeout is installed so CIO drops its default 15 s request timeout, which a 2.5 GB file would hit.
        HttpClient { install(HttpTimeout) { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = 60_000 } }.use { http ->
            for (file in files) {
                if (!has(file)) download(http, file)
                emit(Progress(file.name, file.size, file.size))
            }
        }
    }

    private suspend fun FlowCollector<Progress>.download(http: HttpClient, file: ModelFile) {
        val part = Path(dir, file.name + ".part")
        var have = SystemFileSystem.metadataOrNull(part)?.size ?: 0L
        if (have > file.size) have = 0L.also { SystemFileSystem.delete(part) }
        val digest = Digest("SHA-256")
        val buf = ByteArray(1 shl 16)
        if (have > 0) SystemFileSystem.source(part).buffered().use { src ->
            while (true) { val n = src.readAtMostTo(buf); if (n < 0) break; digest += buf.copyOf(n) }
        }
        if (have < file.size) http.prepareGet(file.url) { if (have > 0) header(HttpHeaders.Range, "bytes=$have-") }.execute { response ->
            when (response.status) {
                HttpStatusCode.PartialContent -> {}
                HttpStatusCode.OK -> { have = 0; digest.reset() }   // the server ignored the range: start over
                else -> error("${file.name}: HTTP ${response.status} from ${file.url}")
            }
            val body = response.bodyAsChannel()
            SystemFileSystem.sink(part, append = have > 0).buffered().use { sink ->
                var shown = have
                while (true) {
                    val n = body.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (have + n > file.size) { SystemFileSystem.delete(part); error("${file.name}: larger than ${file.size} bytes") }
                    sink.write(buf, 0, n)
                    digest += buf.copyOf(n)
                    have += n
                    if (have - shown >= 1 shl 20) { sink.flush(); shown = have; emit(Progress(file.name, have, file.size)) }
                }
            }
        }
        check(have == file.size) { "${file.name}: stopped at $have of ${file.size} bytes, the next fetch resumes" }
        val sha = digest.build().toHexString()
        if (!sha.equals(file.sha256, ignoreCase = true)) { SystemFileSystem.delete(part); error("${file.name}: sha256 $sha, expected ${file.sha256}") }
        SystemFileSystem.atomicMove(part, Path(dir, file.name))
    }
}
