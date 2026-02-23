package n.startapp.models.scraper

import kotlinx.serialization.Serializable

@Serializable
data class ScrapedPronunciation(
    val region: String? = null,     // "uk" | "us" | null
    val ipa: String? = null,
    val audioMp3Url: String? = null
)

@Serializable
data class ScrapedSense(
    val pos: String? = null,
    val guideWord: String? = null,
    val level: String? = null,
    val grammar: String? = null,
    val definition: String,
    val examples: List<String> = emptyList()
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
