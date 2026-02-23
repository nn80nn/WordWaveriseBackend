package n.startapp.models.dictionary

import kotlinx.serialization.Serializable

/**
 * A single pronunciation variant (UK / US / generic).
 */
@Serializable
data class PronunciationEntry(
    val region: String? = null,       // "uk" | "us" | null
    val ipa: String? = null,          // IPA transcription, e.g. "/wɜːd/"
    val audioMp3Url: String? = null   // Direct MP3 URL
)

/**
 * Enhanced word detail response with aggregated data from multiple sources.
 * Fields [phonetic] and [audioUrl] are kept for backward-compatibility;
 * [pronunciations] contains the full UK/US breakdown.
 */
@Serializable
data class WordDetailResponse(
    val word: String,
    val phonetic: String? = null,
    val audioUrl: String? = null,
    val pronunciations: List<PronunciationEntry> = emptyList(),
    val translation: String? = null,
    val definitions: List<DetailedDefinition>,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val examples: List<String> = emptyList()
)

/**
 * Detailed definition with part of speech and metadata.
 */
@Serializable
data class DetailedDefinition(
    val partOfSpeech: String,
    val definition: String,
    val example: String? = null,
    val source: String? = null
)
