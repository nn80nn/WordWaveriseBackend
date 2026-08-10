package n.startapp.models.lookup

import kotlinx.serialization.Serializable
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.query.ResolvedQuery

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
    val raw: WordDetailResponse? = null
)
