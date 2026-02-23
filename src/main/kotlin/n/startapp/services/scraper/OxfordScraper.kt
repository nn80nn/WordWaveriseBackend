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
 * Scraper for Oxford Learner's Dictionaries (oxfordlearnersdictionaries.com).
 * Fetches IPA, audio links, definitions, and examples.
 */
class OxfordScraper(private val httpClient: HttpClient) {

    companion object {
        const val SOURCE_ID = "OXFORD"
        const val PARSER_VERSION = "v1"
        private const val BASE_URL = "https://www.oxfordlearnersdictionaries.com"
        private const val RATE_LIMIT_MS = 1500L
        private val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val logger = LoggerFactory.getLogger(OxfordScraper::class.java)
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    suspend fun scrape(word: String): ScrapeEnrichment? {
        val slug = word.trim().lowercase().replace(' ', '-')
        val startMs = System.currentTimeMillis()
        // Try base word first, then _1 suffix (OALD uses numbered entries for polysemous words)
        for (suffix in listOf("", "_1", "_2")) {
            val url = "$BASE_URL/definition/english/$slug$suffix"
            return try {
                logger.info("Oxford: starting scrape for '$word' → $url")
                val html = fetchHtml(url)
                // OALD redirects or shows "no result" page for unknown words
                if (html.contains("No exact match found") || html.contains("did not match any entries")) {
                    logger.info("Oxford: no match at $url, trying next suffix")
                    continue
                }
                logger.debug("Oxford: fetched ${html.length} bytes for '$word'")
                val result = parseHtml(word, url, html)
                val elapsed = System.currentTimeMillis() - startMs
                if (result != null) {
                    logger.info(
                        "Oxford OK for '$word' in ${elapsed}ms: " +
                        "${result.pronunciations.size} pronunciations " +
                        "(${result.pronunciations.joinToString { "${it.region}:ipa=${it.ipa != null},mp3=${it.audioMp3Url != null}" }}), " +
                        "${result.senses.size} senses, ${result.examples.size} examples"
                    )
                    result
                } else {
                    logger.info("Oxford: no usable data found for '$word' at $url (${elapsed}ms)")
                    continue
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startMs
                logger.warn("Oxford scrape failed for '$word' at $url after ${elapsed}ms: ${e.message}")
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
            header("Accept-Language", "en-US,en;q=0.9")
            header("Accept", "text/html,application/xhtml+xml")
            header("Referer", "https://www.oxfordlearnersdictionaries.com/")
        }
        return response.bodyAsText()
    }

    // ── HTML parsing ─────────────────────────────────────────────────────────

    internal fun parseHtml(word: String, url: String, html: String): ScrapeEnrichment? {
        val doc = Jsoup.parse(html)

        // ── Pronunciations ────────────────────────────────────────────────
        val pronunciations = mutableListOf<ScrapedPronunciation>()

        // BrE IPA: .phons_br span.phon  (OALD uses div.phons_br, not span)
        // span.phon text already contains slashes (e.g. "/ˈpleɪɡraʊnd/"), don't double-wrap
        val brIpa = doc.select(".phons_br span.phon").firstOrNull()
            ?.text()?.trim()?.let { ipa -> if (ipa.startsWith("/")) ipa else "/$ipa/" }
        // NAmE IPA: .phons_n_am span.phon
        val amIpa = doc.select(".phons_n_am span.phon").firstOrNull()
            ?.text()?.trim()?.let { ipa -> if (ipa.startsWith("/")) ipa else "/$ipa/" }

        // Audio: OALD stores MP3 URLs in data-src-mp3 on child elements
        val brMp3 = doc.select(".phons_br [data-src-mp3]").firstOrNull()
            ?.attr("data-src-mp3")?.takeIf { it.isNotBlank() }
        val amMp3 = doc.select(".phons_n_am [data-src-mp3]").firstOrNull()
            ?.attr("data-src-mp3")?.takeIf { it.isNotBlank() }

        if (brIpa != null || brMp3 != null)
            pronunciations += ScrapedPronunciation("uk", brIpa, brMp3)
        if (amIpa != null || amMp3 != null)
            pronunciations += ScrapedPronunciation("us", amIpa, amMp3)

        // Fallback: any [data-src-mp3]
        if (pronunciations.isEmpty()) {
            doc.select("[data-src-mp3]").firstOrNull()?.attr("data-src-mp3")
                ?.takeIf { it.isNotBlank() }
                ?.let { mp3 ->
                    pronunciations += ScrapedPronunciation(null, brIpa, mp3)
                }
        }

        // ── POS (top-level, from the first entry header) ──────────────────
        val topPos = doc.select(".webtop .pos, .top-g .pos, .h-g .pos").firstOrNull()?.text()

        // ── Senses ────────────────────────────────────────────────────────
        val senses = mutableListOf<ScrapedSense>()
        val allExamples = mutableListOf<String>()

        doc.select("li.sense").forEach { senseEl ->
            val def = senseEl.select("span.def").firstOrNull()
                ?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            // POS from sense or from parent entry
            val pos = senseEl.select(".pos").firstOrNull()?.text() ?: topPos
            val examples = senseEl.select("ul.examples span.x, div.x-gs span.x")
                .map { it.text().trim() }.filter { it.isNotBlank() }
            allExamples += examples
            senses += ScrapedSense(pos = pos, definition = def, examples = examples)
        }

        // Fallback: grab any span.def if no li.sense found
        if (senses.isEmpty()) {
            doc.select("span.def").forEach { el ->
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
            examples = allExamples.distinct().take(10),
            meta = mapOf("parserVersion" to PARSER_VERSION)
        )
    }
}
