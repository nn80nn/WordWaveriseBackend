package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * One answer, kept because a teacher has to be able to see how the class is doing.
 *
 * Answers are graded on the client (see `services/exercise/ExerciseGrading.kt`), so this table is
 * fed by a batch report at the end of a session rather than written as the session runs.
 *
 * ⚠️ [groupId] is deliberately NOT NULL. That is the privacy boundary expressed in the schema
 * rather than in a WHERE clause somebody can forget: practice a learner does on their own words
 * has no group to name, so there is physically nowhere to record it. A teacher can only ever see
 * work done on their own group's material.
 */
object PracticeAttempts : Table("practice_attempts") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)

    val groupId = integer("group_id").references(StudyGroups.id)
    val assignmentId = integer("assignment_id").references(Assignments.id).nullable()
    val categoryId = integer("category_id").references(Categories.id).nullable()

    /** "EXERCISE" or "REVIEW" — an exercise answered, or a flashcard graded. */
    val activity = varchar("activity", 16)

    /** [n.startapp.models.exercise.ExerciseKind] name, or null for a flashcard review. */
    val kind = varchar("kind", 32).nullable()

    val word = varchar("word", 255)

    /** No foreign key on purpose: the card may be edited away, the history stays. */
    val cardId = integer("card_id").nullable()

    /** "CORRECT" | "ALMOST" | "WRONG" — the same three verdicts both clients grade to. */
    val verdict = varchar("verdict", 8)

    /** The client's clock, clamped server-side; see the attempts route. */
    val answeredAt = timestamp("answered_at")
    val recordedAt = timestamp("recorded_at").clientDefault { Instant.now() }

    /**
     * Minted on the client at the moment of answering, which is what makes reporting safe to
     * retry: an offline queue that flushes twice, or a session reported both on leaving the page
     * and on finishing it, must not count the same answer as two.
     */
    val clientAttemptId = varchar("client_attempt_id", 64)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, clientAttemptId)
        index(false, groupId, userId, answeredAt)
        index(false, assignmentId, userId)
    }
}
