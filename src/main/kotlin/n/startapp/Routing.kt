package n.startapp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.models.HealthStatus
import n.startapp.routes.authRoutes
import n.startapp.routes.savedWordsRoutes
import n.startapp.services.DictionaryService

fun Application.configureRouting() {
    val dictionaryService = DictionaryService()

    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        // Health check endpoint - supports both GET and HEAD methods
        route("/api/health") {
            get {
                val healthStatus = HealthStatus(
                    status = "ok",
                    version = "1.0.0"
                )
                call.respond(ApiResponse.success(healthStatus))
            }

            head {
                // HEAD request - return only headers, no body
                // Used by monitoring services like UptimeRobot
                call.respond(HttpStatusCode.OK)
            }
        }

        // Word search endpoint
        get("/api/words/search") {
            val query = call.request.queryParameters["query"]
                ?: throw BadRequestException("Query parameter 'query' is required")

            if (query.isBlank()) {
                throw BadRequestException("Query parameter 'query' cannot be empty")
            }

            val result = dictionaryService.searchWord(query)
            call.respond(ApiResponse.success(result))
        }

        // Auth routes
        authRoutes()

        // Saved words routes (protected)
        savedWordsRoutes()
    }
}
