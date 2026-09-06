package n.startapp.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.ContentReports
import n.startapp.database.tables.Users
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.NotFoundException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

@Serializable
data class ContentReportRequest(
    val kind: String,
    val word: String,
    val senseId: String? = null,
    val reason: String,
    val comment: String? = null
)

@Serializable
data class ContentReportDTO(
    val id: Int,
    val kind: String,
    val word: String,
    val senseId: String?,
    val reason: String,
    val comment: String?,
    val email: String?,
    val createdAt: String,
    val resolved: Boolean
)

/** What the clients offer to pick from. Anything else is a client that drifted from this list. */
private val KINDS = setOf("article", "exercise", "other")
private val REASONS = setOf(
    "wrong",       // неверное значение, перевод или пример
    "offensive",   // оскорбительное или неуместное
    "nonsense",    // бессмыслица, обрывок
    "other"
)

/**
 * A reader's complaint about generated text, and the admin side of reading them.
 *
 * Authenticated on purpose: an unauthenticated form on a public dictionary is a spam endpoint,
 * and every screen that can raise a report is already behind sign-in on Android.
 */
fun Route.reportRoutes() {
    authenticate("auth-jwt") {
        post("/api/reports") {
            val principal = call.principal<JWTPrincipal>()
            val userId = principal?.payload?.getClaim("userId")?.asInt()
                ?: throw UnauthorizedException("Invalid token")

            val body = call.receive<ContentReportRequest>()
            val word = body.word.trim()
            if (word.isBlank()) throw BadRequestException("word is required")
            if (body.kind !in KINDS) throw BadRequestException("Unknown report kind")
            if (body.reason !in REASONS) throw BadRequestException("Unknown report reason")

            dbQuery {
                ContentReports.insert {
                    it[ContentReports.userId] = userId
                    it[ContentReports.kind] = body.kind
                    it[ContentReports.word] = word.take(255)
                    it[ContentReports.senseId] = body.senseId?.take(64)
                    it[ContentReports.reason] = body.reason
                    it[ContentReports.comment] = body.comment?.trim()?.take(2000)?.ifBlank { null }
                }
            }
            call.respond(ApiResponse.success("Report received"))
        }
    }
}

/** Reading the reports. Behind the admin secret, like the rest of `/api/admin`. */
fun Route.adminReportRoutes() {
    get("/reports") {
        if (call.rejectedAsNonAdmin()) return@get
        val reports = dbQuery {
            (ContentReports leftJoin Users)
                .selectAll()
                .orderBy(ContentReports.createdAt to SortOrder.DESC)
                .limit(200)
                .map {
                    ContentReportDTO(
                        id = it[ContentReports.id],
                        kind = it[ContentReports.kind],
                        word = it[ContentReports.word],
                        senseId = it[ContentReports.senseId],
                        reason = it[ContentReports.reason],
                        comment = it[ContentReports.comment],
                        email = it.getOrNull(Users.email),
                        createdAt = it[ContentReports.createdAt].toString(),
                        resolved = it[ContentReports.resolvedAt] != null
                    )
                }
        }
        call.respond(ApiResponse.success(reports))
    }

    post("/reports/{id}/resolve") {
        if (call.rejectedAsNonAdmin()) return@post
        val id = call.parameters["id"]?.toIntOrNull()
            ?: throw BadRequestException("Invalid report id")
        val updated = dbQuery {
            ContentReports.update({ ContentReports.id eq id }) {
                it[resolvedAt] = Instant.now()
            }
        }
        if (updated == 0) throw NotFoundException("Report not found")
        call.respond(ApiResponse.success("Resolved"))
    }
}
