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
import n.startapp.models.dictionary.Definition
import n.startapp.models.dictionary.DictionaryApiResponse
import n.startapp.models.dictionary.TranslationApiResponse
import n.startapp.models.dictionary.WordSearchResponse

/**
 * Service for interacting with external dictionary API
 */
class DictionaryService {
    private val apiBaseUrl = "https://api.dictionaryapi.dev/api/v2/entries/en"
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

    /**
     * Search for word definitions
     * @param word The word to search for
     * @return WordSearchResponse containing definitions and phonetics
     * @throws NotFoundException if word is not found
     */
    suspend fun searchWord(word: String): WordSearchResponse {
        try {
            val response = httpClient.get("$apiBaseUrl/${word.trim()}") {
                contentType(ContentType.Application.Json)
            }

            if (response.status == HttpStatusCode.NotFound) {
                throw NotFoundException("Word '$word' not found in dictionary")
            }

            // API returns a list with single element for English words
            val apiResponses = response.body<List<DictionaryApiResponse>>()
            val apiResponse = apiResponses.firstOrNull()
                ?: throw NotFoundException("Word '$word' not found in dictionary")

            // Get Russian translation
            val translation = getTranslation(word.trim())

            return parseApiResponse(apiResponse, translation)
        } catch (e: NotFoundException) {
            throw e
        } catch (e: Exception) {
            throw Exception("Error fetching word definition: ${e.message}", e)
        }
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
            // If translation fails, just return null and continue with English definitions
            null
        }
    }

    /**
     * Parse API response to client-facing model
     */
    private fun parseApiResponse(apiResponse: DictionaryApiResponse, translation: String?): WordSearchResponse {
        // Get the first available phonetic text
        val phonetic = apiResponse.phonetics.firstOrNull { it.text != null }?.text

        // Get the first audio URL if available
        val audioUrl = apiResponse.phonetics.firstOrNull { !it.audio.isNullOrBlank() }?.audio

        // Flatten all definitions from all meanings
        val definitions = apiResponse.meanings.flatMap { meaning ->
            meaning.definitions.map { def ->
                Definition(
                    partOfSpeech = meaning.partOfSpeech,
                    definition = def.definition,
                    example = def.example,
                    synonyms = def.synonyms.take(5), // Limit to 5 synonyms
                    antonyms = def.antonyms.take(5)  // Limit to 5 antonyms
                )
            }
        }

        return WordSearchResponse(
            word = apiResponse.word,
            phonetic = phonetic,
            audioUrl = audioUrl,
            translation = translation,
            definitions = definitions
        )
    }

    /**
     * Close the HTTP client when done
     */
    fun close() {
        httpClient.close()
    }
}
