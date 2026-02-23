package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table

object ScraperCache : Table("scraper_cache") {
    val id = integer("id").autoIncrement()
    val cacheKey = varchar("cache_key", 512).uniqueIndex()
    val word = varchar("word", 255)
    val sourceId = varchar("source_id", 50)
    val dataJson = text("data_json")
    val fetchedAt = long("fetched_at")
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(id)
}
