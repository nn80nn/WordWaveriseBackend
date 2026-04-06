package n.startapp.services

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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import n.startapp.exceptions.NotFoundException
import n.startapp.models.dictionary.*
import n.startapp.services.cache.CacheService
import n.startapp.services.dictionary.DictionaryAggregationService
import org.slf4j.LoggerFactory

/**
 * Enhanced service for dictionary lookups with multi-source aggregation and caching
 */
class DictionaryService {
    private val logger = LoggerFactory.getLogger(DictionaryService::class.java)
    private val translationApiUrl = "https://api.mymemory.translated.net/get"

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
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 15000
            socketTimeoutMillis = 15000
        }
    }

    private val aggregationService = DictionaryAggregationService()
    private val cacheService = CacheService()
    private val aiService = AiService()

    /**
     * Search for word with enhanced details from multiple sources
     * Results are cached for 24 hours to reduce API load
     * @param word The word to search for
     * @return WordDetailResponse with aggregated data
     * @throws NotFoundException if word is not found
     */
    suspend fun searchWordEnhanced(word: String): WordDetailResponse {
        val normalizedWord = word.trim().lowercase()
        val isPhrase = normalizedWord.contains(' ')
        val cacheKey = if (isPhrase) "phrase:$normalizedWord" else normalizedWord

        // Check cache first
        cacheService.getWord(cacheKey)?.let { cached ->
            logger.info("Returning cached result for '${if (isPhrase) "phrase:" else ""}$word'")
            return cached
        }

        // Phrase queries skip scrapers (Cambridge/Oxford don't handle multi-word)
        logger.info("Fetching ${if (isPhrase) "phrase" else "word"} '$word' from multiple sources")
        val aggregatedData = aggregationService.aggregateWordData(normalizedWord, isPhrase = isPhrase)

        // AI selection of best definitions (preserves source attribution via index-based selection)
        // + Russian translations — run in parallel
        val (translation, entriesWithTranslations, selectedDefinitions) = coroutineScope {
            val t = async { getTranslation(normalizedWord) }
            val e = async { addEntryTranslations(normalizedWord, aggregatedData.entries) }
            val d = async {
                if (aggregatedData.definitions.size > 5) {
                    try {
                        val rawTexts = aggregatedData.definitions.map { it.definition }
                        val indices = aiService.selectBestDefinitionIndices(normalizedWord, rawTexts)
                        indices.mapNotNull { aggregatedData.definitions.getOrNull(it - 1) }
                            .ifEmpty { aggregatedData.definitions }
                    } catch (ex: Exception) {
                        logger.warn("AI definition selection failed for '$normalizedWord': ${ex.message}")
                        aggregatedData.definitions
                    }
                } else aggregatedData.definitions
            }
            Triple(t.await(), e.await(), d.await())
        }
        val finalResult = aggregatedData.copy(
            translation = translation,
            entries = entriesWithTranslations,
            definitions = selectedDefinitions
        )

        // Cache the result
        cacheService.putWord(cacheKey, finalResult)

        return finalResult
    }

    /**
     * Quick search — returns only API data (no web scrapers), typically in ~1-2s.
     * Cached separately under "quick:{word}" key for 24h.
     */
    suspend fun searchWordQuick(word: String): WordDetailResponse {
        val normalizedWord = word.trim().lowercase()
        val isPhrase = normalizedWord.contains(' ')
        val quickKey = "quick:${if (isPhrase) "phrase:" else ""}$normalizedWord"

        cacheService.getWord(quickKey)?.let { cached ->
            logger.info("Returning cached quick result for word: '$word'")
            return cached
        }

        logger.info("Quick-fetching '${if (isPhrase) "phrase" else "word"}' '$word' from API sources only")
        val aggregatedData = withTimeoutOrNull(5_000) {
            aggregationService.aggregateWordData(normalizedWord, skipScrapers = true, isPhrase = isPhrase)
        } ?: run {
            logger.warn("Quick fetch timed out for '$word' after 5s")
            throw NotFoundException("Word '$word' not found (timeout)")
        }

        val entriesWithTranslations = addEntryTranslations(normalizedWord, aggregatedData.entries)
        val finalResult = aggregatedData.copy(entries = entriesWithTranslations)
        cacheService.putWord(quickKey, finalResult)
        return finalResult
    }

    /**
     * Legacy search method for backward compatibility
     * Converts new format to old format
     */
    suspend fun searchWord(word: String): WordSearchResponse {
        val enhanced = searchWordEnhanced(word)

        return WordSearchResponse(
            word = enhanced.word,
            phonetic = enhanced.phonetic,
            audioUrl = enhanced.audioUrl,
            pronunciations = enhanced.pronunciations,
            translation = enhanced.translation,
            definitions = enhanced.definitions.map { def ->
                Definition(
                    partOfSpeech = def.partOfSpeech,
                    definition = def.definition,
                    example = def.example,
                    synonyms = enhanced.synonyms.take(5),
                    antonyms = enhanced.antonyms.take(5),
                    source = def.source
                )
            }
        )
    }

    /**
     * Add Russian translations to each WordEntry using its part-of-speech as context.
     * Calls are made in parallel so latency = ~1 MyMemory call regardless of entry count.
     * Example: "lead (noun)" → "свинец", "lead (verb)" → "вести"
     */
    private suspend fun addEntryTranslations(word: String, entries: List<WordEntry>): List<WordEntry> = coroutineScope {
        entries.map { entry ->
            async {
                val pos = entry.partOfSpeech ?: return@async entry
                val raw = getTranslation("$word ($pos)") ?: return@async entry
                // Clean: strip any parenthetical suffix MyMemory may add, take first 3 words
                val translation = raw.substringBefore("(").trim()
                    .split(" ").take(3).joinToString(" ")
                    .takeIf { it.isNotBlank() }
                entry.copy(translation = translation)
            }
        }.map { it.await() }
    }

    /**
     * Get Russian translation for a word
     * @param word The word to translate
     * @return Russian translation or null if translation fails
     */
    private suspend fun getTranslation(word: String): String? {
        return try {
            val response = httpClient.get(translationApiUrl) {
                parameter("q", word)
                parameter("langpair", "en|ru")
            }

            if (response.status == HttpStatusCode.OK) {
                val translationResponse = response.body<TranslationApiResponse>()
                translationResponse.responseData.translatedText
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("Translation failed for '$word': ${e.message}")
            null
        }
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats() = cacheService.getStats()

    /**
     * Clear cache
     */
    fun clearCache() = cacheService.clearAll()

    /**
     * Close the HTTP client when done
     */
    fun close() {
        httpClient.close()
        aggregationService.close()
    }
}
