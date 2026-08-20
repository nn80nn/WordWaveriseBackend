package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PracticeAttempts
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroups
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant

/** What one person did towards one assignment. */
data class AssignmentTally(
    val exercises: Int = 0,
    val reviews: Int = 0,
    val lastAt: Instant? = null
)

/** Right, nearly right and wrong, for whatever slice was asked for. */
data class VerdictTally(
    val correct: Int = 0,
    val almost: Int = 0,
    val wrong: Int = 0,
    val lastAt: Instant? = null
) {
    val total: Int get() = correct + almost + wrong

    operator fun plus(other: VerdictTally) = VerdictTally(
        correct = correct + other.correct,
        almost = almost + other.almost,
        wrong = wrong + other.wrong,
        lastAt = listOfNotNull(lastAt, other.lastAt).maxOrNull()
    )

    companion object {
        fun of(verdict: String, count: Int, lastAt: Instant?) = when (verdict) {
            "CORRECT" -> VerdictTally(correct = count, lastAt = lastAt)
            "ALMOST" -> VerdictTally(almost = count, lastAt = lastAt)
            else -> VerdictTally(wrong = count, lastAt = lastAt)
        }
    }
}

/** One answer, ready to be written. Already validated by the time it arrives here. */
data class AttemptToRecord(
    val clientAttemptId: String,
    val activity: String,
    val kind: String?,
    val word: String,
    val cardId: Int?,
    val verdict: String,
    val answeredAt: Instant
)

/**
 * The only place practice answers are read from.
 *
 * ⚠️ Every teacher-facing method takes an `ownerId` and refuses to return anything unless that
 * person actually owns the group. That guard lives here rather than in the routes on purpose: a
 * missing `WHERE` in a statistics query is not a broken screen, it is one person's private study
 * shown to another. Adding a read to this class means adding the guard with it.
 *
 * Nothing outside a group is ever in this table — `practice_attempts.group_id` is NOT NULL, so
 * a learner's own study has nowhere to be recorded even by mistake.
 */
class PracticeAttemptRepository {

    // ── Writing ───────────────────────────────────────────────────────────

    /**
     * @return how many were written, and how many were already there.
     *
     * A duplicate is not a failure. It is what lets a client retry a whole session freely — the
     * unique index on `(user_id, client_attempt_id)` is the guarantee, and this is where it turns
     * into an answer rather than an error.
     */
    suspend fun record(
        userId: Int,
        groupId: Int,
        assignmentId: Int?,
        categoryId: Int?,
        attempts: List<AttemptToRecord>
    ): Pair<Int, Int> = dbQuery {
        var accepted = 0
        var duplicates = 0
        for (attempt in attempts) {
            val already = PracticeAttempts.selectAll()
                .where {
                    (PracticeAttempts.userId eq userId) and
                        (PracticeAttempts.clientAttemptId eq attempt.clientAttemptId)
                }
                .count() > 0
            if (already) {
                duplicates++
                continue
            }
            try {
                PracticeAttempts.insert {
                    it[PracticeAttempts.userId] = userId
                    it[PracticeAttempts.groupId] = groupId
                    it[PracticeAttempts.assignmentId] = assignmentId
                    it[PracticeAttempts.categoryId] = categoryId
                    it[activity] = attempt.activity
                    it[kind] = attempt.kind
                    it[word] = attempt.word.take(255)
                    it[cardId] = attempt.cardId
                    it[verdict] = attempt.verdict
                    it[answeredAt] = attempt.answeredAt
                    it[clientAttemptId] = attempt.clientAttemptId.take(64)
                }
                accepted++
            } catch (e: ExposedSQLException) {
                // Two sends racing each other. The index decided; nothing to report.
                duplicates++
            }
        }
        accepted to duplicates
    }

    // ── The student's own view ────────────────────────────────────────────

    /** Progress against each of [assignmentIds], for one person. Needs no ownership check. */
    suspend fun tallyFor(userId: Int, assignmentIds: List<Int>): Map<Int, AssignmentTally> = dbQuery {
        if (assignmentIds.isEmpty()) return@dbQuery emptyMap()
        val counted = PracticeAttempts
            .select(
                PracticeAttempts.assignmentId,
                PracticeAttempts.activity,
                PracticeAttempts.id.count(),
                PracticeAttempts.answeredAt.max()
            )
            .where {
                (PracticeAttempts.userId eq userId) and
                    (PracticeAttempts.assignmentId inList assignmentIds)
            }
            .groupBy(PracticeAttempts.assignmentId, PracticeAttempts.activity)

        val result = HashMap<Int, AssignmentTally>()
        for (row in counted) {
            val id = row[PracticeAttempts.assignmentId] ?: continue
            val count = row[PracticeAttempts.id.count()].toInt()
            val last = row[PracticeAttempts.answeredAt.max()]
            val current = result[id] ?: AssignmentTally()
            result[id] = when (row[PracticeAttempts.activity]) {
                "REVIEW" -> current.copy(reviews = current.reviews + count)
                else -> current.copy(exercises = current.exercises + count)
            }.let { tally ->
                tally.copy(lastAt = listOfNotNull(tally.lastAt, last).maxOrNull())
            }
        }
        result
    }

    // ── The teacher's view, guarded ───────────────────────────────────────

    /** Right/nearly/wrong per student across the whole group, since [since] if given. */
    suspend fun verdictsByStudent(
        ownerId: Int,
        groupId: Int,
        since: Instant? = null
    ): Map<Int, VerdictTally> = dbQuery {
        if (!ownsGroupInTx(ownerId, groupId)) return@dbQuery emptyMap()

        val rows = PracticeAttempts
            .select(
                PracticeAttempts.userId,
                PracticeAttempts.verdict,
                PracticeAttempts.id.count(),
                PracticeAttempts.answeredAt.max()
            )
            .where {
                val base = PracticeAttempts.groupId eq groupId
                if (since == null) base else base and (PracticeAttempts.answeredAt greaterEq since)
            }
            .groupBy(PracticeAttempts.userId, PracticeAttempts.verdict)

        val result = HashMap<Int, VerdictTally>()
        for (row in rows) {
            val userId = row[PracticeAttempts.userId]
            val tally = VerdictTally.of(
                verdict = row[PracticeAttempts.verdict],
                count = row[PracticeAttempts.id.count()].toInt(),
                lastAt = row[PracticeAttempts.answeredAt.max()]
            )
            result[userId] = (result[userId] ?: VerdictTally()) + tally
        }
        result
    }

    /** Progress on every assignment of the group, per student. */
    suspend fun tallyByStudent(
        ownerId: Int,
        groupId: Int
    ): Map<Int, Map<Int, AssignmentTally>> = dbQuery {
        if (!ownsGroupInTx(ownerId, groupId)) return@dbQuery emptyMap()

        val rows = PracticeAttempts
            .select(
                PracticeAttempts.userId,
                PracticeAttempts.assignmentId,
                PracticeAttempts.activity,
                PracticeAttempts.id.count(),
                PracticeAttempts.answeredAt.max()
            )
            .where {
                (PracticeAttempts.groupId eq groupId) and PracticeAttempts.assignmentId.isNotNull()
            }
            .groupBy(
                PracticeAttempts.userId,
                PracticeAttempts.assignmentId,
                PracticeAttempts.activity
            )

        val result = HashMap<Int, HashMap<Int, AssignmentTally>>()
        for (row in rows) {
            val userId = row[PracticeAttempts.userId]
            val assignmentId = row[PracticeAttempts.assignmentId] ?: continue
            val count = row[PracticeAttempts.id.count()].toInt()
            val last = row[PracticeAttempts.answeredAt.max()]
            val perUser = result.getOrPut(userId) { HashMap() }
            val current = perUser[assignmentId] ?: AssignmentTally()
            perUser[assignmentId] = when (row[PracticeAttempts.activity]) {
                "REVIEW" -> current.copy(reviews = current.reviews + count)
                else -> current.copy(exercises = current.exercises + count)
            }.let { it.copy(lastAt = listOfNotNull(it.lastAt, last).maxOrNull()) }
        }
        result
    }

    /** How each kind of question is going for one student, in this group. */
    suspend fun byKind(ownerId: Int, groupId: Int, userId: Int): Map<String, VerdictTally> = dbQuery {
        if (!ownsGroupInTx(ownerId, groupId)) return@dbQuery emptyMap()

        val rows = PracticeAttempts
            .select(
                PracticeAttempts.kind,
                PracticeAttempts.verdict,
                PracticeAttempts.id.count()
            )
            .where {
                (PracticeAttempts.groupId eq groupId) and (PracticeAttempts.userId eq userId)
            }
            .groupBy(PracticeAttempts.kind, PracticeAttempts.verdict)

        val result = HashMap<String, VerdictTally>()
        for (row in rows) {
            val kind = row[PracticeAttempts.kind] ?: "REVIEW"
            val tally = VerdictTally.of(
                verdict = row[PracticeAttempts.verdict],
                count = row[PracticeAttempts.id.count()].toInt(),
                lastAt = null
            )
            result[kind] = (result[kind] ?: VerdictTally()) + tally
        }
        result
    }

    /** The words this student keeps getting wrong — the actionable part of the whole feature. */
    suspend fun byWord(ownerId: Int, groupId: Int, userId: Int): Map<String, VerdictTally> = dbQuery {
        if (!ownsGroupInTx(ownerId, groupId)) return@dbQuery emptyMap()

        val rows = PracticeAttempts
            .select(
                PracticeAttempts.word,
                PracticeAttempts.verdict,
                PracticeAttempts.id.count()
            )
            .where {
                (PracticeAttempts.groupId eq groupId) and (PracticeAttempts.userId eq userId)
            }
            .groupBy(PracticeAttempts.word, PracticeAttempts.verdict)

        val result = HashMap<String, VerdictTally>()
        for (row in rows) {
            val word = row[PracticeAttempts.word]
            val tally = VerdictTally.of(
                verdict = row[PracticeAttempts.verdict],
                count = row[PracticeAttempts.id.count()].toInt(),
                lastAt = null
            )
            result[word] = (result[word] ?: VerdictTally()) + tally
        }
        result
    }

    /** The last few answers, newest first. */
    suspend fun recent(ownerId: Int, groupId: Int, userId: Int, limit: Int): List<RecentAttempt> = dbQuery {
        if (!ownsGroupInTx(ownerId, groupId)) return@dbQuery emptyList()

        PracticeAttempts.selectAll()
            .where { (PracticeAttempts.groupId eq groupId) and (PracticeAttempts.userId eq userId) }
            .orderBy(PracticeAttempts.answeredAt to SortOrder.DESC)
            .limit(limit)
            .map {
                RecentAttempt(
                    word = it[PracticeAttempts.word],
                    kind = it[PracticeAttempts.kind],
                    activity = it[PracticeAttempts.activity],
                    verdict = it[PracticeAttempts.verdict],
                    answeredAt = it[PracticeAttempts.answeredAt]
                )
            }
    }

    data class RecentAttempt(
        val word: String,
        val kind: String?,
        val activity: String,
        val verdict: String,
        val answeredAt: Instant
    )

    /**
     * How many cards each student has built from the folders this group hands out.
     *
     * Counted here rather than from the flashcard repository because the answer is only ever
     * wanted about a group, and it has to be guarded the same way everything else here is.
     */
    suspend fun cardsFromGroupFolders(ownerId: Int, groupId: Int): Map<Int, Int> = dbQuery {
        if (!ownsGroupInTx(ownerId, groupId)) return@dbQuery emptyMap()

        val folders = StudyGroupFolders
            .select(StudyGroupFolders.categoryId)
            .where { StudyGroupFolders.groupId eq groupId }
            .map { it[StudyGroupFolders.categoryId] }
        if (folders.isEmpty()) return@dbQuery emptyMap()

        Flashcards
            .select(Flashcards.userId, Flashcards.id.count())
            .where { Flashcards.categoryId inList folders }
            .groupBy(Flashcards.userId)
            .associate { it[Flashcards.userId] to it[Flashcards.id.count()].toInt() }
    }

    private fun ownsGroupInTx(ownerId: Int, groupId: Int): Boolean =
        StudyGroups.selectAll()
            .where { (StudyGroups.id eq groupId) and (StudyGroups.ownerId eq ownerId) }
            .count() > 0
}
