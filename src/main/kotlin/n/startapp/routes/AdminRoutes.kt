package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.Users
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.NotFoundException
import n.startapp.models.ApiResponse
import n.startapp.models.auth.toDTO
import n.startapp.repositories.UserRepository
import n.startapp.utils.EnvConfig
import n.startapp.utils.PasswordUtil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

@Serializable
data class AdminUserInfo(
    val id: Int,
    val email: String,
    val login: String?,
    val createdAt: String
)

@Serializable
data class AdminStats(
    val totalUsers: Long,
    val newUsers7d: Long,
    val newUsers30d: Long,
    val totalSavedWords: Long,
    val totalFlashcards: Long,
    val totalFlashcardsReviewed: Long,
    val recentUsers: List<AdminUserInfo>
)

@Serializable
data class AdminUsersResponse(
    val users: List<AdminUserFull>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class AdminUserFull(
    val id: Int,
    val email: String,
    val login: String?,
    val createdAt: String,
    val savedWordsCount: Long,
    val flashcardsCount: Long,
    val hasGoogleAuth: Boolean
)

@Serializable
data class AdminChangeEmailRequest(val email: String)

@Serializable
data class AdminChangeLoginRequest(val login: String)

private fun checkAdminSecret(secret: String?): Boolean {
    val adminSecret = EnvConfig.adminSecret
    return adminSecret.isNotBlank() && secret == adminSecret
}

fun Application.adminRoutes() {
    val userRepository = UserRepository()

    routing {
        route("/api/admin") {

            // ── Stats ──────────────────────────────────────────────────────
            get("/stats") {
                if (!checkAdminSecret(call.request.headers["X-Admin-Secret"])) {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                    return@get
                }

                val stats = dbQuery {
                    val now = Instant.now()
                    val ago7d = now.minus(7, ChronoUnit.DAYS)
                    val ago30d = now.minus(30, ChronoUnit.DAYS)

                    val totalUsers = Users.selectAll().count()
                    val newUsers7d = Users.selectAll().where { Users.createdAt greater ago7d }.count()
                    val newUsers30d = Users.selectAll().where { Users.createdAt greater ago30d }.count()
                    val totalSavedWords = SavedWords.selectAll().count()
                    val totalFlashcards = Flashcards.selectAll().count()
                    val totalReviewed = Flashcards.selectAll()
                        .where { Flashcards.lastReviewed.isNotNull() }
                        .count()

                    val recentUsers = Users.selectAll()
                        .orderBy(Users.createdAt to SortOrder.DESC)
                        .limit(20)
                        .map { row ->
                            AdminUserInfo(
                                id = row[Users.id],
                                email = row[Users.email],
                                login = row[Users.login],
                                createdAt = row[Users.createdAt].toString()
                            )
                        }

                    AdminStats(
                        totalUsers = totalUsers,
                        newUsers7d = newUsers7d,
                        newUsers30d = newUsers30d,
                        totalSavedWords = totalSavedWords,
                        totalFlashcards = totalFlashcards,
                        totalFlashcardsReviewed = totalReviewed,
                        recentUsers = recentUsers
                    )
                }
                call.respond(ApiResponse.success(stats))
            }

            // ── List users ─────────────────────────────────────────────────
            get("/users") {
                if (!checkAdminSecret(call.request.headers["X-Admin-Secret"])) {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                    return@get
                }

                val search = call.request.queryParameters["search"]?.trim()
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val pageSize = 20
                val offset = (page - 1).toLong() * pageSize

                val users = userRepository.findAll(search, pageSize, offset)
                val total = userRepository.countAll(search)

                val wordCounts = dbQuery {
                    SavedWords.select(SavedWords.userId, SavedWords.id.count())
                        .groupBy(SavedWords.userId)
                        .associate { it[SavedWords.userId] to it[SavedWords.id.count()] }
                }
                val cardCounts = dbQuery {
                    Flashcards.select(Flashcards.userId, Flashcards.id.count())
                        .groupBy(Flashcards.userId)
                        .associate { it[Flashcards.userId] to it[Flashcards.id.count()] }
                }

                val result = AdminUsersResponse(
                    users = users.map { u ->
                        AdminUserFull(
                            id = u.id,
                            email = u.email,
                            login = u.login,
                            createdAt = u.createdAt.toString(),
                            savedWordsCount = wordCounts[u.id] ?: 0L,
                            flashcardsCount = cardCounts[u.id] ?: 0L,
                            hasGoogleAuth = u.googleId != null
                        )
                    },
                    total = total,
                    page = page,
                    pageSize = pageSize
                )
                call.respond(ApiResponse.success(result))
            }

            // ── Delete user ────────────────────────────────────────────────
            delete("/users/{id}") {
                if (!checkAdminSecret(call.request.headers["X-Admin-Secret"])) {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                    return@delete
                }

                val userId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid user id")

                dbQuery {
                    Flashcards.deleteWhere { Flashcards.userId eq userId }
                    SavedWords.deleteWhere { SavedWords.userId eq userId }
                    Categories.deleteWhere { Categories.userId eq userId }
                    Users.deleteWhere { Users.id eq userId }
                }
                call.respond(ApiResponse.success("User deleted"))
            }

            // ── Reset password ─────────────────────────────────────────────
            post("/users/{id}/reset-password") {
                if (!checkAdminSecret(call.request.headers["X-Admin-Secret"])) {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                    return@post
                }

                val userId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid user id")

                val newPassword = generatePassword()
                val hash = PasswordUtil.hashPassword(newPassword)
                userRepository.updatePassword(userId, hash)
                call.respond(ApiResponse.success(mapOf("newPassword" to newPassword)))
            }

            // ── Change email ───────────────────────────────────────────────
            post("/users/{id}/change-email") {
                if (!checkAdminSecret(call.request.headers["X-Admin-Secret"])) {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                    return@post
                }

                val userId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid user id")
                val request = call.receive<AdminChangeEmailRequest>()
                if (request.email.isBlank()) throw BadRequestException("Email required")

                userRepository.updateEmail(userId, request.email.trim().lowercase())
                val updated = userRepository.findById(userId) ?: throw NotFoundException("User not found")
                call.respond(ApiResponse.success(updated.toDTO()))
            }

            // ── Change login ───────────────────────────────────────────────
            post("/users/{id}/change-login") {
                if (!checkAdminSecret(call.request.headers["X-Admin-Secret"])) {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                    return@post
                }

                val userId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid user id")
                val request = call.receive<AdminChangeLoginRequest>()
                val login = request.login.trim()
                if (login.length < 3 || login.length > 30) throw BadRequestException("Login must be 3–30 characters")
                if (!login.matches(Regex("[a-zA-Z0-9_]+"))) throw BadRequestException("Invalid login format")

                userRepository.updateLogin(userId, login)
                val updated = userRepository.findById(userId) ?: throw NotFoundException("User not found")
                call.respond(ApiResponse.success(updated.toDTO()))
            }
        }
    }
}

private fun generatePassword(): String {
    val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#"
    return (1..12).map { chars[Random.nextInt(chars.length)] }.joinToString("")
}
