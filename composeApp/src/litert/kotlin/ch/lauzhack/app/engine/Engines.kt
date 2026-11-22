package ch.lauzhack.app.engine

import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * The models of one app install: [download] them, then open the [embedder] and, once a decision model file is in
 * [modelsDir], the [client]. Get one from desktopEngines() or androidEngines(context).
 */
class Engines(val modelsDir: String, val cacheDir: String) {
    val store = ModelStore(modelsDir)

    init { File(cacheDir).mkdirs() }

    fun download(vararg more: ModelFile): Flow<Progress> = store.fetch(listOf(EmbeddingGemma, *more))

    fun embedder(): GemmaEmbedder = GemmaEmbedder(store.path(EmbeddingGemma), cacheDir)

    /** A Koog client for `.litertlm` files in [modelsDir], or null when [model] is not there yet. Run it with `liteRTModel(model)`. */
    fun client(model: String = "decider.litertlm", maxNumTokens: Int? = null): LiteRTLLMClient? =
        if (File(modelsDir, model).isFile) LiteRTLLMClient(LiteRTClientConfig(modelsDir, cacheDir, maxNumTokens = maxNumTokens)) else null
}
