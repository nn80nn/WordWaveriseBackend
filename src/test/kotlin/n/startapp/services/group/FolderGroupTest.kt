package n.startapp.services.group

import kotlinx.coroutines.runBlocking
import n.startapp.database.TestDatabase
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroupMembers
import n.startapp.database.tables.StudyGroups
import n.startapp.database.tables.Users
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.NotFoundException
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.FlashcardRepository
import n.startapp.repositories.GroupRepository
import n.startapp.repositories.SavedWordRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folders that hold folders.
 *
 * The rule that matters is not the nesting itself but what a filter naming a group reaches: if it
 * stopped at the group's own rows, a group would be a label — unpracticable, unassignable, and
 * empty next to a button that works. Every test here is that one rule from another angle.
 */
class FolderGroupTest {

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("folder_group", block)

    private val categories = CategoryRepository()
    private val savedWords = SavedWordRepository()
    private val flashcards = FlashcardRepository()

    private fun user(): Int = transaction {
        Users.insert {
            it[email] = "u${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
    }

    private fun wordIn(owner: Int, spelling: String, folder: Int) = transaction {
        val id = SavedWords.insert {
            it[userId] = owner
            it[word] = spelling
            it[translation] = "…"
        }[SavedWords.id]
        SavedWordCategories.insert {
            it[savedWordId] = id
            it[categoryId] = folder
        }
    }

    private fun cardIn(owner: Int, spelling: String, folder: Int?) = transaction {
        Flashcards.insert {
            it[userId] = owner
            it[savedWordId] = null
            it[categoryId] = folder
            it[word] = spelling
            it[definition] = "…"
            it[translation] = "…"
            it[nextReview] = Instant.now()
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }[Flashcards.id].value
    }

    @Test
    fun `a filter naming a group reads the folders inside it`() = onFreshDatabase {
        runBlocking {
            val me = user()
            val module = categories.create(me, "Модуль 1", null)
            val lesson = categories.create(me, "Урок 1", null, module.id)

            wordIn(me, "resolve", lesson.id)
            wordIn(me, "settle", module.id)
            cardIn(me, "resolve", lesson.id)
            cardIn(me, "settle", module.id)

            assertEquals(2, savedWords.findByCategory(me, module.id).size)
            assertEquals(1, savedWords.findByCategory(me, lesson.id).size)
            assertEquals(2, flashcards.getAllByUser(me, module.id).size)
            assertEquals(1, flashcards.getAllByUser(me, lesson.id).size)
        }
    }

    @Test
    fun `a group counts the words of its children`() = onFreshDatabase {
        runBlocking {
            val me = user()
            val module = categories.create(me, "Модуль 1", null)
            val lesson = categories.create(me, "Урок 1", null, module.id)
            wordIn(me, "resolve", lesson.id)
            wordIn(me, "settle", module.id)

            val listed = categories.findByUserId(me).associateBy { it.id }
            assertEquals(2, listed.getValue(module.id).wordCount)
            assertEquals(1, listed.getValue(lesson.id).wordCount)
            assertEquals(module.id, listed.getValue(lesson.id).parentId)
        }
    }

    @Test
    fun `nesting stops at one level`() = onFreshDatabase {
        runBlocking {
            val me = user()
            val module = categories.create(me, "Модуль 1", null)
            val lesson = categories.create(me, "Урок 1", null, module.id)
            val other = categories.create(me, "Черновики", null)

            // A folder already inside a group cannot become a group itself.
            assertFailsWith<BadRequestException> { categories.setParent(me, other.id, lesson.id) }
            // And a group cannot be filed inside another one.
            assertFailsWith<BadRequestException> { categories.setParent(me, module.id, other.id) }
            assertFailsWith<BadRequestException> { categories.setParent(me, other.id, other.id) }
            Unit
        }
    }

    @Test
    fun `a folder belonging to somebody else is not a group you can join`() = onFreshDatabase {
        runBlocking {
            val me = user()
            val stranger = user()
            val theirs = categories.create(stranger, "Их модуль", null)
            val mine = categories.create(me, "Мой урок", null)

            assertFailsWith<NotFoundException> { categories.setParent(me, mine.id, theirs.id) }
            Unit
        }
    }

    @Test
    fun `deleting a group leaves its folders standing`() = onFreshDatabase {
        runBlocking {
            val me = user()
            val module = categories.create(me, "Модуль 1", null)
            val lesson = categories.create(me, "Урок 1", null, module.id)
            wordIn(me, "resolve", lesson.id)

            assertTrue(categories.delete(me, module.id))

            val listed = categories.findByUserId(me)
            assertEquals(listOf(lesson.id), listed.map { it.id })
            assertNull(listed.single().parentId)
            assertEquals(1, savedWords.findByCategory(me, lesson.id).size)
        }
    }

    @Test
    fun `handing a group to a class hands over what is inside it`() = onFreshDatabase {
        runBlocking {
            val teacher = user()
            val student = user()
            val module = categories.create(teacher, "Модуль 1", null)
            val lesson = categories.create(teacher, "Урок 1", null, module.id)
            wordIn(teacher, "resolve", lesson.id)

            val group = transaction {
                val id = StudyGroups.insert {
                    it[ownerId] = teacher
                    it[name] = "9Б"
                }[StudyGroups.id]
                StudyGroupFolders.insert {
                    it[groupId] = id
                    it[categoryId] = module.id
                }
                StudyGroupMembers.insert {
                    it[groupId] = id
                    it[userId] = student
                }
                id
            }

            val catalog = FolderCatalog(FolderAccessResolver(), savedWords)
            val visible = catalog.foldersFor(student).associateBy { it.id }
            assertEquals(setOf(module.id, lesson.id), visible.keys)
            assertTrue(visible.getValue(lesson.id).readOnly)
            // The parent is visible too, so the student sees the shape the teacher made.
            assertEquals(module.id, visible.getValue(lesson.id).parentId)
            assertEquals(1, visible.getValue(module.id).wordCount)

            // A card the student built from the lesson survives the class, unfiled — and the
            // sweep has to reach the child, or it would stay filed in a folder that just left.
            val card = cardIn(student, "resolve", lesson.id)
            GroupRepository().removeMember(group, student)

            val filed = transaction {
                Flashcards.selectAll().where { Flashcards.id eq card }.single()[Flashcards.categoryId]
            }
            assertNull(filed)
        }
    }

    @Test
    fun `a lesson given to another class survives leaving the first`() = onFreshDatabase {
        runBlocking {
            val teacher = user()
            val student = user()
            val module = categories.create(teacher, "Модуль 1", null)
            val lesson = categories.create(teacher, "Урок 1", null, module.id)

            val leaving = transaction {
                val a = StudyGroups.insert { it[ownerId] = teacher; it[name] = "9А" }[StudyGroups.id]
                StudyGroupFolders.insert { it[groupId] = a; it[categoryId] = module.id }
                StudyGroupMembers.insert { it[groupId] = a; it[userId] = student }

                val b = StudyGroups.insert { it[ownerId] = teacher; it[name] = "9Б" }[StudyGroups.id]
                StudyGroupFolders.insert { it[groupId] = b; it[categoryId] = lesson.id }
                StudyGroupMembers.insert { it[groupId] = b; it[userId] = student }
                a
            }

            val card = cardIn(student, "resolve", lesson.id)
            GroupRepository().removeMember(leaving, student)

            val filed = transaction {
                Flashcards.selectAll().where { Flashcards.id eq card }.single()[Flashcards.categoryId]
            }
            assertEquals(lesson.id, filed)
        }
    }
}
