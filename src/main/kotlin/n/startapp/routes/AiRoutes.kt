package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.models.ai.AiWordRequest
import n.startapp.services.AiService
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AiRoutes")

fun Application.aiRoutes(aiService: AiService) {
    routing {
        // Public route — no auth required (used from SearchScreen without login)
        route("/api/ai") {
            get("/summary") {
                val word = call.request.queryParameters["word"]?.trim()
                if (word.isNullOrBlank()) throw BadRequestException("word parameter is required")
                try {
                    val result = aiService.quickSummary(word)
                    call.respond(ApiResponse.success(result))
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiResponse.error<Nothing>(e.message ?: "AI unavailable")
                    )
                } catch (e: Exception) {
                    logger.error("AI summary failed for '$word': ${e.message}")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiResponse.error<Nothing>("Ошибка ИИ-сервиса")
                    )
                }
            }
        }

        authenticate("auth-jwt") {
            route("/api/ai") {
                post("/explain") {
                    val request = call.receive<AiWordRequest>()
                    if (request.word.isBlank()) throw BadRequestException("Word is required")
                    try {
                        val result = aiService.explain(request.word.trim())
                        call.respond(ApiResponse.success(result))
                    } catch (e: IllegalStateException) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiResponse.error<Nothing>(e.message ?: "AI service unavailable")
                        )
                    } catch (e: Exception) {
                        logger.error("AI explain failed for '${request.word}': ${e.message}")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiResponse.error<Nothing>("Ошибка ИИ-сервиса")
                        )
                    }
                }

                post("/examples") {
                    val request = call.receive<AiWordRequest>()
                    if (request.word.isBlank()) throw BadRequestException("Word is required")
                    try {
                        val result = aiService.generateExamples(request.word.trim())
                        call.respond(ApiResponse.success(result))
                    } catch (e: IllegalStateException) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiResponse.error<Nothing>(e.message ?: "AI service unavailable")
                        )
                    } catch (e: Exception) {
                        logger.error("AI examples failed for '${request.word}': ${e.message}")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiResponse.error<Nothing>("Ошибка ИИ-сервиса")
                        )
                    }
                }

                post("/exercise") {
                    val request = call.receive<AiWordRequest>()
                    if (request.word.isBlank()) throw BadRequestException("Word is required")
                    try {
                        val result = aiService.generateExercise(request.word.trim())
                        call.respond(ApiResponse.success(result))
                    } catch (e: IllegalStateException) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiResponse.error<Nothing>(e.message ?: "AI service unavailable")
                        )
                    } catch (e: Exception) {
                        logger.error("AI exercise failed for '${request.word}': ${e.message}")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiResponse.error<Nothing>("Ошибка ИИ-сервиса")
                        )
                    }
                }
            }
        }
    }
}
