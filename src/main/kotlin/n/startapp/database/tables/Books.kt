package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * One book in one reader's library.
 *
 * What is stored is the *normalised* text, never the uploaded file. Two reasons, and both matter:
 * the blocks are what positions, highlights and taps anchor to, so they have to exist anyway; and
 * a copy of somebody's EPUB sitting on our disk is a licensing question we have no reason to ask.
 * Re-importing the same file therefore costs one parse and no storage — [contentHash] recognises
 * it and hands back the book that is already there, with its reading position intact.
 */
object Books : Table("books") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)

    val title = varchar("title", 500)
    val author = varchar("author", 300).nullable()

    /** BCP-47 as the file declared it, or null. Nothing branches on it yet; the reader is en→ru. */
    val language = varchar("language", 16).nullable()

    /** "EPUB" | "FB2" | "TXT" | "HTML" | "PASTE" — where the text came from, for diagnostics. */
    val format = varchar("format", 16)

    /**
     * SHA-256 over the normalised text, not over the file.
     *
     * Two exports of the same book differ byte for byte (timestamps, cover compression, reader
     * metadata) while the text is identical, so hashing the upload would file the same novel
     * twice and split its reading position across the copies.
     */
    val contentHash = varchar("content_hash", 64)

    val chapterCount = integer("chapter_count")
    val blockCount = integer("block_count")
    val wordCount = integer("word_count")

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        // One row per (reader, text): the same book imported twice is the same book.
        uniqueIndex(userId, contentHash)
    }
}
