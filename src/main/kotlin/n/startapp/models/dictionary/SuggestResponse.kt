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
    val suggestions: List<String> = emptyList()
)
