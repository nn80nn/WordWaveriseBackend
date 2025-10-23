package n.startapp.models.dictionary

import kotlinx.serialization.Serializable

/**
 * Models for parsing raw API response from dictionary API
 */

@Serializable
data class DictionaryApiResponse(
    val word: String,
    val phonetics: List<Phonetic> = emptyList(),
    val meanings: List<Meaning> = emptyList()
)

@Serializable
data class Phonetic(
    val text: String? = null,
    val audio: String? = null
)

@Serializable
data class Meaning(
    val partOfSpeech: String,
    val definitions: List<DefinitionItem> = emptyList()
)

@Serializable
data class DefinitionItem(
    val definition: String,
    val example: String? = null,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList()
)

// Translation API models
@Serializable
data class TranslationApiResponse(
    val responseData: ResponseData
)

@Serializable
data class ResponseData(
    val translatedText: String
)
