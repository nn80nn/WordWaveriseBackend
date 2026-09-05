package n.startapp.services.dictionary

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import n.startapp.exceptions.NotFoundException
import n.startapp.models.dictionary.DetailedDefinition
import n.startapp.models.dictionary.EntryMeaning
import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.dictionary.SourcedWordData
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.dictionary.WordEntry
import n.startapp.models.scraper.ScrapeEnrichment
import n.startapp.repositories.ScraperCacheRepository
import n.startapp.services.scraper.ScraperService
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Aggregates word data from multiple dictionary APIs + web scrapers in parallel.
 * Applies smart deduplication before returning the final merged response.
 */
class DictionaryAggregationService {
    private val logger = LoggerFactory.getLogger(DictionaryAggregationService::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 10000
        }
    }

    /** All API clients for single-word queries.
     *  Wiktionary is first so it isn't pushed out by the take() limit after scrapers fill up.
     *  WordsAPI needs a paid key; without it every lookup burned a coroutine and a log line
     *  to return null, so it is only wired in when the key is actually present. */
    private val allApiClients: List<DictionaryApiClient> = buildList {
        add(WiktionaryApiClient(httpClient))
        add(FreeDictionaryApiClient(httpClient))
        if (EnvConfig.get("WORDS_API_KEY").isNotBlank()) add(WordsApiClient(httpClient))
        add(DataMuseApiClient(httpClient))
    }

    /** Phrase-safe API clients — only these support multi-word queries reliably */
    private val phraseApiClients: List<DictionaryApiClient> = listOf(
        WiktionaryApiClient(httpClient),
        FreeDictionaryApiClient(httpClient)
    )

    private val scraperService = ScraperService(ScraperCacheRepository())

    /** Beyond this the query is prose, and no dictionary site has a page for it. */
    private val MAX_SCRAPER_TOKENS = 5

    /**
     * How long one API source may take while somebody is watching a spinner.
     *
     * The shared [HttpTimeout] above is a ceiling on a request, not a budget for a screen: with
     * four sources in parallel the first paint costs whatever the slowest one costs, so a single
     * unreachable dictionary set the wait for every cold word. These sources answer in well under
     * a second when they answer at all — Wiktionary and DataMuse in ~0.5s — so anything past this
     * is not a slow reply, it is an absent one.
     */
    private val URGENT_SOURCE_BUDGET_MS = 2_500L

    /**
     * The same for a fetch nobody is waiting on — the aggregate an article is written from.
     *
     * Deliberately generous: a source that is merely slow still belongs in the corpus, and the
     * article is already minutes away. The breaker below is what keeps a *dead* source from
     * spending this budget over and over.
     */
    private val BACKGROUND_SOURCE_BUDGET_MS = 10_000L

    private val health = SourceHealth()

    /**
     * What each source answered for a word, kept just long enough to be reused.
     *
     * A cold lookup aggregates the same word twice within seconds — once quickly, for the
     * response the reader gets immediately, and once in full, for the article — so every API
     * source was asked twice for an answer that could not have changed in between. That is the
     * second half of the ten seconds a dead source used to cost.
     *
     * ⚠️ Only sources that **answered** are remembered, and a null answer counts: "this
     * dictionary has no entry for this word" is knowledge, and re-asking for it is the same
     * waste. A source that timed out is deliberately absent, so the background pass — which has
     * a far bigger budget — gets to try it again rather than inheriting the quick path's verdict.
     */
    private val apiMemo = Caffeine.newBuilder()
        .expireAfterWrite(3, TimeUnit.MINUTES)
        .maximumSize(500)
        .build<String, Map<String, SourcedWordData?>>()

    /**
     * Fetch word data from all API sources + scrapers in parallel and merge results.
     * @param skipScrapers if true, skip web scrapers for faster response (API data only)
     * @param isPhrase if true, restricts to phrase-capable sources and skips scrapers
     * @throws NotFoundException if word not found in any source
     */
    suspend fun aggregateWordData(
        word: String,
        skipScrapers: Boolean = false,
        isPhrase: Boolean = false,
        urgent: Boolean = false
    ): WordDetailResponse = aggregateDetailed(word, skipScrapers, isPhrase, urgent).response

    /**
     * Same fetch, but also returns the material the public response throws away:
     * the un-truncated per-source definition list (the grounding input for LLM annotation)
     * and the POS-tagged pronunciation map (homograph display).
     *
     * @param urgent somebody is waiting on this call for something to appear on screen, so the
     *   API sources get [URGENT_SOURCE_BUDGET_MS] rather than [BACKGROUND_SOURCE_BUDGET_MS].
     *   Affects only the APIs: the scrapers are what a full aggregate is *for*, and the paths
     *   that cannot afford them skip them outright.
     */
    suspend fun aggregateDetailed(
        word: String,
        skipScrapers: Boolean = false,
        isPhrase: Boolean = false,
        urgent: Boolean = false
    ): AggregatedWord {
        val activeClients = if (isPhrase) phraseApiClients else allApiClients

        // Cambridge and Oxford slugify spaces to '-' and do carry idiom entries, so phrases were
        // never the reason to skip them — a blanket skip is why anything longer than one word
        // only ever came back from Wiktionary. Skip only what is too long to be a headword.
        val tokenCount = word.trim().split(Regex("\\s+")).size
        val actuallySkipScrapers = skipScrapers || tokenCount > MAX_SCRAPER_TOKENS

        val memoKey = "${word.trim().lowercase()}|${if (isPhrase) "p" else "w"}"
        val remembered = apiMemo.getIfPresent(memoKey).orEmpty()
        val toFetch = activeClients.filter { it.sourceName !in remembered }
        val budget = if (urgent) URGENT_SOURCE_BUDGET_MS else BACKGROUND_SOURCE_BUDGET_MS

        // ⚠️ When the breaker would leave us with nothing at all, ask anyway. A lookup that comes
        // back empty because we declined to try is indistinguishable from a word that does not
        // exist — and on the phrase path "not found" is exactly what makes the model write an
        // article from nothing, which then lives in the corpus forever. Paying the budget is the
        // cheaper mistake. Note this asks about *what is left to fetch*: a source already
        // answered from the memo counts, so a single dead dictionary never triggers it.
        val lastResort = remembered.isEmpty() &&
            toFetch.isNotEmpty() &&
            toFetch.all { health.isOpen(it.sourceName) }

        val started = System.currentTimeMillis()

        // Run API clients and scrapers in parallel
        val (fetched, scraperResults) = coroutineScope {
            val apis = async { fetchFromApiSources(word, toFetch, budget, ignoreHealth = lastResort) }
            val scrapers = async {
                if (actuallySkipScrapers) return@async emptyList<n.startapp.models.scraper.ScrapeEnrichment>()
                withTimeoutOrNull(12_000) {
                    try { scraperService.enrichWord(word) }
                    catch (e: Exception) {
                        logger.warn("Scraper enrichment failed for '$word': ${e.message}")
                        emptyList()
                    }
                } ?: run {
                    logger.warn("Scrapers timed out for '$word' after 12s")
                    emptyList()
                }
            }
            apis.await() to scrapers.await()
        }

        val answered = remembered + fetched.results
        if (answered.isNotEmpty()) apiMemo.put(memoKey, answered)

        // Client order, not answer order: Wiktionary is deliberately first so the later take()
        // cannot push it out, and a memo hit must not quietly reshuffle that.
        val validApiResults = activeClients.mapNotNull { answered[it.sourceName] }

        // "Found" must mean "somebody had a definition". DataMuse answers 200 with synonyms for
        // plenty of non-words, which used to make a garbage query look like a hit and return a
        // 200 carrying zero definitions.
        val hasRealDefinitions = validApiResults.any { it.definitions.isNotEmpty() } ||
            scraperResults.any { it.senses.isNotEmpty() }

        // One line, and it has to name every source and its cost. A dead upstream is otherwise
        // indistinguishable from a slow model: both look like "the article took a while".
        val reused = remembered.keys.filter { key -> activeClients.any { it.sourceName == key } }
        logger.info(
            "'{}' aggregated in {}ms ({}{}): {} API source(s), {} scraper source(s){}",
            word,
            System.currentTimeMillis() - started,
            if (urgent) "urgent ${budget}ms" else "background ${budget}ms",
            if (actuallySkipScrapers) ", no scrapers" else "",
            validApiResults.size,
            scraperResults.size,
            buildString {
                if (fetched.timings.isNotEmpty()) append(" [").append(fetched.timings.joinToString(" ")).append("]")
                if (reused.isNotEmpty()) append(" reused[").append(reused.joinToString(" ")).append("]")
            }
        )

        if (!hasRealDefinitions) {
            logger.warn("Word '$word' not found in any source (no definition-bearing result)")
            throw NotFoundException("Word '$word' not found in dictionary")
        }

        return mergeResults(word, validApiResults, scraperResults)
    }

    /** What one round of API calls produced: the answers, and what each of them cost. */
    private data class ApiFetch(
        /** Only sources that answered — see [apiMemo] for why a timeout is not an answer. */
        val results: Map<String, SourcedWordData?>,
        /** `Wiktionary=412ms`, `FreeDictionary=timeout`, `DataMuse=skipped` — for the one log line. */
        val timings: List<String>
    )

    /** One source's turn: whether it answered, what it said, and how long it took to say it. */
    private data class SourceOutcome(
        val source: String,
        val answered: Boolean,
        val data: SourcedWordData?,
        val timing: String
    )

    private suspend fun fetchFromApiSources(
        word: String,
        clients: List<DictionaryApiClient>,
        budgetMs: Long,
        /** Nothing else can answer this word — see the `lastResort` note at the call site. */
        ignoreHealth: Boolean = false
    ): ApiFetch = coroutineScope {
        val outcomes = clients.map { client ->
            async {
                val source = client.sourceName
                if (!ignoreHealth && health.isOpen(source)) {
                    return@async SourceOutcome(source, answered = false, data = null, timing = "$source=skipped")
                }

                val started = System.currentTimeMillis()
                // ⚠️ The `runCatching` sits *inside* the timeout on purpose: `withTimeoutOrNull`
                // returns null both for "timed out" and for "the client answered null", and those
                // must not be confused — one is an outage, the other an ordinary "no entry for
                // this word". Wrapping the answer is what keeps them distinguishable, and the
                // breaker depends on the distinction.
                val outcome = withTimeoutOrNull(budgetMs) {
                    runCatching { client.fetchWordData(word) }
                }
                val ms = System.currentTimeMillis() - started

                if (outcome == null) {
                    if (health.recordTimeout(source)) {
                        logger.warn(
                            "Source '{}' stopped answering ({} timeouts in a row); skipping it for {} minutes",
                            source, SourceHealth.BREAKER_THRESHOLD, SourceHealth.BREAKER_OPEN_MS / 60_000
                        )
                    }
                    return@async SourceOutcome(source, answered = false, data = null, timing = "$source=timeout(${ms}ms)")
                }

                if (health.recordAnswer(source)) logger.info("Source '{}' is answering again", source)
                outcome.exceptionOrNull()?.let { logger.warn("Error fetching from {}: {}", source, it.message) }

                SourceOutcome(source, answered = true, data = outcome.getOrNull(), timing = "$source=${ms}ms")
            }
        }.awaitAll()

        ApiFetch(
            results = outcomes.filter { it.answered }.associate { it.source to it.data },
            timings = outcomes.map { it.timing }
        )
    }

    // ── Smart merge & deduplication ──────────────────────────────────────────

    private fun mergeResults(
        word: String,
        apiResults: List<SourcedWordData>,
        scraperResults: List<ScrapeEnrichment>
    ): AggregatedWord {

        // ── Pronunciations ────────────────────────────────────────────────
        // Collect from API clients (FreeDictionary provides UK/US entries)
        val apiPronunciations = apiResults.flatMap { it.pronunciations }
        // Collect from scrapers (Cambridge/OED have more accurate IPA + audio)
        // Keep pos metadata for per-POS disambiguation (homographs)
        val scraperPronunciations = scraperResults.flatMap { enrichment ->
            enrichment.pronunciations.map { p ->
                PronunciationEntry(region = p.region, ipa = p.ipa, audioMp3Url = p.audioMp3Url)
            }
        }
        // Scraper data takes priority; API data fills gaps
        val pronunciations = mergePronunciations(scraperPronunciations + apiPronunciations)

        // One variant per headword block of every scraper: how that block sounds, and which
        // definitions were printed under it. Only scrapers know this — the APIs hand back one
        // pronunciation for the whole word.
        val variants = scraperResults.flatMap { enrichment ->
            enrichment.pronunciations
                .groupBy { it.entryIndex }
                .mapNotNull { (block, prons) ->
                    val merged = mergePronunciations(
                        prons.map { PronunciationEntry(it.region, it.ipa, it.audioMp3Url) }
                    )
                    if (merged.isEmpty()) return@mapNotNull null
                    PronunciationVariant(
                        source = enrichment.source,
                        pos = prons.firstNotNullOfOrNull { it.pos }?.lowercase()?.trim(),
                        pronunciations = merged,
                        definitionKeys = enrichment.senses
                            .filter { it.entryIndex == block }
                            .map { PronunciationVariant.key(it.definition) }
                            .filter { it.isNotBlank() }
                            .toSet()
                    )
                }
        }

        // Per-POS pronunciation map (for homograph support): pos → list of PronunciationEntry.
        // Blocks stay in page order, so the first dictionary on the page wins a region — which
        // for Cambridge means the British entry rather than the American respelling of it.
        val perPosPronunciations: Map<String, List<PronunciationEntry>> = variants
            .filter { it.pos != null }
            .groupBy { it.pos!! }
            .mapValues { (_, group) -> mergePronunciations(group.flatMap { it.pronunciations }) }

        // Legacy fields for backward-compat
        val phonetic = pronunciations.firstOrNull { it.ipa != null }?.ipa
            ?: apiResults.firstNotNullOfOrNull { it.phonetic }
        val audioUrl = pronunciations.firstOrNull { it.audioMp3Url != null }?.audioMp3Url
            ?: apiResults.firstNotNullOfOrNull { it.audioUrl }

        // ── Definitions ───────────────────────────────────────────────────
        val apiDefs = apiResults.flatMap { it.definitions }
            .map { DetailedDefinition(it.partOfSpeech, it.definition, it.example, it.source) }
        val scraperDefs = scraperResults.flatMap { enrichment ->
            enrichment.senses.map { sense ->
                DetailedDefinition(
                    partOfSpeech = sense.pos ?: "",
                    definition = sense.definition,
                    example = sense.examples.firstOrNull(),
                    source = enrichment.source,
                    entryIndex = sense.entryIndex
                )
            }
        }
        // Two views of the same material. The wider one is the grounding input for LLM
        // annotation, which benefits from seeing near-duplicates it can merge itself; the
        // narrower one is what the legacy per-source tabs render.
        val groundingDefinitions = deduplicateDefinitions(scraperDefs + apiDefs, perSourceLimit = 12).take(40)
        val allDefinitions = deduplicateDefinitions(scraperDefs + apiDefs, perSourceLimit = 8).take(30)

        // ── Synonyms / antonyms ───────────────────────────────────────────
        val allSynonyms = apiResults.flatMap { it.synonyms }
            .distinct().sorted().take(20)
        val allAntonyms = apiResults.flatMap { it.antonyms }
            .distinct().sorted().take(20)

        // ── Examples ──────────────────────────────────────────────────────
        val apiExamples = apiResults.flatMap { it.examples }
        val scraperExamples = scraperResults.flatMap { it.examples }
        val allExamples = deduplicateExamples(scraperExamples + apiExamples).take(10)

        // ── Entries (grouped by POS for homograph support) ────────────────
        val entries = buildEntries(allDefinitions, pronunciations, perPosPronunciations, allSynonyms, allAntonyms)

        return AggregatedWord(
            response = WordDetailResponse(
                word = word.trim(),
                phonetic = phonetic,
                audioUrl = audioUrl,
                pronunciations = pronunciations,
                translation = null, // added by DictionaryService
                definitions = allDefinitions,
                entries = entries,
                synonyms = allSynonyms,
                antonyms = allAntonyms,
                examples = allExamples
            ),
            sourceDefinitions = groundingDefinitions,
            perPosPronunciations = perPosPronunciations,
            pronunciationVariants = variants
        )
    }

    /**
     * Merge pronunciations: one entry per region, scraper data wins over API data.
     */
    private fun mergePronunciations(entries: List<PronunciationEntry>): List<PronunciationEntry> {
        val byRegion = linkedMapOf<String, PronunciationEntry>()
        for (entry in entries) {
            val key = entry.region ?: "any"
            // First non-null wins per region key
            byRegion.getOrPut(key) { entry }
            // Upgrade: fill in missing fields from later entries for same region
            val existing = byRegion[key]!!
            if (existing.audioMp3Url == null && entry.audioMp3Url != null ||
                existing.ipa == null && entry.ipa != null) {
                byRegion[key] = PronunciationEntry(
                    region = existing.region,
                    ipa = existing.ipa ?: entry.ipa,
                    audioMp3Url = existing.audioMp3Url ?: entry.audioMp3Url
                )
            }
        }
        return byRegion.values.toList()
    }

    /**
     * Group deduplicated definitions by part-of-speech to produce [WordEntry] items.
     * Ordering follows standard lexicographic POS order (noun → verb → adjective → …).
     * If [perPosPronunciations] has data for a given POS (e.g., from Cambridge homograph entries),
     * those pronunciations are used for that entry — enabling correct homograph display.
     */
    private fun buildEntries(
        definitions: List<DetailedDefinition>,
        pronunciations: List<PronunciationEntry>,
        perPosPronunciations: Map<String, List<PronunciationEntry>> = emptyMap(),
        synonyms: List<String>,
        antonyms: List<String>
    ): List<WordEntry> {
        if (definitions.isEmpty()) return emptyList()

        val posOrder = listOf(
            "noun", "verb", "adjective", "adverb", "pronoun",
            "preposition", "conjunction", "interjection", "phrase", "abbreviation"
        )

        return definitions
            .groupBy { it.partOfSpeech.lowercase().trim() }
            .entries
            .sortedBy { (pos, _) ->
                val idx = posOrder.indexOf(pos)
                if (idx < 0) posOrder.size else idx
            }
            .mapIndexed { idx, (pos, defs) ->
                // Use per-POS pronunciations for homograph support; fall back to global
                val entryPronunciations = perPosPronunciations[pos]?.takeIf { it.isNotEmpty() }
                    ?: pronunciations
                WordEntry(
                    id = (idx + 1).toString(),
                    partOfSpeech = pos.ifBlank { null },
                    phonetic = entryPronunciations.firstOrNull { it.ipa != null }?.ipa,
                    audioUrl = entryPronunciations.firstOrNull { it.audioMp3Url != null }?.audioMp3Url,
                    pronunciations = entryPronunciations,
                    meanings = defs.map { def ->
                        EntryMeaning(
                            definition = def.definition,
                            example = def.example,
                            source = def.source
                        )
                    }.take(10),
                    synonyms = synonyms.take(10),
                    antonyms = antonyms.take(10),
                    examples = defs.mapNotNull { it.example }.distinct().take(5)
                )
            }
            .filter { it.meanings.isNotEmpty() }
    }

    /**
     * Remove duplicate definitions.
     * A definition is a duplicate only if the same SOURCE already has an identical definition.
     * Definitions from different sources are always kept — they power per-source tabs.
     * Also applies a per-source limit of 8 to avoid flooding the All tab.
     */
    private fun deduplicateDefinitions(
        defs: List<DetailedDefinition>,
        perSourceLimit: Int = 8
    ): List<DetailedDefinition> = DefinitionBudget.apply(defs, perSourceLimit)

    /**
     * Remove duplicate examples (exact lowercase match).
     */
    private fun deduplicateExamples(examples: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return examples.filter { seen.add(it.lowercase().trim()) }
    }

    fun close() {
        httpClient.close()
        scraperService.close()
    }
}

/**
 * Which upstream sources are worth asking right now.
 *
 * A source that has stopped answering is not free: every lookup pays its whole budget to learn
 * nothing, and a cold word pays it twice — once for the response the reader is staring at, and
 * again for the aggregate the article is written from. `api.dictionaryapi.dev` sat behind a
 * Cloudflare 522 and charged ten seconds of that per word, which was most of the wait for a
 * word the corpus did not have yet.
 *
 * ⚠️ Only a **timeout** counts against a source, never an empty answer. The clients swallow their
 * own errors and return null for "no entry here" just as they do for "the request failed", so
 * counting nulls would open the breaker on a healthy dictionary as soon as somebody looked up a
 * few rare words. Exceeding the budget is the one signal that cannot also mean "this word isn't
 * in me".
 */
internal class SourceHealth {
    private val consecutiveTimeouts = ConcurrentHashMap<String, Int>()
    private val openUntil = ConcurrentHashMap<String, Long>()

    fun isOpen(source: String): Boolean = (openUntil[source] ?: 0L) > System.currentTimeMillis()

    /** @return true when this timeout is the one that opens the breaker — i.e. worth a log line. */
    fun recordTimeout(source: String): Boolean {
        val timeouts = consecutiveTimeouts.merge(source, 1) { a, b -> a + b } ?: 1
        if (timeouts < BREAKER_THRESHOLD) return false
        val alreadyOpen = isOpen(source)
        openUntil[source] = System.currentTimeMillis() + BREAKER_OPEN_MS
        return !alreadyOpen
    }

    /** @return true when this answer is the one that closes the breaker. */
    fun recordAnswer(source: String): Boolean {
        val hadFailed = (consecutiveTimeouts.put(source, 0) ?: 0) > 0
        val wasOpen = openUntil.remove(source) != null
        return wasOpen || hadFailed
    }

    companion object {
        /** One slow moment is weather; three in a row is an outage. */
        const val BREAKER_THRESHOLD = 3

        /**
         * Long enough that a dead source stops costing anything, short enough that its return is
         * noticed without a deploy. When this expires the next lookup asks it again, and a single
         * answer closes the breaker.
         */
        const val BREAKER_OPEN_MS = 5 * 60_000L
    }
}
