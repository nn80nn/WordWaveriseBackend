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
    val savedAt = timestamp("saved_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Create unique index on user_id and word combination
        uniqueIndex(userId, word)
    }
}
