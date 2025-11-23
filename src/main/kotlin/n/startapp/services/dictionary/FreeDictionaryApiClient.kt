package n.startapp.services.dictionary

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import n.startapp.models.dictionary.DictionaryApiResponse
import n.startapp.models.dictionary.SourcedDefinition
import n.startapp.models.dictionary.SourcedWordData
import org.slf4j.LoggerFactory

/**
 * Client for Free Dictionary API (api.dictionaryapi.dev)
 */
class FreeDictionaryApiClient(private val httpClient: HttpClient) : DictionaryApiClient {
    private val logger = LoggerFactory.getLogger(FreeDictionaryApiClient::class.java)
    private val apiBaseUrl = "https://api.dictionaryapi.dev/api/v2/entries/en"

    override val sourceName: String = "FreeDictionary"

    override suspend fun fetchWordData(word: String): SourcedWordData? {
        return try {
            val response = httpClient.get("$apiBaseUrl/${word.trim()}") {
                contentType(ContentType.Application.Json)
            }

            if (response.status != HttpStatusCode.OK) {
                logger.debug("$sourceName: Word '$word' not found (status: ${response.status})")
                return null
            }

            val apiResponses = response.body<List<DictionaryApiResponse>>()
            val apiResponse = apiResponses.firstOrNull() ?: return null

            parseApiResponse(apiResponse)
        } catch (e: Exception) {
            logger.warn("$sourceName: Error fetching word '$word': ${e.message}")
            null
        }
    }

    private fun parseApiResponse(apiResponse: DictionaryApiResponse): SourcedWordData {
        val phonetic = apiResponse.phonetics.firstOrNull { it.text != null }?.text
        val audioUrl = apiResponse.phonetics.firstOrNull { !it.audio.isNullOrBlank() }?.audio

        val definitions = apiResponse.meanings.flatMap { meaning ->
            meaning.definitions.map { def ->
                SourcedDefinition(
                    partOfSpeech = meaning.partOfSpeech,
                    definition = def.definition,
                    example = def.example,
                    source = sourceName
                )
            }
        }

        val allSynonyms = apiResponse.meanings.flatMap { meaning ->
            meaning.definitions.flatMap { it.synonyms }
        }.toSet()

        val allAntonyms = apiResponse.meanings.flatMap { meaning ->
            meaning.definitions.flatMap { it.antonyms }
        }.toSet()

        val examples = apiResponse.meanings.flatMap { meaning ->
            meaning.definitions.mapNotNull { it.example }
        }

        return SourcedWordData(
            word = apiResponse.word,
            source = sourceName,
            phonetic = phonetic,
            audioUrl = audioUrl,
            definitions = definitions,
            synonyms = allSynonyms,
            antonyms = allAntonyms,
            examples = examples
        )
    }
}
