package n.startapp.services.group

import kotlinx.coroutines.runBlocking
import n.startapp.database.TestDatabase
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroups
import n.startapp.database.tables.Users
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.GroupRepository
import n.startapp.services.AccountDeletionService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The moments when a folder stops being available, run against a real engine.
 *
 * Each of these is a place where getting it wrong destroys work somebody did: a student's deck is
 * their own, however the words reached them, and it has to survive the class ending. The other
 * half is that none of these paths may leave a row pointing at something that was deleted —
 * `categories.id` now has four foreign keys aimed at it, and missing one turns "delete folder"
 * into a 500 that no client can do anything about.
 */
class GroupLifecycleTest {

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("lifecycle", block)

    /** Teacher, student, a folder with one word in it, handed to a group the student is in. */
    private class Classroom(val teacher: Int, val student: Int, val folder: Int, val group: Int)

    private fun classroom(): Classroom = transaction {
        val teacher = Users.insert {
            it[email] = "teacher${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
        val student = Users.insert {
            it[email] = "student${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
        val folder = Categories.insert {
            it[userId] = teacher
            it[name] = "Unit 5"
        }[Categories.id]
        SavedWords.insert {
            it[userId] = teacher
            it[word] = "resolve"
            it[translation] = "решать"
            it[categoryId] = folder
        }
        val group = StudyGroups.insert {
            it[ownerId] = teacher
            it[name] = "9Б"
        }[StudyGroups.id]
        StudyGroupFolders.insert {
            it[groupId] = group
            it[categoryId] = folder
        }
        Classroom(teacher, student, folder, group)
    }

    /** The student's own card, built from the class folder and filed under it. */
    private fun cardFor(student: Int, folder: Int): Int = transaction {
        Flashcards.insert {
            it[userId] = student
            it[savedWordId] = null
            it[categoryId] = folder
            it[word] = "resolve"
            it[translation] = "решать"
            it[nextReview] = Instant.now()
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }[Flashcards.id].value
    }

    private fun folderOf(cardId: Int): Int? = transaction {
        Flashcards.selectAll().where { Flashcards.id eq cardId }.single()[Flashcards.categoryId]
    }

    private fun cardExists(cardId: Int): Boolean = transaction {
        Flashcards.selectAll().where { Flashcards.id eq cardId }.count() > 0
    }

    @Test
    fun `deleting a folder a class holds unfiles the students cards instead of failing`() = onFreshDatabase {
        val room = classroom()
        val card = cardFor(room.student, room.folder)

        runBlocking {
            // Before the group tables existed this was a plain delete. Now `study_group_folders`
            // and the student's card both point at the folder, and a delete that ignores either
            // of them is a 500 rather than a mistake anybody can see.
            assertTrue(CategoryRepository().delete(room.teacher, room.folder))
        }

        assertTrue(cardExists(card), "the student's card must survive the folder")
        assertNull(folderOf(card), "the card must be unfiled, not left pointing at a deleted folder")
        transaction {
            assertEquals(
                0,
                StudyGroupFolders.selectAll().where { StudyGroupFolders.categoryId eq room.folder }
                    .count().toInt()
            )
        }
    }

    @Test
    fun `leaving a class takes the folder away and leaves the deck`() = onFreshDatabase {
        val room = classroom()
        val groups = GroupRepository()
        runBlocking { groups.addMember(room.group, room.student) }
        val card = cardFor(room.student, room.folder)

        runBlocking { assertTrue(groups.removeMember(room.group, room.student)) }

        assertTrue(cardExists(card))
        assertNull(folderOf(card), "the folder was the class's, so it goes; the deck is the student's")
    }

    @Test
    fun `a folder both classes hand out survives leaving one of them`() = onFreshDatabase {
        val room = classroom()
        val groups = GroupRepository()
        val second = transaction {
            val g = StudyGroups.insert {
                it[ownerId] = room.teacher
                it[name] = "9А"
            }[StudyGroups.id]
            StudyGroupFolders.insert {
                it[groupId] = g
                it[categoryId] = room.folder
            }
            g
        }
        runBlocking {
            groups.addMember(room.group, room.student)
            groups.addMember(second, room.student)
        }
        val card = cardFor(room.student, room.folder)

        runBlocking { groups.removeMember(room.group, room.student) }

        assertEquals(
            room.folder,
            folderOf(card),
            "the second class still hands this folder out, so the deck must stay filed"
        )
    }

    @Test
    fun `deleting the teachers account leaves the students deck standing`() = onFreshDatabase {
        val room = classroom()
        runBlocking { GroupRepository().addMember(room.group, room.student) }
        val card = cardFor(room.student, room.folder)

        runBlocking { AccountDeletionService().purgeUser(room.teacher) }

        assertTrue(cardExists(card), "the deck was the student's work, whoever the words came from")
        assertNull(folderOf(card))
        transaction {
            assertEquals(0, StudyGroups.selectAll().where { StudyGroups.ownerId eq room.teacher }.count().toInt())
            assertEquals(0, Users.selectAll().where { Users.id eq room.teacher }.count().toInt())
            // The student is untouched.
            assertEquals(1, Users.selectAll().where { Users.id eq room.student }.count().toInt())
        }
    }

    @Test
    fun `deleting a student account does not disturb the class`() = onFreshDatabase {
        val room = classroom()
        runBlocking { GroupRepository().addMember(room.group, room.student) }
        cardFor(room.student, room.folder)

        runBlocking { AccountDeletionService().purgeUser(room.student) }

        transaction {
            assertEquals(1, StudyGroups.selectAll().where { StudyGroups.id eq room.group }.count().toInt())
            assertEquals(
                1,
                SavedWords.selectAll()
                    .where { (SavedWords.userId eq room.teacher) and (SavedWords.categoryId eq room.folder) }
                    .count().toInt()
            )
        }
    }
}
