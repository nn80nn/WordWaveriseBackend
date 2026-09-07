package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * How far one reader has got in one book.
 *
 * ⚠️ Last write wins, and [updatedAt] is kept so the client can *ask* rather than jump: someone
 * who deliberately turned back on the phone must not be dragged forward the moment the tablet
 * syncs. The server states where each device left off; deciding is the reader's.
 */
object ReadingPositions : Table("reading_positions") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val bookId = integer("book_id").references(Books.id, onDelete = ReferenceOption.CASCADE)

    /** [BookBlocks.ordinal] of the block at the top of the screen. */
    val ordinal = integer("ordinal")

    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, bookId)
    }
}
