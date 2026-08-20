package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Assignments
import n.startapp.database.tables.StudyGroupMembers
import n.startapp.database.tables.StudyGroups
import n.startapp.models.exercise.ExerciseKind
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** An assignment row, before it is shaped for a teacher or for a student. */
data class AssignmentRow(
    val id: Int,
    val groupId: Int,
    val title: String,
    val categoryId: Int?,
    val exerciseTarget: Int?,
    val reviewTarget: Int?,
    val kinds: List<ExerciseKind>,
    val dueAt: Instant?,
    val createdAt: Instant
)

class AssignmentRepository {

    private fun rowTo(row: ResultRow) = AssignmentRow(
        id = row[Assignments.id],
        groupId = row[Assignments.groupId],
        title = row[Assignments.title],
        categoryId = row[Assignments.categoryId],
        exerciseTarget = row[Assignments.exerciseTarget],
        reviewTarget = row[Assignments.reviewTarget],
        kinds = decodeKinds(row[Assignments.kinds]),
        dueAt = row[Assignments.dueAt],
        createdAt = row[Assignments.createdAt]
    )

    suspend fun create(
        groupId: Int,
        title: String,
        categoryId: Int?,
        exerciseTarget: Int?,
        reviewTarget: Int?,
        kinds: List<ExerciseKind>,
        dueAt: Instant?
    ): AssignmentRow = dbQuery {
        val stmt = Assignments.insert {
            it[Assignments.groupId] = groupId
            it[Assignments.title] = title.trim()
            it[Assignments.categoryId] = categoryId
            it[Assignments.exerciseTarget] = exerciseTarget
            it[Assignments.reviewTarget] = reviewTarget
            it[Assignments.kinds] = encodeKinds(kinds)
            it[Assignments.dueAt] = dueAt
        }
        AssignmentRow(
            id = stmt[Assignments.id],
            groupId = groupId,
            title = title.trim(),
            categoryId = categoryId,
            exerciseTarget = exerciseTarget,
            reviewTarget = reviewTarget,
            kinds = kinds,
            dueAt = dueAt,
            createdAt = stmt[Assignments.createdAt]
        )
    }

    suspend fun findById(id: Int): AssignmentRow? = dbQuery {
        Assignments.selectAll()
            .where { (Assignments.id eq id) and (Assignments.archived eq false) }
            .singleOrNull()
            ?.let(::rowTo)
    }

    suspend fun findByGroup(groupId: Int): List<AssignmentRow> = dbQuery {
        Assignments.selectAll()
            .where { (Assignments.groupId eq groupId) and (Assignments.archived eq false) }
            .orderBy(Assignments.createdAt to SortOrder.DESC)
            .map(::rowTo)
    }

    suspend fun countByGroup(groupId: Int): Int = dbQuery {
        Assignments.selectAll()
            .where { (Assignments.groupId eq groupId) and (Assignments.archived eq false) }
            .count().toInt()
    }

    /** Everything set for the groups this student belongs to. */
    suspend fun findForStudent(userId: Int, groupId: Int? = null): List<AssignmentRow> = dbQuery {
        val groupIds = StudyGroupMembers
            .select(StudyGroupMembers.groupId)
            .where { StudyGroupMembers.userId eq userId }
            .map { it[StudyGroupMembers.groupId] }
            .let { ids -> if (groupId == null) ids else ids.filter { it == groupId } }
        if (groupIds.isEmpty()) return@dbQuery emptyList()

        val live = StudyGroups
            .select(StudyGroups.id)
            .where { (StudyGroups.id inList groupIds) and (StudyGroups.archived eq false) }
            .map { it[StudyGroups.id] }
        if (live.isEmpty()) return@dbQuery emptyList()

        Assignments.selectAll()
            .where { (Assignments.groupId inList live) and (Assignments.archived eq false) }
            .orderBy(Assignments.createdAt to SortOrder.DESC)
            .map(::rowTo)
    }

    suspend fun update(
        id: Int,
        title: String,
        categoryId: Int?,
        exerciseTarget: Int?,
        reviewTarget: Int?,
        kinds: List<ExerciseKind>,
        dueAt: Instant?
    ): Boolean = dbQuery {
        Assignments.update({ Assignments.id eq id }) {
            it[Assignments.title] = title.trim()
            it[Assignments.categoryId] = categoryId
            it[Assignments.exerciseTarget] = exerciseTarget
            it[Assignments.reviewTarget] = reviewTarget
            it[Assignments.kinds] = encodeKinds(kinds)
            it[Assignments.dueAt] = dueAt
            it[Assignments.updatedAt] = Instant.now()
        } > 0
    }

    /**
     * Retired, not deleted: the attempts recorded against it are a record of work that happened,
     * and they would have nothing to point at.
     */
    suspend fun archive(id: Int): Boolean = dbQuery {
        Assignments.update({ Assignments.id eq id }) {
            it[archived] = true
            it[updatedAt] = Instant.now()
        } > 0
    }

    private fun encodeKinds(kinds: List<ExerciseKind>): String? =
        kinds.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }

    /** An unknown name is dropped rather than fatal: a retired kind must not break the list. */
    private fun decodeKinds(raw: String?): List<ExerciseKind> =
        raw?.split(",").orEmpty()
            .mapNotNull { name -> ExerciseKind.entries.firstOrNull { it.name == name.trim() } }
}
