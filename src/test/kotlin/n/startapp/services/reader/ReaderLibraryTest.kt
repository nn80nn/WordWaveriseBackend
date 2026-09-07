package n.startapp.services.reader

import kotlinx.coroutines.runBlocking
import n.startapp.database.TestDatabase
import n.startapp.database.tables.Users
import n.startapp.models.reader.ImportTextRequest
import n.startapp.database.tables.BookBlocks
import n.startapp.database.tables.BookChapters
import n.startapp.database.tables.Books
import n.startapp.database.tables.ReadingPositions
import n.startapp.repositories.BookRepository
import n.startapp.services.AccountDeletionService
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library, end to end: import, page, remember the place, delete.
 *
 * The thing under test that is easy to get wrong is the ordinal. It is the book's address space —
 * positions store it and blocks are fetched by it — so it has to be assigned once, in reading
 * order, and never move again.
 */
class ReaderLibraryTest {

    private val books = BookRepository()
    private val import = BookImportService(books)

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("reader", block)

    private fun user(): Int = transaction {
        Users.insert {
            it[email] = "r${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
    }

    private val sample = """
        Chapter I

        He was late for the train. The platform was empty.

        Chapter II

        She was not late at all.
    """.trimIndent()

    @Test
    fun `an imported book keeps its chapters and its reading order`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val result = import.importText(reader, ImportTextRequest(text = sample, title = "Notes"))

            assertFalse(result.alreadyExisted)
            val detail = assertNotNull(books.detail(reader, result.book.id))
            assertEquals(2, detail.chapters.size)
            assertEquals(listOf("Chapter I", "Chapter II"), detail.chapters.map { it.title })

            // Chapter two starts where chapter one stopped: the ordinal runs across the book,
            // not within a chapter, which is the whole reason a position needs only one number.
            assertEquals(0, detail.chapters[0].firstOrdinal)
            assertEquals(detail.chapters[0].blockCount, detail.chapters[1].firstOrdinal)
        }
    }

    @Test
    fun `re-importing the same text returns the book already on the shelf`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val first = import.importText(reader, ImportTextRequest(text = sample, title = "Notes"))
            val second = import.importText(reader, ImportTextRequest(text = sample, title = "Notes"))

            assertTrue(second.alreadyExisted)
            assertEquals(first.book.id, second.book.id)
            assertEquals(1, books.listFor(reader).size)
        }
    }

    @Test
    fun `two readers importing the same text get their own copies`(): Unit = onFreshDatabase {
        runBlocking {
            val one = user()
            val two = user()
            val a = import.importText(one, ImportTextRequest(text = sample, title = "Notes"))
            val b = import.importText(two, ImportTextRequest(text = sample, title = "Notes"))

            // The hash de-duplicates within one library. It is not a shared catalogue: a book
            // here is text somebody uploaded for themselves.
            assertFalse(b.alreadyExisted)
            assertTrue(a.book.id != b.book.id)
            assertEquals(1, books.listFor(one).size)
        }
    }

    @Test
    fun `blocks arrive in windows that chain by ordinal`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val book = import.importText(reader, ImportTextRequest(text = sample, title = "Notes")).book

            val page = assertNotNull(books.blocks(reader, book.id, from = 0, limit = 2, withTokens = false))
            assertEquals(2, page.blocks.size)
            assertEquals(listOf(0, 1), page.blocks.map { it.ordinal })
            assertEquals(2, page.nextOrdinal)

            val rest = assertNotNull(books.blocks(reader, book.id, from = 2, limit = 100, withTokens = false))
            assertEquals(book.blockCount - 2, rest.blocks.size)
            assertNull(rest.nextOrdinal, "the last window has nothing after it")
        }
    }

    @Test
    fun `sentences and tokens are derived on read, and the offsets hold`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val book = import.importText(reader, ImportTextRequest(text = sample, title = "Notes")).book

            val page = assertNotNull(books.blocks(reader, book.id, 0, 100, withTokens = true))
            val prose = page.blocks.first { it.text.startsWith("He was late") }

            // Two sentences in one paragraph, and a tap has to be able to send just one of them:
            // the analysis cache is keyed by the text it was asked about.
            assertEquals(2, prose.sentences.size)
            for (sentence in prose.sentences) {
                assertEquals(sentence.text, prose.text.substring(sentence.start, sentence.end))
                assertTrue(sentence.tokens.isNotEmpty())
                for (token in sentence.tokens) {
                    assertEquals(token.text, sentence.text.substring(token.start, token.end))
                }
            }
        }
    }

    @Test
    fun `asking for text without tokens leaves the sentences out`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val book = import.importText(reader, ImportTextRequest(text = sample, title = "Notes")).book

            val page = assertNotNull(books.blocks(reader, book.id, 0, 100, withTokens = false))
            assertTrue(page.blocks.all { it.sentences.isEmpty() })
            assertTrue(page.blocks.all { it.text.isNotBlank() })
        }
    }

    @Test
    fun `a position is remembered, clamped and reported as progress`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val book = import.importText(reader, ImportTextRequest(text = sample, title = "Notes")).book

            assertNull(books.listFor(reader).first().position, "an unopened book has no position")

            val saved = assertNotNull(books.setPosition(reader, book.id, 3))
            assertEquals(3, saved.ordinal)
            assertTrue(saved.progress > 0.0 && saved.progress < 1.0)

            // A client that has stale counts must not be able to store a place that is not in
            // the book: the position is read back on another device and has to resolve.
            val clamped = assertNotNull(books.setPosition(reader, book.id, 9_999))
            assertEquals(book.blockCount - 1, clamped.ordinal)

            assertEquals(book.blockCount - 1, books.listFor(reader).first().position?.ordinal)
        }
    }

    @Test
    fun `one reader cannot reach another reader's book`(): Unit = onFreshDatabase {
        runBlocking {
            val owner = user()
            val stranger = user()
            val book = import.importText(owner, ImportTextRequest(text = sample, title = "Notes")).book

            assertNull(books.detail(stranger, book.id))
            assertNull(books.blocks(stranger, book.id, 0, 10, withTokens = false))
            assertNull(books.setPosition(stranger, book.id, 1))
            assertFalse(books.delete(stranger, book.id))
        }
    }

    @Test
    fun `deleting a book takes its blocks, chapters and position with it`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val book = import.importText(reader, ImportTextRequest(text = sample, title = "Notes")).book
            books.setPosition(reader, book.id, 2)

            assertTrue(books.delete(reader, book.id))
            assertTrue(books.listFor(reader).isEmpty())
            assertNull(books.detail(reader, book.id))

            // Nothing may be left pointing at a row that has gone: the same text imported again
            // is a new book, and it must be able to store a position of its own.
            val again = import.importText(reader, ImportTextRequest(text = sample, title = "Notes"))
            assertFalse(again.alreadyExisted)
            assertNotNull(books.setPosition(reader, again.book.id, 1))
        }
    }

    @Test
    fun `deleting the account takes the library with it`(): Unit = onFreshDatabase {
        runBlocking {
            val reader = user()
            val book = import.importText(reader, ImportTextRequest(text = sample, title = "Notes")).book
            books.setPosition(reader, book.id, 2)

            // ⚠️ A reading position names both the reader and the book, so any order that leaves
            // it standing makes the account undeletable — a foreign key violation on a path with
            // no way back for the person asking to be forgotten.
            AccountDeletionService().purgeUser(reader)
        }

        transaction {
            assertEquals(0, Books.selectAll().count().toInt())
            assertEquals(0, BookChapters.selectAll().count().toInt())
            assertEquals(0, BookBlocks.selectAll().count().toInt())
            assertEquals(0, ReadingPositions.selectAll().count().toInt())
            assertEquals(0, Users.selectAll().count().toInt())
        }
    }
}
