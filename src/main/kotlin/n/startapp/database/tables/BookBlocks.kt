package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * One paragraph, heading, quote or list item — the unit the reader renders and anchors to.
 *
 * ⚠️ [ordinal] is the book's address space, and it is stored rather than computed. A position
 * saved on a phone has to mean the same place when the tablet opens the book, so the number it
 * refers to may not shift when anything else changes; a chapter-relative index would have to be
 * added up, and adding it up needs every preceding chapter's size at read time.
 *
 * Sentences are deliberately *not* stored. They are what a tap sends to
 * `/api/v2/context/analyze`, and they are derived from the block text by
 * [n.startapp.services.reader.SentenceSplitter] on read — so improving the splitter improves
 * every book already imported, instead of only the ones imported after the deploy.
 */
object BookBlocks : Table("book_blocks") {
    val id = integer("id").autoIncrement()
    val bookId = integer("book_id").references(Books.id, onDelete = ReferenceOption.CASCADE)

    /** 0-based across the whole book. The only anchor a reading position needs. */
    val ordinal = integer("ordinal")

    val chapterIndex = integer("chapter_index")

    /** [n.startapp.models.reader.BlockKind] name. */
    val kind = varchar("kind", 16)

    val text = text("text")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(bookId, ordinal)
    }
}
