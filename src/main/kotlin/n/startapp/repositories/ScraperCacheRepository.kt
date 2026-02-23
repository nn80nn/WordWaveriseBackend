package n.startapp.repositories

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.ScraperCache
import n.startapp.models.scraper.ScrapeEnrichment
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory

class ScraperCacheRepository {
    private val logger = LoggerFactory.getLogger(ScraperCacheRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns cached enrichment if it exists and hasn't expired. */
    suspend fun get(cacheKey: String): ScrapeEnrichment? = dbQuery {
        val now = System.currentTimeMillis()
        ScraperCache.select { ScraperCache.cacheKey eq cacheKey }
            .firstOrNull()
            ?.let { row ->
                if (row[ScraperCache.expiresAt] < now) {
                    // Expired — clean up lazily
                    ScraperCache.deleteWhere { ScraperCache.cacheKey eq cacheKey }
                    return@dbQuery null
                }
                try {
                    json.decodeFromString<ScrapeEnrichment>(row[ScraperCache.dataJson])
                } catch (e: Exception) {
                    logger.warn("Failed to deserialize cached scrape for key '$cacheKey': ${e.message}")
                    null
                }
            }
    }

    /** Stores scrape result in the DB cache with given TTL in days. */
    suspend fun put(cacheKey: String, data: ScrapeEnrichment, ttlDays: Int = 14) = dbQuery {
        val now = System.currentTimeMillis()
        val expiresAt = now + ttlDays.toLong() * 24 * 60 * 60 * 1000
        val dataJson = json.encodeToString(data)

        val existing = ScraperCache.select { ScraperCache.cacheKey eq cacheKey }.firstOrNull()
        if (existing == null) {
            ScraperCache.insert {
                it[ScraperCache.cacheKey] = cacheKey
                it[ScraperCache.word] = data.word
                it[ScraperCache.sourceId] = data.source
                it[ScraperCache.dataJson] = dataJson
                it[ScraperCache.fetchedAt] = now
                it[ScraperCache.expiresAt] = expiresAt
            }
        } else {
            ScraperCache.update({ ScraperCache.cacheKey eq cacheKey }) {
                it[ScraperCache.dataJson] = dataJson
                it[ScraperCache.fetchedAt] = now
                it[ScraperCache.expiresAt] = expiresAt
            }
        }
    }
}
