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
    /**
     * What to look up if no dictionary turns out to have [lemma] at all.
     *
     * Set when the typed word is real but has a near neighbour a speller would have put in its
     * place. Deferring the substitution until the dictionary comes back empty is what lets both
     * cases work: a rare word ("missive", 0.27 per million) keeps its own article instead of
     * being hijacked by a commoner one ("massive", 18.2), while a typo that merely appears in a
     * frequency corpus ("teh", 0.40) still lands on the word that was meant. Frequency alone
     * cannot tell those two apart — having an entry can.
     */
    val fallback: QueryAlternative? = null,
    val alternatives: List<QueryAlternative> = emptyList(),
    val confidence: Double = 1.0,
    /** "heuristic" | "cache" | "datamuse" | "llm" | "exact" — for debugging and cost accounting. */
    val resolvedBy: String = "heuristic"
) {
    /**
     * The user asked for these characters and no others.
     *
     * Set by `QueryResolver.resolveExact`, and read downstream by everything that would
     * otherwise swap the word out. The resolver is not the last place a substitution can
     * happen — the dictionary's own gloss can redirect a query too — so this has to travel
     * with the query rather than stay a parameter of the call that started it.
     */
    val isExact: Boolean get() = resolvedBy == "exact"
}
