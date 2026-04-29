package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Users
import n.startapp.models.auth.User
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like

class UserRepository {

    private fun resultRowToUser(row: ResultRow): User = User(
        id = row[Users.id],
        email = row[Users.email],
        login = row[Users.login],
        passwordHash = row[Users.passwordHash],
        googleId = row[Users.googleId],
        createdAt = row[Users.createdAt]
    )

    suspend fun create(email: String, passwordHash: String, login: String? = null): User? = dbQuery {
        val insertStatement = Users.insert {
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.login] = login
        }
        insertStatement.resultedValues?.singleOrNull()?.let(::resultRowToUser)
    }

    suspend fun findByEmail(email: String): User? = dbQuery {
        Users.selectAll()
            .where { Users.email eq email }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    suspend fun findById(id: Int): User? = dbQuery {
        Users.selectAll()
            .where { Users.id eq id }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    suspend fun existsByEmail(email: String): Boolean = dbQuery {
        Users.selectAll().where { Users.email eq email }.count() > 0
    }

    suspend fun existsByLogin(login: String): Boolean = dbQuery {
        Users.selectAll().where { Users.login eq login }.count() > 0
    }

    suspend fun findByGoogleId(googleId: String): User? = dbQuery {
        Users.selectAll()
            .where { Users.googleId eq googleId }
            .map(::resultRowToUser)
            .singleOrNull()
    }

    suspend fun findOrCreateByGoogle(email: String, googleId: String): User = dbQuery {
        Users.selectAll().where { Users.googleId eq googleId }
            .map(::resultRowToUser)
            .singleOrNull()
            ?: run {
                val existing = Users.selectAll().where { Users.email eq email }
                    .map(::resultRowToUser)
                    .singleOrNull()
                if (existing != null) {
                    Users.update({ Users.id eq existing.id }) { it[Users.googleId] = googleId }
                    existing.copy(googleId = googleId)
                } else {
                    val generatedLogin = generateLoginFromEmail(email)
                    val stmt = Users.insert {
                        it[Users.email] = email
                        it[Users.passwordHash] = ""
                        it[Users.googleId] = googleId
                        it[Users.login] = generatedLogin
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

    suspend fun updateLogin(id: Int, newLogin: String) = dbQuery {
        Users.update({ Users.id eq id }) { it[login] = newLogin }
    }

    suspend fun delete(id: Int): Boolean = dbQuery {
        Users.deleteWhere { Users.id eq id } > 0
    }

    suspend fun findAll(search: String? = null, limit: Int = 50, offset: Long = 0): List<User> = dbQuery {
        val query = if (!search.isNullOrBlank()) {
            val pattern = "%${search.lowercase()}%"
            Users.selectAll().where {
                (Users.email.lowerCase() like pattern) or
                (Users.login.lowerCase() like pattern)
            }
        } else {
            Users.selectAll()
        }
        query.orderBy(Users.id to SortOrder.DESC)
            .limit(limit, offset)
            .map(::resultRowToUser)
    }

    suspend fun countAll(search: String? = null): Long = dbQuery {
        if (!search.isNullOrBlank()) {
            val pattern = "%${search.lowercase()}%"
            Users.selectAll().where {
                (Users.email.lowerCase() like pattern) or
                (Users.login.lowerCase() like pattern)
            }.count()
        } else {
            Users.selectAll().count()
        }
    }

    private fun generateLoginFromEmail(email: String): String {
        return email.substringBefore("@").replace(Regex("[^a-zA-Z0-9_]"), "_").take(30)
    }
}
