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
import n.startapp.models.dictionary.SourcedWordData
import n.startapp.models.dictionary.WordDetailResponse
import org.slf4j.LoggerFactory

/**
 * Service that aggregates word data from multiple dictionary APIs in parallel
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

    /**
     * Fetch word data from all sources in parallel and merge results
     * @param word The word to look up
     * @return Aggregated WordDetailResponse
     * @throws NotFoundException if word not found in any source
     */
    suspend fun aggregateWordData(word: String): WordDetailResponse {
        logger.info("Fetching word data for '$word' from ${apiClients.size} sources")

        // Fetch from all sources in parallel using coroutines
        val results = fetchFromAllSources(word)

        // Filter out null results
        val validResults = results.filterNotNull()

        if (validResults.isEmpty()) {
            logger.warn("Word '$word' not found in any dictionary source")
            throw NotFoundException("Word '$word' not found in dictionary")
        }

        logger.info("Successfully fetched word '$word' from ${validResults.size} source(s)")

        // Merge results from all sources
        return mergeResults(word, validResults)
    }

    /**
     * Fetch word data from all API clients in parallel
     */
    private suspend fun fetchFromAllSources(word: String): List<SourcedWordData?> = coroutineScope {
        apiClients.map { client ->
            async {
                try {
                    client.fetchWordData(word)
                } catch (e: Exception) {
                    logger.warn("Error fetching from ${client.sourceName}: ${e.message}")
                    null
                }
            }
        }.awaitAll()
    }

    /**
     * Merge results from multiple sources into a single response
     */
    private fun mergeResults(word: String, results: List<SourcedWordData>): WordDetailResponse {
        // Pick first non-null phonetic
        val phonetic = results.firstNotNullOfOrNull { it.phonetic }

        // Pick first non-null audio URL
        val audioUrl = results.firstNotNullOfOrNull { it.audioUrl }

        // Combine all definitions (limit to avoid overwhelming response)
        val allDefinitions = results
            .flatMap { it.definitions }
            .distinctBy { it.definition.lowercase() } // Remove exact duplicates
            .take(15) // Limit to 15 definitions
            .map { sourcedDef ->
                DetailedDefinition(
                    partOfSpeech = sourcedDef.partOfSpeech,
                    definition = sourcedDef.definition,
                    example = sourcedDef.example,
                    source = sourcedDef.source
                )
            }

        // Combine all synonyms from all sources
        val allSynonyms = results
            .flatMap { it.synonyms }
            .distinct()
            .sorted()
            .take(20) // Limit to 20 synonyms

        // Combine all antonyms from all sources
        val allAntonyms = results
            .flatMap { it.antonyms }
            .distinct()
            .sorted()
            .take(20) // Limit to 20 antonyms

        // Combine all examples from all sources
        val allExamples = results
            .flatMap { it.examples }
            .distinct()
            .take(10) // Limit to 10 examples

        return WordDetailResponse(
            word = word.trim(),
            phonetic = phonetic,
            audioUrl = audioUrl,
            translation = null, // Translation will be added separately
            definitions = allDefinitions,
            synonyms = allSynonyms,
            antonyms = allAntonyms,
            examples = allExamples
        )
    }

    /**
     * Close the HTTP client when done
     */
    fun close() {
        httpClient.close()
    }
}
