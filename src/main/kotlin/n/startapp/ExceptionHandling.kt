package n.startapp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import n.startapp.exceptions.ApiException
import n.startapp.models.ApiResponse
import org.slf4j.LoggerFactory

fun Application.configureExceptionHandling() {
    val logger = LoggerFactory.getLogger("ExceptionHandling")

    install(StatusPages) {
        // Handle custom API exceptions
        exception<ApiException> { call, cause ->
            logger.error("API Exception: ${cause.message}", cause)
            call.respond(
                cause.statusCode,
                ApiResponse.error<Unit>(
                    message = cause.message
                )
            )
        }

        // Handle generic exceptions.
        // The cause goes to the log only — echoing it back leaks stack/driver detail to clients.
        exception<Throwable> { call, cause ->
            logger.error("Unexpected error on ${call.request.local.uri}: ${cause.message}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error<Unit>(message = "Внутренняя ошибка сервера")
            )
        }

        // Handle 404 Not Found
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ApiResponse.error<Unit>(
                    message = "Endpoint not found: ${call.request.local.uri}"
                )
            )
        }
    }
}
