package n.startapp.models.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiWordRequest(val word: String)

@Serializable
data class AiTextResponse(val result: String)

@Serializable
data class AiExerciseResponse(val sentence: String, val answer: String)

// OpenAI-compatible internal models
@Serializable
internal data class ChatMessage(val role: String, val content: String)

// NOTE: the request body is assembled as a JsonObject at call time rather than declared here —
// the token-limit field name differs between providers (`max_completion_tokens` on gpt-5.x vs
// `max_tokens` elsewhere) and `temperature` has to be omitted entirely for some models.
// See AiService.buildRequestBody / EnvConfig.aiTokenParam.

@Serializable
internal data class ChatChoice(
    val message: ChatMessage = ChatMessage("", ""),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
internal data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null
)
