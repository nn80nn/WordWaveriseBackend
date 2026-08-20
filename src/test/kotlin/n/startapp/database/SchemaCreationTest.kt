package n.startapp.database

import n.startapp.database.tables.Assignments
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PracticeAttempts
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroupMembers
import n.startapp.database.tables.StudyGroups
import n.startapp.database.tables.Users
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Boot, against a real database engine.
 *
 * The project has no migration tool: `SchemaUtils.createMissingTablesAndColumns` runs inside a
 * single transaction on every start, and [DatabaseFactory.init] rethrows if it fails. A table
 * definition Exposed cannot emit therefore does not fail a feature — it stops the server coming
 * up, on a deploy that is already live, with nothing to roll back to.
 *
 * H2 is not Postgres and this does not pretend otherwise. What it does catch is everything
 * structural: a column type with no mapping, an index over a column that is not there, a foreign
 * key declared before the table it points at. That is the whole class of mistake that has cost
 * this project an outage before.
 */
class SchemaCreationTest {

    private val ids = AtomicInteger(1)
    private fun nextId() = ids.getAndIncrement()

    private fun <T> onFreshDatabase(block: () -> T): T {
        // A private database per test: creation is the thing under test, so it cannot be shared.
        val name = "schema_${nextId()}_${System.nanoTime()}"
        Database.connect("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        return transaction {
            SchemaUtils.createMissingTablesAndColumns(*DatabaseFactory.ALL_TABLES)
            block()
        }
    }

    @Test
    fun `every table the application owns can actually be created`() {
        onFreshDatabase {
            // If any definition were unemittable, createMissingTablesAndColumns would have thrown.
            for (table in DatabaseFactory.ALL_TABLES) {
                // Selecting from a table that was never created throws; that is the assertion.
                assertEquals(0, table.selectAll().count().toInt(), "${table.tableName} is empty or absent")
            }
        }
    }

    @Test
    fun `creating the schema twice changes nothing`() {
        onFreshDatabase {
            // Every restart runs this again, including restarts that change no code at all.
            SchemaUtils.createMissingTablesAndColumns(*DatabaseFactory.ALL_TABLES)
            for (table in DatabaseFactory.ALL_TABLES) {
                assertEquals(0, table.selectAll().count().toInt(), "${table.tableName} did not survive")
            }
        }
    }

    @Test
    fun `a group, its students and its folders hang together`() {
        onFreshDatabase {
            val teacher = insertUser("teacher@t.dev")
            val student = insertUser("student@t.dev")
            val folder = insertCategory(teacher, "Unit 5")

            val group = StudyGroups.insert {
                it[ownerId] = teacher
                it[name] = "9Б"
            }[StudyGroups.id]

            StudyGroupMembers.insert {
                it[groupId] = group
                it[userId] = student
            }
            StudyGroupFolders.insert {
                it[groupId] = group
                it[categoryId] = folder
            }
            val assignment = Assignments.insert {
                it[groupId] = group
                it[title] = "Пройти Unit 5"
                it[categoryId] = folder
                it[exerciseTarget] = 10
            }[Assignments.id]

            PracticeAttempts.insert {
                it[userId] = student
                it[groupId] = group
                it[assignmentId] = assignment
                it[categoryId] = folder
                it[activity] = "EXERCISE"
                it[kind] = "TRANSLATE_RU_EN"
                it[word] = "resolve"
                it[verdict] = "CORRECT"
                it[answeredAt] = Instant.now()
                it[clientAttemptId] = "attempt-1"
            }

            assertEquals(
                1,
                PracticeAttempts.selectAll().where { PracticeAttempts.groupId eq group }.count().toInt()
            )
        }
    }

    @Test
    fun `the same answer cannot be reported twice`() {
        onFreshDatabase {
            val student = insertUser("student@t.dev")
            val teacher = insertUser("teacher@t.dev")
            val group = StudyGroups.insert {
                it[ownerId] = teacher
                it[name] = "9Б"
            }[StudyGroups.id]

            fun report() = PracticeAttempts.insert {
                it[userId] = student
                it[groupId] = group
                it[activity] = "EXERCISE"
                it[word] = "resolve"
                it[verdict] = "CORRECT"
                it[answeredAt] = Instant.now()
                it[clientAttemptId] = "same-answer"
            }

            report()
            // This is what makes an offline queue safe to flush twice. Without the index,
            // a retry would inflate a student's progress instead of doing nothing.
            assertFailsWith<ExposedSQLException> { report() }
        }
    }

    @Test
    fun `a student cannot be added to the same group twice`() {
        onFreshDatabase {
            val teacher = insertUser("teacher@t.dev")
            val student = insertUser("student@t.dev")
            val group = StudyGroups.insert {
                it[ownerId] = teacher
                it[name] = "9Б"
            }[StudyGroups.id]

            fun join() = StudyGroupMembers.insert {
                it[groupId] = group
                it[userId] = student
            }

            join()
            // Following the same invite link twice is one membership, not two.
            assertFailsWith<ExposedSQLException> { join() }
        }
    }

    @Test
    fun `two groups cannot share an invite code`() {
        onFreshDatabase {
            val teacher = insertUser("teacher@t.dev")
            fun group(code: String) = StudyGroups.insert {
                it[ownerId] = teacher
                it[name] = "класс"
                it[joinCode] = code
            }

            group("abc23xyz")
            assertFailsWith<ExposedSQLException> { group("abc23xyz") }
        }
    }

    @Test
    fun `a card can be filed under a folder belonging to somebody else`() {
        onFreshDatabase {
            val teacher = insertUser("teacher@t.dev")
            val student = insertUser("student@t.dev")
            val folder = insertCategory(teacher, "Unit 5")

            // The shape read-through relies on: the student's own card carrying the teacher's
            // category_id, and no saved_word_id at all — the word is not theirs to point at.
            Flashcards.insert {
                it[userId] = student
                it[categoryId] = folder
                it[word] = "resolve"
                it[translation] = "решать"
                it[nextReview] = Instant.now()
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }

            val filed = Flashcards.selectAll()
                .where { (Flashcards.userId eq student) and (Flashcards.categoryId eq folder) }
                .count()
            assertEquals(1, filed.toInt())
        }
    }

    private fun insertUser(email: String): Int = Users.insert {
        it[Users.email] = email
        it[passwordHash] = "x"
    }[Users.id]

    private fun insertCategory(ownerId: Int, name: String): Int = Categories.insert {
        it[userId] = ownerId
        it[Categories.name] = name
    }[Categories.id]
}
