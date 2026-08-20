package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * A folder the teacher has handed to a group.
 *
 * This is a live link, not a copy: the folder stays the teacher's, and students read through to
 * it. Adding a word to the folder adds it for the whole class, and leaving the group takes the
 * folder away again — neither of which a copy could do.
 *
 * The folder must belong to the group's owner. Exposed cannot express that without a trigger,
 * so it is enforced where the row is written.
 */
object StudyGroupFolders : Table("study_group_folders") {
    val id = integer("id").autoIncrement()
    val groupId = integer("group_id").references(StudyGroups.id)
    val categoryId = integer("category_id").references(Categories.id)
    val assignedAt = timestamp("assigned_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(groupId, categoryId)

        // "Is this folder shared with anyone" — asked before every folder deletion.
        index(false, categoryId)
    }
}
