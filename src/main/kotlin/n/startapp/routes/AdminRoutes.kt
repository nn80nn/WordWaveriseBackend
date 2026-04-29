package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.Users
import n.startapp.models.ApiResponse
import n.startapp.utils.EnvConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import java.time.Instant
import java.time.temporal.ChronoUnit

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

fun Application.adminRoutes() {
    routing {
        route("/api/admin") {
            get("/stats") {
                val secret = call.request.headers["X-Admin-Secret"]
                val adminSecret = EnvConfig.adminSecret
                if (adminSecret.isBlank() || secret != adminSecret) {
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
        }
    }
}
