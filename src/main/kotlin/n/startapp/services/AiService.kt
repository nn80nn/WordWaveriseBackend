package n.startapp.services

import kotlinx.serialization.json.Json
import n.startapp.models.ai.AiExerciseResponse
import n.startapp.models.ai.AiTextResponse
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.ResponseFormat
import org.slf4j.LoggerFactory

/**
 * Thin façade over [LlmClient] for the user-facing AI endpoints under `/api/ai`.
 *
 * All transport concerns (provider dialect, retries, token accounting) live in the client;
 * this class only owns prompts.
 */
class AiService(private val llm: LlmClient) {
    private val logger = LoggerFactory.getLogger(AiService::class.java)
    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    suspend fun explain(word: String): AiTextResponse {
        val result = llm.complete(
            LlmRequest(
                task = "explain",
                user = """
                    Explain the English word "$word" in simple, clear English.
                    Include: 1) A concise definition, 2) When/how to use it, 3) One memory tip.
                    Keep it under 120 words. Be direct and helpful.
                """.trimIndent(),
                maxTokens = 400,
                temperature = 0.7
            )
        )
        return AiTextResponse(result = result.content)
    }

    suspend fun generateExamples(word: String): AiTextResponse {
        val result = llm.complete(
            LlmRequest(
                task = "examples",
                user = """
                    Write 3 natural, varied example sentences using the English word "$word".
                    Each sentence should show different contexts.
                    Format: just the sentences, one per line, no numbers or bullets.
                """.trimIndent(),
                maxTokens = 400,
                temperature = 0.7
            )
        )
        return AiTextResponse(result = result.content)
    }

    /**
     * Translates an English word to Russian.
     *
     * Deliberately context-poor: it only knows the word and its part of speech, which is why
     * translations produced this way read badly. The lexical annotation layer supersedes it —
     * this remains only as the fallback path for entries that have not been annotated yet.
     */
    suspend fun translateToRussian(word: String, posHint: String? = null): String? {
        val context = if (posHint != null) "$word ($posHint)" else word
        return try {
            llm.complete(
                LlmRequest(
                    task = "translate",
                    user = "Translate the English word/phrase \"$context\" to Russian. " +
                        "Return ONLY 1-3 Russian words, no punctuation, no explanation.",
                    maxTokens = 32,
                    temperature = 0.1,
                    maxRetries = 0
                )
            ).content
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
        val result = llm.complete(
            LlmRequest(
                task = "summary",
                user = """
                    Give a quick, helpful 2-sentence English explanation of the word "$word".
                    Then on a new line add: "RU: <brief Russian translation 1-3 words>".
                    Be concise and practical.
                """.trimIndent(),
                maxTokens = 200,
                temperature = 0.5
            )
        )
        return AiTextResponse(result = result.content)
    }

    suspend fun generateExercise(word: String): AiExerciseResponse {
        val raw = llm.complete(
            LlmRequest(
                task = "exercise",
                user = """
                    Create a fill-in-the-blank exercise for the English word "$word".
                    Write one natural English sentence where "$word" is replaced with "_____".
                    Respond ONLY in this exact JSON format with no extra text:
                    {"sentence": "...", "answer": "$word"}
                """.trimIndent(),
                maxTokens = 200,
                temperature = 0.5,
                responseFormat = ResponseFormat.JsonObject
            )
        ).content

        return try {
            lenientJson.decodeFromString<AiExerciseResponse>(LlmJson.extract(raw))
        } catch (e: Exception) {
            logger.warn("Failed to parse exercise JSON for '$word': ${e.message}")
            AiExerciseResponse(
                sentence = "Please use _____ in your next English sentence.",
                answer = word
            )
        }
    }
}
