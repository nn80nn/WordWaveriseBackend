package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * SavedWords table definition
 */
object SavedWords : Table("saved_words") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val word = varchar("word", 255)
    val translation = varchar("translation", 500).nullable()
    val definition = text("definition").nullable()
    val example = text("example").nullable()

    /**
     * Which sense of the article the user pinned ("n1", "v2"), or null for "whatever the
     * article puts first". The id is assigned server-side when the article is annotated, so it
     * is stable across re-reads and cannot be fabricated by a client.
     */
    val senseId = varchar("sense_id", 32).nullable()

    val savedAt = timestamp("saved_at").clientDefault { Instant.now() }
    val categoryId = integer("category_id").references(Categories.id).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // Create unique index on user_id and word combination
        uniqueIndex(userId, word)
    }
}
