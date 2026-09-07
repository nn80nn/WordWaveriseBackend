package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * A chapter is a table of contents entry and nothing more: the text itself lives in
 * [BookBlocks], addressed by a single ordinal that runs across the whole book.
 *
 * Stored rather than derived because the alternative is a GROUP BY over every block of the book
 * on each open, to answer a question — "what are the chapters called" — whose answer never
 * changes after import.
 */
object BookChapters : Table("book_chapters") {
    val id = integer("id").autoIncrement()
    val bookId = integer("book_id").references(Books.id, onDelete = ReferenceOption.CASCADE)

    /** 0-based position in the spine. */
    val index = integer("idx")
    val title = varchar("title", 500).nullable()

    /** Ordinal of this chapter's first block, so opening a chapter is one indexed read. */
    val firstOrdinal = integer("first_ordinal")
    val blockCount = integer("block_count")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(bookId, index)
    }
}
