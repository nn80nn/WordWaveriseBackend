package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * TestingRequests table definition — Android (Google Play) tester requests
 */
object TestingRequests : Table("testing_requests") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255)
    val userId = integer("user_id").references(Users.id).nullable()
    val status = varchar("status", 20).default("pending") // pending | invited
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val invitedAt = timestamp("invited_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
