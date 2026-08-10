package n.startapp.models.lookup

import kotlinx.serialization.Serializable
import n.startapp.models.dictionary.RuEnCandidates
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.query.ResolvedQuery
import n.startapp.services.context.TokenizedText

@Serializable
enum class AnnotationStatus {
    /** [LookupResponse.entry] is a finished article. */
    READY,
    /** Annotation is running; retry after [LookupResponse.retryAfterMs]. Raw data is already usable. */
    PENDING,
    /** Annotation failed; [LookupResponse.entry] was derived mechanically and has no Russian. */
    DEGRADED,
    /** No article and none coming — render the sources view. */
    UNAVAILABLE
}

/** A non-error message worth showing above the result, e.g. an applied spelling correction. */
@Serializable
data class LookupNotice(
    val type: String,
    val textRu: String,
    val originalQuery: String? = null
)

@Serializable
data class LookupResponse(
    val resolution: ResolvedQuery,
    val notice: LookupNotice? = null,
    val entry: LexicalEntry? = null,
    val annotationStatus: AnnotationStatus,
    /**
     * Machine-readable reason accompanying [AnnotationStatus.DEGRADED]
     * (llm_call_failed | llm_timeout | parse_failed | validation_failed).
     * A code only — provider error text stays in the logs and the admin diagnose endpoint.
     */
    val annotationNote: String? = null,
    /** Set with [AnnotationStatus.PENDING]: how long to wait before re-issuing the same request. */
    val retryAfterMs: Int? = null,
    /** Always present when the dictionary pipeline ran — powers the sources view. */
    val raw: WordDetailResponse? = null,
    /** Present when the query was Russian: English options with the context to choose between them. */
    val ruEn: RuEnCandidates? = null,
    /**
     * Present when the query was a sentence: its words, ready to be rendered tappable.
     * Shipped inline so tapping a word costs no extra round trip.
     */
    val tokenized: TokenizedText? = null
)
