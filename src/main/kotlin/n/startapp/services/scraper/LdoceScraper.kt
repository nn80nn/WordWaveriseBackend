package n.startapp.services.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
        const val PARSER_VERSION = "v4"
        private const val BASE_URL = "https://www.ldoceonline.com"
        private const val RATE_LIMIT_MS = 1500L
        private val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val CLOUDFLARE_TITLES = listOf(
            "just a moment", "attention required", "please wait",
            "checking your browser", "ddos-guard", "403 forbidden",
            "access denied", "enable javascript"
        )
    }

    private val logger = LoggerFactory.getLogger(LdoceScraper::class.java)
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    suspend fun scrape(word: String): ScrapeEnrichment? {
        val slug = word.trim().lowercase().replace(' ', '-')
        val startMs = System.currentTimeMillis()

        // Try multiple URL patterns: standard, numbered variant, and english-specific path
        val urlsToTry = listOf(
            "$BASE_URL/dictionary/$slug",
            "$BASE_URL/dictionary/${slug}_1",
            "$BASE_URL/dictionary/english/$slug"
        )

        var cloudflareBlocked = false

        for (url in urlsToTry) {
            if (cloudflareBlocked) break
            val result = try {
                logger.info("LDOCE: trying '$word' → $url")
                val html = fetchHtml(url)
                // Quick Cloudflare check before full parse
                val titleMatch = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)?.trim()?.lowercase() ?: ""
                logger.info("LDOCE: got ${html.length} bytes, title='$titleMatch' for '$word'")
                if (CLOUDFLARE_TITLES.any { titleMatch.contains(it) }) {
                    logger.warn("LDOCE: Cloudflare detected for '$word', bailing all URLs")
                    cloudflareBlocked = true
                    null
                } else {
                    parseHtml(word, url, html)
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startMs
                logger.warn("LDOCE scrape failed for '$word' at $url after ${elapsed}ms: ${e.message}")
                null
            }

            if (cloudflareBlocked) break

            if (result != null) {
                val elapsed = System.currentTimeMillis() - startMs
                logger.info(
                    "LDOCE OK for '$word' in ${elapsed}ms: " +
                    "${result.pronunciations.size} pronunciations " +
                    "(${result.pronunciations.joinToString { "${it.region}:ipa=${it.ipa != null},mp3=${it.audioMp3Url != null}" }}), " +
                    "${result.senses.size} senses, ${result.examples.size} examples"
                )
                return result
            } else {
                val elapsed = System.currentTimeMillis() - startMs
                logger.info("LDOCE: no usable data at $url (${elapsed}ms), trying next")
            }
        }

        val elapsed = System.currentTimeMillis() - startMs
        logger.info("LDOCE: no usable data found for '$word' (${elapsed}ms)")
        return null
    }

    // ── Rate-limited HTTP fetch ──────────────────────────────────────────────

    private suspend fun fetchHtml(url: String): String {
        mutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestTime
            if (elapsed < RATE_LIMIT_MS) delay(RATE_LIMIT_MS - elapsed)
            lastRequestTime = System.currentTimeMillis()
        }
        val response: HttpResponse = httpClient.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            // NOTE: Do NOT send Accept-Encoding — Ktor CIO has no ContentEncoding plugin,
            // so gzip responses would arrive as raw compressed bytes and break JSoup parsing.
            header("Referer", "https://www.google.com/")
            header("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            header("sec-ch-ua-mobile", "?0")
            header("sec-ch-ua-platform", "\"Windows\"")
            header("sec-fetch-dest", "document")
            header("sec-fetch-mode", "navigate")
            header("sec-fetch-site", "cross-site")
            header("sec-fetch-user", "?1")
            header("Upgrade-Insecure-Requests", "1")
            header("Cache-Control", "max-age=0")
            // Simulate accepted GDPR/OneTrust consent to bypass cookie consent wall
            header(
                "Cookie",
                "OptanonAlertBoxClosed=2025-01-01T00:00:00.000Z; " +
                "OptanonConsent=isGpcEnabled=0&datestamp=Wed+Jan+01+2025&version=202501.1.0&" +
                "interactionCount=2&groups=C0001%3A1%2CC0002%3A1%2CC0003%3A1%2CC0004%3A1; " +
                "cf_clearance=placeholder"
            )
        }
        val statusCode = response.status.value
        if (statusCode == 403 || statusCode == 429) {
            logger.warn("LDOCE: HTTP $statusCode at $url — likely bot-protection, giving up")
            throw Exception("HTTP $statusCode bot-protection")
        }
        if (statusCode !in 200..299) {
            logger.warn("LDOCE: HTTP $statusCode at $url")
            throw Exception("HTTP $statusCode")
        }
        return response.bodyAsText()
    }

    // ── HTML parsing ─────────────────────────────────────────────────────────

    internal fun parseHtml(word: String, url: String, html: String): ScrapeEnrichment? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)

        // Detect Cloudflare / bot-protection challenge pages
        val pageTitle = doc.title().lowercase()
        if (CLOUDFLARE_TITLES.any { pageTitle.contains(it) }) {
            logger.warn("LDOCE: Cloudflare/bot-protection detected for '$word' at $url (title='${doc.title()}')")
            return null
        }

        // Detect redirect / "word not found" pages (no dictionary content at all)
        val hasDictContent = doc.select("span.DEF, span.Sense, .dictentry, .entry_content").isNotEmpty()
        val hasMetaRedirect = doc.select("meta[http-equiv=refresh]").isNotEmpty()
        if (!hasDictContent && (hasMetaRedirect || doc.select("body").text().length < 200)) {
            logger.info("LDOCE: no dictionary content at $url for '$word'")
            return null
        }

        // ── Pronunciations ────────────────────────────────────────────────
        val pronunciations = mutableListOf<ScrapedPronunciation>()

        // IPA texts — first two PronCodes entries
        val ipaList = doc.select("span.PronCodes span.PRON").map { "/${it.text()}/" }

        // British English audio — try multiple selector patterns
        val brMp3 = listOf(
            "span.speaker.brefile",
            ".BrEPrn span.speaker",
            "[class*='brefile'][data-src-mp3]"
        ).map { doc.select(it).firstOrNull()?.attr("data-src-mp3")?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.toAbsoluteUrl()

        // American English audio
        val amMp3 = listOf(
            "span.speaker.amefile",
            ".AmEPrn span.speaker",
            "[class*='amefile'][data-src-mp3]"
        ).map { doc.select(it).firstOrNull()?.attr("data-src-mp3")?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.toAbsoluteUrl()

        if (brMp3 != null || ipaList.isNotEmpty())
            pronunciations += ScrapedPronunciation("uk", ipaList.getOrNull(0), brMp3)
        if (amMp3 != null)
            pronunciations += ScrapedPronunciation("us", ipaList.getOrNull(1), amMp3)

        // Fallback: first [data-src-mp3] if nothing found
        if (pronunciations.isEmpty()) {
            doc.select("[data-src-mp3]").firstOrNull()?.attr("data-src-mp3")
                ?.takeIf { it.isNotBlank() }
                ?.let { mp3 ->
                    pronunciations += ScrapedPronunciation(null, ipaList.firstOrNull(), mp3.toAbsoluteUrl())
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

        // Fallback: any span.DEF on the page
        if (senses.isEmpty()) {
            doc.select("span.DEF").forEach { el ->
                val def = el.text().trim()
                if (def.isNotBlank()) senses += ScrapedSense(definition = def)
            }
        }

        // Broader fallback: look for definition-like elements
        if (senses.isEmpty()) {
            doc.select(".definition, .sense-definition, [class*='DEF']").forEach { el ->
                val def = el.text().trim()
                if (def.isNotBlank() && def.length > 10) senses += ScrapedSense(definition = def)
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
    internal fun Element.posFromParent(): String? {
        var el: Element? = this
        while (el != null) {
            if (el.hasClass("dictentry")) {
                return el.select("span.Head span.POS").firstOrNull()?.text()
            }
            el = el.parent()
        }
        return select("span.POS").firstOrNull()?.text()
    }

    /** Convert relative URL to absolute by prepending BASE_URL if needed. */
    private fun String.toAbsoluteUrl() = if (startsWith("http")) this else "$BASE_URL$this"
}
