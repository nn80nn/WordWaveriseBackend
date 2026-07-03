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

    // Email verification — default true so existing rows and Google sign-ups stay unaffected;
    // only password registration explicitly sets this to false.
    val emailVerified = bool("email_verified").default(true)
    val verificationCode = varchar("verification_code", 10).nullable()
    val verificationCodeExpiresAt = timestamp("verification_code_expires_at").nullable()

    // Delayed account deletion
    val deletionRequestedAt = timestamp("deletion_requested_at").nullable()
    val deletionScheduledFor = timestamp("deletion_scheduled_for").nullable()

    override val primaryKey = PrimaryKey(id)
}
