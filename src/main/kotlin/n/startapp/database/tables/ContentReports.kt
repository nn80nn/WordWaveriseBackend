package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Something a reader flagged as wrong or offensive in generated text.
 *
 * Articles, examples and exercises are written by a model, and Google Play requires an app that
 * ships generated content to give the reader a way to report it from inside the app. That is the
 * whole point of the table: a complaint that only reaches a support address is a complaint most
 * people never make.
 *
 * The row names *what was on screen* — word, sense, kind — rather than the text itself. The text
 * is regenerated from the corpus on demand, and a copy stored here would go stale the moment the
 * article it complains about is rewritten.
 */
object ContentReports : Table("content_reports") {
    val id = integer("id").autoIncrement()

    /** Nullable so deleting an account does not have to delete the report it left behind. */
    val userId = integer("user_id").references(Users.id).nullable()

    /** What the reader was looking at: `article`, `exercise`, or `other`. */
    val kind = varchar("kind", 20)
    val word = varchar("word", 255)
    val senseId = varchar("sense_id", 64).nullable()

    /** One of a short fixed list the client offers, so reports can be counted, not only read. */
    val reason = varchar("reason", 40)
    val comment = text("comment").nullable()

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val resolvedAt = timestamp("resolved_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
