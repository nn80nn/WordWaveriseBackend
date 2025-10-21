package n.startapp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.models.ApiResponse
import n.startapp.models.HealthStatus

fun Application.configureRouting() {
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
    }
}
