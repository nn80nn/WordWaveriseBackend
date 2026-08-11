package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.WarmupQueue
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/** Words queued for warming by hand. Membership only — the corpus answers "is it done". */
class WarmupQueueRepository {

    /** Oldest first, so a hand-queued word is warmed in the order it was asked for. */
    suspend fun all(): List<Pair<String, Long>> = dbQuery {
        WarmupQueue.selectAll()
            .orderBy(WarmupQueue.addedAt to SortOrder.ASC)
            .map { it[WarmupQueue.word] to it[WarmupQueue.addedAt] }
    }

    suspend fun words(): List<String> = all().map { it.first }

    /** @return true when the word was new to the queue. */
    suspend fun add(word: String): Boolean = dbQuery {
        val normalized = word.trim().lowercase()
        if (normalized.isBlank()) return@dbQuery false
        val exists = WarmupQueue.selectAll().where { WarmupQueue.word eq normalized }.any()
        if (exists) return@dbQuery false
        WarmupQueue.insert {
            it[WarmupQueue.word] = normalized
            it[addedAt] = System.currentTimeMillis()
        }
        true
    }

    suspend fun remove(word: String): Int = dbQuery {
        WarmupQueue.deleteWhere { WarmupQueue.word eq word.trim().lowercase() }
    }
}
