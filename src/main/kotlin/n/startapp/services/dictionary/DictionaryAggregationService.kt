package n.startapp.services.dictionary

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import n.startapp.exceptions.NotFoundException
import n.startapp.models.dictionary.DetailedDefinition
import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.dictionary.SourcedWordData
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.scraper.ScrapeEnrichment
import n.startapp.repositories.ScraperCacheRepository
import n.startapp.services.scraper.ScraperService
import org.slf4j.LoggerFactory

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

    private val apiClients: List<DictionaryApiClient> = listOf(
        FreeDictionaryApiClient(httpClient),
        WordsApiClient(httpClient),
        DataMuseApiClient(httpClient)
    )

    private val scraperService = ScraperService(ScraperCacheRepository())

    /**
     * Fetch word data from all API sources + scrapers in parallel and merge results.
     * @throws NotFoundException if word not found in any source
     */
    suspend fun aggregateWordData(word: String): WordDetailResponse {
        logger.info("Fetching word data for '$word' from ${apiClients.size} API sources + scrapers")

        // Run API clients and scrapers in parallel
        val (apiResults, scraperResults) = coroutineScope {
            val apis = async { fetchFromAllApiSources(word) }
            val scrapers = async {
                try { scraperService.enrichWord(word) }
                catch (e: Exception) {
                    logger.warn("Scraper enrichment failed for '$word': ${e.message}")
                    emptyList()
                }
            }
            apis.await() to scrapers.await()
        }

        val validApiResults = apiResults.filterNotNull()

        if (validApiResults.isEmpty() && scraperResults.isEmpty()) {
            logger.warn("Word '$word' not found in any source")
            throw NotFoundException("Word '$word' not found in dictionary")
        }

        logger.info("'$word': ${validApiResults.size} API source(s), ${scraperResults.size} scraper source(s)")

        return mergeResults(word, validApiResults, scraperResults)
    }

    private suspend fun fetchFromAllApiSources(word: String): List<SourcedWordData?> = coroutineScope {
        apiClients.map { client ->
            async {
                try { client.fetchWordData(word) }
                catch (e: Exception) {
                    logger.warn("Error fetching from ${client.sourceName}: ${e.message}")
                    null
                }
            }
        }.awaitAll()
    }

    // ── Smart merge & deduplication ──────────────────────────────────────────

    private fun mergeResults(
        word: String,
        apiResults: List<SourcedWordData>,
        scraperResults: List<ScrapeEnrichment>
    ): WordDetailResponse {

        // ── Pronunciations ────────────────────────────────────────────────
        // Collect from API clients (FreeDictionary provides UK/US entries)
        val apiPronunciations = apiResults.flatMap { it.pronunciations }
        // Collect from scrapers (Cambridge/LDOCE have more accurate IPA + audio)
        val scraperPronunciations = scraperResults.flatMap { enrichment ->
            enrichment.pronunciations.map { p ->
                PronunciationEntry(region = p.region, ipa = p.ipa, audioMp3Url = p.audioMp3Url)
            }
        }
        // Scraper data takes priority; API data fills gaps
        val pronunciations = mergePronunciations(scraperPronunciations + apiPronunciations)

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
                    source = enrichment.source
                )
            }
        }
        val allDefinitions = deduplicateDefinitions(scraperDefs + apiDefs).take(15)

        // ── Synonyms / antonyms ───────────────────────────────────────────
        val allSynonyms = apiResults.flatMap { it.synonyms }
            .distinct().sorted().take(20)
        val allAntonyms = apiResults.flatMap { it.antonyms }
            .distinct().sorted().take(20)

        // ── Examples ──────────────────────────────────────────────────────
        val apiExamples = apiResults.flatMap { it.examples }
        val scraperExamples = scraperResults.flatMap { it.examples }
        val allExamples = deduplicateExamples(scraperExamples + apiExamples).take(10)

        return WordDetailResponse(
            word = word.trim(),
            phonetic = phonetic,
            audioUrl = audioUrl,
            pronunciations = pronunciations,
            translation = null, // added by DictionaryService
            definitions = allDefinitions,
            synonyms = allSynonyms,
            antonyms = allAntonyms,
            examples = allExamples
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
     * Remove duplicate definitions using normalized text comparison.
     * Two definitions are considered duplicates if their normalized first 60 chars match.
     */
    private fun deduplicateDefinitions(defs: List<DetailedDefinition>): List<DetailedDefinition> {
        val seen = mutableSetOf<String>()
        return defs.filter { def ->
            val key = def.definition.lowercase().replace(Regex("[^a-z0-9 ]"), "")
                .trim().take(60)
            seen.add(key)
        }
    }

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
