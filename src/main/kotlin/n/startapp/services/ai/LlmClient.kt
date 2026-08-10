package n.startapp.services.ai

/** Which configured model to use. STRONG is the quality model; FAST is for cheap side tasks. */
enum class LlmModelTier { STRONG, FAST }

/**
 * How the provider should constrain the reply.
 *
 * [JsonSchema] is what makes the lexical annotation layer viable: the model gets no field to
 * put invented IPA or audio URLs in. Providers that do not support it are downgraded at
 * runtime — see [OpenAiCompatibleLlmClient].
 */
sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data object JsonObject : ResponseFormat
    data class JsonSchema(val name: String, val schema: String, val strict: Boolean = true) : ResponseFormat
}

data class LlmRequest(
    /** Short label for logs and cache keys: "annotate", "resolve", "context", "ru_en". */
    val task: String,
    val user: String,
    val system: String? = null,
    val tier: LlmModelTier = LlmModelTier.STRONG,
    val maxTokens: Int = 1000,
    /** null omits the field entirely — required by models that only accept the default. */
    val temperature: Double? = 0.2,
    val responseFormat: ResponseFormat = ResponseFormat.Text,
    val maxRetries: Int = 2,
    /**
     * Which provider carries this request. Bulk work stays on the reserve pool so it can never
     * spend the quota a waiting user depends on.
     */
    val route: LlmRoute = LlmRoute.LIVE
)

data class LlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0
)

data class LlmResult(
    val content: String,
    val usage: LlmUsage,
    val model: String,
    val attempts: Int
)

/**
 * Raised when the provider is unreachable, misconfigured, or keeps returning nothing usable.
 *
 * [worthSpillingOver] separates "this provider has no capacity" — rate limits, outages, bad
 * credentials — from "this request is malformed", which would fail identically on the reserve
 * and so must not be retried there.
 */
class LlmException(
    message: String,
    cause: Throwable? = null,
    val worthSpillingOver: Boolean = false
) : Exception(message, cause)

interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResult
    fun close()
}
