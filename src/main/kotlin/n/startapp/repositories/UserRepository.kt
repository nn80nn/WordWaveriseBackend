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

    /**
     * Delete user by ID
     */
    suspend fun delete(id: Int): Boolean = dbQuery {
        Users.deleteWhere { Users.id eq id } > 0
    }
}
