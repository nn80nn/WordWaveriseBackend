package n.startapp.models.query

import kotlinx.serialization.Serializable

@Serializable
enum class QueryKind {
    /** A known English headword, as typed. */
    WORD,
    /** An inflected form; [ResolvedQuery.lemma] holds the dictionary form. */
    INFLECTION,
    /** A typo; [ResolvedQuery.lemma] holds the correction. */
    MISSPELLING,
    /** A multi-word unit worth a dictionary lookup ("kick the bucket"). */
    PHRASE,
    /** Free text — routed to the context pipeline rather than the dictionary. */
    SENTENCE,
    RU_WORD,
    RU_PHRASE,
    RU_SENTENCE,
    UNKNOWN
}

@Serializable
data class QueryAlternative(
    val form: String,
    val kind: QueryKind = QueryKind.WORD,
    val hintRu: String? = null
)

/**
 * What the search box input actually is.
 *
 * Resolving this before the lookup is what turns a typo into a corrected result instead of an
 * error, and what keeps a pasted sentence from being sent to the dictionary as a headword.
 */
@Serializable
data class ResolvedQuery(
    val raw: String,
    val normalized: String,
    val language: String,
    val kind: QueryKind,
    /** What to actually look up. Null when nothing sensible could be derived. */
    val lemma: String?,
    val surface: String,
    val correctionApplied: Boolean = false,
    val correctedFrom: String? = null,
    val alternatives: List<QueryAlternative> = emptyList(),
    val confidence: Double = 1.0,
    /** "heuristic" | "cache" | "datamuse" | "llm" — for debugging and cost accounting. */
    val resolvedBy: String = "heuristic"
)
