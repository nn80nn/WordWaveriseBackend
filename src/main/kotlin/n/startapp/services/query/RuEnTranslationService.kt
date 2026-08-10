package n.startapp.services.query

import kotlinx.serialization.json.Json
import n.startapp.models.dictionary.RuEnCandidate
import n.startapp.models.dictionary.RuEnCandidates
import n.startapp.repositories.LlmCacheRepository
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.ResponseFormat
import org.slf4j.LoggerFactory

/**
 * Russian → English, answered with options a learner can actually choose between.
 *
 * The old path machine-translated the query and then expanded the result through DataMuse's
 * "means like" — semantic neighbours of an already-lossy translation, i.e. noise on noise, which
 * is exactly why it produced a pile of bare words. That expansion is gone.
 */
class RuEnTranslationService(
    private val llm: LlmClient,
    private val cache: LlmCacheRepository? = null,
    /** Machine-translation fallback used only when the model is unavailable. */
    private val fallback: (suspend (String) -> List<String>)? = null
) {
    private val logger = LoggerFactory.getLogger(RuEnTranslationService::class.java)
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    companion object {
        const val PROMPT_VERSION_RU_EN = 1

        private val SYSTEM = """
            Ты — русско-английский словарь для изучающего английский.

            На вход — русское слово или короткая фраза. Верни 3–6 английских вариантов,
            чтобы человек мог осознанно выбрать нужный, а не гадать.

            Для каждого варианта:
              en           — английское слово или выражение
              pos          — часть речи
              ruGloss      — какое именно значение русского слова покрывает этот вариант
              whenToUse    — по-русски, когда употребляется именно он и чем отличается
                             от соседних вариантов; будь конкретен
              example      — естественное английское предложение с этим вариантом
              exampleRu    — его перевод
              cefr         — A1..C2 или null
              register     — neutral | formal | informal | slang | vulgar | dated | literary | technical

            Правила:
            - Сначала самый частотный и нейтральный вариант.
            - Если русское слово многозначно или омографично (замок, лук, ключ), поставь
              isAmbiguous: true, а в note объясни разницу; варианты должны покрывать ВСЕ
              основные значения, а не только первое.
            - Не давай синонимы ради количества. Каждый вариант должен отличаться
              значением, стилем или сочетаемостью.

            Формат ответа — строго такой JSON:
            {
              "isAmbiguous": true | false,
              "note": "строка или null",
              "candidates": [
                {"en":"...","pos":"...","ruGloss":"...","whenToUse":"...",
                 "example":"...","exampleRu":"...","cefr":"B1","register":"neutral"}
              ]
            }

            Только JSON, без пояснений.
        """.trimIndent()
    }

    suspend fun translate(query: String): RuEnCandidates {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return RuEnCandidates(query = query)

        val cacheKey = LlmCacheRepository.key("ru_en", PROMPT_VERSION_RU_EN, normalized)

        val payload = cache?.get(cacheKey) ?: try {
            val result = llm.complete(
                LlmRequest(
                    task = "ru_en",
                    system = SYSTEM,
                    user = "ЗАПРОС: \"$normalized\"",
                    maxTokens = 1500,
                    temperature = 0.3,
                    responseFormat = ResponseFormat.JsonObject,
                    maxRetries = 2
                )
            )
            LlmJson.extract(result.content).also {
                cache?.put(cacheKey, "ru_en", it, result.model, PROMPT_VERSION_RU_EN, result.usage)
            }
        } catch (e: Exception) {
            logger.warn("RU→EN translation failed for '$normalized': ${e.message}")
            return degradedResult(normalized)
        }

        return try {
            val draft = json.decodeFromString<Draft>(payload)
            val candidates = draft.candidates
                .filter { it.en.isNotBlank() }
                .map { it.toModel() }
                .distinctBy { it.en.lowercase() }
                .take(6)

            if (candidates.isEmpty()) degradedResult(normalized)
            else RuEnCandidates(
                query = normalized,
                isAmbiguous = draft.isAmbiguous,
                candidates = candidates,
                note = draft.note?.trim()?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            logger.warn("Unparseable RU→EN reply for '$normalized': ${e.message}")
            degradedResult(normalized)
        }
    }

    private suspend fun degradedResult(query: String): RuEnCandidates {
        val words = fallback?.let { runCatching { it(query) }.getOrNull() }.orEmpty()
        return RuEnCandidates(
            query = query,
            candidates = words.take(6).map { RuEnCandidate(en = it) },
            note = if (words.isEmpty()) null else "ИИ недоступен — показан машинный перевод",
            degraded = true
        )
    }

    @kotlinx.serialization.Serializable
    private data class Draft(
        val isAmbiguous: Boolean = false,
        val note: String? = null,
        val candidates: List<DraftCandidate> = emptyList()
    )

    @kotlinx.serialization.Serializable
    private data class DraftCandidate(
        val en: String = "",
        val pos: String = "",
        val ruGloss: String = "",
        val whenToUse: String = "",
        val example: String = "",
        val exampleRu: String = "",
        val cefr: String? = null,
        val register: String = "neutral"
    ) {
        fun toModel() = RuEnCandidate(
            en = en.trim(),
            pos = pos.trim(),
            ruGloss = ruGloss.trim(),
            whenToUse = whenToUse.trim(),
            example = example.trim(),
            exampleRu = exampleRu.trim(),
            cefr = cefr?.trim()?.takeIf { it.isNotBlank() },
            register = n.startapp.models.lexical.parseRegister(register)
        )
    }
}
