package n.startapp.models.dictionary

import kotlinx.serialization.Serializable

/**
 * Response for /api/words/suggest endpoint.
 * Returns spelling corrections (English) or translation candidates (Russian input).
 */
@Serializable
data class SuggestResponse(
    val query: String,
    val lang: String,                       // "en" | "ru"
    val suggestions: List<String> = emptyList(),
    /**
     * Russian input only: the same options with the context needed to choose between them.
     *
     * Added alongside [suggestions] rather than replacing it — existing app builds parse the
     * string list and ignore unknown keys, so they keep working and simply get better strings.
     */
    val candidates: List<RuEnCandidate> = emptyList()
)
