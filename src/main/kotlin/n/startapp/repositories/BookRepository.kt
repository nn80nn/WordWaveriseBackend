package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.BookBlocks
import n.startapp.database.tables.BookChapters
import n.startapp.database.tables.Books
import n.startapp.database.tables.Categories
import n.startapp.database.tables.ReadingPositions
import n.startapp.models.reader.BlockDTO
import n.startapp.models.reader.BlockKind
import n.startapp.models.reader.BlockPageDTO
import n.startapp.models.reader.BookDTO
import n.startapp.models.reader.BookDetailDTO
import n.startapp.models.reader.ChapterDTO
import n.startapp.models.reader.ReadingPositionDTO
import n.startapp.services.context.Tokenizer
import n.startapp.services.reader.ParsedBook
import n.startapp.services.reader.SentenceSplitter
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

class BookRepository {

    /** How many blocks one request may carry. Beyond this the reader is prefetching, not reading. */
    private val maxPageSize = 200

    suspend fun findByHash(userId: Int, contentHash: String): BookDTO? = dbQuery {
        Books.selectAll()
            .where { (Books.userId eq userId) and (Books.contentHash eq contentHash) }
            .limit(1)
            .firstOrNull()
            ?.let { row -> toDTO(row, positionOf(userId, row[Books.id], row[Books.blockCount])) }
    }

    /**
     * Writes the book, its chapters and every block in one transaction.
     *
     * All or nothing on purpose: a half-imported book is worse than a failed import, because it
     * opens, looks right for two chapters and then stops, and nothing about it says why.
     */
    suspend fun insert(userId: Int, parsed: ParsedBook, contentHash: String): BookDTO = dbQuery {
        val blockCount = parsed.chapters.sumOf { it.blocks.size }
        val wordCount = parsed.chapters.sumOf { chapter ->
            chapter.blocks.sumOf { block -> countWords(block.text) }
        }

        val bookId = Books.insert {
            it[Books.userId] = userId
            it[title] = parsed.title.take(500)
            it[author] = parsed.author?.take(300)
            it[language] = parsed.language?.take(16)
            it[format] = parsed.format
            it[Books.contentHash] = contentHash
            it[chapterCount] = parsed.chapters.size
            it[Books.blockCount] = blockCount
            it[Books.wordCount] = wordCount
        }[Books.id]

        // The ordinal is assigned here, once, walking the chapters in reading order. It is the
        // book's address space from this point on: positions store it and blocks are fetched by
        // it, so nothing may renumber it afterwards.
        var ordinal = 0
        val chapterRows = mutableListOf<ChapterRow>()
        val blockRows = mutableListOf<BlockRow>()

        parsed.chapters.forEachIndexed { chapterIndex, chapter ->
            chapterRows += ChapterRow(chapterIndex, chapter.title, ordinal, chapter.blocks.size)
            for (block in chapter.blocks) {
                blockRows += BlockRow(ordinal, chapterIndex, block.kind.name, block.text)
                ordinal++
            }
        }

        BookChapters.batchInsert(chapterRows) { row ->
            this[BookChapters.bookId] = bookId
            this[BookChapters.index] = row.index
            this[BookChapters.title] = row.title?.take(500)
            this[BookChapters.firstOrdinal] = row.firstOrdinal
            this[BookChapters.blockCount] = row.blockCount
        }

        BookBlocks.batchInsert(blockRows) { row ->
            this[BookBlocks.bookId] = bookId
            this[BookBlocks.ordinal] = row.ordinal
            this[BookBlocks.chapterIndex] = row.chapterIndex
            this[BookBlocks.kind] = row.kind
            this[BookBlocks.text] = row.text
        }

        Books.selectAll().where { Books.id eq bookId }.first().let { toDTO(it, null) }
    }

    suspend fun listFor(userId: Int): List<BookDTO> = dbQuery {
        Books.selectAll()
            .where { Books.userId eq userId }
            .orderBy(Books.createdAt to SortOrder.DESC)
            .map { row -> toDTO(row, positionOf(userId, row[Books.id], row[Books.blockCount])) }
    }

    suspend fun detail(userId: Int, bookId: Int): BookDetailDTO? = dbQuery {
        val row = ownedRow(userId, bookId) ?: return@dbQuery null
        val chapters = BookChapters.selectAll()
            .where { BookChapters.bookId eq bookId }
            .orderBy(BookChapters.index to SortOrder.ASC)
            .map {
                ChapterDTO(
                    index = it[BookChapters.index],
                    title = it[BookChapters.title],
                    firstOrdinal = it[BookChapters.firstOrdinal],
                    blockCount = it[BookChapters.blockCount]
                )
            }
        BookDetailDTO(
            book = toDTO(row, positionOf(userId, bookId, row[Books.blockCount])),
            chapters = chapters
        )
    }

    /**
     * A window of blocks, with sentences and tokens derived here rather than stored.
     *
     * [withTokens] exists because two callers want different things from the same rows: a reader
     * needs every word tappable, while a table of contents or a preview needs the text and
     * nothing else — and tokens are by far the larger half of the payload.
     */
    suspend fun blocks(
        userId: Int,
        bookId: Int,
        from: Int,
        limit: Int,
        withTokens: Boolean
    ): BlockPageDTO? = dbQuery {
        val row = ownedRow(userId, bookId) ?: return@dbQuery null
        val size = limit.coerceIn(1, maxPageSize)
        val start = from.coerceAtLeast(0)

        val blocks = BookBlocks.selectAll()
            .where { (BookBlocks.bookId eq bookId) and (BookBlocks.ordinal greaterEq start) }
            .orderBy(BookBlocks.ordinal to SortOrder.ASC)
            .limit(size)
            .map { toBlockDTO(it, withTokens) }

        val last = blocks.lastOrNull()?.ordinal
        BlockPageDTO(
            bookId = bookId,
            from = start,
            blocks = blocks,
            nextOrdinal = if (last != null && last + 1 < row[Books.blockCount]) last + 1 else null
        )
    }

    suspend fun setPosition(userId: Int, bookId: Int, ordinal: Int): ReadingPositionDTO? = dbQuery {
        val row = ownedRow(userId, bookId) ?: return@dbQuery null
        val blockCount = row[Books.blockCount]
        val clamped = ordinal.coerceIn(0, (blockCount - 1).coerceAtLeast(0))
        val now = Instant.now()

        val existing = ReadingPositions.selectAll()
            .where { (ReadingPositions.userId eq userId) and (ReadingPositions.bookId eq bookId) }
            .limit(1)
            .firstOrNull()

        if (existing == null) {
            ReadingPositions.insert {
                it[ReadingPositions.userId] = userId
                it[ReadingPositions.bookId] = bookId
                it[ReadingPositions.ordinal] = clamped
                it[updatedAt] = now
            }
        } else {
            ReadingPositions.update({
                (ReadingPositions.userId eq userId) and (ReadingPositions.bookId eq bookId)
            }) {
                it[ReadingPositions.ordinal] = clamped
                it[updatedAt] = now
            }
        }

        positionOf(userId, bookId, blockCount)
    }

    suspend fun delete(userId: Int, bookId: Int): Boolean = dbQuery {
        if (ownedRow(userId, bookId) == null) return@dbQuery false
        // Explicit, in dependency order, rather than trusting ON DELETE CASCADE: the schema is
        // created by `createMissingTablesAndColumns`, which does not add a cascade to a table
        // that already exists without one, so a database older than this file would refuse.
        ReadingPositions.deleteWhere { ReadingPositions.bookId eq bookId }
        BookBlocks.deleteWhere { BookBlocks.bookId eq bookId }
        BookChapters.deleteWhere { BookChapters.bookId eq bookId }
        // ⚠️ The book's folder outlives the book, together with the words in it: those belong to
        // the person, not to the file they once uploaded. Only the marker goes — and it has to go
        // first, because the reference points folder → book and would otherwise refuse the delete.
        Categories.update({ Categories.bookId eq bookId }) { it[Categories.bookId] = null }
        Books.deleteWhere { (Books.id eq bookId) and (Books.userId eq userId) }
        true
    }

    /** Everything one reader owns, for account deletion. */
    suspend fun deleteAllFor(userId: Int) = dbQuery {
        val ids = Books.selectAll().where { Books.userId eq userId }.map { it[Books.id] }
        ReadingPositions.deleteWhere { ReadingPositions.userId eq userId }
        for (bookId in ids) {
            ReadingPositions.deleteWhere { ReadingPositions.bookId eq bookId }
            BookBlocks.deleteWhere { BookBlocks.bookId eq bookId }
            BookChapters.deleteWhere { BookChapters.bookId eq bookId }
        }
        // Same reason as in [delete]: the folder points at the book, so the marker goes first.
        if (ids.isNotEmpty()) {
            Categories.update({ Categories.bookId inList ids }) { it[Categories.bookId] = null }
        }
        Books.deleteWhere { Books.userId eq userId }
        Unit
    }

    private fun ownedRow(userId: Int, bookId: Int): ResultRow? =
        Books.selectAll()
            .where { (Books.id eq bookId) and (Books.userId eq userId) }
            .limit(1)
            .firstOrNull()

    private fun positionOf(userId: Int, bookId: Int, blockCount: Int): ReadingPositionDTO? {
        val row = ReadingPositions.selectAll()
            .where { (ReadingPositions.userId eq userId) and (ReadingPositions.bookId eq bookId) }
            .limit(1)
            .firstOrNull() ?: return null

        val ordinal = row[ReadingPositions.ordinal]
        val chapterIndex = BookBlocks.selectAll()
            .where { (BookBlocks.bookId eq bookId) and (BookBlocks.ordinal eq ordinal) }
            .limit(1)
            .firstOrNull()
            ?.get(BookBlocks.chapterIndex) ?: 0

        return ReadingPositionDTO(
            ordinal = ordinal,
            chapterIndex = chapterIndex,
            progress = if (blockCount <= 0) 0.0 else ordinal.toDouble() / blockCount,
            updatedAt = row[ReadingPositions.updatedAt].toString()
        )
    }

    private fun toBlockDTO(row: ResultRow, withTokens: Boolean): BlockDTO {
        val text = row[BookBlocks.text]
        val sentences = if (!withTokens) emptyList() else SentenceSplitter.split(text).map { sentence ->
            sentence.copy(tokens = Tokenizer.tokenize(sentence.text).tokens)
        }
        return BlockDTO(
            ordinal = row[BookBlocks.ordinal],
            chapterIndex = row[BookBlocks.chapterIndex],
            kind = runCatching { BlockKind.valueOf(row[BookBlocks.kind]) }
                .getOrDefault(BlockKind.PARAGRAPH),
            text = text,
            sentences = sentences
        )
    }

    private fun toDTO(row: ResultRow, position: ReadingPositionDTO?) = BookDTO(
        id = row[Books.id],
        title = row[Books.title],
        author = row[Books.author],
        language = row[Books.language],
        format = row[Books.format],
        chapterCount = row[Books.chapterCount],
        blockCount = row[Books.blockCount],
        wordCount = row[Books.wordCount],
        createdAt = row[Books.createdAt].toString(),
        position = position
    )

    private data class ChapterRow(
        val index: Int,
        val title: String?,
        val firstOrdinal: Int,
        val blockCount: Int
    )

    private data class BlockRow(
        val ordinal: Int,
        val chapterIndex: Int,
        val kind: String,
        val text: String
    )

    companion object {
        private val WORD = Regex("\\p{L}[\\p{L}\\p{M}'-]*")

        /** Rough by design: the number answers "how long is this", nothing is counted against it. */
        fun countWords(text: String): Int = WORD.findAll(text).count()
    }
}
