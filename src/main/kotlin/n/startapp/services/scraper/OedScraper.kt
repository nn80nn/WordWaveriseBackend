package n.startapp.services.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n.startapp.models.scraper.ScrapedPronunciation
import n.startapp.models.scraper.ScrapedSense
import n.startapp.models.scraper.ScrapeEnrichment
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

/**
 * Scraper for the Oxford English Dictionary (oed.com).
 * URL: https://www.oed.com/search/advanced/Entries?q={word}&sortOption=Frequency
 *
 * NOTE: OED is a subscription service and uses client-side rendering.
 * This scraper provides best-effort extraction of publicly visible content.
 * Returns null gracefully when content is unavailable (paywall / JS-required).
 */
class OedScraper(private val httpClient: HttpClient) {

    companion object {
        const val SOURCE_ID = "OED"
        const val PARSER_VERSION = "v1"
        private const val BASE_URL = "https://www.oed.com"
        private const val RATE_LIMIT_MS = 2000L
        private val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val logger = LoggerFactory.getLogger(OedScraper::class.java)
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    suspend fun scrape(word: String): ScrapeEnrichment? {
        val slug = word.trim().lowercase().replace(' ', '_')
        val startMs = System.currentTimeMillis()

        // Try entry URL first (e.g. /dictionary/iron_n1), then search fallback
        val entryUrl = "$BASE_URL/dictionary/${slug}_n1"
        val searchUrl = "$BASE_URL/search/advanced/Entries?q=${word.trim().lowercase()}&sortOption=Frequency"

        for (url in listOf(entryUrl, searchUrl)) {
            return try {
                logger.info("OED: fetching '$word' → $url")
                val html = fetchHtml(url)
                val result = parseHtml(word, url, html)
                val elapsed = System.currentTimeMillis() - startMs
                if (result != null) {
                    logger.info(
                        "OED OK for '$word' in ${elapsed}ms: " +
                        "${result.senses.size} senses"
                    )
                    result
                } else {
                    logger.info("OED: no usable data at $url (${elapsed}ms), trying next")
                    continue
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startMs
                logger.warn("OED scrape failed for '$word' at $url after ${elapsed}ms: ${e.message}")
                continue
            }
        }
        return null
    }

    // ── Rate-limited HTTP fetch ──────────────────────────────────────────────

    private suspend fun fetchHtml(url: String): String {
        mutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestTime
            if (elapsed < RATE_LIMIT_MS) delay(RATE_LIMIT_MS - elapsed)
            lastRequestTime = System.currentTimeMillis()
        }
        val response = httpClient.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "en-GB,en;q=0.9")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Referer", "https://www.oed.com/")
        }
        return response.bodyAsText()
    }

    // ── HTML parsing ─────────────────────────────────────────────────────────

    private fun parseHtml(word: String, url: String, html: String): ScrapeEnrichment? {
        // OED is primarily JS-rendered; most content won't be in initial HTML.
        // We attempt best-effort extraction of any server-side rendered content.
        if (html.isBlank()) return null

        val doc = Jsoup.parse(html)

        val senses = mutableListOf<ScrapedSense>()
        val pronunciations = mutableListOf<ScrapedPronunciation>()

        // ── Pronunciations ────────────────────────────────────────────────
        // OED uses IPA in spans that might be class "oed_IPA" or "pron"
        val ipaEl = doc.select("span.oed_IPA, span.pron, .pronunciation .ipa").firstOrNull()
        val ipa = ipaEl?.text()?.trim()?.let { if (it.isNotBlank()) "/$it/" else null }
        if (ipa != null) {
            pronunciations += ScrapedPronunciation("uk", ipa, null)
        }

        // ── Definitions from search results ───────────────────────────────
        // Search results page: each result card may have a snippet
        doc.select(".result-entry, .search-result, article.entry").forEach { el ->
            val def = el.select(".definition, .sense-body, .def, p.definition").firstOrNull()
                ?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val pos = el.select(".pos, .part-of-speech, .grammatical-info").firstOrNull()?.text()
            val examples = el.select(".quotation-body, .example, .quote-text")
                .map { it.text().trim() }.filter { it.isNotBlank() }.take(3)
            senses += ScrapedSense(pos = pos, definition = def, examples = examples)
        }

        // ── Fallback: single entry page ───────────────────────────────────
        if (senses.isEmpty()) {
            // Try structured entry: section.entry, div.sense, etc.
            doc.select("section.senseSection, div.sense, .sense-container").forEach { el ->
                val def = el.select(".definition, .def").firstOrNull()
                    ?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val pos = el.select(".pos, .gramb").firstOrNull()?.text()
                senses += ScrapedSense(pos = pos, definition = def)
            }
        }

        // ── Last-resort: any definition-like text ─────────────────────────
        if (senses.isEmpty()) {
            doc.select("p.definition, .definition-body, .def").take(5).forEach { el ->
                val def = el.text().trim()
                if (def.isNotBlank()) senses += ScrapedSense(definition = def)
            }
        }

        if (senses.isEmpty() && pronunciations.isEmpty()) return null

        return ScrapeEnrichment(
            source = SOURCE_ID,
            word = word,
            fetchedAt = System.currentTimeMillis(),
            url = url,
            pronunciations = pronunciations,
            senses = senses.take(10),
            examples = senses.flatMap { it.examples }.distinct().take(10),
            meta = mapOf("parserVersion" to PARSER_VERSION)
        )
    }
}
