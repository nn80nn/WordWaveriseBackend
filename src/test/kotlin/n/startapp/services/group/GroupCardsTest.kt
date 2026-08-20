package n.startapp.services.group

import kotlinx.coroutines.runBlocking
import n.startapp.database.TestDatabase
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.Users
import n.startapp.repositories.FlashcardRepository
import n.startapp.repositories.SavedWordRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Building a deck out of somebody else's folder.
 *
 * The card is the student's own row carrying the teacher's `category_id` — that shape is what
 * lets every existing folder filter keep working with no changes at all. The one thing it must
 * never carry is a `saved_word_id`, and there is a concrete reason: deleting a word removes the
 * cards of *that word's owner*, so a student's card pointing at the teacher's row would leave the
 * teacher unable to delete their own word. The last test here is that exact scenario.
 */
class GroupCardsTest {

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("cards", block)

    private fun user(name: String): Int = transaction {
        Users.insert {
            it[email] = "$name${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
    }

    private fun folder(owner: Int, name: String): Int = transaction {
        Categories.insert {
            it[userId] = owner
            it[Categories.name] = name
        }[Categories.id]
    }

    private fun word(owner: Int, text: String, folderId: Int?, sense: String? = null): Int = transaction {
        SavedWords.insert {
            it[userId] = owner
            it[word] = text
            it[translation] = "перевод"
            it[categoryId] = folderId
            it[senseId] = sense
        }[SavedWords.id]
    }

    private fun cardsOf(userId: Int) = transaction {
        Flashcards.selectAll().where { Flashcards.userId eq userId }.map {
            Triple(it[Flashcards.word], it[Flashcards.categoryId], it[Flashcards.savedWordId])
        }
    }

    @Test
    fun `filling a class folder builds the students own cards from the teachers words`() = onFreshDatabase {
        val teacher = user("teacher")
        val student = user("student")
        val unit = folder(teacher, "Unit 5")
        word(teacher, "resolve", unit, sense = "v1")
        word(teacher, "settle", unit)

        val outcome = runBlocking {
            FlashcardRepository().createMissingFromCategory(
                userId = student,
                categoryId = unit,
                contentOwnerId = teacher
            )
        }

        assertEquals(2, outcome.created)
        val cards = cardsOf(student)
        assertEquals(2, cards.size)
        for ((word, category, savedWordId) in cards) {
            assertEquals(unit, category, "$word must be filed under the teacher's folder")
            assertNull(savedWordId, "$word must not point at the teacher's saved row")
        }
        assertEquals(emptyList(), cardsOf(teacher), "the teacher gets no cards out of this")
    }

    @Test
    fun `the teachers chosen sense travels to the students card`() = onFreshDatabase {
        val teacher = user("teacher")
        val student = user("student")
        val unit = folder(teacher, "Unit 5")
        word(teacher, "resolve", unit, sense = "v2")

        runBlocking {
            FlashcardRepository().createMissingFromCategory(student, unit, contentOwnerId = teacher)
        }

        val sense = transaction {
            Flashcards.selectAll().where { Flashcards.userId eq student }.single()[Flashcards.senseId]
        }
        // Which meaning the class is learning is the teacher's decision, and the card is where
        // that decision has to land — otherwise the deck quizzes a sense nobody picked.
        assertEquals("v2", sense)
    }

    @Test
    fun `a loose card the student already had is pulled into the class folder`() = onFreshDatabase {
        val teacher = user("teacher")
        val student = user("student")
        val unit = folder(teacher, "Unit 5")
        word(teacher, "resolve", unit)
        val own = word(student, "resolve", null)
        runBlocking { FlashcardRepository().createFromSavedWord(student, own) }

        val outcome = runBlocking {
            FlashcardRepository().createMissingFromCategory(student, unit, contentOwnerId = teacher)
        }

        assertEquals(0, outcome.created)
        assertEquals(1, outcome.moved)
        assertEquals(1, cardsOf(student).size, "one word, one card — the class does not add a second")
        assertEquals(unit, cardsOf(student).single().second)
    }

    @Test
    fun `a word from a folder no group lends is simply not found`() = onFreshDatabase {
        val teacher = user("teacher")
        val stranger = user("stranger")
        val private = folder(teacher, "Личное")
        val hidden = word(teacher, "resolve", private)

        val card = runBlocking {
            // No group hands this folder out, so nothing reaches it.
            FlashcardRepository().createFromSavedWord(stranger, hidden, reachableFolderIds = emptySet())
        }

        assertNull(card)
        assertEquals(emptyList(), cardsOf(stranger))
    }

    @Test
    fun `a word from a lent folder makes a card with no pointer back to it`() = onFreshDatabase {
        val teacher = user("teacher")
        val student = user("student")
        val unit = folder(teacher, "Unit 5")
        val lent = word(teacher, "resolve", unit)

        val card = runBlocking {
            FlashcardRepository().createFromSavedWord(student, lent, reachableFolderIds = setOf(unit))
        }

        assertNotNull(card)
        assertEquals(unit, cardsOf(student).single().second)
        assertNull(cardsOf(student).single().third)
    }

    @Test
    fun `the teacher can still delete a word the class built cards from`() = onFreshDatabase {
        val teacher = user("teacher")
        val student = user("student")
        val unit = folder(teacher, "Unit 5")
        val lent = word(teacher, "resolve", unit)
        runBlocking {
            FlashcardRepository().createFromSavedWord(student, lent, reachableFolderIds = setOf(unit))
        }

        // This is the whole reason `saved_word_id` stays null. With a pointer here, the delete
        // below would hit a foreign key and the teacher could never remove their own word again.
        val deleted = runBlocking { SavedWordRepository().delete(teacher, "resolve") }

        assertTrue(deleted)
        assertEquals(1, cardsOf(student).size, "the student's card is not the teacher's to delete")
        transaction {
            assertEquals(
                0,
                SavedWords.selectAll()
                    .where { (SavedWords.userId eq teacher) and (SavedWords.word eq "resolve") }
                    .count().toInt()
            )
        }
    }
}
