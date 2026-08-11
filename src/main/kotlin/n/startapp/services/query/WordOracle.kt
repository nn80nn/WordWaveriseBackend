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

    /**
     * Occurrences per million words, or null when unknown.
     *
     * Default null so a test fake need not implement it: callers must already treat "unknown"
     * as "no opinion" rather than as a reason to reject a word.
     */
    suspend fun frequency(word: String): Double? = null
}

/**
 * DataMuse-backed implementation. Its `sp=` parameter is a Levenshtein speller, which already
 * fixes the common misspellings (recieve, neccessary, definately) with no model call.
 */
class DataMuseWordOracle(private val httpClient: HttpClient) : WordOracle {
    private val logger = LoggerFactory.getLogger(DataMuseWordOracle::class.java)

    /**
     * Occurrences per million below which an exact match is corpus noise rather than a word.
     *
     * Common misspellings really are present in DataMuse's vocabulary — "recieve" comes back for
     * `sp=recieve` — so a self-match proves nothing, and frequency has to do the separating.
     *
     * The floor only clears out corpus dust (decieve 0.003, definately 0.008), and deliberately
     * does not try to separate typos from words — it cannot. Those populations overlap: "teh"
     * (0.40) is commoner than "intertwine" (0.17), "occured" (0.42) than "serendipity" (0.29).
     * Raising the floor to catch the typos would declare the app's own B2/C1 vocabulary
     * nonexistent, which is exactly how "intertwine" reached users as "intertwined". Deciding
     * between a rare word and a misspelling needs a comparison, and belongs to the caller.
     */
    private val MIN_FREQUENCY_EXISTS = 0.05

    /**
     * The bar a *suggestion* has to clear, which is a different and stricter question: proposing
     * one misspelling in place of another helps nobody. Whether a suggestion is worth overriding
     * the user with is decided comparatively, by the caller.
     */
    private val MIN_FREQUENCY_SUGGEST = 1.0

    override suspend fun exists(word: String): Boolean {
        val target = word.trim().lowercase()
        if (target.isBlank()) return false
        return try {
            val match = query(target, 1).firstOrNull { it.word.equals(target, ignoreCase = true) }
                ?: return false
            frequencyOf(match)?.let { it >= MIN_FREQUENCY_EXISTS } ?: true
        } catch (e: Exception) {
            logger.debug("DataMuse existence check failed for '$target': ${e.message}")
            false
        }
    }

    /** Ordered by how common the word is, so a caller breaking an edit-distance tie picks well. */
    override suspend fun spellingSuggestions(word: String, max: Int): List<String> = try {
        query(word.trim().lowercase(), max)
            .filter { !it.word.equals(word, ignoreCase = true) }
            // Suggesting one misspelling in place of another helps nobody.
            .filter { (frequencyOf(it) ?: Double.MAX_VALUE) >= MIN_FREQUENCY_SUGGEST }
            .sortedByDescending { frequencyOf(it) ?: 0.0 }
            .map { it.word }
    } catch (e: Exception) {
        logger.debug("DataMuse spelling suggestions failed for '$word': ${e.message}")
        emptyList()
    }

    override suspend fun frequency(word: String): Double? = try {
        query(word.trim().lowercase(), 1)
            .firstOrNull { it.word.equals(word.trim(), ignoreCase = true) }
            ?.let(::frequencyOf)
    } catch (e: Exception) {
        logger.debug("DataMuse frequency lookup failed for '$word': ${e.message}")
        null
    }

    private suspend fun query(word: String, max: Int): List<DataMuseWord> {
        val response = httpClient.get("https://api.datamuse.com/words") {
            parameter("sp", word)
            parameter("md", "f")   // attach frequency metadata
            parameter("max", max)
        }
        if (response.status != HttpStatusCode.OK) return emptyList()
        return response.body<List<DataMuseWord>>()
    }

    /** DataMuse reports frequency as a tag of the form "f:47.926155". */
    private fun frequencyOf(word: DataMuseWord): Double? =
        word.tags?.firstOrNull { it.startsWith("f:") }?.removePrefix("f:")?.toDoubleOrNull()

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
