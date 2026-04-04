package n.startapp.services.dictionary

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import n.startapp.models.dictionary.SourcedDefinition
import n.startapp.models.dictionary.SourcedWordData
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

@Serializable
data class WiktionaryDef(
    val definition: String = "",
    val parsedExamples: List<WiktionaryParsedExample> = emptyList(),
    val examples: List<String> = emptyList()
)

@Serializable
data class WiktionaryParsedExample(val example: String = "")

@Serializable
data class WiktionaryEntry(
    val partOfSpeech: String = "",
    val language: String = "",
    val definitions: List<WiktionaryDef> = emptyList()
)

/**
 * Client for the Wiktionary REST API.
 * Endpoint: https://en.wiktionary.org/api/rest_v1/page/definition/{word}
 * No API key required, no Cloudflare protection.
 * Source ID: "WIKTIONARY" → shown as "Wiktionary" tab in the app.
 */
class WiktionaryApiClient(private val httpClient: HttpClient) : DictionaryApiClient {

    companion object {
        const val SOURCE_ID = "WIKTIONARY"
    }

    private val logger = LoggerFactory.getLogger(WiktionaryApiClient::class.java)
    override val sourceName: String = SOURCE_ID

    override suspend fun fetchWordData(word: String): SourcedWordData? {
        return try {
            val encoded = word.trim().replace(' ', '_')
            val response = httpClient.get(
                "https://en.wiktionary.org/api/rest_v1/page/definition/$encoded"
            ) {
                header("Accept", "application/json; charset=utf-8; profile=\"https://www.mediawiki.org/wiki/Specs/definition/0.8.0\"")
                header("User-Agent", "WordWaveriseApp/1.0 (educational; contact via GitHub)")
            }

            if (response.status != HttpStatusCode.OK) {
                logger.debug("Wiktionary: '$word' not found (${response.status})")
                return null
            }

            val data = response.body<Map<String, List<WiktionaryEntry>>>()
            val enEntries = data["en"] ?: return null

            val definitions = mutableListOf<SourcedDefinition>()
            val allExamples = mutableListOf<String>()

            for (entry in enEntries) {
                for (def in entry.definitions.take(4)) {
                    val cleanDef = Jsoup.parse(def.definition).text().trim()
                    if (cleanDef.isBlank()) continue

                    val example = def.parsedExamples.firstOrNull()?.example?.trim()
                        ?: def.examples.firstOrNull()?.let { Jsoup.parse(it).text().trim() }

                    definitions += SourcedDefinition(
                        partOfSpeech = entry.partOfSpeech,
                        definition = cleanDef,
                        example = example?.takeIf { it.isNotBlank() },
                        source = SOURCE_ID
                    )

                    def.parsedExamples.forEach { ex ->
                        ex.example.trim().takeIf { it.isNotBlank() }?.let { allExamples += it }
                    }
                }
                if (definitions.size >= 10) break
            }

            if (definitions.isEmpty()) return null

            logger.info("Wiktionary: '${word}' → ${definitions.size} defs, ${allExamples.size} examples")

            SourcedWordData(
                word = word,
                source = SOURCE_ID,
                phonetic = null,
                audioUrl = null,
                pronunciations = emptyList(),
                definitions = definitions,
                synonyms = emptySet(),
                antonyms = emptySet(),
                examples = allExamples.distinct().take(10)
            )
        } catch (e: Exception) {
            logger.warn("Wiktionary: error fetching '$word': ${e.message}")
            null
        }
    }
}
