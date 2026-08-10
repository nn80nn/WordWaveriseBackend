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
 * Owns provider dialect handling, structured output, retries, token accounting — and the choice
 * of which provider to send a request to. User-facing work goes to the primary and spills over
 * to the reserve pool when the primary runs out of quota; bulk work goes to the pool only, so a
 * background job can never spend the budget someone waiting on a screen depends on.
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

    val primary: LlmProvider get() = LlmProvider.primary()
    val pool: LlmProvider get() = LlmProvider.pool()

    /** Where the request goes first, and where it may spill over to. */
    private fun providersFor(route: LlmRoute): List<LlmProvider> = when (route) {
        LlmRoute.BULK -> listOf(pool).filter { it.isConfigured }
        LlmRoute.LIVE -> listOfNotNull(
            primary.takeIf { it.isConfigured },
            pool.takeIf { it.isConfigured }
        )
    }

    override suspend fun complete(request: LlmRequest): LlmResult {
        val providers = providersFor(request.route)
        if (providers.isEmpty()) {
            throw IllegalStateException(
                if (request.route == LlmRoute.BULK) "Reserve pool not configured: AI_DOMEN_POOL or AI_API_POOL missing"
                else "AI service not configured: AI_DOMEN or AI_API missing"
            )
        }

        var lastFailure: Exception? = null
        for ((index, provider) in providers.withIndex()) {
            try {
                return attempt(provider, request)
            } catch (e: LlmException) {
                lastFailure = e
                val hasSpare = index < providers.lastIndex
                if (!hasSpare) throw e
                // Exhausted quota or an unreachable endpoint is exactly what the reserve is for;
                // a rejected request body would fail the same way on the next provider, so only
                // capacity failures are worth spilling over.
                if (!e.worthSpillingOver) throw e
                logger.warn(
                    "llm task={} exhausted on {}, falling over to {}: {}",
                    request.task, provider.name, providers[index + 1].name, e.message
                )
            }
        }
        throw lastFailure ?: LlmException("AI request '${request.task}' had nowhere to go")
    }

    private suspend fun attempt(provider: LlmProvider, request: LlmRequest): LlmResult {
        val model = provider.modelFor(request.tier)
        var attempt = 0
        var lastError: Exception? = null
        var relaxations = 0
        var budgetRaised = false

        // maxRetries counts retries, so the loop body runs maxRetries + 1 times. Dialect
        // adaptations (rejections that tell us how to fix the body) do not consume an attempt.
        while (attempt <= request.maxRetries) {
            attempt++
            val started = System.currentTimeMillis()
            try {
                val response = httpClient.post(provider.endpoint) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer ${provider.apiKey}")
                    setBody(buildBody(provider, request, model, budgetRaised))
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
                            "llm task={} provider={} model={} in={} out={} ms={} attempts={} finish={}",
                            request.task, provider.name, model, usage.promptTokens,
                            usage.completionTokens, elapsed, attempt, choice?.finishReason
                        )

                        // A reply cut off at the token ceiling is not retryable as-is: the JSON
                        // is truncated mid-string and will never parse. Only a bigger budget
                        // helps, so grant one once.
                        if (choice?.finishReason == "length" && !budgetRaised) {
                            budgetRaised = true
                            logger.warn(
                                "llm task={} hit the token ceiling; retrying with a larger budget",
                                request.task
                            )
                            attempt--
                            continue
                        }

                        if (content.isEmpty()) {
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
                        if (adaptDialect(provider, body, request)) {
                            attempt--          // a config fix is not a failed attempt
                            continue
                        }
                        // A malformed request fails identically everywhere — do not spill over.
                        throw LlmException("[${provider.name}] rejected the request: ${body.take(500)}")
                    }

                    response.status.value == 401 || response.status.value == 403 ->
                        throw LlmException(
                            "[${provider.name}] rejected the credentials (${response.status.value})",
                            worthSpillingOver = true
                        )

                    response.status.value == 429 -> {
                        // Rate limits are per minute, so exponential backoff from 500ms just
                        // burns the remaining attempts. Providers say how long to wait — use it.
                        val waitMs = retryAfterMs(response)
                        lastError = LlmException(
                            "[${provider.name}] rate limited (429)", worthSpillingOver = true
                        )
                        logger.warn(
                            "llm task={} provider={} rate limited, waiting {}ms, attempt={}/{}",
                            request.task, provider.name, waitMs, attempt, request.maxRetries + 1
                        )
                        if (attempt <= request.maxRetries) delay(waitMs)
                    }

                    else -> {
                        // Read the body: a 5xx from a gateway usually still explains itself
                        // ("no healthy upstream", "model not found"), and without it the only
                        // signal is a bare status code that fits a dozen different causes.
                        val body = runCatching { response.bodyAsText() }.getOrDefault("").take(300)
                        lastError = LlmException(
                            "[${provider.name}] returned ${response.status.value}" +
                                if (body.isNotBlank()) ": $body" else "",
                            worthSpillingOver = true
                        )
                        logger.warn(
                            "llm task={} provider={} status={} attempt={}/{} body={}",
                            request.task, provider.name, response.status.value,
                            attempt, request.maxRetries + 1, body
                        )

                        // A gateway in front of the model often reports a rejected request body
                        // as 5xx rather than passing the 400 through, so an unsupported field
                        // looks like a transient outage and retrying the same body can never
                        // succeed. Only relax when the body reads like a structured rejection;
                        // a plain error page means the upstream is down, not that we asked wrong.
                        if (response.status.value >= 500 &&
                            looksLikeRequestRejection(body) &&
                            relaxations < MAX_RELAXATIONS &&
                            relax(provider, request)
                        ) {
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
                logger.warn("llm task={} provider={} transport failure attempt={}: {}",
                    request.task, provider.name, attempt, e.message)
                backoff(attempt)
            } catch (e: HttpRequestTimeoutException) {
                lastError = e
                logger.warn("llm task={} provider={} timed out attempt={}", request.task, provider.name, attempt)
                backoff(attempt)
            }
        }

        throw LlmException(
            "[${provider.name}] '${request.task}' failed after $attempt attempt(s): ${lastError?.message}",
            lastError,
            worthSpillingOver = true
        )
    }

    /**
     * A gateway that rejected our body says so in a structured error; one whose upstream is
     * simply down serves a plain error page ("Bad Gateway").
     */
    private fun looksLikeRequestRejection(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith("{") && trimmed.contains("\"error\"", ignoreCase = true)
    }

    /**
     * Drops the next optional request field, most specialised first: a schema constraint is
     * replaced by plain JSON (the validator still checks the result), and only then is
     * temperature given up, which merely makes replies less deterministic.
     */
    private fun relax(provider: LlmProvider, request: LlmRequest): Boolean {
        if (request.responseFormat !is ResponseFormat.Text &&
            AiCompat.structuredMode(provider.name) != AiCompat.StructuredMode.NONE &&
            AiCompat.downgradeStructuredMode(provider.name, logger)
        ) return true

        if (request.temperature != null && AiCompat.disableTemperature(provider.name, logger)) return true

        return false
    }

    /** Exponential backoff with jitter; skipped after the final attempt by the loop condition. */
    private suspend fun backoff(attempt: Int) {
        val base = 500L * (1 shl (attempt - 1).coerceAtMost(4))
        delay(base + Random.nextLong(0, 250))
    }

    /**
     * How long the provider wants us to wait, from `Retry-After` (seconds) or the
     * `x-ratelimit-reset-*` headers, which carry durations like "7.66s" or "2m59s".
     */
    private fun retryAfterMs(response: HttpResponse): Long {
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

    private fun adaptDialect(provider: LlmProvider, errorBody: String, request: LlmRequest): Boolean {
        val lower = errorBody.lowercase()
        val mentionsResponseFormat = "response_format" in lower || "json_schema" in lower
        if (mentionsResponseFormat && request.responseFormat != ResponseFormat.Text) {
            return AiCompat.downgradeStructuredMode(provider.name, logger)
        }
        return AiCompat.adaptTo(provider.name, errorBody, logger)
    }

    private fun buildBody(
        provider: LlmProvider,
        request: LlmRequest,
        model: String,
        doubleBudget: Boolean
    ): JsonObject = buildJsonObject {
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
        val budget = if (doubleBudget) request.maxTokens * 2 else request.maxTokens
        put(AiCompat.tokenParam(provider.name), AiCompat.effectiveMaxTokens(provider.name, budget))
        if (AiCompat.supportsTemperature(provider.name)) request.temperature?.let { put("temperature", it) }
        putResponseFormat(provider, request.responseFormat)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putResponseFormat(
        provider: LlmProvider,
        format: ResponseFormat
    ) {
        val mode = AiCompat.structuredMode(provider.name)
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
