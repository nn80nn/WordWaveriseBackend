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
import n.startapp.models.dictionary.DataMuseWord
import n.startapp.models.dictionary.SuggestResponse
import n.startapp.models.dictionary.TranslationApiResponse
import org.slf4j.LoggerFactory

/**
 * Provides word suggestions:
 *  - English input  → DataMuse spelling suggestions (sp=)
 *  - Russian input  → MyMemory en translation candidate
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

    suspend fun getSuggestions(query: String): SuggestResponse {
        val trimmed = query.trim()
        return if (trimmed.any { it in '\u0400'..'\u04FF' }) {
            val translated = translateRuToEn(trimmed)
            SuggestResponse(
                query = trimmed,
                lang = "ru",
                suggestions = if (translated != null) listOf(translated) else emptyList()
            )
        } else {
            val suggestions = fetchSpellingSuggestions(trimmed)
            SuggestResponse(query = trimmed, lang = "en", suggestions = suggestions)
        }
    }

    private suspend fun translateRuToEn(text: String): String? {
        return try {
            val response = httpClient.get("https://api.mymemory.translated.net/get") {
                parameter("q", text)
                parameter("langpair", "ru|en")
            }
            if (response.status == HttpStatusCode.OK) {
                val body = response.body<TranslationApiResponse>()
                val translated = body.responseData.translatedText.trim()
                if (translated.isNotBlank() && !translated.equals(text, ignoreCase = true)) {
                    translated.lowercase()
                } else null
            } else null
        } catch (e: Exception) {
            logger.debug("Translation failed for '$text': ${e.message}")
            null
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
