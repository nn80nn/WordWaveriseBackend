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
