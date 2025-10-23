package n.startapp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.models.HealthStatus
import n.startapp.services.DictionaryService

fun Application.configureRouting() {
    val dictionaryService = DictionaryService()

    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        get("/api/health") {
            val healthStatus = HealthStatus(
                status = "ok",
                version = "1.0.0"
            )
            call.respond(ApiResponse.success(healthStatus))
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
    }
}
