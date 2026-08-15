package n.startapp.services.query

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import n.startapp.models.query.QueryAlternative
import n.startapp.models.query.QueryKind
import n.startapp.models.query.ResolvedQuery
import n.startapp.repositories.LlmCacheRepository
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmModelTier
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.ResponseFormat
import org.slf4j.LoggerFactory
import java.text.Normalizer

/** What the model is asked to return when the deterministic rungs all miss. */
@Serializable
private data class ResolveDraft(
    val kind: String = "UNKNOWN",
    val lemma: String? = null,
    val alternatives: List<String> = emptyList(),
    val confidence: Double = 0.5
)

/**
 * Classifies the search box input before any lookup happens.
 *
 * Ordered so the model is the last resort rather than the mechanism: script detection, token
 * counting, the local corpus, a spelling oracle and regular-morphology guessing between them
 * cover the overwhelming majority of inputs at zero marginal cost. The point of the ladder is
 * that a typo becomes a corrected result instead of an error, and that a pasted sentence is
 * never sent to the dictionary as a headword.
 */
class QueryResolver(
    private val oracle: WordOracle? = null,
    /** Does the annotated corpus already know this surface form? */
    private val knownForm: (suspend (String) -> String?)? = null,
    private val llm: LlmClient? = null,
    private val llmCache: LlmCacheRepository? = null
) {
    private val logger = LoggerFactory.getLogger(QueryResolver::class.java)
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val MAX_PHRASE_TOKENS = 5
    private val SENTENCE_TOKEN_THRESHOLD = 6
    private val MAX_EDIT_DISTANCE = 2

    /** How much more common a candidate must be before it may override what the user typed. */
    private val CORRECTION_FREQUENCY_RATIO = 50.0

    /** Occurrences per million above which a word is too common to be anyone's typo. */
    private val COMMON_ENOUGH_TO_TRUST = 1.0

    companion object {
        const val PROMPT_VERSION_RESOLVE = 1

        private val SYSTEM = """
            Ты — модуль нормализации поискового запроса в англо-русском словаре.
            На вход — то, что пользователь ввёл в строку поиска. Определи, что это, и верни JSON.

            kind:
              WORD        — существующее английское слово в словарной форме
              INFLECTION  — словоформа английского слова (running, geese, better) → lemma = словарная форма
              MISSPELLING — опечатка в английском слове → lemma = правильное написание
              PHRASE      — устойчивое сочетание или идиома из 2–5 слов
              SENTENCE    — предложение или произвольный текст
              RU_WORD / RU_PHRASE / RU_SENTENCE — ввод на русском
              UNKNOWN     — не удалось опознать

            Правила:
            - lemma — то, что имеет смысл искать в словаре. Для WORD это сам запрос.
            - Не «исправляй» реально существующие слова. bare, bear, bore — все существуют.
            - Если правдоподобных вариантов несколько, положи их в alternatives по убыванию.
            - confidence: 0.0–1.0.

            Формат ответа:
            {"kind": "...", "lemma": "строка или null", "alternatives": ["строка", ...], "confidence": 0.0}

            Только JSON, без пояснений.
        """.trimIndent()
    }

    suspend fun resolve(rawInput: String): ResolvedQuery {
        val hadTerminalPunctuation = rawInput.trimEnd().lastOrNull() in setOf('.', '!', '?')
        val normalized = normalize(rawInput)

        if (normalized.isBlank()) {
            return base(rawInput, "", "unknown", QueryKind.UNKNOWN, null)
        }

        val tokens = normalized.split(' ').filter { it.isNotBlank() }

        // 1. Cyrillic goes to the Russian pipeline — unless it is Latin typed in a Russian layout.
        if (normalized.any { it in 'Ѐ'..'ӿ' }) {
            KeyboardLayout.cyrillicToLatin(normalized)?.let { latin ->
                if (oracle?.exists(latin) == true) {
                    return base(rawInput, latin, "en", QueryKind.MISSPELLING, latin).copy(
                        correctionApplied = true,
                        correctedFrom = normalized,
                        resolvedBy = "layout"
                    )
                }
            }
            val kind = when {
                tokens.size == 1 -> QueryKind.RU_WORD
                tokens.size <= 4 -> QueryKind.RU_PHRASE
                else -> QueryKind.RU_SENTENCE
            }
            return base(rawInput, normalized, "ru", kind, normalized)
        }

        // 2. Prose is never a headword.
        val looksLikeProse = tokens.size >= SENTENCE_TOKEN_THRESHOLD ||
            (tokens.size >= 4 && hadTerminalPunctuation) ||
            rawInput.contains(", ")
        if (looksLikeProse) return base(rawInput, normalized, "en", QueryKind.SENTENCE, null)

        if (tokens.size > 1) {
            return base(
                rawInput, normalized, "en",
                if (tokens.size <= MAX_PHRASE_TOKENS) QueryKind.PHRASE else QueryKind.SENTENCE,
                normalized
            )
        }

        // 3. Already annotated — either as a lemma or as one of its recorded forms.
        knownForm?.invoke(normalized)?.let { lemma ->
            val kind = if (lemma.equals(normalized, true)) QueryKind.WORD else QueryKind.INFLECTION
            return base(rawInput, normalized, "en", kind, lemma).copy(resolvedBy = "cache")
        }

        // 4a. A common word as typed. Rare ones deliberately carry on down the ladder.
        //
        // Frequency cannot tell a rare word from a misspelling, because the two populations
        // overlap: "teh" (0.40 per million) is commoner than "intertwine" (0.17), "occured"
        // (0.42) commoner than "serendipity" (0.29). No threshold separates them, so returning
        // on mere existence has to be wrong in one direction or the other — either every typo
        // becomes a headword or every rare word gets hijacked by its commoner neighbour.
        //
        // So only words too common to be worth a second thought stop here, skipping the search
        // for alternatives entirely. A rarer word still comes back as itself below — it just
        // pays for the neighbour to be looked up and carried along as a fallback. Unknown
        // frequency counts as trustworthy: an oracle that cannot measure is no grounds for
        // second-guessing the user.
        val existsAsTyped = oracle?.exists(normalized) == true
        val typedFrequency = if (existsAsTyped) oracle?.frequency(normalized) else null
        if (existsAsTyped && (typedFrequency == null || typedFrequency >= COMMON_ENOUGH_TO_TRUST)) {
            return base(rawInput, normalized, "en", QueryKind.WORD, normalized).copy(resolvedBy = "datamuse")
        }

        // 4b-4d. The deterministic alternatives to what was typed, best first.
        val alternative = findAlternative(normalized, typedFrequency)

        // Whatever the ladder found only *replaces* the query when the query is not a word.
        //
        // Overriding a real word is the one failure the user can neither see nor undo: they
        // typed "missive", got the article for "massive", and nothing on the screen is the word
        // they asked about. It happened because the frequency margin at 4d is the only guard,
        // and a rare word beside a common one clears it by accident — "massive" (18.2 per
        // million) is 66x "missive" (0.27), over the 50x bar meant to catch typos.
        //
        // So a word that exists keeps its own article, and the neighbour is demoted to a
        // fallback: LookupService swaps it in only after every dictionary has come back empty.
        // That check is the thing frequency could never be — it asks whether the string has an
        // entry, not merely whether some corpus has seen it. "teh" is still corrected, not
        // because it is rarer than "the" but because no dictionary has heard of it.
        //
        // This is also what keeps the whole B2/C1 band off the model: rare words end here by
        // design, and an LLM call for each would be the cost of the rung above being honest.
        if (existsAsTyped) {
            return base(rawInput, normalized, "en", QueryKind.WORD, normalized).copy(
                fallback = alternative?.let { QueryAlternative(it.lemma, it.kind) },
                alternatives = alternative?.others.orEmpty().map { QueryAlternative(it) },
                resolvedBy = "datamuse"
            )
        }

        if (alternative != null) {
            val corrected = alternative.kind == QueryKind.MISSPELLING
            return base(rawInput, normalized, "en", alternative.kind, alternative.lemma).copy(
                correctionApplied = corrected,
                correctedFrom = if (corrected) normalized else null,
                alternatives = alternative.others.map { QueryAlternative(it) },
                resolvedBy = alternative.resolvedBy
            )
        }

        // 4e. Latin typed in an English layout when Russian was meant. Last before the model,
        // because reinterpreting letters is a far bigger leap than fixing two of them.
        if (looksLikeWrongLayout(normalized)) {
            KeyboardLayout.latinToCyrillic(normalized)?.takeIf { looksLikeRussian(it) }?.let { cyrillic ->
                return base(rawInput, cyrillic, "ru", QueryKind.RU_WORD, cyrillic).copy(
                    correctionApplied = true,
                    correctedFrom = normalized,
                    resolvedBy = "layout"
                )
            }
        }

        // 5. Everything deterministic missed: proper nouns, neologisms, heavy garbling.
        return resolveWithLlm(rawInput, normalized)
            ?: base(rawInput, normalized, "en", QueryKind.WORD, normalized)
    }

    /** A deterministic reading of the input other than the literal one. */
    private data class Alternative(
        val lemma: String,
        val kind: QueryKind,
        val resolvedBy: String,
        /** Also-rans, worth showing as "did you mean" whichever way the caller decides. */
        val others: List<String> = emptyList()
    )

    /**
     * The best alternative reading of [normalized], or null if nothing plausible is close.
     *
     * Finding one and acting on one are separate decisions on purpose: the same three rungs
     * answer "what did they mean instead" for a string that is not a word, and "what should we
     * try if this word turns out to have no entry" for one that is.
     */
    private suspend fun findAlternative(normalized: String, typedFrequency: Double?): Alternative? {
        // 4b. Regular inflection whose stem is a real word.
        for (candidate in MorphologyHeuristics.candidates(normalized)) {
            val confirmed = knownForm?.invoke(candidate) != null || oracle?.exists(candidate) == true
            if (confirmed) return Alternative(candidate, QueryKind.INFLECTION, "morphology")
        }

        // 4c. Swapped adjacent letters, which the spelling provider cannot reach on its own.
        // Checked before its suggestions because those confidently offer a same-distance
        // substitution ("relieve") for what is really a transposition ("receive").
        for (candidate in MorphologyHeuristics.transpositions(normalized)) {
            val confirmed = knownForm?.invoke(candidate) != null || oracle?.exists(candidate) == true
            if (confirmed && worthOverriding(normalized, typedFrequency, candidate)) {
                return Alternative(candidate, QueryKind.MISSPELLING, "transposition")
            }
        }

        // 4d. Spelling correction from the provider. Ranked by edit distance first: it orders by
        // frequency, which alone would prefer a common word over the one actually meant.
        val suggestions = oracle?.spellingSuggestions(normalized, 8).orEmpty()
        val best = suggestions
            .filter { editDistance(normalized, it) <= MAX_EDIT_DISTANCE }
            .minByOrNull { editDistance(normalized, it) }
        if (best != null && worthOverriding(normalized, typedFrequency, best)) {
            return Alternative(
                best, QueryKind.MISSPELLING, "datamuse",
                others = suggestions.filter { it != best }.take(4)
            )
        }

        return null
    }

    /**
     * Guards the one rung that tells the user they typed the wrong thing.
     *
     * Edit distance says two strings are close; it never says which one was meant. Two failures
     * follow from taking the nearest neighbour as the answer, and each needs its own test:
     *
     *  - the neighbour is the typed word plus a regular ending. "intertwine" → "intertwined" is
     *    not a correction at all, it is inflection pointing backwards: the user typed the lemma
     *    and got sent to its own past participle, which then reads as a verb wearing "-ed";
     *  - the neighbour is merely more common. Real corrections are buried by their target by
     *    three orders of magnitude (receive 47.9 vs recieve 0.033, necessary 155.8 vs neccessary
     *    0.036), while a rare word beside a common one is not (intertwined 1.96 vs intertwine
     *    0.17). Demanding a wide margin keeps the first and rejects the second.
     *
     * A null [typedFrequency] means the string is absent from the corpus entirely — that is
     * garbage, so the correction stands.
     */
    private suspend fun worthOverriding(
        typed: String, typedFrequency: Double?, candidate: String
    ): Boolean {
        if (MorphologyHeuristics.candidates(candidate).any { it.equals(typed, true) }) {
            logger.debug("Not correcting '$typed' to its own inflection '$candidate'")
            return false
        }
        if (typedFrequency == null) return true
        val candidateFrequency = oracle?.frequency(candidate) ?: return true
        val wideMargin = candidateFrequency >= typedFrequency * CORRECTION_FREQUENCY_RATIO
        if (!wideMargin) {
            logger.debug(
                "Not correcting '$typed' ({}/M) to '$candidate' ({}/M): margin too narrow",
                typedFrequency, candidateFrequency
            )
        }
        return wideMargin
    }

    private suspend fun resolveWithLlm(rawInput: String, normalized: String): ResolvedQuery? {
        val client = llm ?: return null
        val cacheKey = LlmCacheRepository.key("resolve", PROMPT_VERSION_RESOLVE, normalized)

        val payload = llmCache?.get(cacheKey) ?: try {
            val result = client.complete(
                LlmRequest(
                    task = "resolve",
                    system = SYSTEM,
                    user = "ЗАПРОС: \"$normalized\"",
                    tier = LlmModelTier.FAST,
                    maxTokens = 300,
                    temperature = 0.1,
                    responseFormat = ResponseFormat.JsonObject,
                    maxRetries = 1
                )
            )
            LlmJson.extract(result.content).also {
                llmCache?.put(cacheKey, "resolve", it, result.model, PROMPT_VERSION_RESOLVE, result.usage)
            }
        } catch (e: Exception) {
            logger.debug("LLM query resolution failed for '$normalized': ${e.message}")
            return null
        }

        val draft = try {
            json.decodeFromString<ResolveDraft>(payload)
        } catch (e: Exception) {
            logger.debug("Unparseable resolution for '$normalized': ${e.message}")
            return null
        }

        val kind = QueryKind.entries.firstOrNull { it.name.equals(draft.kind.trim(), true) }
            ?: return null
        val lemma = draft.lemma?.trim()?.takeIf { it.isNotBlank() }
        val language = if (kind.name.startsWith("RU_")) "ru" else "en"

        return ResolvedQuery(
            raw = rawInput,
            normalized = normalized,
            language = language,
            kind = kind,
            lemma = if (kind == QueryKind.SENTENCE) null else (lemma ?: normalized),
            surface = normalized,
            correctionApplied = kind == QueryKind.MISSPELLING && lemma != null,
            correctedFrom = if (kind == QueryKind.MISSPELLING) normalized else null,
            alternatives = draft.alternatives.take(4).map { QueryAlternative(it) },
            confidence = draft.confidence,
            resolvedBy = "llm"
        )
    }

    private fun base(
        raw: String, normalized: String, language: String, kind: QueryKind, lemma: String?
    ) = ResolvedQuery(
        raw = raw,
        normalized = normalized,
        language = language,
        kind = kind,
        lemma = lemma,
        surface = normalized
    )

    /**
     * Trims, unifies Unicode, collapses whitespace and strips wrapping quotes plus trailing
     * sentence punctuation, so `"Running."` and `running` resolve to the same lookup.
     */
    fun normalize(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim('"', '\'', '«', '»', '“', '”', ' ')
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()
            .lowercase()

    /**
     * English text has vowels. Latin with none of them is not a word someone meant to type,
     * which is the signal that the layout, rather than the spelling, was wrong.
     */
    private fun looksLikeWrongLayout(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 3 && letters.none { it in "aeiouy" }
    }

    /**
     * A layout conversion is only believable if the result reads like Russian.
     *
     * Counts core vowels only: the iotated ones (я, ю, ё) sit on keys that plain consonants map
     * to, so including them lets any consonant mash clear the bar — "zzxqv" becomes "яячйм",
     * which is 40% vowels by that measure and 0% by this one.
     */
    private fun looksLikeRussian(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return false
        val coreVowels = letters.count { it in "аеиоуы" }
        return coreVowels.toDouble() / letters.length >= 0.2
    }

    /**
     * Damerau-Levenshtein (optimal string alignment): a transposition costs 1, not 2.
     *
     * That single difference decides the most common English typo. Under plain Levenshtein
     * "recieve" is 2 edits from "receive" but only 1 from "relieve", so the speller confidently
     * corrects it to the wrong word. Counting the swapped "ie" as one edit puts both at 1, and
     * the oracle's frequency ordering breaks the tie in favour of the word people actually mean.
     */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Three rolling rows: i-2, i-1 and i. The i-2 row is what makes transpositions visible.
        var twoBack = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, twoBack[j - 2] + 1)
                }
                curr[j] = best
            }
            val rotated = twoBack
            twoBack = prev
            prev = curr
            curr = rotated
        }
        return prev[b.length]
    }
}
