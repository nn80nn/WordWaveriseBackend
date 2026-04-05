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
import kotlinx.serialization.json.Json
import n.startapp.models.dictionary.DataMuseWord
import n.startapp.models.dictionary.SuggestResponse
import n.startapp.models.dictionary.TranslationApiResponse
import org.slf4j.LoggerFactory

/**
 * Provides word suggestions:
 *  - English prefix  → DataMuse prefix autocomplete (sp={word}*)
 *  - English input   → DataMuse spelling suggestions (sp=)
 *  - Russian input   → MyMemory translation + DataMuse ml= for each candidate (multiple results)
 */
class SuggestService {
    private val logger = LoggerFactory.getLogger(SuggestService::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(Logging) { logger = Logger.DEFAULT; level = LogLevel.NONE }
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 8_000
        }
    }

    /**
     * @param prefix If true and query is English, returns prefix autocomplete suggestions.
     */
    suspend fun getSuggestions(query: String, prefix: Boolean = false): SuggestResponse {
        val trimmed = query.trim()
        return if (trimmed.any { it in '\u0400'..'\u04FF' }) {
            // Russian input — translate and expand with related words
            val suggestions = translateRuToEnMultiple(trimmed)
            SuggestResponse(query = trimmed, lang = "ru", suggestions = suggestions)
        } else if (prefix) {
            // English prefix — autocomplete
            val suggestions = fetchPrefixSuggestions(trimmed)
            SuggestResponse(query = trimmed, lang = "en", suggestions = suggestions)
        } else {
            // English — spelling correction
            val suggestions = fetchSpellingSuggestions(trimmed)
            SuggestResponse(query = trimmed, lang = "en", suggestions = suggestions)
        }
    }

    /**
     * Translates Russian query via MyMemory, splits multiple candidates,
     * then expands each via DataMuse ml= (means-like) in parallel.
     * Returns up to 8 deduplicated English word suggestions.
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

        if (primaryCandidates.isEmpty()) return emptyList()

        // Step 3: for each candidate, fetch DataMuse "means like" to expand synonyms
        val expanded = coroutineScope {
            primaryCandidates.map { candidate ->
                async {
                    try {
                        fetchMeansLike(candidate).take(3)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.map { it.await() }
        }

        // Step 4: merge — primary candidates first, then expansions, dedup
        val result = (primaryCandidates + expanded.flatten())
            .distinct()
            .filter { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
            .take(8)

        logger.debug("RU suggestions for '$text': $result")
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
            logger.debug("Translation failed for '$text': ${e.message}")
            null
        }
    }

    /** DataMuse "means like" — returns words with similar meaning */
    private suspend fun fetchMeansLike(word: String): List<String> {
        return try {
            val response = httpClient.get("https://api.datamuse.com/words") {
                parameter("ml", word)
                parameter("max", 5)
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            response.body<List<DataMuseWord>>()
                .map { it.word }
                .filter { it.lowercase() != word.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** DataMuse prefix autocomplete using wildcard sp={word}* */
    private suspend fun fetchPrefixSuggestions(word: String): List<String> {
        return try {
            val response = httpClient.get("https://api.datamuse.com/words") {
                parameter("sp", "$word*")
                parameter("max", 8)
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            response.body<List<DataMuseWord>>()
                .map { it.word }
                .filter { it.lowercase().startsWith(word.lowercase()) }
                .take(6)
        } catch (e: Exception) {
            logger.debug("Prefix suggestions failed for '$word': ${e.message}")
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
                .take(6)
        } catch (e: Exception) {
            logger.debug("Spelling suggestions failed for '$word': ${e.message}")
            emptyList()
        }
    }

    fun close() = httpClient.close()
}
