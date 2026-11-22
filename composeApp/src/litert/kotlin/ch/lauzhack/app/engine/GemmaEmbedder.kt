package ch.lauzhack.app.engine

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** EmbeddingGemma 2 (int4 build of the model the Python brain ran through Ollama). */
val EmbeddingGemma = ModelFile(
    "embeddinggemma-2-text-270m.litertlm",
    "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
    164_626_432,
    "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
)

/** GPU first, CPU when the GPU backend does not start. */
fun defaultBackends(): List<Backend> = listOf(Backend.GPU(), Backend.CPU())

/** Creates and initializes an engine on the first backend that starts. */
internal fun <T : AutoCloseable> firstWorking(backends: List<Backend>, create: (Backend) -> T, initialize: (T) -> Unit): T {
    val failures = mutableListOf<String>()
    for (backend in backends) {
        val engine = create(backend)
        try {
            initialize(engine)
            return engine
        } catch (t: Throwable) {
            runCatching { engine.close() }
            failures += "${backend.name}: ${t.message}"
        }
    }
    error("no LiteRT backend started (${failures.joinToString("; ")})")
}

/**
 * 768-dimensional unit vectors from an EmbeddingGemma `.litertlm` file. The caller adds the task prefix
 * (`task: classification | query: …`, `title: … | text: …`). Calls are serialized: one engine, one inference at a time.
 */
class GemmaEmbedder(modelPath: String, cacheDir: String?, backends: List<Backend> = defaultBackends()) : Embedder, AutoCloseable {
    private val engine = firstWorking(backends, { EmbeddingEngine(EmbeddingEngineConfig(modelPath, backend = it, cacheDir = cacheDir)) }, EmbeddingEngine::initialize)
    private val lock = Mutex()
    private val options = EmbeddingOptions(normalize = true, outputSize = DIMENSION)

    val backend: String get() = engine.config.backend.name

    suspend fun embedFloats(text: String): FloatArray = lock.withLock {
        withContext(Dispatchers.IO) { engine.computeEmbedding(listOf(InputData.Text(text)), options).embedding }
    }

    override suspend fun embed(text: String): Vector = Vector(embedFloats(text).map(Float::toDouble))

    override fun diff(embedding1: Vector, embedding2: Vector): Double = 1.0 - embedding1.cosineSimilarity(embedding2)

    override fun close() = runBlocking { lock.withLock { engine.close() } }

    companion object {
        const val DIMENSION = 768
    }
}
