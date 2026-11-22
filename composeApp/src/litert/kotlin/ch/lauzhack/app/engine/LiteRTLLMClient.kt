// Copyright 2000-2026 JetBrains s.r.o. and contributors. Licensed under the Apache License, Version 2.0
// (https://www.apache.org/licenses/LICENSE-2.0). Adapted from JetBrains Koog at commit 921f7c1,
// prompt/prompt-executor/prompt-executor-clients/prompt-executor-litert-client/src/androidMain/kotlin/ai/koog/prompt/executor/clients/litert/LiteRTLLMClient.kt
// Changes: package; config takes a list of backends tried in order and maxNumTokens, and has no default model or path.
package ch.lauzhack.app.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.utils.time.KoogClock
import com.google.ai.edge.litertlm.Backend
import kotlin.time.ExperimentalTime

/**
 * Configuration for [LiteRTLLMClient].
 *
 * @property modelsPath Directory holding the `.litertlm` files; a model's [LLModel.id] is its file name there.
 * @property cacheDir Absolute path used by the LiteRT engine for intermediate caches.
 *   When `null`, LiteRT defaults to the directory of the model file. Set to `":nocache"`
 *   to disable caching entirely (see LiteRT's `EngineConfig.cacheDir` contract).
 * @property backends LiteRT compute backends, tried in order until one initializes.
 * @property maxNumTokens Context size of the engine; `null` keeps the model file's default.
 * @property clock Clock instance used for response timestamp metadata.
 */
public data class LiteRTClientConfig(
    val modelsPath: String,
    val cacheDir: String? = null,
    val backends: List<Backend> = defaultBackends(),
    val maxNumTokens: Int? = null,
    @OptIn(ExperimentalTime::class)
    val clock: KoogClock = KoogClock.System,
)

/**
 * [LLMClient] implementation that runs inference on-device using the LiteRT runtime.
 *
 * Delegates to an internal [LiteRTLLMSession] which manages the LiteRT [Engine] and
 * [Conversation] lifecycle. Tools are forwarded to LiteRT as part of the
 * [Conversation] configuration; tool execution itself is performed by the koog
 * agent framework, not by LiteRT (`automaticToolCalling = false`).
 *
 * @param config Configuration specifying model paths, backend, and runtime options.
 */
public class LiteRTLLMClient(config: LiteRTClientConfig) : LLMClient() {
    private val session = LiteRTLLMSession(config)

    /** Runs inference for [prompt] using [model] and returns the model's response messages. */
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        return session.execute(prompt, model, tools)
    }

    /**
     * Not supported — content moderation is unavailable for on-device models.
     *
     * @throws UnsupportedOperationException always.
     */
    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ModerationResult {
        throw UnsupportedOperationException("Moderation is not supported for local models")
    }

    /** Returns [LiteRTLLMProvider] as the provider for all models served by this client. */
    override fun llmProvider(): LLMProvider = LiteRTLLMProvider

    /** Closes the underlying [LiteRTLLMSession] and releases all LiteRT resources. */
    override fun close() {
        kotlinx.coroutines.runBlocking { session.close() }
    }
}
