package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Место в книге, которое читатель отметил сам.
 *
 * Отдельно от [ReadingPositions], потому что это разные вещи: позиция — «где я сейчас», и она
 * одна и переписывается на каждом экране; закладка — «сюда я хочу вернуться», и их сколько
 * угодно. Хранить одну таблицу вместо двух значило бы либо терять место чтения при каждой
 * закладке, либо считать последнюю закладку текущим местом.
 */
object BookBookmarks : Table("book_bookmarks") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val bookId = integer("book_id").references(Books.id)

    /** [BookBlocks.ordinal] отмеченного блока — тот же адрес, что у позиции чтения. */
    val ordinal = integer("ordinal")

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Отметить одно и то же место дважды — это одна закладка, а не две.
        uniqueIndex(userId, bookId, ordinal)
    }
}
