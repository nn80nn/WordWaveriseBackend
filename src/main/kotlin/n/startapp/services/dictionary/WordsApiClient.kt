package n.startapp.services.dictionary

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import n.startapp.models.dictionary.SourcedDefinition
import n.startapp.models.dictionary.SourcedWordData
import n.startapp.models.dictionary.WordsApiResponse
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory

/**
 * Client for WordsAPI (wordsapi.com)
 * Note: Requires API key from environment variable WORDS_API_KEY
 * Get free key at https://www.wordsapi.com/
 */
class WordsApiClient(private val httpClient: HttpClient) : DictionaryApiClient {
    private val logger = LoggerFactory.getLogger(WordsApiClient::class.java)
    private val apiBaseUrl = "https://wordsapiv1.p.rapidapi.com/words"
    private val apiKey = EnvConfig.get("WORDS_API_KEY")

    override val sourceName: String = "WordsAPI"

    override suspend fun fetchWordData(word: String): SourcedWordData? {
        // Skip if API key not configured
        if (apiKey.isNullOrBlank()) {
            logger.debug("$sourceName: API key not configured, skipping")
            return null
        }

        return try {
            val response = httpClient.get("$apiBaseUrl/${word.trim()}") {
                contentType(ContentType.Application.Json)
                header("X-RapidAPI-Key", apiKey)
                header("X-RapidAPI-Host", "wordsapiv1.p.rapidapi.com")
            }

            if (response.status != HttpStatusCode.OK) {
                logger.debug("$sourceName: Word '$word' not found (status: ${response.status})")
                return null
            }

            val apiResponse = response.body<WordsApiResponse>()
            parseApiResponse(apiResponse)
        } catch (e: Exception) {
            logger.warn("$sourceName: Error fetching word '$word': ${e.message}")
            null
        }
    }

    private fun parseApiResponse(apiResponse: WordsApiResponse): SourcedWordData {
        val results = apiResponse.results ?: emptyList()

        val definitions = results.map { result ->
            SourcedDefinition(
                partOfSpeech = result.partOfSpeech ?: "unknown",
                definition = result.definition,
                example = result.examples?.firstOrNull(),
                source = sourceName
            )
        }

        val allSynonyms = results.flatMap { it.synonyms ?: emptyList() }.toSet()
        val allAntonyms = results.flatMap { it.antonyms ?: emptyList() }.toSet()
        val allExamples = results.flatMap { it.examples ?: emptyList() }

        return SourcedWordData(
            word = apiResponse.word,
            source = sourceName,
            phonetic = apiResponse.pronunciation?.all,
            definitions = definitions,
            synonyms = allSynonyms,
            antonyms = allAntonyms,
            examples = allExamples
        )
    }
}
