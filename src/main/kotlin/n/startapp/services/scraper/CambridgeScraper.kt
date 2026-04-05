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

class CambridgeScraper(private val httpClient: HttpClient) {

    companion object {
        const val SOURCE_ID = "CAMBRIDGE"
        const val PARSER_VERSION = "v2"
        private const val BASE_URL = "https://dictionary.cambridge.org"
        private const val RATE_LIMIT_MS = 1200L
        private val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val logger = LoggerFactory.getLogger(CambridgeScraper::class.java)
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    suspend fun scrape(word: String): ScrapeEnrichment? {
        val slug = word.trim().lowercase().replace(' ', '-')
        val url = "$BASE_URL/dictionary/english/$slug"
        val startMs = System.currentTimeMillis()
        return try {
            logger.info("Cambridge: starting scrape for '$word' → $url")
            val html = fetchHtml(url)
            logger.debug("Cambridge: fetched ${html.length} bytes for '$word'")
            val result = parseHtml(word, url, html)
            val elapsed = System.currentTimeMillis() - startMs
            if (result != null) {
                logger.info(
                    "Cambridge OK for '$word' in ${elapsed}ms: " +
                    "${result.pronunciations.size} pronunciations " +
                    "(${result.pronunciations.joinToString { "${it.region}:ipa=${it.ipa != null},mp3=${it.audioMp3Url != null}" }}), " +
                    "${result.senses.size} senses, ${result.examples.size} examples"
                )
            } else {
                logger.info("Cambridge: no usable data found for '$word' (${elapsed}ms)")
            }
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            logger.warn("Cambridge scrape failed for '$word' after ${elapsed}ms: ${e.message}")
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

    internal fun parseHtml(word: String, url: String, html: String): ScrapeEnrichment? {
        val doc = Jsoup.parse(html)

        val pronunciations = mutableListOf<ScrapedPronunciation>()
        val senses = mutableListOf<ScrapedSense>()
        val allExamples = mutableListOf<String>()

        // ── Try per-homograph entry blocks (div.entry-body__el) ───────────
        // Cambridge groups homographs into separate blocks, each with its own POS + pronunciation
        val entryBlocks = doc.select("div.entry-body__el")
        if (entryBlocks.isNotEmpty()) {
            entryBlocks.forEach { entryEl ->
                // POS for this homograph entry (from di-info header)
                val entryPos = entryEl.select("div.di-info span.pos.dpos").firstOrNull()?.text()
                    ?: entryEl.select("div.di-info .posgram .pos").firstOrNull()?.text()

                // Per-entry pronunciations (tagged with POS for homograph disambiguation)
                entryEl.select("div.di-info span.uk.dpron-i").firstOrNull()?.let { el ->
                    val ipa = el.select("span.ipa.dipa").firstOrNull()?.text()?.let { "/$it/" }
                    val mp3 = el.select("audio source[type=audio/mpeg]").firstOrNull()
                        ?.attr("src")?.prefixBase()
                    if (ipa != null || mp3 != null)
                        pronunciations += ScrapedPronunciation("uk", ipa, mp3, pos = entryPos)
                }
                entryEl.select("div.di-info span.us.dpron-i").firstOrNull()?.let { el ->
                    val ipa = el.select("span.ipa.dipa").firstOrNull()?.text()?.let { "/$it/" }
                    val mp3 = el.select("audio source[type=audio/mpeg]").firstOrNull()
                        ?.attr("src")?.prefixBase()
                    if (ipa != null || mp3 != null)
                        pronunciations += ScrapedPronunciation("us", ipa, mp3, pos = entryPos)
                }

                // Parse senses within this entry
                entryEl.select("div.dsense").forEach { dsenseEl ->
                    val pos = dsenseEl.select("span.pos.dpos").firstOrNull()?.text() ?: entryPos
                    val guideWord = dsenseEl.select("span.guideword.dsense_gw span").firstOrNull()?.text()
                    dsenseEl.select("div.def-block.ddef_block").forEach { defBlock ->
                        val definition = defBlock.select("div.def.ddef_d").firstOrNull()
                            ?.text()?.trimEnd(':')?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                        val examples = defBlock.select("span.eg.deg")
                            .map { it.text().trim() }.filter { it.isNotBlank() }
                        allExamples += examples
                        senses += ScrapedSense(
                            pos = pos, guideWord = guideWord,
                            level = defBlock.select("span.epp-xref").firstOrNull()?.text(),
                            grammar = defBlock.select("span.gram.dgram").firstOrNull()?.text(),
                            definition = definition, examples = examples
                        )
                    }
                }
            }
        }

        // ── Fallback: global pronunciation selectors if per-entry failed ──
        if (pronunciations.isEmpty()) {
            doc.select("span.uk.dpron-i").firstOrNull()?.let { el ->
                val ipa = el.select("span.ipa.dipa").firstOrNull()?.text()?.let { "/$it/" }
                val mp3 = el.select("audio source[type=audio/mpeg]").firstOrNull()
                    ?.attr("src")?.prefixBase()
                if (ipa != null || mp3 != null)
                    pronunciations += ScrapedPronunciation("uk", ipa, mp3)
            }
            doc.select("span.us.dpron-i").firstOrNull()?.let { el ->
                val ipa = el.select("span.ipa.dipa").firstOrNull()?.text()?.let { "/$it/" }
                val mp3 = el.select("audio source[type=audio/mpeg]").firstOrNull()
                    ?.attr("src")?.prefixBase()
                if (ipa != null || mp3 != null)
                    pronunciations += ScrapedPronunciation("us", ipa, mp3)
            }
        }

        // ── Fallback: global dsense parsing if per-entry failed ──────────
        if (senses.isEmpty()) {
            doc.select("div.dsense").forEach { dsenseEl ->
                val pos = dsenseEl.select("span.pos.dpos").firstOrNull()?.text()
                val guideWord = dsenseEl.select("span.guideword.dsense_gw span").firstOrNull()?.text()
                dsenseEl.select("div.def-block.ddef_block").forEach { defBlock ->
                    val definition = defBlock.select("div.def.ddef_d").firstOrNull()
                        ?.text()?.trimEnd(':')?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    val examples = defBlock.select("span.eg.deg")
                        .map { it.text().trim() }.filter { it.isNotBlank() }
                    allExamples += examples
                    senses += ScrapedSense(
                        pos = pos, guideWord = guideWord,
                        level = defBlock.select("span.epp-xref").firstOrNull()?.text(),
                        grammar = defBlock.select("span.gram.dgram").firstOrNull()?.text(),
                        definition = definition, examples = examples
                    )
                }
            }
        }

        // Last-resort fallback
        if (senses.isEmpty()) {
            doc.select("div.def.ddef_d").forEach { el ->
                val def = el.text().trimEnd(':').trim()
                if (def.isNotBlank())
                    senses += ScrapedSense(definition = def)
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

    private fun String.prefixBase() =
        if (startsWith("/")) "$BASE_URL$this" else this
}
