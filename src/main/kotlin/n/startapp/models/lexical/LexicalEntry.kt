package n.startapp.models.lexical

import kotlinx.serialization.Serializable
import n.startapp.models.dictionary.PronunciationEntry

/**
 * Bump when the Kotlin/JSON shape changes. Part of the persistent cache key, so a bump
 * makes existing rows unreadable rather than mis-parsed.
 */
const val LEXICAL_SCHEMA_VERSION = 1

/**
 * Bump when the *pronunciation* binding changes — see [LexicalEntry.pronunciationVersion].
 * Unlike the schema and prompt versions this is not part of any cache key.
 */
const val PRONUNCIATION_VERSION = 1

@Serializable
enum class LexicalKind { WORD, PHRASE, IDIOM, PHRASAL_VERB, ABBREVIATION, PROPER_NOUN }

@Serializable
enum class Register { NEUTRAL, FORMAL, INFORMAL, SLANG, VULGAR, DATED, LITERARY, TECHNICAL }

/**
 * Countability of a noun sense — the one grammatical fact a Russian learner cannot infer.
 *
 * It belongs to the **sense**, not to the word: `paper` is uncountable as material and countable
 * as a document, and a label on the headword would be wrong for one of them whichever way it
 * pointed. Null everywhere outside nouns, where the question does not arise.
 */
@Serializable
enum class Countability { COUNTABLE, UNCOUNTABLE, BOTH }

/** An example is only useful to a Russian learner with its translation, so both are required. */
@Serializable
data class BilingualExample(
    val en: String,
    val ru: String,
    /** Index into [LexicalEntry.sources], or null when the model wrote the example itself. */
    val sourceRef: Int? = null
)

@Serializable
data class Collocation(
    val pattern: String,
    val ru: String? = null
)

@Serializable
data class Sense(
    /** Assigned server-side ("n1", "v2"): stable, and impossible for the model to fabricate. */
    val id: String,
    val definitionEn: String,
    /**
     * A full Russian explanation, not a word-for-word rendering of [definitionEn].
     * This is the field that actually addresses the "кривые переводы" complaint — the old
     * pipeline translated the headword alone, with no sense to anchor it.
     */
    val definitionRu: String,
    /** 1–4 short Russian equivalents for THIS sense. Feeds cards and flashcards. */
    val translationsRu: List<String> = emptyList(),
    val register: Register = Register.NEUTRAL,
    /** Nouns only; null for every other part of speech. See [Countability]. */
    val countability: Countability? = null,
    val cefr: String? = null,
    val domain: String? = null,
    val examples: List<BilingualExample> = emptyList(),
    val collocations: List<Collocation> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    /** 1-based indices into [LexicalEntry.sources]. Empty implies [generated]. */
    val sourceRefs: List<Int> = emptyList(),
    /**
     * How the word sounds in *this* sense, when that differs from the rest of its part of speech.
     *
     * Null is the normal case and means "the same as the group". Set only where a source printed
     * this definition under a different pronunciation — `lead` the metal against `lead` the
     * front, both nouns — because a part-of-speech label cannot separate those two.
     *
     * ⚠️ Filled server-side from the scraped block the definition came from. The model is never
     * asked for it: the JSON schema has no field for IPA at all, precisely so it cannot invent one.
     */
    val phonetic: String? = null,
    val audioUrl: String? = null,
    /** No source supported this sense; the model added it. Surfaced as an "ИИ" badge. */
    val generated: Boolean = false,
    val usageNote: String? = null
)

/** Derivable and verifiable, so safe for the model to fill — unlike pronunciation. */
@Serializable
data class InflectedForms(
    val plural: String? = null,
    val past: String? = null,
    val pastParticiple: String? = null,
    val presentParticiple: String? = null,
    val thirdPerson: String? = null,
    val comparative: String? = null,
    val superlative: String? = null
) {
    fun all(): List<String> = listOfNotNull(
        plural, past, pastParticiple, presentParticiple, thirdPerson, comparative, superlative
    )
}

@Serializable
data class PosGroup(
    val pos: String,
    /** Russian name of the part of speech, e.g. "существительное". */
    val posRu: String,
    /**
     * Pronunciations for THIS part of speech when a scraper supplied POS-tagged data.
     * Homographs (lead, bow, read, live, close) are unreadable without this split.
     */
    val pronunciations: List<PronunciationEntry> = emptyList(),
    val forms: InflectedForms? = null,
    val senses: List<Sense> = emptyList()
)

/** Provenance record: [index] is 1-based and is what a sense's `sourceRefs` point at. */
@Serializable
data class SourceRef(
    val index: Int,
    val source: String,
    val partOfSpeech: String? = null,
    val definition: String,
    val example: String? = null
)

@Serializable
data class LexicalEntry(
    val lemma: String,
    /** What the user actually typed; differs from [lemma] for inflections and misspellings. */
    val queryForm: String = "",
    val kind: LexicalKind = LexicalKind.WORD,
    val language: String = "en",

    // ── Never produced by the model: copied verbatim from the scraped aggregate ──
    val pronunciations: List<PronunciationEntry> = emptyList(),
    val phonetic: String? = null,
    val audioUrl: String? = null,

    val posGroups: List<PosGroup> = emptyList(),
    val etymology: String? = null,
    val usageNotes: List<String> = emptyList(),
    val frequencyBand: String? = null,

    val sources: List<SourceRef> = emptyList(),
    /** No source had this headword at all; the whole article is model-written. */
    val aiGenerated: Boolean = false,
    /** Annotation failed validation twice; this entry was derived mechanically from the raw data. */
    val degraded: Boolean = false,

    val schemaVersion: Int = LEXICAL_SCHEMA_VERSION,
    val promptVersion: Int = 0,
    /**
     * Which generation of the pronunciation binding wrote the fields above.
     *
     * Deliberately **not** part of the cache key. Pronunciation comes from the scrapers, not
     * from the model, so fixing it must not invalidate a single article — that would cost the
     * whole corpus a rewrite for a field the model never wrote. Instead a read notices the old
     * number and repairs that one entry in the background, so the corpus mends itself word by
     * word as it is used. See `LookupService.repairPronunciation`.
     */
    val pronunciationVersion: Int = 0,
    val model: String = "",
    val generatedAt: Long = 0L
) {
    /** Lemma plus every inflected form, lowercase — indexed so a form can find its entry. */
    fun formsIndex(): String =
        (listOf(lemma) + posGroups.mapNotNull { it.forms }.flatMap { it.all() })
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
}
