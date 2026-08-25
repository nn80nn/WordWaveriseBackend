package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object Categories : Table("categories") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val name = varchar("name", 100)
    val color = varchar("color", 20).nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    /**
     * The folder this one is filed under, or null when it stands on its own.
     *
     * Exactly one level deep, and that is a rule the repository enforces rather than the schema:
     * a folder that already has a parent cannot become one. Two levels is what "объединить папки
     * в группы" asks for, and every level past it multiplies the questions a filter has to answer
     * ("does practising the grandparent reach here?") without answering any of them better.
     *
     * ⚠️ A filter naming a parent reaches its children too. Otherwise a group of folders would
     * be a label and nothing else: it could not be practised, assigned, or handed to a class,
     * which is the whole reason for grouping folders in the first place.
     */
    val parentId = integer("parent_id").references(id).nullable()

    /**
     * The link that lets anyone else copy this folder, or null while it is private.
     *
     * A capability, not an identifier: whoever holds it can read the folder and take a copy,
     * which is exactly what sharing a link means. It is generated on request and can be
     * revoked, and revoking it does not touch the copies anyone already made — those are
     * their words now.
     */
    val shareToken = varchar("share_token", 32).nullable().uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}
