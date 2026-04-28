package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Users table definition
 */
object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex()
    val login = varchar("login", 50).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val googleId = varchar("google_id", 255).nullable().uniqueIndex()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)
}
