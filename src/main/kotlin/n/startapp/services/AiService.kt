package n.startapp.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import n.startapp.models.ai.*
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
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 30000
        }
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

    suspend fun quickSummary(word: String): AiTextResponse {
        val prompt = """
            Give a quick, helpful 2-sentence English explanation of the word "$word".
            Then on a new line add: "RU: <brief Russian translation 1-3 words>".
            Be concise and practical.
        """.trimIndent()
        val result = callAi(prompt, maxTokens = 150, temperature = 0.5)
        return AiTextResponse(result = result)
    }

    suspend fun deduplicateDefinitions(word: String, rawDefs: List<String>): List<String> {
        if (rawDefs.size <= 3) return rawDefs
        val defsText = rawDefs.take(8).mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n")
        val prompt = """
            Word: "$word"
            Raw definitions from multiple sources:
            $defsText

            Select and rewrite the 3 most distinct, useful definitions. Remove duplicates.
            Return ONLY a JSON array of 3 strings: ["def1", "def2", "def3"]
            No extra text.
        """.trimIndent()
        return try {
            val raw = callAi(prompt, maxTokens = 300, temperature = 0.3)
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            lenientJson.decodeFromString<List<String>>(cleaned)
        } catch (e: Exception) {
            logger.warn("deduplicateDefinitions failed for '$word': ${e.message}")
            rawDefs.take(3)
        }
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
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            maxTokens = maxTokens,
            temperature = temperature
        )
        val response = httpClient.post("$aiDomen/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $aiApiKey")
            setBody(request)
        }
        val chatResponse = response.body<ChatResponse>()
        return chatResponse.choices.firstOrNull()?.message?.content?.trim()
            ?: throw IllegalStateException("Empty AI response")
    }

    fun close() {
        httpClient.close()
    }
}
