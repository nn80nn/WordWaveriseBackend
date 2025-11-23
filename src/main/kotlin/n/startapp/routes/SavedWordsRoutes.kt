package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.NotFoundException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import n.startapp.models.auth.SaveWordRequest
import n.startapp.models.auth.SavedWordsResponse
import n.startapp.models.auth.toDTO
import n.startapp.repositories.SavedWordRepository

fun Route.savedWordsRoutes() {
    val savedWordRepository = SavedWordRepository()

    authenticate("auth-jwt") {
        route("/api/words") {
            // Save a word
            post("/save") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val request = call.receive<SaveWordRequest>()

                if (request.word.isBlank()) {
                    throw BadRequestException("Word cannot be empty")
                }

                val savedWord = savedWordRepository.save(
                    userId = userId,
                    word = request.word.trim().lowercase(),
                    translation = request.translation,
                    definition = request.definition
                ) ?: throw Exception("Failed to save word")

                call.respond(
                    ApiResponse.success(savedWord.toDTO())
                )
            }

            // Get all saved words
            get("/saved") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")

                val savedWords = savedWordRepository.findByUserId(userId)
                call.respond(
                    ApiResponse.success(
                        SavedWordsResponse(
                            words = savedWords.map { it.toDTO() }
                        )
                    )
                )
            }

            // Delete a saved word
            delete("/saved/{word}") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val word = call.parameters["word"]?.trim()?.lowercase()
                    ?: throw BadRequestException("Word parameter is required")

                val deleted = savedWordRepository.delete(userId, word)
                if (!deleted) {
                    throw NotFoundException("Word not found in saved words")
                }

                call.respond(HttpStatusCode.OK)
            }
        }
    }
}

/**
 * Extract user ID from JWT principal
 */
private fun getUserIdFromPrincipal(call: ApplicationCall): Int? {
    val principal = call.principal<JWTPrincipal>()
    return principal?.payload?.getClaim("userId")?.asInt()
}
