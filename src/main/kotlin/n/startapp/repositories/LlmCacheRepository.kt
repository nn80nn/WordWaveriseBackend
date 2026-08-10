package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.LlmCache
import n.startapp.services.ai.LlmUsage
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest

class LlmCacheRepository {
    private val logger = LoggerFactory.getLogger(LlmCacheRepository::class.java)

    /** Cached payload, or null when absent or expired. Bumps the hit counter on a hit. */
    suspend fun get(cacheKey: String): String? = dbQuery {
        val row = LlmCache.selectAll().where { LlmCache.cacheKey eq cacheKey }.firstOrNull()
            ?: return@dbQuery null

        val expiresAt = row[LlmCache.expiresAt]
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
            LlmCache.deleteWhere { LlmCache.cacheKey eq cacheKey }
            return@dbQuery null
        }

        LlmCache.update({ LlmCache.cacheKey eq cacheKey }) {
            it[hitCount] = row[LlmCache.hitCount] + 1
        }
        row[LlmCache.payloadJson]
    }

    /**
     * Stores a payload. A cache write must never fail the request that produced it — the
     * result is already in hand, and losing the cache entry only costs one repeated call.
     */
    suspend fun put(
        cacheKey: String,
        task: String,
        payloadJson: String,
        model: String,
        promptVersion: Int,
        usage: LlmUsage = LlmUsage(),
        ttlMs: Long? = null
    ) {
        runCatching {
            dbQuery {
                val now = System.currentTimeMillis()
                val exists = LlmCache.selectAll().where { LlmCache.cacheKey eq cacheKey }.any()
                if (exists) {
                    LlmCache.update({ LlmCache.cacheKey eq cacheKey }) {
                        it[LlmCache.payloadJson] = payloadJson
                        it[LlmCache.model] = model
                        it[LlmCache.promptVersion] = promptVersion
                        it[promptTokens] = usage.promptTokens
                        it[completionTokens] = usage.completionTokens
                        it[createdAt] = now
                        it[expiresAt] = ttlMs?.let { ttl -> now + ttl }
                    }
                } else {
                    LlmCache.insert {
                        it[LlmCache.cacheKey] = cacheKey
                        it[LlmCache.task] = task
                        it[LlmCache.payloadJson] = payloadJson
                        it[LlmCache.model] = model
                        it[LlmCache.promptVersion] = promptVersion
                        it[promptTokens] = usage.promptTokens
                        it[completionTokens] = usage.completionTokens
                        it[createdAt] = now
                        it[expiresAt] = ttlMs?.let { ttl -> now + ttl }
                    }
                }
            }
        }.onFailure { logger.warn("Failed to cache LLM result for '$cacheKey': ${it.message}") }
    }

    companion object {
        /** Cache key shared by every task: "{task}|v{promptVersion}|{sha256(input)}". */
        fun key(task: String, promptVersion: Int, input: String): String =
            "$task|v$promptVersion|${sha256(input)}"

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
