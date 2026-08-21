package n.startapp.repositories

import kotlinx.coroutines.runBlocking
import n.startapp.database.TestDatabase
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A saved word is a *sense* in a *set of folders*, and both halves of that used to be single.
 *
 * These are the two limits people actually hit: `resolve` is «решать» and «разлагать», and one
 * word honestly belongs both to Monday's lesson and to the exam topic. The old shape — one row
 * per headword, one folder per row — could express neither, and the unique index on
 * (user_id, word) meant no amount of client work could get around it.
 */
class SavedWordEntriesTest {

    private val words = SavedWordRepository()
    private val cards = FlashcardRepository()

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("saved", block)

    private fun user(): Int = transaction {
        Users.insert {
            it[email] = "u${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
    }

    private fun folder(owner: Int, title: String): Int = transaction {
        Categories.insert {
            it[userId] = owner
            it[name] = title
        }[Categories.id]
    }

    @Test
    fun `two senses of one word are two entries`() = onFreshDatabase {
        val me = user()

        val solve = runBlocking {
            words.save(me, "resolve", translation = "решать", senseId = "v1")
        }
        val decompose = runBlocking {
            words.save(me, "resolve", translation = "разлагать", senseId = "v2")
        }

        assertNotNull(solve)
        assertNotNull(decompose)
        assertTrue(solve.id != decompose.id, "the second sense reused the first row")

        val all = runBlocking { words.findByUserId(me) }
        assertEquals(2, all.size)
        assertEquals(setOf("v1", "v2"), all.mapNotNull { it.senseId }.toSet())
    }

    @Test
    fun `pinning a word saved as a whole fills the row it already has`() = onFreshDatabase {
        val me = user()

        runBlocking { words.save(me, "resolve", translation = "решать") }
        val pinned = runBlocking {
            words.save(me, "resolve", translation = "разлагать", senseId = "v2")
        }

        // «Слово целиком» was the absence of a choice, not a different one — turning it into a
        // second entry would hand the person a duplicate they never asked for.
        val all = runBlocking { words.findByUserId(me) }
        assertEquals(1, all.size)
        assertEquals("v2", all.single().senseId)
        assertEquals("разлагать", pinned?.translation)
    }

    @Test
    fun `saving the same sense twice changes nothing`() = onFreshDatabase {
        val me = user()

        runBlocking { words.save(me, "resolve", translation = "решать", senseId = "v1") }
        runBlocking { words.save(me, "resolve", translation = "переписано", senseId = "v1") }

        val all = runBlocking { words.findByUserId(me) }
        assertEquals(1, all.size)
        assertEquals("решать", all.single().translation)
    }

    @Test
    fun `one word lives in several folders and is listed by each of them`() = onFreshDatabase {
        val me = user()
        val monday = folder(me, "Урок 5")
        val exam = folder(me, "К экзамену")

        val saved = runBlocking {
            words.save(me, "resolve", translation = "решать", senseId = "v1", categoryIds = listOf(monday, exam))
        }
        assertNotNull(saved)
        assertEquals(setOf(monday, exam), saved.categoryIds.toSet())

        assertEquals(1, runBlocking { words.findByCategory(me, monday) }.size)
        assertEquals(1, runBlocking { words.findByCategory(me, exam) }.size)

        // One entry, two folders — the list must not show the word twice for being filed twice.
        assertEquals(1, runBlocking { words.findByUserId(me) }.size)
        assertEquals(mapOf(monday to 1, exam to 1), runBlocking { words.countByCategory(me) })
    }

    @Test
    fun `replacing the folder set removes only the folders left out`() = onFreshDatabase {
        val me = user()
        val monday = folder(me, "Урок 5")
        val exam = folder(me, "К экзамену")
        val saved = runBlocking {
            words.save(me, "resolve", senseId = "v1", categoryIds = listOf(monday, exam))
        }!!

        val updated = runBlocking { words.setFolders(me, saved.id, listOf(exam)) }

        assertEquals(listOf(exam), updated?.categoryIds)
        assertEquals(0, runBlocking { words.findByCategory(me, monday) }.size)
    }

    @Test
    fun `each sense gets its own card, on its own schedule`() = onFreshDatabase {
        val me = user()
        val solve = runBlocking { words.save(me, "resolve", translation = "решать", senseId = "v1") }!!
        val decompose = runBlocking { words.save(me, "resolve", translation = "разлагать", senseId = "v2") }!!

        assertNotNull(runBlocking { cards.createFromSavedWord(me, solve.id) })
        assertNotNull(runBlocking { cards.createFromSavedWord(me, decompose.id) })

        val deck = runBlocking { cards.getAllByUser(me) }
        assertEquals(2, deck.size, "the second sense did not get a card of its own")
        assertEquals(setOf("v1", "v2"), deck.mapNotNull { it.senseId }.toSet())
    }

    @Test
    fun `deleting one sense leaves the other and its card`() = onFreshDatabase {
        val me = user()
        val solve = runBlocking { words.save(me, "resolve", translation = "решать", senseId = "v1") }!!
        val decompose = runBlocking { words.save(me, "resolve", translation = "разлагать", senseId = "v2") }!!
        runBlocking { cards.createFromSavedWord(me, solve.id) }
        runBlocking { cards.createFromSavedWord(me, decompose.id) }

        assertTrue(runBlocking { words.deleteEntry(me, solve.id) })

        val left = runBlocking { words.findByUserId(me) }
        assertEquals(1, left.size)
        assertEquals("v2", left.single().senseId)

        // The card of the sense that stayed keeps its history; the other one goes, because a
        // card outliving its word is reviewable and unremovable.
        val deck = runBlocking { cards.getAllByUser(me) }
        assertEquals(listOf("v2"), deck.mapNotNull { it.senseId })
    }

    @Test
    fun `deleting a word by headword still takes every sense of it`() = onFreshDatabase {
        val me = user()
        runBlocking { words.save(me, "resolve", senseId = "v1") }
        runBlocking { words.save(me, "resolve", senseId = "v2") }

        assertTrue(runBlocking { words.delete(me, "resolve") })
        assertEquals(0, runBlocking { words.findByUserId(me) }.size)
        // The links must go with the rows: a folder id gets reused, and a leftover pair would
        // quietly hand somebody else's folder a word that no longer exists.
        assertEquals(0, transaction { SavedWordCategories.selectAll().count().toInt() })
    }

    @Test
    fun `deleting a folder unfiles its words instead of deleting them`() = onFreshDatabase {
        val me = user()
        val monday = folder(me, "Урок 5")
        runBlocking { words.save(me, "resolve", senseId = "v1", categoryIds = listOf(monday)) }

        assertTrue(runBlocking { CategoryRepository().delete(me, monday) })

        val left = runBlocking { words.findByUserId(me) }
        assertEquals(1, left.size)
        assertEquals(emptyList(), left.single().categoryIds)
    }

    @Test
    fun `an imported word already saved gains the folder rather than moving`() = onFreshDatabase {
        val sharer = user()
        val me = user()
        val mine = folder(me, "Мои")
        val incoming = folder(me, "Присланное")

        runBlocking { words.save(me, "resolve", translation = "решать", senseId = "v1", categoryIds = listOf(mine)) }
        val theirs = runBlocking { words.save(sharer, "resolve", translation = "решать", senseId = "v1") }!!

        val (added, alreadyHad) = runBlocking { words.copyInto(me, incoming, listOf(theirs)) }

        assertEquals(0, added)
        assertEquals(1, alreadyHad)
        // Their own folder is untouched, and the folder they were sent is nonetheless complete.
        val word = runBlocking { words.findByUserId(me) }.single()
        assertEquals(setOf(mine, incoming), word.categoryIds.toSet())
    }

    @Test
    fun `an imported sense the recipient has not saved arrives as its own word`() = onFreshDatabase {
        val sharer = user()
        val me = user()
        val incoming = folder(me, "Присланное")

        runBlocking { words.save(me, "resolve", translation = "решать", senseId = "v1") }
        val other = runBlocking { words.save(sharer, "resolve", translation = "разлагать", senseId = "v2") }!!

        val (added, _) = runBlocking { words.copyInto(me, incoming, listOf(other)) }

        assertEquals(1, added)
        assertEquals(2, runBlocking { words.findByUserId(me) }.size)
    }

    @Test
    fun `filling a folder does not build a second card for a sense that has one`() = onFreshDatabase {
        val me = user()
        val monday = folder(me, "Урок 5")
        val solve = runBlocking { words.save(me, "resolve", senseId = "v1", categoryIds = listOf(monday)) }!!
        runBlocking { cards.createFromSavedWord(me, solve.id) }

        val outcome = runBlocking { cards.createMissingFromCategory(me, monday) }

        assertEquals(0, outcome.created)
        assertEquals(1, transaction { Flashcards.selectAll().count().toInt() })
    }

    @Test
    fun `filling a folder builds a card for every sense in it`() = onFreshDatabase {
        val me = user()
        val monday = folder(me, "Урок 5")
        runBlocking { words.save(me, "resolve", senseId = "v1", categoryIds = listOf(monday)) }
        runBlocking { words.save(me, "resolve", senseId = "v2", categoryIds = listOf(monday)) }

        val outcome = runBlocking { cards.createMissingFromCategory(me, monday) }

        assertEquals(2, outcome.created, "two senses in the folder produced ${outcome.created} card(s)")
    }

    @Test
    fun `a word saved with no folder is what «без папки» means`() = onFreshDatabase {
        val me = user()
        val monday = folder(me, "Урок 5")
        runBlocking { words.save(me, "resolve", senseId = "v1", categoryIds = listOf(monday)) }
        runBlocking { words.save(me, "waver", senseId = "v1") }

        val unfiled = runBlocking { words.findByUserId(me) }.filter { it.categoryIds.isEmpty() }
        assertEquals(listOf("waver"), unfiled.map { it.word })
        assertNull(unfiled.single().categoryId)
    }
}
