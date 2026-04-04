package n.startapp.services.scraper

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import n.startapp.models.scraper.ScrapeEnrichment
import n.startapp.repositories.ScraperCacheRepository
import org.slf4j.LoggerFactory

class ScraperService(private val cacheRepo: ScraperCacheRepository) {

    private val logger = LoggerFactory.getLogger(ScraperService::class.java)

    private val httpClient = HttpClient(CIO) {
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.NONE
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 20_000
        }
    }

    private val cambridge = CambridgeScraper(httpClient)
    private val ldoce = LdoceScraper(httpClient)
    private val oxford = OxfordScraper(httpClient)
    private val oed = OedScraper(httpClient)

    /**
     * Fetch enrichment data from Cambridge, LDOCE, Oxford (OALD), and OED in parallel.
     * Results are cached in Postgres (TTL 14 days). Returns empty list on total failure.
     */
    suspend fun enrichWord(word: String): List<ScrapeEnrichment> = coroutineScope {
        val startMs = System.currentTimeMillis()
        // LDOCE removed — ldoceonline.com is blocked by Cloudflare on server IPs
        val results = listOf(
            async { scrapeWithCache(CambridgeScraper.SOURCE_ID, word) },
            async { scrapeWithCache(OxfordScraper.SOURCE_ID, word) },
            async { scrapeWithCache(OedScraper.SOURCE_ID, word) }
        ).awaitAll().filterNotNull()
        val elapsed = System.currentTimeMillis() - startMs
        if (results.isEmpty()) {
            logger.warn("ScraperService: all scrapers returned nothing for '$word' (${elapsed}ms)")
        } else {
            logger.info(
                "ScraperService: enrichWord('$word') done in ${elapsed}ms — " +
                "sources: ${results.map { it.source }.joinToString()}, " +
                "total senses: ${results.sumOf { it.senses.size }}, " +
                "total pronunciations: ${results.sumOf { it.pronunciations.size }}"
            )
        }
        results
    }

    private suspend fun scrapeWithCache(sourceId: String, word: String): ScrapeEnrichment? {
        val cacheKey = "$sourceId|$word|ANY|${parserVersion(sourceId)}"
        cacheRepo.get(cacheKey)?.let { cached ->
            logger.info("Scraper cache HIT [$sourceId] for '$word'")
            return cached
        }
        logger.info("Scraper cache MISS [$sourceId] for '$word' — scraping...")

        val result = scrapeWithRetry(sourceId, word)
        if (result != null) {
            try {
                cacheRepo.put(cacheKey, result)
                logger.debug("Cached scrape result [$sourceId] for '$word'")
            } catch (e: Exception) {
                logger.warn("Failed to cache scrape result for '$word' from $sourceId: ${e.message}")
            }
        } else {
            logger.warn("Scraper [$sourceId] returned no data for '$word' after retries")
        }
        return result
    }

    private suspend fun scrapeWithRetry(sourceId: String, word: String, maxAttempts: Int = 1): ScrapeEnrichment? {
        repeat(maxAttempts) { attempt ->
            try {
                val result = when (sourceId) {
                    CambridgeScraper.SOURCE_ID -> cambridge.scrape(word)
                    LdoceScraper.SOURCE_ID -> ldoce.scrape(word)
                    OxfordScraper.SOURCE_ID -> oxford.scrape(word)
                    OedScraper.SOURCE_ID -> oed.scrape(word)
                    else -> null
                }
                if (result != null) return result
                logger.warn("Scraper [$sourceId] attempt ${attempt + 1}/$maxAttempts: null result for '$word'")
            } catch (e: Exception) {
                logger.warn("Scrape attempt ${attempt + 1}/$maxAttempts failed for '$word' from $sourceId: ${e.message}")
                if (attempt + 1 < maxAttempts) kotlinx.coroutines.delay(500)
            }
        }
        return null
    }

    private fun parserVersion(sourceId: String) = when (sourceId) {
        CambridgeScraper.SOURCE_ID -> CambridgeScraper.PARSER_VERSION
        LdoceScraper.SOURCE_ID -> LdoceScraper.PARSER_VERSION
        OxfordScraper.SOURCE_ID -> OxfordScraper.PARSER_VERSION
        OedScraper.SOURCE_ID -> OedScraper.PARSER_VERSION
        else -> "v1"
    }

    fun close() = httpClient.close()
}
