package n.startapp.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import n.startapp.models.ai.*
import n.startapp.services.ai.AiCompat
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory

class AiService {
    private val logger = LoggerFactory.getLogger(AiService::class.java)

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            // Reasoning models routinely take longer than the old 30s ceiling.
            requestTimeoutMillis = EnvConfig.aiTimeoutMs
            connectTimeoutMillis = 10000
            socketTimeoutMillis = EnvConfig.aiTimeoutMs
        }
        expectSuccess = false
    }

    private val aiDomen: String get() {
        val raw = EnvConfig.aiDomen.trimEnd('/')
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }
    private val aiApiKey: String get() = EnvConfig.aiApiKey
    private val model: String get() = EnvConfig.aiModel

    suspend fun explain(word: String): AiTextResponse {
        val prompt = """
            Explain the English word "$word" in simple, clear English.
            Include: 1) A concise definition, 2) When/how to use it, 3) One memory tip.
            Keep it under 120 words. Be direct and helpful.
        """.trimIndent()
        val result = callAi(prompt)
        return AiTextResponse(result = result)
    }

    suspend fun generateExamples(word: String): AiTextResponse {
        val prompt = """
            Write 3 natural, varied example sentences using the English word "$word".
            Each sentence should show different contexts.
            Format: just the sentences, one per line, no numbers or bullets.
        """.trimIndent()
        val result = callAi(prompt)
        return AiTextResponse(result = result)
    }

    /**
     * Translates an English word to Russian using AI.
     * @param word The word or phrase to translate
     * @param posHint Optional part-of-speech hint for context (e.g. "noun", "verb")
     * @return Russian translation (1-3 words) or null on failure
     */
    suspend fun translateToRussian(word: String, posHint: String? = null): String? {
        val context = if (posHint != null) "$word ($posHint)" else word
        val prompt = "Translate the English word/phrase \"$context\" to Russian. Return ONLY 1-3 Russian words, no punctuation, no explanation."
        return try {
            callAi(prompt, maxTokens = 20, temperature = 0.1)
                .trim()
                .substringBefore("(").trim()
                .split(Regex("\\s+")).take(3).joinToString(" ")
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug("AI translation failed for '$context': ${e.message}")
            null
        }
    }

    suspend fun quickSummary(word: String): AiTextResponse {
        val prompt = """
            Give a quick, helpful 2-sentence English explanation of the word "$word".
            Then on a new line add: "RU: <brief Russian translation 1-3 words>".
            Be concise and practical.
        """.trimIndent()
        val result = callAi(prompt, maxTokens = 150, temperature = 0.5)
        return AiTextResponse(result = result)
    }

    suspend fun generateExercise(word: String): AiExerciseResponse {
        val prompt = """
            Create a fill-in-the-blank exercise for the English word "$word".
            Write one natural English sentence where "$word" is replaced with "_____".
            Respond ONLY in this exact JSON format with no extra text:
            {"sentence": "...", "answer": "$word"}
        """.trimIndent()
        val raw = callAi(prompt, maxTokens = 150, temperature = 0.5)
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            lenientJson.decodeFromString<AiExerciseResponse>(cleaned)
        } catch (e: Exception) {
            logger.warn("Failed to parse exercise JSON for '$word': ${e.message}")
            AiExerciseResponse(
                sentence = "Please use _____ in your next English sentence.",
                answer = word
            )
        }
    }

    private suspend fun callAi(
        prompt: String,
        maxTokens: Int = 400,
        temperature: Double = 0.7
    ): String {
        if (aiDomen.isEmpty() || aiApiKey.isEmpty()) {
            logger.error("AI service not configured: AI_DOMEN or AI_API missing")
            throw IllegalStateException("AI service not configured")
        }

        // First attempt uses the configured dialect; a 400 that names the offending field
        // flips the process-wide flags and we retry once. This keeps a provider swap from
        // taking every AI feature down until someone edits the env.
        repeat(2) { attempt ->
            val started = System.currentTimeMillis()
            val response = httpClient.post("$aiDomen/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $aiApiKey")
                setBody(buildRequestBody(prompt, maxTokens, temperature))
            }

            if (response.status.value == 400 && attempt == 0) {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                if (AiCompat.adaptTo(body, logger)) return@repeat   // flags changed → retry
                throw IllegalStateException("AI provider rejected the request: $body")
            }

            val chatResponse = response.body<ChatResponse>()
            val choice = chatResponse.choices.firstOrNull()
            val content = choice?.message?.content?.trim().orEmpty()
            val usage = chatResponse.usage

            logger.info(
                "AI call model={} in={} out={} ms={} finish={}",
                model, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0,
                System.currentTimeMillis() - started, choice?.finishReason
            )

            if (content.isEmpty()) {
                // Reasoning models spend the completion budget on hidden reasoning tokens;
                // an empty body with finish_reason=length means the budget was too small.
                throw IllegalStateException(
                    "Empty AI response (finish_reason=${choice?.finishReason}, " +
                        "completion_tokens=${usage?.completionTokens ?: 0})"
                )
            }
            return content
        }
        throw IllegalStateException("AI request failed after dialect adaptation")
    }

    /**
     * The chat-completions body is assembled dynamically: the token-limit field name and
     * whether `temperature` is accepted both vary by provider and model.
     */
    private fun buildRequestBody(prompt: String, maxTokens: Int, temperature: Double): JsonObject =
        buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            put(AiCompat.tokenParam, AiCompat.effectiveMaxTokens(maxTokens))
            if (AiCompat.supportsTemperature) put("temperature", temperature)
        }

    fun close() {
        httpClient.close()
    }
}
