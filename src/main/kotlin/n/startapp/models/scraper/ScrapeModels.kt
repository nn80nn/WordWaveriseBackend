package n.startapp.models.scraper

import kotlinx.serialization.Serializable

@Serializable
data class ScrapedPronunciation(
    val region: String? = null,     // "uk" | "us" | null
    val ipa: String? = null,
    val audioMp3Url: String? = null,
    val pos: String? = null,         // part of speech this pronunciation belongs to (for homographs)
    /**
     * Which headword block on the page this came from.
     *
     * Part of speech is not enough to tell homographs apart: Cambridge gives `lead` two *noun*
     * blocks, /liːd/ and /led/, and `bass` two more. The block is the only thing that ties a
     * pronunciation to the definitions printed beside it, which is what lets a sense claim it.
     */
    val entryIndex: Int = 0
)

@Serializable
data class ScrapedSense(
    val pos: String? = null,
    val guideWord: String? = null,
    val level: String? = null,
    val grammar: String? = null,
    val definition: String,
    val examples: List<String> = emptyList(),
    /** The headword block this definition was printed in — see [ScrapedPronunciation.entryIndex]. */
    val entryIndex: Int = 0
)

@Serializable
data class ScrapeEnrichment(
    val source: String,             // "CAMBRIDGE" | "LDOCE"
    val word: String,
    val fetchedAt: Long,            // epoch millis
    val url: String,
    val pronunciations: List<ScrapedPronunciation> = emptyList(),
    val senses: List<ScrapedSense> = emptyList(),
    val examples: List<String> = emptyList(),
    val meta: Map<String, String> = emptyMap()
)
