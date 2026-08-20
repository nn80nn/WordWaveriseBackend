package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * A piece of work the teacher set: practise this folder, this much, by this date.
 */
object Assignments : Table("assignments") {
    val id = integer("id").autoIncrement()
    val groupId = integer("group_id").references(StudyGroups.id)
    val title = varchar("title", 200)

    /** Which folder to practise, or null for "any folder this group has". */
    val categoryId = integer("category_id").references(Categories.id).nullable()

    /**
     * The goals. At least one is set — validated where the row is written, because
     * `createMissingTablesAndColumns` cannot add a CHECK constraint to an existing table, and a
     * constraint only new deployments have is worse than none at all.
     */
    val exerciseTarget = integer("exercise_target").nullable()
    val reviewTarget = integer("review_target").nullable()

    /** [n.startapp.models.exercise.ExerciseKind] names, comma-joined; null means every kind. */
    val kinds = varchar("kinds", 255).nullable()

    val dueAt = timestamp("due_at").nullable()

    /**
     * Retired rather than deleted, so the attempts recorded against it keep their meaning.
     * A student's work does not stop having happened because the teacher moved on.
     */
    val archived = bool("archived").default(false)

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, groupId)
    }
}
