package n.startapp.services.query

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import n.startapp.models.dictionary.DataMuseWord
import org.slf4j.LoggerFactory

/**
 * Answers "is this a real English word, and if not what did they mean?".
 *
 * An interface so the resolver's decision ladder can be unit-tested offline — the rung order
 * matters more than the data source, and a test must be able to assert that a given input never
 * reaches the LLM.
 */
interface WordOracle {
    suspend fun exists(word: String): Boolean
    suspend fun spellingSuggestions(word: String, max: Int = 5): List<String>
}

/**
 * DataMuse-backed implementation. Its `sp=` parameter is a Levenshtein speller, which already
 * fixes the common misspellings (recieve, neccessary, definately) with no model call.
 */
class DataMuseWordOracle(private val httpClient: HttpClient) : WordOracle {
    private val logger = LoggerFactory.getLogger(DataMuseWordOracle::class.java)

    override suspend fun exists(word: String): Boolean {
        val target = word.trim().lowercase()
        if (target.isBlank()) return false
        return try {
            query(target, 1).any { it.equals(target, ignoreCase = true) }
        } catch (e: Exception) {
            logger.debug("DataMuse existence check failed for '$target': ${e.message}")
            false
        }
    }

    override suspend fun spellingSuggestions(word: String, max: Int): List<String> = try {
        query(word.trim().lowercase(), max).filter { !it.equals(word, ignoreCase = true) }
    } catch (e: Exception) {
        logger.debug("DataMuse spelling suggestions failed for '$word': ${e.message}")
        emptyList()
    }

    private suspend fun query(word: String, max: Int): List<String> {
        val response = httpClient.get("https://api.datamuse.com/words") {
            parameter("sp", word)
            parameter("max", max)
        }
        if (response.status != HttpStatusCode.OK) return emptyList()
        return response.body<List<DataMuseWord>>().map { it.word }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 5_000
            }
        }
    }
}
