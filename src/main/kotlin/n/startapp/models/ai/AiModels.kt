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

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 400,
    val temperature: Double = 0.7
)

@Serializable
internal data class ChatChoice(
    val message: ChatMessage = ChatMessage("", ""),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class ChatResponse(
    val choices: List<ChatChoice> = emptyList()
)
