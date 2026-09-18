package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * One row per (reader, book, day): the daily cap on offline downloads.
 *
 * Warming a whole book is thousands of model calls — cheap per call, but a novel a day per
 * reader adds up across a library, so it is rationed like anything else that spends the AI
 * budget. [day] rather than [requestedAt] alone because "how many today" has to be a count
 * distinct rather than a scan with a time-range filter re-derived on every check.
 *
 * ⚠️ Re-requesting the same book on the same day is not a second download — the unique index
 * makes that an upsert-shaped no-op rather than a second row, so resuming an interrupted
 * download never costs the quota twice.
 */
object BookOfflineDownloads : Table("book_offline_downloads") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val bookId = integer("book_id").references(Books.id, onDelete = ReferenceOption.CASCADE)

    /** UTC calendar day as `YYYY-MM-DD` — the unit the daily cap counts in. */
    val day = varchar("day", 10)

    val requestedAt = timestamp("requested_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, bookId, day)
    }
}
