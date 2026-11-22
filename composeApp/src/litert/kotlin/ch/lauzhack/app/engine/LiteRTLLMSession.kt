// Copyright 2000-2026 JetBrains s.r.o. and contributors. Licensed under the Apache License, Version 2.0
// (https://www.apache.org/licenses/LICENSE-2.0). Adapted from JetBrains Koog at commit 921f7c1,
// prompt/prompt-executor/prompt-executor-clients/prompt-executor-litert-client/src/androidMain/kotlin/ai/koog/prompt/executor/clients/litert/LiteRTLLMSession.kt
// Changes: package; the engine starts on the first of config.backends that initializes, with config.maxNumTokens,
// on the CPU with as many threads as performance cores unless told; a JSON schema in prompt.params becomes LiteRT's
// ResponseFormat (constrained decoding), and so does a REGEX, with LLGuidance; a prompt of one message without tools is
// answered in a one-off conversation, the next one created meanwhile; prompt.params.maxTokens caps the answer.
package ch.lauzhack.app.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

/** This Mac's performance cores, or null: a prefill on as many threads is the fastest, faster than on LiteRT's default
 * number and than with efficiency cores too (6 and 4 cores: up to 1800 tokens a second by default, 2000 on 6, slower on 8). */
private val PERFORMANCE_CORES: Int? by lazy {
    runCatching { ProcessBuilder("sysctl", "-n", "hw.perflevel0.physicalcpu").start().inputStream.bufferedReader().readText().trim().toInt() }.getOrNull()
}

/**
 * Manages a LiteRT inference session for a single [LiteRTLLMClient].
 *
 * Maintains a lazily created [Engine] and a long-lived [Conversation] that are
 * reused across [execute] calls while the requested model, tool set, sampling
 * parameters and system instruction remain unchanged. Only newly appended
 * messages (since the previous call) are forwarded to the native conversation
 * via `sendMessage`; the conversation owns its full chat history natively. This
 * matches LiteRT's intended usage and avoids the native crashes that occur when
 * a fresh conversation is repeatedly seeded with the full prior history via
 * `initialMessages`.
 *
 * The conversation is rebuilt only when the cache key (model, tools, sampler
 * config, system instruction, response format) changes or when the incoming prompt's prefix
 * diverges from what was previously sent (e.g. history truncation / branching).
 *
 * Session access is serialized via a coroutine [Mutex] and all blocking LiteRT
 * native calls are dispatched on [Dispatchers.IO] so callers (including the Android
 * main thread) are never blocked.
 *
 * @param config Configuration for the LiteRT client, including model paths and backend.
 */
internal class LiteRTLLMSession(private val config: LiteRTClientConfig) {
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var engineConfig: EngineConfig? = null

    private var conversation: Conversation? = null
    private var conversationKey: ConversationKey? = null

    // Snapshot of koog messages already sent to the native conversation (system
    // instruction excluded — it lives in ConversationConfig). Used to detect
    // history divergence and to compute the incremental delta to send.
    private var sentMessages: List<Message> = emptyList()

    // The next one-off conversation for each key, made while the last one answers: one with LLGuidance takes 0.6 s,
    // and made between answers it held up the next one. LiteRT lets a conversation answer while the engine makes another.
    private val spares = HashMap<ConversationKey, Deferred<Conversation>>()
    private val make = Mutex() // ExperimentalFlags is global: one conversation made at a time
    private val ahead = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Returns the current [Engine] if it already targets [model], otherwise builds and
     * initializes a new engine, then atomically replaces the previously cached one.
     *
     * Initialization is performed before the engine is published to session state so
     * a failed `initialize()` leaves the session usable: the new engine is closed
     * (best-effort) and the previous engine (if any) is retained.
     */
    private fun getCurrentEngine(model: LLModel): Engine {
        val modelPath = "${config.modelsPath}/${model.id}"
        val cached = engine
        if (cached != null && engineConfig?.modelPath == modelPath) {
            return cached
        }

        val newEngine = firstWorking(
            config.backends.map { if (it is Backend.CPU && it.threadCount == null) Backend.CPU(PERFORMANCE_CORES) else it },
            { Engine(EngineConfig(modelPath = modelPath, backend = it, maxNumTokens = config.maxNumTokens, cacheDir = config.cacheDir)) },
            Engine::initialize,
        )

        // Initialization succeeded — tear down old state and publish the new engine.
        closeQuietly()
        engineConfig = newEngine.engineConfig
        engine = newEngine
        return newEngine
    }

    /**
     * Sends new messages from [prompt] to the model and returns the response.
     *
     * The underlying [Conversation] is created on the first call and reused on
     * subsequent calls. On each call, only messages that were not previously sent
     * are forwarded to the native conversation; the last new message produces the
     * response. If the cache key (model, tools, sampler config, system instruction)
     * changes, or if the incoming prefix diverges from the previously sent history,
     * the conversation is closed and recreated.
     *
     * All native calls run on [Dispatchers.IO]; the whole operation is serialized
     * with [mutex] so concurrent calls cannot race with each other or with [close].
     *
     * @param prompt Prompt containing at least one message.
     * @param model The LLM to run inference with.
     * @param tools Tool descriptors registered with the conversation.
     * @return [Message.Assistant] produced by the model.
     * @throws IllegalArgumentException if [prompt] contains no messages.
     */
    suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        require(prompt.messages.isNotEmpty()) { "There should be at least one message" }

        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val currentEngine = getCurrentEngine(model)

                val params: AndroidLocalLLMParams = prompt.params.toAndroidLocalParams()
                val samplerConfig = SamplerConfig(
                    topK = params.topK,
                    topP = params.topP,
                    temperature = params.exactTemperature,
                    seed = params.seed ?: 0,
                )
                // LLGuidance matches SentencePiece's space mark: a literal space in the regex fails to parse.
                val regex = prompt.params.additionalProperties?.get(REGEX)?.jsonPrimitive?.content?.replace(' ', '▁')
                val responseFormat = regex?.let { ResponseFormat.regex(it) }
                    ?: (prompt.params.schema as? LLMParams.Schema.JSON)?.let { ResponseFormat.json(it.schema.toString()) }

                // System instruction is part of the ConversationConfig in LiteRT and
                // cannot change without recreating the conversation. We extract the
                // first leading system message (if any) and exclude all system
                // messages from the per-turn message stream.
                val systemInstruction = prompt.messages
                    .firstOrNull { it is Message.System }
                    ?.let { (it as Message.System).parts.joinToString("\n") { part -> part.text } }
                val nonSystemMessages = prompt.messages.filter { it !is Message.System }
                require(nonSystemMessages.isNotEmpty()) {
                    "Prompt must contain at least one non-system message"
                }

                val androidTools = tools.map { AndroidLocalTool(it) }
                val key = ConversationKey(
                    modelPath = engineConfig?.modelPath,
                    toolNames = androidTools.map { it.tool.name },
                    sampler = SamplerKey(
                        topK = samplerConfig.topK,
                        topP = samplerConfig.topP,
                        temperature = samplerConfig.temperature,
                        seed = samplerConfig.seed,
                    ),
                    systemInstruction = systemInstruction,
                    responseFormat = responseFormat != null,
                )

                // The cached conversation can be reused only when the cache key
                // matches AND the only new message vs. previously sent history is
                // exactly the last one (`sentMessages == nonSystemMessages.dropLast(1)`).
                // In all other cases (different key, divergent history, or more than
                // one new message) we rebuild the conversation and seed prior turns
                // via `initialMessages` — which only adds to history without running
                // inference. We never replay prior messages through `sendMessage()`,
                // because each `sendMessage()` triggers a native generation.
                val priorHistory = nonSystemMessages.dropLast(1)
                require(regex == null || priorHistory.isEmpty() && androidTools.isEmpty()) { "A regex prompt is one message, without tools" }
                if (priorHistory.isEmpty() && androidTools.isEmpty()) {
                    val oneOff = ConversationConfig(
                        samplerConfig = samplerConfig,
                        systemInstruction = systemInstruction?.let { Contents.of(it) },
                        enableResponseFormat = responseFormat != null,
                    )
                    return@withContext once(currentEngine, key, oneOff, regex != null, nonSystemMessages.last(), responseFormat, prompt.params.maxTokens)
                }
                val canReuse = conversation != null &&
                    conversationKey == key &&
                    sentMessages == priorHistory

                val activeConversation: Conversation = if (canReuse) {
                    conversation!!
                } else {
                    closeConversationQuietly()
                    val newConfig = ConversationConfig(
                        samplerConfig = samplerConfig,
                        systemInstruction = systemInstruction?.let { Contents.of(it) },
                        tools = androidTools.map { tool(it) },
                        automaticToolCalling = false,
                        initialMessages = priorHistory.map { it.toLitertMessage() },
                        enableResponseFormat = responseFormat != null,
                    )
                    val created = create(currentEngine, newConfig, llGuidance = false)
                    conversation = created
                    conversationKey = key
                    sentMessages = priorHistory
                    created
                }

                val lastMessage = nonSystemMessages.last()
                try {
                    val response = activeConversation.sendMessage(
                        lastMessage.toLitertMessage(), maxOutputToken = prompt.params.maxTokens, responseFormat = responseFormat,
                    )
                    // Commit on success: the native conversation has now consumed
                    // the last user/tool message AND produced the response, both of
                    // which it keeps in its internal history. Mirror that in
                    // `sentMessages` so the next call's prefix check matches the
                    // prompt that Koog will build (which appends our response).
                    val responseMessage = response.toKoogMessage(config.clock)
                    sentMessages = nonSystemMessages + responseMessage
                    responseMessage
                } catch (t: Throwable) {
                    // The native conversation state may be inconsistent after a
                    // failure; drop it so the next call rebuilds cleanly.
                    closeConversationQuietly()
                    throw t
                }
            }
        }
    }

    /** Answers [message] in the spare conversation for [key], or a new one, while the next spare for [key] is made. */
    private suspend fun once(
        engine: Engine, key: ConversationKey, conversationConfig: ConversationConfig, llGuidance: Boolean, message: Message, format: ResponseFormat?, maxTokens: Int?,
    ): Message.Assistant {
        val conversation = spares.remove(key)?.let { runCatching { it.await() }.getOrNull() } ?: create(engine, conversationConfig, llGuidance)
        spares[key] = ahead.async { create(engine, conversationConfig, llGuidance) }
        try {
            return conversation.sendMessage(message.toLitertMessage(), maxOutputToken = maxTokens, responseFormat = format).toKoogMessage(config.clock)
        } finally {
            runCatching { conversation.close() }
        }
    }

    /** With [llGuidance], LLGuidance is the conversation's constraint provider, which regex decoding needs. */
    @OptIn(ExperimentalApi::class)
    private suspend fun create(engine: Engine, conversationConfig: ConversationConfig, llGuidance: Boolean): Conversation = make.withLock {
        ExperimentalFlags.enableConversationConstrainedDecoding = llGuidance
        engine.createConversation(conversationConfig)
    }

    /**
     * Closes and releases all LiteRT resources held by this session.
     *
     * Tolerates partially initialized or already-closed objects: each native close()
     * is wrapped in a `runCatching` so cleanup always completes. Safe to call
     * multiple times and from any dispatcher.
     */
    suspend fun close() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                closeQuietly()
            }
        }
        ahead.cancel()
    }

    /** Best-effort tear-down of all cached LiteRT objects. Resets all session state. */
    private fun closeQuietly() {
        closeConversationQuietly()
        spares.values.forEach { runCatching { runBlocking { it.await() }.close() } } // the engine outlives what it makes
        spares.clear()
        runCatching { engine?.close() }
        engine = null
        engineConfig = null
    }

    /** Best-effort tear-down of the cached [Conversation]. Resets conversation state. */
    private fun closeConversationQuietly() {
        runCatching { conversation?.close() }
        conversation = null
        conversationKey = null
        sentMessages = emptyList()
    }

    private data class SamplerKey(
        val topK: Int,
        val topP: Double,
        val temperature: Double,
        val seed: Int,
    )

    private data class ConversationKey(
        val modelPath: String?,
        val toolNames: List<String>,
        val sampler: SamplerKey,
        val systemInstruction: String?,
        val responseFormat: Boolean,
    )
}
