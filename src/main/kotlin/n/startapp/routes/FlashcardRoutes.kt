package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.models.flashcard.CreateFlashcardRequest
import n.startapp.models.flashcard.ReviewRequest
import n.startapp.services.FlashcardService

/**
 * Routes for flashcard management with spaced repetition
 */
fun Route.flashcardRoutes() {
    val flashcardService = FlashcardService()

    authenticate {
        route("/api/flashcards") {

            // GET /api/flashcards/due - Get all flashcards due for review
            get("/due") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw BadRequestException("Invalid authentication token")

                val userId = principal.payload.getClaim("userId").asInt()

                val response = flashcardService.getDueFlashcards(userId)
                call.respond(ApiResponse.success(response))
            }

            // POST /api/flashcards/review - Review a flashcard
            post("/review") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw BadRequestException("Invalid authentication token")

                val userId = principal.payload.getClaim("userId").asInt()
                val request = call.receive<ReviewRequest>()

                val response = flashcardService.reviewFlashcard(userId, request)
                call.respond(ApiResponse.success(response))
            }

            // POST /api/flashcards/create - Create flashcard from saved word
            post("/create") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw BadRequestException("Invalid authentication token")

                val userId = principal.payload.getClaim("userId").asInt()
                val request = call.receive<CreateFlashcardRequest>()

                val flashcard = flashcardService.createFlashcard(userId, request.savedWordId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(flashcard))
            }

            // GET /api/flashcards - Get all flashcards
            get {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw BadRequestException("Invalid authentication token")

                val userId = principal.payload.getClaim("userId").asInt()

                val flashcards = flashcardService.getAllFlashcards(userId)
                call.respond(ApiResponse.success(flashcards))
            }

            // GET /api/flashcards/statistics - Get flashcard statistics
            get("/statistics") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw BadRequestException("Invalid authentication token")

                val userId = principal.payload.getClaim("userId").asInt()

                val stats = flashcardService.getStatistics(userId)
                call.respond(ApiResponse.success(stats))
            }

            // DELETE /api/flashcards/{id} - Delete a flashcard
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw BadRequestException("Invalid authentication token")

                val userId = principal.payload.getClaim("userId").asInt()
                val cardId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid flashcard ID")

                flashcardService.deleteFlashcard(userId, cardId)
                call.respond(ApiResponse.success("Flashcard deleted successfully"))
            }
        }
    }
}
