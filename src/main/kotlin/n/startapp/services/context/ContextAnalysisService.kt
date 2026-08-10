package n.startapp.services.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import n.startapp.exceptions.BadRequestException
import n.startapp.models.lexical.LexicalEntry
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.LlmCacheRepository
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.ResponseFormat
import org.slf4j.LoggerFactory

@Serializable
data class ContextTarget(val index: Int, val surface: String)

@Serializable
data class ContextAnalysis(
    val text: String,
    val tokens: List<Token> = emptyList(),
    val target: ContextTarget? = null,
    val lemma: String? = null,
    /** Part of speech as used in THIS sentence, which is often not the word's usual one. */
    val pos: String? = null,
    /** Sense id within the annotated article, when one could be matched. */
    val senseId: String? = null,
    val senseMatched: Boolean = false,
    val senseDefinitionEn: String? = null,
    /** Russian for the word as it appears here, in the right grammatical form. */
    val translationRu: String? = null,
    /** Russian for the dictionary form. */
    val translationLemmaRu: String? = null,
    val sentenceRu: String? = null,
    /** Why this sense and not another, pointing at words in the sentence. */
    val whyRu: String? = null,
    /** Whether a full article for [lemma] is available to open. */
    val entryAvailable: Boolean = false
)

@Serializable
private data class ContextDraft(
    val lemma: String? = null,
    val pos: String? = null,
    val senseGlossEn: String? = null,
    val translationRu: String? = null,
    val translationLemmaRu: String? = null,
    val sentenceRu: String? = null,
    val whyRu: String? = null
)

/**
 * Explains one word as used in one sentence.
 *
 * The dictionary answers "what can this word mean"; this answers "what does it mean here",
 * which is the question someone reading English actually has. A homograph like "lead" is the
 * test case: the article lists both senses, only the sentence says which one is in play.
 */
class ContextAnalysisService(
    private val llm: LlmClient,
    private val entryRepository: LexicalEntryRepository,
    private val cache: LlmCacheRepository? = null
) {
    private val logger = LoggerFactory.getLogger(ContextAnalysisService::class.java)
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    companion object {
        const val PROMPT_VERSION_CONTEXT = 1

        /** Word overlap above this counts as the same sense. */
        private const val SENSE_MATCH_THRESHOLD = 0.35

        private val SYSTEM = """
            Ты помогаешь русскоязычному человеку понять конкретное слово в конкретном предложении.

            Тебе дают предложение и одно выделенное в нём слово. Определи:
            - lemma: словарная форма выделенного слова
            - pos: часть речи ИМЕННО в этом предложении
            - senseGlossEn: краткое (до 12 слов) английское определение того значения,
              в котором слово употреблено ЗДЕСЬ
            - translationRu: перевод выделенного слова так, как его следует перевести
              в этом предложении, в нужной грамматической форме
            - translationLemmaRu: перевод словарной формы
            - sentenceRu: естественный перевод всего предложения на русский
            - whyRu: 1–2 предложения по-русски о том, почему это именно такое значение —
              опирайся на конкретные слова из предложения

            Правила:
            - Не выдумывай транскрипцию, ссылки и источники.
            - Если слово входит в устойчивое сочетание или фразовый глагол
              (gave up, look forward to), укажи в lemma всё сочетание целиком.
            - Если предложение бессмысленно или слово не опознано — верни lemma: null.

            Формат ответа — строго такой JSON:
            {"lemma":"...","pos":"...","senseGlossEn":"...","translationRu":"...",
             "translationLemmaRu":"...","sentenceRu":"...","whyRu":"..."}

            Только JSON, без пояснений.
        """.trimIndent()
    }

    fun tokenize(text: String): TokenizedText = Tokenizer.tokenize(text)

    suspend fun analyze(text: String, tokenIndex: Int?, token: String?): ContextAnalysis {
        if (text.isBlank()) throw BadRequestException("Field 'text' cannot be empty")

        val tokenized = Tokenizer.tokenize(text)
        val target = resolveTarget(tokenized, tokenIndex, token)
            ?: throw BadRequestException("Could not locate the requested token in the text")

        // The phrasal unit, when there is one, is what should be looked up — "gave up", not "gave".
        val surface = expandToUnit(tokenized, target)

        val cacheKey = LlmCacheRepository.key(
            "context", PROMPT_VERSION_CONTEXT, "${text.trim()}#${target.index}"
        )

        val payload = cache?.get(cacheKey) ?: try {
            val result = llm.complete(
                LlmRequest(
                    task = "context",
                    system = SYSTEM,
                    user = "ПРЕДЛОЖЕНИЕ: $text\nВЫДЕЛЕННОЕ СЛОВО: \"$surface\" (позиция ${target.index})",
                    maxTokens = 900,
                    temperature = 0.2,
                    responseFormat = ResponseFormat.JsonObject,
                    maxRetries = 2
                )
            )
            LlmJson.extract(result.content).also {
                cache?.put(cacheKey, "context", it, result.model, PROMPT_VERSION_CONTEXT, result.usage)
            }
        } catch (e: Exception) {
            logger.warn("Context analysis failed for '$surface': ${e.message}")
            return ContextAnalysis(text = text, tokens = tokenized.tokens, target = target)
        }

        val draft = try {
            json.decodeFromString<ContextDraft>(payload)
        } catch (e: Exception) {
            logger.warn("Unparseable context reply for '$surface': ${e.message}")
            return ContextAnalysis(text = text, tokens = tokenized.tokens, target = target)
        }

        val lemma = draft.lemma?.trim()?.takeIf { it.isNotBlank() }
        val entry = lemma?.let { runCatching { entryRepository.findLatestByLemma(it) }.getOrNull() }
        val senseId = entry?.let { matchSense(it, draft.senseGlossEn, draft.pos) }

        return ContextAnalysis(
            text = text,
            tokens = tokenized.tokens,
            target = target,
            lemma = lemma,
            pos = draft.pos?.trim()?.takeIf { it.isNotBlank() },
            senseId = senseId,
            senseMatched = senseId != null,
            senseDefinitionEn = draft.senseGlossEn?.trim()?.takeIf { it.isNotBlank() },
            translationRu = draft.translationRu?.trim()?.takeIf { it.isNotBlank() },
            translationLemmaRu = draft.translationLemmaRu?.trim()?.takeIf { it.isNotBlank() },
            sentenceRu = draft.sentenceRu?.trim()?.takeIf { it.isNotBlank() },
            whyRu = draft.whyRu?.trim()?.takeIf { it.isNotBlank() },
            entryAvailable = entry != null
        )
    }

    private fun resolveTarget(tokenized: TokenizedText, index: Int?, token: String?): ContextTarget? {
        if (index != null) {
            return tokenized.tokens.firstOrNull { it.index == index && it.tappable }
                ?.let { ContextTarget(it.index, it.text) }
        }
        val needle = token?.trim()?.trim { it in ".,!?;:\"'" }?.lowercase() ?: return null
        return tokenized.tokens
            .firstOrNull { it.tappable && it.text.lowercase() == needle }
            ?.let { ContextTarget(it.index, it.text) }
    }

    /** Includes a grouped particle so a phrasal verb reaches the model as one unit. */
    private fun expandToUnit(tokenized: TokenizedText, target: ContextTarget): String {
        val token = tokenized.tokens.firstOrNull { it.index == target.index } ?: return target.surface
        val partner = token.groupWith.firstOrNull { it > token.index } ?: return target.surface
        val next = tokenized.tokens.firstOrNull { it.index == partner } ?: return target.surface
        return "${token.text} ${next.text}"
    }

    /**
     * Maps the model's short gloss onto a sense of the stored article by word overlap.
     *
     * Deterministic and testable, unlike asking the model to pick an id it has not been shown.
     * A miss is reported honestly rather than guessed at, so the client can still show the
     * context answer without pretending it belongs to a particular dictionary sense.
     */
    private fun matchSense(entry: LexicalEntry, gloss: String?, pos: String?): String? {
        val glossWords = significantWords(gloss ?: return null)
        if (glossWords.isEmpty()) return null

        val groups = entry.posGroups
            .filter { pos == null || it.pos.equals(pos.trim(), ignoreCase = true) }
            .ifEmpty { entry.posGroups }

        var best: Pair<String, Double>? = null
        for (group in groups) {
            for (sense in group.senses) {
                val senseWords = significantWords(sense.definitionEn)
                if (senseWords.isEmpty()) continue
                val overlap = glossWords.intersect(senseWords).size.toDouble()
                val score = overlap / minOf(glossWords.size, senseWords.size)
                if (best == null || score > best!!.second) best = sense.id to score
            }
        }
        return best?.takeIf { it.second >= SENSE_MATCH_THRESHOLD }?.first
    }

    private val STOP_WORDS = setOf(
        "a", "an", "the", "of", "to", "or", "and", "in", "on", "for", "with",
        "that", "this", "is", "are", "be", "as", "by", "at", "from", "it", "its", "something", "someone"
    )

    private fun significantWords(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()
}
