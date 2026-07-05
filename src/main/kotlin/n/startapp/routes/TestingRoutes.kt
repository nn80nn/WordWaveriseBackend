package n.startapp.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.TestingRequests
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class TestingRequestBody(val email: String)

private val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()

fun Application.testingRoutes() {
    routing {
        route("/api/testing") {

            // ── Submit a testing request ────────────────────────────────────
            post("/request") {
                val body = call.receive<TestingRequestBody>()
                val email = body.email.trim().lowercase()

                if (!email.matches(emailRegex)) {
                    throw BadRequestException("Invalid email format")
                }

                val alreadyPending = dbQuery {
                    !TestingRequests.selectAll()
                        .where { (TestingRequests.email eq email) and (TestingRequests.status eq "pending") }
                        .empty()
                }
                if (alreadyPending) {
                    throw BadRequestException("This email already has a pending testing request")
                }

                dbQuery {
                    TestingRequests.insert {
                        it[TestingRequests.email] = email
                    }
                }
                call.respond(ApiResponse.success("Testing request submitted"))
            }
        }
    }
}
