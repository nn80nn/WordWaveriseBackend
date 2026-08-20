package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * A student in a group. The teacher is not one of these rows — see [StudyGroups].
 */
object StudyGroupMembers : Table("study_group_members") {
    val id = integer("id").autoIncrement()
    val groupId = integer("group_id").references(StudyGroups.id)
    val userId = integer("user_id").references(Users.id)
    val joinedAt = timestamp("joined_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Following the same link twice is one membership, not two.
        uniqueIndex(groupId, userId)

        // "Which groups am I in" is asked on every read of the folder list.
        index(false, userId)
    }
}
