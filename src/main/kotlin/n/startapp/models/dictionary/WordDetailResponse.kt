package n.startapp.models.dictionary

import kotlinx.serialization.Serializable

/**
 * Enhanced word detail response with aggregated data from multiple sources
 */
@Serializable
data class WordDetailResponse(
    val word: String,
    val phonetic: String? = null,
    val audioUrl: String? = null,
    val translation: String? = null,
    val definitions: List<DetailedDefinition>,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val examples: List<String> = emptyList()
)

/**
 * Detailed definition with part of speech and metadata
 */
@Serializable
data class DetailedDefinition(
    val partOfSpeech: String,
    val definition: String,
    val example: String? = null,
    val source: String? = null // Which API provided this definition
)
