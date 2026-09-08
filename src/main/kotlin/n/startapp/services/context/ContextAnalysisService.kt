package n.startapp.services.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import n.startapp.exceptions.BadRequestException
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.Sense
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.LlmCacheRepository
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmModelTier
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

/**
 * The fast answer: what the word means here, and nothing else.
 *
 * A reader who taps a word wants to keep reading, and the full analysis takes seconds — long
 * enough that the moment it was asked for has passed. This one is written by the cheap model in a
 * handful of tokens, and everything the corpus can answer is taken from the corpus rather than
 * from the model: the sense, its English definition and the id needed to save it are all matched
 * locally against the stored article.
 */
@Serializable
data class ContextHint(
    val text: String,
    val tokens: List<Token> = emptyList(),
    val target: ContextTarget? = null,
    val lemma: String? = null,
    val pos: String? = null,
    /** Russian for the word as it stands here — one to three words, not a sentence. */
    val translationRu: String? = null,
    val senseId: String? = null,
    val senseMatched: Boolean = false,
    /** From the corpus, when a sense matched: free, exact, and not the model's to invent. */
    val senseDefinitionEn: String? = null,

    /**
     * The sense's other Russian equivalents — «вести, провожать, направлять».
     *
     * One word often does not fit the sentence the reader is looking at, and the neighbours are
     * what make the meaning land. Comes from the corpus, so it costs nothing; the first item is
     * dropped when it repeats [translationRu], which the model already answered.
     */
    val translationsRu: List<String> = emptyList(),

    /** Пометы значения — все из корпуса, ни одна не спрашивается у модели. */
    val cefr: String? = null,
    val register: String? = null,
    val countability: String? = null,

    val entryAvailable: Boolean = false
)

@Serializable
private data class ContextHintDraft(
    val lemma: String? = null,
    val pos: String? = null,
    val translationRu: String? = null
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
        const val PROMPT_VERSION_CONTEXT = 2
        const val PROMPT_VERSION_HINT = 2

        /** Word overlap above this counts as the same sense. */
        private const val SENSE_MATCH_THRESHOLD = 0.35

        /** How far ahead of the runner-up a sense must be to count as the one that was meant. */
        private const val SENSE_MATCH_MARGIN = 0.2

        /**
         * Сколько букв русского слова считается его началом.
         *
         * Пять — это «лошад» от «лошади» и «свинц» от «свинцовый»: достаточно, чтобы форма
         * узнала свою словарную запись, и коротко настолько, чтобы окончание не мешало.
         */
        private const val STEM_LENGTH = 5

        /**
         * Сколько соседних переводов показать рядом с основным.
         *
         * Три — потому что подсказка обязана читаться одним взглядом: весь ряд из статьи
         * превращает её в статью, а её и так можно открыть одним нажатием.
         */
        private const val MAX_NEIGHBOUR_TRANSLATIONS = 3

        /**
         * Deliberately small. Every field the corpus can answer is left out of the reply: the
         * model is asked for the three things only it knows, and for nothing that would cost it
         * another second to write.
         */
        private val HINT_SYSTEM = """
            Ты помогаешь русскоязычному читателю понять одно слово в предложении.

            Ответь ТОЛЬКО таким JSON, без пояснений:
            {"lemma":"...","pos":"...","translationRu":"..."}

            - lemma: словарная форма выделенного слова. Если это фразовый глагол или устойчивое
              сочетание (gave up, look forward to) — всё сочетание целиком.
            - pos: часть речи ИМЕННО в этом предложении. Ровно одно слово из списка:
              noun, verb, adjective, adverb, pronoun, preposition, conjunction,
              determiner, numeral, interjection, phrase, idiom, phrasal verb.
            - translationRu: перевод выделенного слова в этом предложении, 1–3 слова,
              в нужной грамматической форме.
        """.trimIndent()

        private val SYSTEM = """
            Ты помогаешь русскоязычному человеку понять конкретное слово в конкретном предложении.

            Тебе дают предложение и одно выделенное в нём слово. Определи:
            - lemma: словарная форма выделенного слова
            - pos: часть речи ИМЕННО в этом предложении. Ровно одно слово из списка:
              noun, verb, adjective, adverb, pronoun, preposition, conjunction,
              determiner, numeral, interjection, phrase, idiom, phrasal verb.
              Без уточнений про время, залог и форму — только сама часть речи.
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

    /**
     * The hint: lemma, part of speech, translation — plus whatever the corpus adds for free.
     *
     * ⚠️ One attempt, no retries, on the cheap model. A hint that arrives late is not a hint; if
     * the provider is busy the reader is left with the full analysis, which the client offers as
     * a deliberate second step rather than as a wait nobody asked for.
     */
    suspend fun hint(text: String, tokenIndex: Int?, token: String?): ContextHint {
        if (text.isBlank()) throw BadRequestException("Field 'text' cannot be empty")

        val tokenized = Tokenizer.tokenize(text)
        val target = resolveTarget(tokenized, tokenIndex, token)
            ?: throw BadRequestException("Could not locate the requested token in the text")
        val surface = expandToUnit(tokenized, target)

        val cacheKey = LlmCacheRepository.key(
            "context_hint", PROMPT_VERSION_HINT, "${text.trim()}#${target.index}"
        )

        val payload = cache?.get(cacheKey) ?: try {
            val result = llm.complete(
                LlmRequest(
                    task = "context_hint",
                    system = HINT_SYSTEM,
                    user = "ПРЕДЛОЖЕНИЕ: $text\nВЫДЕЛЕННОЕ СЛОВО: \"$surface\"",
                    tier = LlmModelTier.DRAFT,
                    maxTokens = 120,
                    temperature = 0.1,
                    responseFormat = ResponseFormat.JsonObject,
                    maxRetries = 0
                )
            )
            LlmJson.extract(result.content).also {
                cache?.put(cacheKey, "context_hint", it, result.model, PROMPT_VERSION_HINT, result.usage)
            }
        } catch (e: Exception) {
            logger.warn("Context hint failed for '$surface': ${e.message}")
            return ContextHint(text = text, tokens = tokenized.tokens, target = target)
        }

        val draft = try {
            json.decodeFromString<ContextHintDraft>(payload)
        } catch (e: Exception) {
            logger.warn("Unparseable context hint for '$surface': ${e.message}")
            return ContextHint(text = text, tokens = tokenized.tokens, target = target)
        }

        val lemma = draft.lemma?.trim()?.takeIf { it.isNotBlank() }
        val pos = draft.pos?.trim()?.takeIf { it.isNotBlank() }
        val translation = draft.translationRu?.trim()?.takeIf { it.isNotBlank() }
        val entry = lemma?.let { runCatching { entryRepository.findLatestByLemma(it) }.getOrNull() }

        // ⚠️ Значение подбирается по переводу, а не по английскому определению: определения в
        // этом ответе нет, а просить его — заплатить теми самыми секундами, ради которых всё
        // и затевалось. Русские варианты значения лежат в статье, сравнение с ними бесплатно.
        val sense = entry?.let { matchSenseByTranslation(it, translation, pos) }

        return ContextHint(
            text = text,
            tokens = tokenized.tokens,
            target = target,
            lemma = lemma,
            pos = pos,
            translationRu = translation,
            senseId = sense?.id,
            senseMatched = sense != null,
            senseDefinitionEn = sense?.definitionEn?.takeIf { it.isNotBlank() },
            // ⚠️ Ровно то же слово, что уже стоит заголовком, из ряда выбрасывается: перевод,
            // повторённый под самим собой, читается как ошибка вёрстки, а не как синоним.
            translationsRu = sense?.translationsRu
                .orEmpty()
                .filter { !it.equals(translation, ignoreCase = true) }
                .take(MAX_NEIGHBOUR_TRANSLATIONS),
            cefr = sense?.cefr?.takeIf { it.isNotBlank() },
            register = sense?.register?.name?.takeIf { it != "NEUTRAL" },
            // Свойство значения, а не слова: `paper`-материал неисчисляем, `paper`-документ нет.
            countability = sense?.countability?.name,
            entryAvailable = entry != null
        )
    }

    /**
     * Finds the sense whose Russian equivalents overlap the hint's translation.
     *
     * A miss returns null and the hint is still shown: «что значит здесь» is useful on its own,
     * and pretending it belongs to a particular dictionary sense would put the wrong id on a
     * saved word for ever.
     *
     * ⚠️ A tie is a miss, not a coin toss. One Russian word covers several senses at once —
     * «вести» is `lead` an organisation and `lead` a horse — so the top score alone says nothing
     * about which of them was meant. Picking the first of two equals looks like an answer, reads
     * as an answer, and files the reader's word under a meaning they never chose. The margin
     * below is the whole guard, and lowering it trades a visible "no match" for an invisible
     * wrong one.
     */
    private fun matchSenseByTranslation(entry: LexicalEntry, translation: String?, pos: String?): Sense? {
        val exact = russianWords(translation ?: return null)
        val stems = russianStems(translation)
        if (stems.isEmpty()) return null

        val groups = entry.posGroups
            .filter { pos == null || it.pos.equals(pos.trim(), ignoreCase = true) }
            .ifEmpty { entry.posGroups }

        val scored = groups
            .flatMap { it.senses }
            .mapNotNull { sense ->
                val senseStems = sense.translationsRu.flatMap { russianStems(it) }.toSet()
                if (senseStems.isEmpty()) return@mapNotNull null
                val senseExact = sense.translationsRu.flatMap { russianWords(it) }.toSet()
                Triple(sense, overlap(exact, senseExact), overlap(stems, senseStems))
            }

        /**
         * ⚠️ Целое слово побеждает обрубок, и это не оптимизация.
         *
         * Обрубки сравнивают то, что осталось от разных слов: «лошадей» и «лошадка» дают одно
         * и то же начало, и подсказка к «After the horses came Muriel» приезжала со значением
         * «„лошадка“ — баскетбольная игра» — с пометами, с определением и с видом полной
         * уверенности. Сохранённое из книги слово легло бы в словарь под этим смыслом навсегда.
         * Поэтому сначала ищется совпадение по целым словам, и только если его нет ни у одного
         * значения — по началам слов, где ничья по-прежнему считается промахом.
         */
        val byExact = scored.filter { it.second > 0.0 }
        val ranked = (byExact.ifEmpty { scored })
            .map { it.first to if (byExact.isNotEmpty()) it.second else it.third }
            .sortedByDescending { it.second }

        val best = ranked.firstOrNull()?.takeIf { it.second >= SENSE_MATCH_THRESHOLD } ?: return null
        val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
        if (best.second - runnerUp < SENSE_MATCH_MARGIN) return null
        return best.first
    }

    private fun overlap(words: Set<String>, senseWords: Set<String>): Double {
        if (words.isEmpty() || senseWords.isEmpty()) return 0.0
        return words.intersect(senseWords).size.toDouble() / minOf(words.size, senseWords.size)
    }

    /** Русские слова как они есть — «лошадь» это «лошадь», а не начало чего-то похожего. */
    private fun russianWords(text: String): Set<String> =
        text.lowercase().replace('ё', 'е')
            .split(Regex("[^а-яё]+"))
            .filter { it.length > 2 }
            .toSet()

    /**
     * Russian words with the tail cut off.
     *
     * The hint translates the word **as it stands** — «свинцовая», — while the article stores the
     * dictionary form — «свинцовый». Comparing them literally matches almost nothing, so the
     * ending is dropped: crude, but it is the difference between a sense that matches and one
     * that never does.
     *
     * ⚠️ Отрезается **до одной длины**, а не «по два символа с конца». Прежнее правило делало
     * длину обрубка зависимой от длины слова: «лошадь» давало «лоша», «лошадей» — «лошад», и
     * форма одного и того же слова переставала совпадать сама с собой, зато совпадала с соседним
     * словом той же длины. Ошибка при этом выглядела как уверенный ответ.
     */
    private fun russianStems(text: String): Set<String> =
        russianWords(text).map { it.take(STEM_LENGTH) }.toSet()

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
