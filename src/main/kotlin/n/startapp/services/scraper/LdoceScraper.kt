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
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory

class LdoceScraper(private val httpClient: HttpClient) {

    companion object {
        const val SOURCE_ID = "LDOCE"
        const val PARSER_VERSION = "v1"
        private const val BASE_URL = "https://www.ldoceonline.com"
        private const val RATE_LIMIT_MS = 1200L
        private val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val logger = LoggerFactory.getLogger(LdoceScraper::class.java)
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    suspend fun scrape(word: String): ScrapeEnrichment? {
        val slug = word.trim().lowercase().replace(' ', '-')
        val url = "$BASE_URL/dictionary/$slug"
        val startMs = System.currentTimeMillis()
        return try {
            logger.info("LDOCE: starting scrape for '$word' → $url")
            val html = fetchHtml(url)
            logger.debug("LDOCE: fetched ${html.length} bytes for '$word'")
            val result = parseHtml(word, url, html)
            val elapsed = System.currentTimeMillis() - startMs
            if (result != null) {
                logger.info(
                    "LDOCE OK for '$word' in ${elapsed}ms: " +
                    "${result.pronunciations.size} pronunciations " +
                    "(${result.pronunciations.joinToString { "${it.region}:ipa=${it.ipa != null},mp3=${it.audioMp3Url != null}" }}), " +
                    "${result.senses.size} senses, ${result.examples.size} examples"
                )
            } else {
                logger.info("LDOCE: no usable data found for '$word' (${elapsed}ms)")
            }
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            logger.warn("LDOCE scrape failed for '$word' after ${elapsed}ms: ${e.message}")
            null
        }
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
        }
        return response.bodyAsText()
    }

    // ── HTML parsing ─────────────────────────────────────────────────────────

    private fun parseHtml(word: String, url: String, html: String): ScrapeEnrichment? {
        val doc = Jsoup.parse(html)

        // ── Pronunciations ────────────────────────────────────────────────
        val pronunciations = mutableListOf<ScrapedPronunciation>()

        // IPA texts — first two from PronCodes
        val ipaList = doc.select("span.PronCodes span.PRON").map { "/${it.text()}/" }

        // British English audio
        val brMp3 = doc.select("span.speaker.brefile, .BrEPrn span.speaker")
            .firstOrNull()?.attr("data-src-mp3")?.takeIf { it.isNotBlank() }
            ?: doc.select("[data-src-mp3]")
                .firstOrNull { it.hasClass("brefile") }?.attr("data-src-mp3")

        // American English audio
        val amMp3 = doc.select("span.speaker.amefile, .AmEPrn span.speaker")
            .firstOrNull()?.attr("data-src-mp3")?.takeIf { it.isNotBlank() }
            ?: doc.select("[data-src-mp3]")
                .firstOrNull { it.hasClass("amefile") }?.attr("data-src-mp3")

        if (brMp3 != null || ipaList.isNotEmpty())
            pronunciations += ScrapedPronunciation("uk", ipaList.getOrNull(0), brMp3)
        if (amMp3 != null)
            pronunciations += ScrapedPronunciation("us", ipaList.getOrNull(1), amMp3)

        // Fallback: first [data-src-mp3] if nothing found
        if (pronunciations.isEmpty()) {
            doc.select("[data-src-mp3]").firstOrNull()?.attr("data-src-mp3")
                ?.takeIf { it.isNotBlank() }
                ?.let { mp3 ->
                    pronunciations += ScrapedPronunciation(null, ipaList.firstOrNull(), mp3)
                }
        }

        // ── Senses ────────────────────────────────────────────────────────
        val senses = mutableListOf<ScrapedSense>()
        val allExamples = mutableListOf<String>()

        doc.select("span.Sense").forEach { senseEl ->
            val pos = senseEl.posFromParent()
            val definition = senseEl.select("span.DEF").firstOrNull()
                ?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val examples = senseEl.select("span.EXAMPLE")
                .map { it.text().trim() }.filter { it.isNotBlank() }
            allExamples += examples
            senses += ScrapedSense(pos = pos, definition = definition, examples = examples)
        }

        // Fallback
        if (senses.isEmpty()) {
            doc.select("span.DEF").forEach { el ->
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

    /** Walk up DOM to find POS from the nearest dictentry Head. */
    private fun Element.posFromParent(): String? {
        var el: Element? = this
        while (el != null) {
            if (el.hasClass("dictentry")) {
                return el.select("span.Head span.POS").firstOrNull()?.text()
            }
            el = el.parent()
        }
        return select("span.POS").firstOrNull()?.text()
    }
}
