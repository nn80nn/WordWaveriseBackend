package n.startapp.services.group

import kotlinx.coroutines.runBlocking
import n.startapp.database.TestDatabase
import n.startapp.database.tables.StudyGroups
import n.startapp.database.tables.Users
import n.startapp.repositories.AttemptToRecord
import n.startapp.repositories.GroupRepository
import n.startapp.repositories.PracticeAttemptRepository
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who can read a student's practice, and what a repeated report does.
 *
 * The privacy rule is not a preference: a teacher sees work done on their own group's material
 * and nothing else. It is enforced twice over — `practice_attempts.group_id` is NOT NULL, so a
 * learner's private study has nowhere to be recorded, and every read in
 * [PracticeAttemptRepository] refuses to answer someone who does not own the group.
 */
class PracticePrivacyTest {

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("privacy", block)

    private fun user(name: String): Int = transaction {
        Users.insert {
            it[email] = "$name${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
    }

    private fun group(owner: Int, name: String): Int = transaction {
        StudyGroups.insert {
            it[ownerId] = owner
            it[StudyGroups.name] = name
        }[StudyGroups.id]
    }

    private fun attempt(id: String, verdict: String = "CORRECT", word: String = "resolve") =
        AttemptToRecord(
            clientAttemptId = id,
            activity = "EXERCISE",
            kind = "TRANSLATE_RU_EN",
            word = word,
            cardId = null,
            verdict = verdict,
            answeredAt = Instant.now()
        )

    @Test
    fun `the same session reported twice is counted once`() = onFreshDatabase {
        val teacher = user("teacher")
        val student = user("student")
        val classId = group(teacher, "9Б")
        val attempts = PracticeAttemptRepository()
        val session = listOf(attempt("a1"), attempt("a2"), attempt("a3"))

        val first = runBlocking { attempts.record(student, classId, null, null, session) }
        val second = runBlocking { attempts.record(student, classId, null, null, session) }

        assertEquals(3 to 0, first)
        // This is what lets an offline queue flush freely, and a session be sent both when the
        // page closes and when it finishes, without inflating anybody's progress.
        assertEquals(0 to 3, second)

        val tally = runBlocking { attempts.verdictsByStudent(teacher, classId) }
        assertEquals(3, tally.getValue(student).total)
    }

    @Test
    fun `another teacher reading the same group gets nothing`() = onFreshDatabase {
        val teacher = user("teacher")
        val stranger = user("stranger")
        val student = user("student")
        val classId = group(teacher, "9Б")
        val attempts = PracticeAttemptRepository()
        runBlocking { attempts.record(student, classId, null, null, listOf(attempt("a1"))) }

        runBlocking {
            assertTrue(attempts.verdictsByStudent(stranger, classId).isEmpty())
            assertTrue(attempts.tallyByStudent(stranger, classId).isEmpty())
            assertTrue(attempts.byKind(stranger, classId, student).isEmpty())
            assertTrue(attempts.byWord(stranger, classId, student).isEmpty())
            assertTrue(attempts.recent(stranger, classId, student, 50).isEmpty())
            assertTrue(attempts.cardsFromGroupFolders(stranger, classId).isEmpty())

            // ...and the owner does see it, so the check above is a guard and not a broken query.
            assertEquals(1, attempts.verdictsByStudent(teacher, classId).getValue(student).total)
        }
    }

    @Test
    fun `work in one class is invisible from another`() = onFreshDatabase {
        val teacherA = user("teacherA")
        val teacherB = user("teacherB")
        val student = user("student")
        val classA = group(teacherA, "Английский")
        val classB = group(teacherB, "Немецкий")
        val attempts = PracticeAttemptRepository()

        runBlocking {
            attempts.record(student, classA, null, null, listOf(attempt("a1"), attempt("a2")))
            attempts.record(student, classB, null, null, listOf(attempt("b1")))

            // The same student, two teachers, two separate pictures. Neither sees the other's.
            assertEquals(2, attempts.verdictsByStudent(teacherA, classA).getValue(student).total)
            assertEquals(1, attempts.verdictsByStudent(teacherB, classB).getValue(student).total)
        }
    }

    @Test
    fun `the hardest words are the ones actually got wrong`() = onFreshDatabase {
        val teacher = user("teacher")
        val student = user("student")
        val classId = group(teacher, "9Б")
        val attempts = PracticeAttemptRepository()

        runBlocking {
            attempts.record(
                student, classId, null, null,
                listOf(
                    attempt("w1", "WRONG", "resolve"),
                    attempt("w2", "WRONG", "resolve"),
                    attempt("w3", "CORRECT", "resolve"),
                    attempt("w4", "ALMOST", "settle"),
                    attempt("w5", "CORRECT", "linger")
                )
            )
            val byWord = attempts.byWord(teacher, classId, student)
            assertEquals(2, byWord.getValue("resolve").wrong)
            assertEquals(3, byWord.getValue("resolve").total)
            assertEquals(0, byWord.getValue("settle").wrong)
            assertEquals(1, byWord.getValue("settle").almost)
        }
    }

    @Test
    fun `a student who left is dropped from the class list but their work stays counted`() =
        onFreshDatabase {
            val teacher = user("teacher")
            val student = user("student")
            val classId = group(teacher, "9Б")
            val groups = GroupRepository()
            val attempts = PracticeAttemptRepository()

            runBlocking {
                groups.addMember(classId, student)
                attempts.record(student, classId, null, null, listOf(attempt("a1")))
                groups.removeMember(classId, student)

                assertTrue(groups.members(classId).isEmpty())
                // The attempts are history, not membership. Deleting them on the way out would
                // rewrite what the class actually did.
                assertEquals(1, attempts.verdictsByStudent(teacher, classId).getValue(student).total)
            }
        }
}
