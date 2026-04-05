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
 * One definition within a WordEntry.
 */
@Serializable
data class EntryMeaning(
    val definition: String,
    val example: String? = null,
    val source: String? = null          // "LDOCE" | "CAMBRIDGE" | "OXFORD" | "FREEDICT" etc.
)

/**
 * A single entry (homograph) for a word.
 * Words like "lead" have two entries: noun/verb /liːd/ and noun/adj /lɛd/.
 * For most words there will be 1–3 entries grouped by part of speech.
 */
@Serializable
data class WordEntry(
    val id: String,                                     // "1", "2", …
    val partOfSpeech: String? = null,                   // "noun", "verb", null = unknown
    val phonetic: String? = null,                       // IPA for this entry (/liːd/ vs /lɛd/)
    val audioUrl: String? = null,                       // legacy MP3 field
    val pronunciations: List<PronunciationEntry> = emptyList(),
    val meanings: List<EntryMeaning> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val translation: String? = null                     // Russian translation for this POS (e.g. "свинец" for noun, "вести" for verb)
)

/**
 * Enhanced word detail response with aggregated data from multiple sources.
 * Fields [phonetic] and [audioUrl] are kept for backward-compatibility;
 * [pronunciations] contains the full UK/US breakdown.
 * [entries] groups meanings by part-of-speech (homograph support).
 */
@Serializable
data class WordDetailResponse(
    val word: String,
    val phonetic: String? = null,
    val audioUrl: String? = null,
    val pronunciations: List<PronunciationEntry> = emptyList(),
    val translation: String? = null,
    val definitions: List<DetailedDefinition>,          // flat list — backward-compat
    val entries: List<WordEntry> = emptyList(),          // grouped by POS — preferred
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val examples: List<String> = emptyList()
)

/**
 * Detailed definition with part of speech and metadata (flat list, backward-compat).
 */
@Serializable
data class DetailedDefinition(
    val partOfSpeech: String,
    val definition: String,
    val example: String? = null,
    val source: String? = null
)
