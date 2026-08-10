package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table

/**
 * Persistent cache for auxiliary LLM results (query resolution, context analysis, RU→EN).
 *
 * Distinct from the in-process Caffeine cache because these results are expensive, identical
 * for every user, and must survive restarts — a redeploy previously threw away every AI result
 * the process had produced.
 */
object LlmCache : Table("llm_cache") {
    val id = long("id").autoIncrement()

    /** "{task}|v{promptVersion}|{sha256(input)}" */
    val cacheKey = varchar("cache_key", 512).uniqueIndex()
    val task = varchar("task", 48).index()
    val payloadJson = text("payload_json")
    val model = varchar("model", 96)
    val promptVersion = integer("prompt_version")
    val promptTokens = integer("prompt_tokens").default(0)
    val completionTokens = integer("completion_tokens").default(0)
    val createdAt = long("created_at")

    /** null = never expires. */
    val expiresAt = long("expires_at").nullable()
    val hitCount = long("hit_count").default(0)

    override val primaryKey = PrimaryKey(id)
}
