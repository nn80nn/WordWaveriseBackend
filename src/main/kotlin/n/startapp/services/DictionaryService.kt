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

    /**
     * Search for word with enhanced details from multiple sources
     * Results are cached for 24 hours to reduce API load
     * @param word The word to search for
     * @return WordDetailResponse with aggregated data
     * @throws NotFoundException if word is not found
     */
    suspend fun searchWordEnhanced(word: String): WordDetailResponse {
        val normalizedWord = word.trim().lowercase()

        // Check cache first
        cacheService.getWord(normalizedWord)?.let { cached ->
            logger.info("Returning cached result for word: '$word'")
            return cached
        }

        // Fetch from multiple sources in parallel
        logger.info("Fetching word '$word' from multiple sources")
        val aggregatedData = aggregationService.aggregateWordData(normalizedWord)

        // Add Russian translation
        val translation = getTranslation(normalizedWord)
        val finalResult = aggregatedData.copy(translation = translation)

        // Cache the result
        cacheService.putWord(normalizedWord, finalResult)

        return finalResult
    }

    /**
     * Quick search — returns only API data (no web scrapers), typically in ~1-2s.
     * Cached separately under "quick:{word}" key for 24h.
     */
    suspend fun searchWordQuick(word: String): WordDetailResponse {
        val normalizedWord = word.trim().lowercase()
        val quickKey = "quick:$normalizedWord"

        cacheService.getWord(quickKey)?.let { cached ->
            logger.info("Returning cached quick result for word: '$word'")
            return cached
        }

        logger.info("Quick-fetching word '$word' from API sources only")
        val aggregatedData = aggregationService.aggregateWordData(normalizedWord, skipScrapers = true)
        val translation = getTranslation(normalizedWord)
        val finalResult = aggregatedData.copy(translation = translation)

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
