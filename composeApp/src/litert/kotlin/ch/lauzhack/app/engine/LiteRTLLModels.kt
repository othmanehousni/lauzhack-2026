// Copyright 2000-2026 JetBrains s.r.o. and contributors. Licensed under the Apache License, Version 2.0
// (https://www.apache.org/licenses/LICENSE-2.0). Adapted from JetBrains Koog at commit 921f7c1,
// prompt/prompt-executor/prompt-executor-clients/prompt-executor-litert-client/src/androidMain/kotlin/ai/koog/prompt/executor/clients/litert/LiteRTLLModels.kt
// Changes: the catalog of named Gemma files is replaced by liteRTModel(file), and models declare native JSON schema support.
package ch.lauzhack.app.engine

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/** LLM provider identifier for on-device inference via the LiteRT runtime. */
public data object LiteRTLLMProvider : LLMProvider("litert", "LiteRT")

/**
 * A `.litertlm` file in [LiteRTClientConfig.modelsPath], named by its file name. Schema.JSON.Standard makes Koog's
 * executeStructured pass the schema to the client, which decodes under it (LiteRT `ResponseFormat.json`).
 */
public fun liteRTModel(fileName: String, contextLength: Long = 8_192, maxOutputTokens: Long = 1_024): LLModel = LLModel(
    provider = LiteRTLLMProvider,
    id = fileName,
    capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion, LLMCapability.Schema.JSON.Standard),
    contextLength = contextLength,
    maxOutputTokens = maxOutputTokens,
)
