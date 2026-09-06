package n.startapp.services

import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import n.startapp.models.dictionary.DataMuseWord
import n.startapp.models.dictionary.SuggestItem
import n.startapp.models.dictionary.SuggestResponse
import n.startapp.models.dictionary.TranslationApiResponse
import n.startapp.models.lexical.LexicalEntry
import n.startapp.repositories.CorpusHeadword
import n.startapp.repositories.LexicalEntryRepository
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Что показать под строкой поиска, пока человек печатает.
 *
 * Три источника, в порядке убывания пользы:
 *  - **корпус** — слова, чьи статьи уже написаны. Открываются мгновенно, и к ним есть русский
 *    перевод и часть речи, то есть список становится выбором, а не шестью догадками;
 *  - **автодополнение DataMuse** (`sp={word}*`) — написания, которых в корпусе ещё нет;
 *  - **исправление опечаток** (`sp={word}`) — отдельной группой и только тогда, когда первые
 *    два ничего не дали или запрос уже искали и не нашли.
 *
 * ⚠️ Раньше исправление опечаток было единственным английским путём при вводе: веб звал
 * `/suggest` без `prefix`, и на «resol» словарь отвечал похоже написанными словами, среди
 * которых `resolve` мог и не оказаться вовсе. Подсказка при вводе и подсказка после
 * «не найдено» — разные вопросы, и отвечать на первый вторым значит показывать шум.
 *
 * Русский ввод отвечает объяснёнными вариантами (`RuEnTranslationService`) — как и раньше.
 */
class SuggestService(
    /** When present, Russian input is answered with explained options instead of bare words. */
    private val ruEnTranslationService: n.startapp.services.query.RuEnTranslationService? = null,
    /** Корпус: и самый быстрый источник подсказок, и единственный, который знает перевод. */
    private val lexicalEntries: LexicalEntryRepository? = null
) {
    private val logger = LoggerFactory.getLogger(SuggestService::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(Logging) { logger = Logger.DEFAULT; level = LogLevel.NONE }
        install(HttpTimeout) {
            // ⚠️ Короче общего таймаута словарей в разы: подсказка, приехавшая через восемь
            // секунд, приезжает под курсор, стоящий уже на другом слове. Опоздавшую лучше
            // потерять — корпус к этому моменту свою часть списка уже отдал.
            requestTimeoutMillis = 2_500
            connectTimeoutMillis = 2_500
            socketTimeoutMillis = 2_500
        }
    }

    /**
     * Печать — это N запросов на одно слово: «r», «re», «res»… и «res» повторится у каждого,
     * кто ищет `research`. Ответ подсказки не персональный, поэтому кэшируется целиком.
     */
    private val cache = Caffeine.newBuilder()
        .maximumSize(4_000)
        .expireAfterWrite(Duration.ofMinutes(15))
        .build<String, SuggestResponse>()

    /**
     * @param prefix ввод продолжается (автодополнение). `false` — запрос уже искали и не нашли,
     *   поэтому исправления опечаток нужны всегда, а не только когда автодополнение пусто.
     */
    suspend fun getSuggestions(query: String, prefix: Boolean = false): SuggestResponse {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SuggestResponse(query = trimmed, lang = "en")

        val isRussian = trimmed.any { it in 'Ѐ'..'ӿ' }
        val key = "${if (isRussian) "ru" else "en"}|$prefix|${trimmed.lowercase()}"
        cache.getIfPresent(key)?.let { return it }

        val response = if (isRussian) russian(trimmed) else english(trimmed, prefix)
        // ⚠️ Пустой ответ не кэшируется: чаще всего он означает, что внешний источник не
        // ответил за 2.5 с, и запомнить это значило бы оставить слово без подсказок на
        // четверть часа из-за одной медленной минуты.
        if (response.items.isNotEmpty()) cache.put(key, response)
        return response
    }

    // ── English ──────────────────────────────────────────────────────────────

    private suspend fun english(query: String, prefix: Boolean): SuggestResponse = coroutineScope {
        val needle = query.lowercase()

        // Корпус и DataMuse спрашиваются разом: сеть медленнее базы, и складывать их задержки
        // на каждой букве значит показывать список тогда, когда он уже не нужен.
        val corpusDeferred = async { corpusItems(needle) }
        val autocompleteDeferred = async { fetchPrefixSuggestions(needle) }

        val corpus = corpusDeferred.await()
        val known = corpus.map { it.word }.toMutableSet()

        val autocomplete = autocompleteDeferred.await()
            // ⚠️ Строка, дословно повторяющая запрос, из автодополнения выбрасывается: нажать
            // на неё — то же самое, что нажать «искать», и первую строку списка она занимает
            // зря. Хуже того, DataMuse принимает по `sp=` и несуществующие написания, поэтому
            // «recieve» подтверждал сам себя над группой «возможно, вы имели в виду». Слово из
            // корпуса это не касается: там точное совпадение значит «статья есть», и рядом с
            // ним стоит перевод.
            .filter { it != needle && known.add(it) }
            .map { SuggestItem(word = it, kind = SuggestItem.KIND_AUTOCOMPLETE) }

        // Опечатки — отдельный вопрос, и задаётся он только когда первые два источника не
        // справились. Иначе «res» получал бы «rest», «red» и «yes» рядом с `research`.
        val needsSpelling = !prefix || corpus.size + autocomplete.size < MIN_BEFORE_SPELLING
        val spelling = if (needsSpelling) {
            fetchSpellingSuggestions(needle)
                .filter { known.add(it) }
                .map { SuggestItem(word = it, kind = SuggestItem.KIND_SPELLING) }
        } else emptyList()

        val items = (corpus + autocomplete + spelling).take(MAX_ITEMS)
        SuggestResponse(
            query = query,
            lang = "en",
            suggestions = items.map { it.word },
            items = items
        )
    }

    /**
     * Слова корпуса с их русской стороной.
     *
     * Точное совпадение идёт первым всегда: человек, напечатавший слово целиком, ищет его, а не
     * то, что с него начинается. Дальше — как отранжировала база (по числу открытий), при
     * равенстве короткое выше: у автодополнения короткий вариант почти всегда и есть тот, ради
     * которого начали печатать.
     */
    private suspend fun corpusItems(needle: String): List<SuggestItem> {
        val repository = lexicalEntries ?: return emptyList()
        val headwords = runCatching { repository.headwordsByPrefix(needle, limit = CORPUS_LIMIT) }
            .onFailure { logger.debug("Corpus suggestions failed for '{}': {}", needle, it.message) }
            .getOrNull()
            ?.sortedWith(
                compareByDescending<CorpusHeadword> { it.lemma == needle }
                    .thenByDescending { it.hits }
                    .thenBy { it.lemma.length }
                    .thenBy { it.lemma }
            )
            ?: return emptyList()
        if (headwords.isEmpty()) return emptyList()

        val entries = runCatching { repository.findLatestByLemmas(headwords.map { it.lemma }) }
            .getOrNull()
            .orEmpty()

        return headwords.map { headword ->
            val entry = entries[headword.lemma]
            SuggestItem(
                word = headword.lemma,
                translation = entry?.let(::translationOf),
                partOfSpeech = entry?.posGroups?.firstOrNull()?.posRu?.takeIf { it.isNotBlank() },
                kind = SuggestItem.KIND_CORPUS,
                inCorpus = true
            )
        }
    }

    /**
     * Русская сторона слова — эквиваленты первого значения.
     *
     * Именно первого: словари ставят самое частое значение первым, и это единственная
     * информация о важности, которая у нас есть. Собрать переводы со всех значений значило бы
     * выдать под словом строку, в которой нет ни одного цельного смысла.
     */
    private fun translationOf(entry: LexicalEntry): String? =
        entry.posGroups.firstOrNull()?.senses?.firstOrNull()
            ?.translationsRu
            ?.take(2)
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }

    // ── Russian ──────────────────────────────────────────────────────────────

    private suspend fun russian(trimmed: String): SuggestResponse {
        // The rich shape is authoritative; the string list is derived from it so clients built
        // against the old contract keep working and get better strings.
        val explained = ruEnTranslationService?.translate(trimmed)
        if (explained != null && explained.candidates.isNotEmpty() && !explained.degraded) {
            return SuggestResponse(
                query = trimmed,
                lang = "ru",
                suggestions = explained.candidates.map { it.en },
                items = explained.candidates.map { candidate ->
                    SuggestItem(
                        word = candidate.en,
                        translation = candidate.ruGloss.takeIf { it.isNotBlank() },
                        partOfSpeech = candidate.pos.takeIf { it.isNotBlank() },
                        kind = SuggestItem.KIND_TRANSLATION
                    )
                },
                candidates = explained.candidates
            )
        }
        val fallback = translateRuToEnMultiple(trimmed)
        return SuggestResponse(
            query = trimmed,
            lang = "ru",
            suggestions = fallback,
            items = fallback.map { SuggestItem(word = it, kind = SuggestItem.KIND_TRANSLATION) }
        )
    }

    /**
     * Degraded Russian → English path, used only when the model is unavailable.
     *
     * Machine translation only. The DataMuse "means like" expansion this used to apply is gone:
     * it took semantic neighbours of an already-lossy translation, which is what produced the
     * pile of unexplained near-synonyms rather than a set of real options.
     */
    private suspend fun translateRuToEnMultiple(text: String): List<String> {
        // Step 1: get translation from MyMemory
        val translated = translateRuToEnRaw(text) ?: return emptyList()

        // Step 2: split multiple variants (MyMemory sometimes returns "word1 / word2, word3")
        val primaryCandidates = translated
            .split(Regex("[/,;|]"))
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.length > 1 && it.all { c -> c.isLetter() || c == ' ' } }
            .distinct()
            .take(4)

        val result = primaryCandidates
            .filter { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
            .take(6)

        logger.debug("RU suggestions for '{}' (degraded path): {}", text, result)
        return result
    }

    private suspend fun translateRuToEnRaw(text: String): String? {
        return try {
            val response = httpClient.get("https://api.mymemory.translated.net/get") {
                parameter("q", text)
                parameter("langpair", "ru|en")
            }
            if (response.status == HttpStatusCode.OK) {
                val body = response.body<TranslationApiResponse>()
                val translated = body.responseData.translatedText.trim()
                if (translated.isNotBlank() && !translated.equals(text, ignoreCase = true))
                    translated.lowercase()
                else null
            } else null
        } catch (e: Exception) {
            logger.debug("Translation failed for '{}': {}", text, e.message)
            null
        }
    }

    /** DataMuse prefix autocomplete using wildcard sp={word}* */
    private suspend fun fetchPrefixSuggestions(word: String): List<String> {
        return try {
            val response = httpClient.get("https://api.datamuse.com/words") {
                parameter("sp", "$word*")
                parameter("max", 12)
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            response.body<List<DataMuseWord>>()
                .map { it.word }
                .filter { it.lowercase().startsWith(word.lowercase()) }
                .take(8)
        } catch (e: Exception) {
            logger.debug("Prefix suggestions failed for '{}': {}", word, e.message)
            emptyList()
        }
    }

    private suspend fun fetchSpellingSuggestions(word: String): List<String> {
        return try {
            val response = httpClient.get("https://api.datamuse.com/words") {
                parameter("sp", word)
                parameter("max", 8)
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            response.body<List<DataMuseWord>>()
                .map { it.word }
                .filter { it.lowercase() != word.lowercase() }
                .take(5)
        } catch (e: Exception) {
            logger.debug("Spelling suggestions failed for '{}': {}", word, e.message)
            emptyList()
        }
    }

    fun close() = httpClient.close()

    private companion object {
        /** Сколько строк корпуса имеет смысл показать: список длиннее не читают, а проматывают. */
        const val CORPUS_LIMIT = 6
        const val MAX_ITEMS = 8
        /** Ниже этого числа список выглядит пустым, и догадка про опечатку лучше пустоты. */
        const val MIN_BEFORE_SPELLING = 3
    }
}
