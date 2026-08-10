package n.startapp.services.ai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import n.startapp.models.ai.ChatResponse
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.random.Random

/**
 * The single LLM entry point for the whole application.
 *
 * Everything that used to be scattered across AiService lives here: provider dialect handling,
 * structured output, retries, and token accounting. One [HttpClient] is shared by every caller —
 * previously each service constructed its own engine and never closed it.
 */
class OpenAiCompatibleLlmClient : LlmClient {
    private val logger = LoggerFactory.getLogger(OpenAiCompatibleLlmClient::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { isLenient = true; ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = EnvConfig.aiTimeoutMs
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = EnvConfig.aiTimeoutMs
        }
        // Non-2xx must reach us as a response so the body can be inspected for dialect hints.
        expectSuccess = false
    }

    /** Optional fields that can be dropped when a gateway hides the real rejection. */
    private val MAX_RELAXATIONS = 2

    private val baseUrl: String
        get() {
            val raw = EnvConfig.aiDomen.trimEnd('/')
            return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        }

    private fun modelFor(tier: LlmModelTier) =
        if (tier == LlmModelTier.FAST) EnvConfig.aiModelFast else EnvConfig.aiModel

    override suspend fun complete(request: LlmRequest): LlmResult {
        if (EnvConfig.aiDomen.isBlank() || EnvConfig.aiApiKey.isBlank()) {
            throw IllegalStateException("AI service not configured: AI_DOMEN or AI_API missing")
        }

        val model = modelFor(request.tier)
        var attempt = 0
        var lastError: Exception? = null
        var relaxations = 0

        // maxRetries counts retries, so the loop body runs maxRetries + 1 times. Dialect
        // adaptations (400s that tell us how to fix the body) do not consume an attempt.
        while (attempt <= request.maxRetries) {
            attempt++
            val started = System.currentTimeMillis()
            try {
                val response = httpClient.post("$baseUrl/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer ${EnvConfig.aiApiKey}")
                    setBody(buildBody(request, model))
                }
                val elapsed = System.currentTimeMillis() - started

                when {
                    response.status.isSuccess() -> {
                        val parsed = response.body<ChatResponse>()
                        val choice = parsed.choices.firstOrNull()
                        val content = choice?.message?.content?.trim().orEmpty()
                        val usage = LlmUsage(
                            promptTokens = parsed.usage?.promptTokens ?: 0,
                            completionTokens = parsed.usage?.completionTokens ?: 0,
                            latencyMs = elapsed
                        )
                        logger.info(
                            "llm task={} model={} in={} out={} ms={} attempts={} finish={}",
                            request.task, model, usage.promptTokens, usage.completionTokens,
                            elapsed, attempt, choice?.finishReason
                        )
                        if (content.isEmpty()) {
                            // Reasoning models spend the completion budget on hidden reasoning
                            // tokens, so too small a cap yields an empty message rather than an error.
                            lastError = LlmException(
                                "Empty AI response (finish_reason=${choice?.finishReason}, " +
                                    "completion_tokens=${usage.completionTokens})"
                            )
                            backoff(attempt)
                            continue
                        }
                        return LlmResult(content, usage, model, attempt)
                    }

                    response.status.value == 400 -> {
                        val body = runCatching { response.bodyAsText() }.getOrDefault("")
                        if (adaptDialect(body, request)) {
                            attempt--          // a config fix is not a failed attempt
                            continue
                        }
                        throw LlmException("AI provider rejected the request: ${body.take(500)}")
                    }

                    response.status.value == 401 || response.status.value == 403 ->
                        throw LlmException("AI provider rejected the credentials (${response.status.value})")

                    response.status.value == 429 -> {
                        // Rate limits are per minute, so exponential backoff from 500ms just
                        // burns the remaining attempts. Providers say how long to wait — use it.
                        val waitMs = retryAfterMs(response)
                        lastError = LlmException("AI provider returned 429 (rate limited)")
                        logger.warn(
                            "llm task={} model={} rate limited, waiting {}ms, attempt={}/{}",
                            request.task, model, waitMs, attempt, request.maxRetries + 1
                        )
                        if (attempt <= request.maxRetries) delay(waitMs)
                    }

                    else -> {
                        lastError = LlmException("AI provider returned ${response.status.value}")
                        logger.warn(
                            "llm task={} model={} status={} attempt={}/{}",
                            request.task, model, response.status.value, attempt, request.maxRetries + 1
                        )

                        // A gateway in front of the model often reports a rejected request body
                        // as 5xx rather than passing the 400 through, so an unsupported field
                        // looks like a transient outage and retrying the same body can never
                        // succeed. Drop one optional field per failure and retry immediately;
                        // each relaxation is process-wide, so the cost is paid once per deploy.
                        if (response.status.value >= 500 && relaxations < MAX_RELAXATIONS && relax(request)) {
                            relaxations++
                            attempt--
                            continue
                        }

                        backoff(attempt)
                    }
                }
            } catch (e: LlmException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                logger.warn("llm task={} transport failure attempt={}: {}", request.task, attempt, e.message)
                backoff(attempt)
            } catch (e: HttpRequestTimeoutException) {
                lastError = e
                logger.warn("llm task={} timed out attempt={}", request.task, attempt)
                backoff(attempt)
            }
        }

        throw LlmException(
            "AI request '${request.task}' failed after $attempt attempt(s): ${lastError?.message}",
            lastError
        )
    }

    /**
     * Drops the next optional request field, most specialised first.
     *
     * Ordered by how likely the field is to be the culprit and how little is lost by dropping it:
     * a schema constraint is replaced by plain JSON (the validator still checks the result), and
     * only then is temperature given up, which merely makes replies less deterministic.
     *
     * @return true when something changed and the request is worth re-sending.
     */
    private fun relax(request: LlmRequest): Boolean {
        if (request.responseFormat !is ResponseFormat.Text &&
            AiCompat.structuredMode != AiCompat.StructuredMode.NONE &&
            AiCompat.downgradeStructuredMode(logger)
        ) return true

        if (request.temperature != null && AiCompat.disableTemperature(logger)) return true

        return false
    }

    /** Exponential backoff with jitter; skipped after the final attempt by the loop condition. */
    private suspend fun backoff(attempt: Int) {
        val base = 500L * (1 shl (attempt - 1).coerceAtMost(4))
        delay(base + Random.nextLong(0, 250))
    }

    /**
     * How long the provider wants us to wait, from `Retry-After` (seconds) or the
     * `x-ratelimit-reset-*` headers OpenAI-compatible providers send with durations like
     * "7.66s" or "2m59s". Capped so one throttled call cannot monopolise an annotation slot.
     */
    private fun retryAfterMs(response: io.ktor.client.statement.HttpResponse): Long {
        val cap = 30_000L
        val header = response.headers["Retry-After"]
            ?: response.headers["retry-after"]
            ?: response.headers["x-ratelimit-reset-tokens"]
            ?: response.headers["x-ratelimit-reset-requests"]
            ?: return 5_000L

        header.trim().toDoubleOrNull()?.let { return (it * 1000).toLong().coerceIn(1_000L, cap) }

        val duration = Regex("(?:(\\d+)m)?([\\d.]+)s").find(header.trim())
        if (duration != null) {
            val minutes = duration.groupValues[1].toLongOrNull() ?: 0L
            val seconds = duration.groupValues[2].toDoubleOrNull() ?: 0.0
            return ((minutes * 60 + seconds) * 1000).toLong().coerceIn(1_000L, cap)
        }
        return 5_000L
    }

    /**
     * Interprets a provider 400 and mutates the process-wide dialect flags.
     * @return true when something changed and the request is worth re-sending.
     */
    private fun adaptDialect(errorBody: String, request: LlmRequest): Boolean {
        val mentionsResponseFormat = "response_format" in errorBody.lowercase() ||
            "json_schema" in errorBody.lowercase()
        if (mentionsResponseFormat && request.responseFormat != ResponseFormat.Text) {
            return AiCompat.downgradeStructuredMode(logger)
        }
        return AiCompat.adaptTo(errorBody, logger)
    }

    private fun buildBody(request: LlmRequest, model: String): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            request.system?.let { system ->
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
            }
            addJsonObject {
                put("role", "user")
                put("content", request.user)
            }
        }
        put(AiCompat.tokenParam, AiCompat.effectiveMaxTokens(request.maxTokens))
        if (AiCompat.supportsTemperature) request.temperature?.let { put("temperature", it) }
        putResponseFormat(request.responseFormat)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putResponseFormat(format: ResponseFormat) {
        val mode = AiCompat.structuredMode
        if (mode == AiCompat.StructuredMode.NONE) return

        when (format) {
            is ResponseFormat.Text -> Unit

            is ResponseFormat.JsonObject -> putJsonObject("response_format") { put("type", "json_object") }

            is ResponseFormat.JsonSchema ->
                if (mode == AiCompat.StructuredMode.JSON_SCHEMA) {
                    putJsonObject("response_format") {
                        put("type", "json_schema")
                        put("json_schema", Json.parseToJsonElement(format.schema))
                    }
                } else {
                    // Downgraded: keep the reply JSON-shaped even without schema enforcement.
                    putJsonObject("response_format") { put("type", "json_object") }
                }
        }
    }

    override fun close() = httpClient.close()
}
