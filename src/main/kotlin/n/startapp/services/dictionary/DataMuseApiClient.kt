package n.startapp.services.dictionary

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import n.startapp.models.dictionary.DataMuseWord
import n.startapp.models.dictionary.SourcedWordData
import org.slf4j.LoggerFactory

/**
 * Client for DataMuse API (datamuse.com)
 * Free API for finding synonyms, antonyms, and related words
 * No API key required
 */
class DataMuseApiClient(private val httpClient: HttpClient) : DictionaryApiClient {
    private val logger = LoggerFactory.getLogger(DataMuseApiClient::class.java)
    private val apiBaseUrl = "https://api.datamuse.com/words"

    override val sourceName: String = "DataMuse"

    override suspend fun fetchWordData(word: String): SourcedWordData? {
        return try {
            // Fetch synonyms (means like)
            val synonyms = fetchRelatedWords(word, "ml")

            // Fetch antonyms (related with trigger "rel_ant")
            val antonyms = fetchRelatedWords(word, "rel_ant")

            // DataMuse doesn't provide definitions, so we only return synonyms/antonyms
            if (synonyms.isEmpty() && antonyms.isEmpty()) {
                logger.debug("$sourceName: No related words found for '$word'")
                return null
            }

            SourcedWordData(
                word = word.trim(),
                source = sourceName,
                synonyms = synonyms,
                antonyms = antonyms
            )
        } catch (e: Exception) {
            logger.warn("$sourceName: Error fetching word '$word': ${e.message}")
            null
        }
    }

    private suspend fun fetchRelatedWords(word: String, relationship: String): Set<String> {
        return try {
            val response = httpClient.get(apiBaseUrl) {
                parameter(relationship, word.trim())
                parameter("max", 20) // Limit to 20 results
                contentType(ContentType.Application.Json)
            }

            if (response.status != HttpStatusCode.OK) {
                return emptySet()
            }

            val words = response.body<List<DataMuseWord>>()
            words.map { it.word }.toSet()
        } catch (e: Exception) {
            logger.debug("$sourceName: Error fetching $relationship for '$word': ${e.message}")
            emptySet()
        }
    }
}
