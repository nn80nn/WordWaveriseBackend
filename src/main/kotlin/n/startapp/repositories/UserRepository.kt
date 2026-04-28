package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Users
import n.startapp.models.auth.User
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Repository for User CRUD operations
 */
class UserRepository {

    /**
     * Convert ResultRow to User model
     */
    private fun resultRowToUser(row: ResultRow): User = User(
        id = row[Users.id],
        email = row[Users.email],
        passwordHash = row[Users.passwordHash],
        googleId = row[Users.googleId],
        createdAt = row[Users.createdAt]
    )

    /**
     * Create a new user
     */
    suspend fun create(email: String, passwordHash: String): User? = dbQuery {
        val insertStatement = Users.insert {
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
        }
        insertStatement.resultedValues?.singleOrNull()?.let(::resultRowToUser)
    }

    /**
     * Find user by email
     */
    suspend fun findByEmail(email: String): User? = dbQuery {
        Users.selectAll()
            .where { Users.email eq email }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    /**
     * Find user by ID
     */
    suspend fun findById(id: Int): User? = dbQuery {
        Users.selectAll()
            .where { Users.id eq id }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    /**
     * Check if user with email exists
     */
    suspend fun existsByEmail(email: String): Boolean = dbQuery {
        Users.selectAll()
            .where { Users.email eq email }
            .count() > 0
    }

    suspend fun findByGoogleId(googleId: String): User? = dbQuery {
        Users.selectAll()
            .where { Users.googleId eq googleId }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    suspend fun findOrCreateByGoogle(email: String, googleId: String): User = dbQuery {
        // already linked by googleId
        Users.selectAll().where { Users.googleId eq googleId }
            .map(::resultRowToUser)
            .singleOrNull()
            ?: run {
                // existing email account — link googleId
                val existing = Users.selectAll().where { Users.email eq email }
                    .map(::resultRowToUser)
                    .singleOrNull()
                if (existing != null) {
                    Users.update({ Users.id eq existing.id }) { it[Users.googleId] = googleId }
                    existing.copy(googleId = googleId)
                } else {
                    // brand-new user via Google
                    val stmt = Users.insert {
                        it[Users.email] = email
                        it[Users.passwordHash] = ""
                        it[Users.googleId] = googleId
                    }
                    stmt.resultedValues!!.first().let(::resultRowToUser)
                }
            }
    }

    suspend fun updatePassword(id: Int, newHash: String) = dbQuery {
        Users.update({ Users.id eq id }) { it[passwordHash] = newHash }
    }

    suspend fun updateEmail(id: Int, newEmail: String) = dbQuery {
        Users.update({ Users.id eq id }) { it[email] = newEmail }
    }

    /**
     * Delete user by ID
     */
    suspend fun delete(id: Int): Boolean = dbQuery {
        Users.deleteWhere { Users.id eq id } > 0
    }
}
