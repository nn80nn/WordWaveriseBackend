package n.startapp.models.dictionary

import kotlinx.serialization.Serializable

/**
 * Client-facing models for word search responses
 */

@Serializable
data class WordSearchResponse(
    val word: String,
    val phonetic: String?,
    val audioUrl: String?,
    val translation: String?, // Russian translation
    val definitions: List<Definition>
)

@Serializable
data class Definition(
    val partOfSpeech: String, // noun, verb, interjection, etc.
    val definition: String,
    val example: String?,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val source: String? = null  // "CAMBRIDGE" | "LDOCE" | "OXFORD" | "FreeDictionary" etc.
)
