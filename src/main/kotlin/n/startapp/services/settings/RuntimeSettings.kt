package n.startapp.services.settings

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.AppSettings
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Overrides applied on top of the environment, editable while the server runs.
 *
 * Every setting in the app is read through `EnvConfig.get(key, default)`, so intercepting that one
 * function is enough to make any of them adjustable — no call site changes, and a value takes
 * effect on the next read rather than on the next deploy. That matters most for the settings
 * worth touching at all: model choice and warm-up pacing are exactly what you want to try,
 * observe, and revert, and going through a redeploy to change them restarts the run being tuned.
 *
 * Held in memory because reads are synchronous and constant (`EnvConfig.get` is called per
 * request), while writes are rare and go through the admin panel. The database is the durable
 * copy; this map is the one every read actually touches.
 */
object RuntimeSettings {
    private val logger = LoggerFactory.getLogger(RuntimeSettings::class.java)
    private val overrides = ConcurrentHashMap<String, String>()

    /** Null when the environment should be left to answer. */
    fun override(key: String): String? = overrides[key]

    /**
     * Loaded once the database is up; until then every read falls through to the environment.
     *
     * Blocking on purpose. This has to finish before anything reads configuration — the warm-up
     * decides whether to auto-start from a setting during service construction — and a
     * suspending load would let that read happen against an empty map.
     */
    fun load() {
        runCatching {
            val rows = transaction {
                AppSettings.selectAll().associate { it[AppSettings.key] to it[AppSettings.value] }
            }
            overrides.clear()
            overrides.putAll(rows)
            if (rows.isNotEmpty()) {
                logger.info("Runtime settings loaded: {}", rows.keys.sorted().joinToString(", "))
            }
        }.onFailure { logger.warn("Could not load runtime settings: ${it.message}") }
    }

    suspend fun set(key: String, value: String) {
        val now = System.currentTimeMillis()
        dbQuery {
            val exists = AppSettings.selectAll().where { AppSettings.key eq key }.any()
            if (exists) {
                AppSettings.update({ AppSettings.key eq key }) {
                    it[AppSettings.value] = value
                    it[updatedAt] = now
                }
            } else {
                AppSettings.insert {
                    it[AppSettings.key] = key
                    it[AppSettings.value] = value
                    it[updatedAt] = now
                }
            }
        }
        overrides[key] = value
        logger.info("Runtime setting '{}' set to '{}'", key, if (isSecret(key)) "***" else value)
    }

    /** Drops the override so the environment answers again. */
    suspend fun clear(key: String) {
        dbQuery { AppSettings.deleteWhere { AppSettings.key eq key } }
        overrides.remove(key)
        logger.info("Runtime setting '{}' reverted to environment", key)
    }

    fun isSecret(key: String): Boolean =
        key.contains("API") || key.contains("SECRET") || key.contains("PASSWORD") || key.contains("KEY")
}
