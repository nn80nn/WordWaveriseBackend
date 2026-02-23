package n.startapp.models.dictionary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Models for various external dictionary APIs
 */

// ============ DataMuse API Models ============
@Serializable
data class DataMuseWord(
    val word: String,
    val score: Int? = null,
    val tags: List<String>? = null
)

// ============ WordsAPI Models ============
@Serializable
data class WordsApiResponse(
    val word: String,
    val results: List<WordsApiResult>? = null,
    val syllables: Syllables? = null,
    val pronunciation: Pronunciation? = null
)

@Serializable
data class WordsApiResult(
    val definition: String,
    val partOfSpeech: String? = null,
    val synonyms: List<String>? = null,
    val antonyms: List<String>? = null,
    val examples: List<String>? = null
)

@Serializable
data class Syllables(
    val count: Int,
    val list: List<String>
)

@Serializable
data class Pronunciation(
    val all: String? = null
)

// ============ Internal aggregation models ============
/**
 * Internal model for word data from a single source
 */
data class SourcedWordData(
    val word: String,
    val source: String,
    val phonetic: String? = null,
    val audioUrl: String? = null,
    val pronunciations: List<PronunciationEntry> = emptyList(),
    val definitions: List<SourcedDefinition> = emptyList(),
    val synonyms: Set<String> = emptySet(),
    val antonyms: Set<String> = emptySet(),
    val examples: List<String> = emptyList()
)

/**
 * Definition from a specific source
 */
data class SourcedDefinition(
    val partOfSpeech: String,
    val definition: String,
    val example: String? = null,
    val source: String
)
