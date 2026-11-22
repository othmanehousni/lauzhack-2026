package ch.lauzhack.app.decider

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ch.lauzhack.app.engine.REGEX
import ch.lauzhack.app.engine.liteRTModel
import kotlinx.serialization.json.JsonPrimitive

const val DECIDER = "decider.litertlm"

/** [ModelAgent]'s model call through Koog: the prompt as one user turn, greedy, at most 16 tokens, under the grammar when given. */
fun decider(client: LLMClient, model: LLModel = liteRTModel(DECIDER)): suspend (String, String?) -> String = { text, grammar ->
    val params = LLMParams(
        maxTokens = 16,
        additionalProperties = buildMap {
            put("topK", JsonPrimitive(1))
            put("seed", JsonPrimitive(0))
            if (grammar != null) put(REGEX, JsonPrimitive(grammar))
        },
    )
    client.execute(prompt("decider", params) { user(text) }, model, emptyList()).textContent().replace('▁', ' ')
}
