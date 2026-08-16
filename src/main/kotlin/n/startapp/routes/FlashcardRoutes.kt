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
import n.startapp.models.flashcard.BulkCreateFlashcardsRequest
import n.startapp.models.flashcard.CreateFlashcardDirectRequest
import n.startapp.models.flashcard.CreateFlashcardRequest
import n.startapp.models.flashcard.ReviewRequest
import n.startapp.models.flashcard.SetFlashcardCategoryRequest
import n.startapp.models.flashcard.UpdateFlashcardContentRequest
import n.startapp.models.flashcard.UpdateFlashcardProgressRequest
import n.startapp.services.FlashcardService

/**
 * Routes for flashcard management with spaced repetition.
 *
 * Every listing endpoint takes the same optional `categoryId`: absent means all folders, `-1`
 * means the cards in no folder, anything else is a folder id.
 */
fun Route.flashcardRoutes() {
    val flashcardService = FlashcardService()

    authenticate("auth-jwt") {
        route("/api/flashcards") {

            // POST /api/flashcards - Create flashcard directly
            post {
                val userId = call.userId()
                val request = call.receive<CreateFlashcardDirectRequest>()

                if (request.word.isBlank()) {
                    throw BadRequestException("Word cannot be empty")
                }
                if (request.translation.isBlank()) {
                    throw BadRequestException("Translation cannot be empty")
                }

                val flashcard = flashcardService.createFlashcardDirect(
                    userId = userId,
                    word = request.word.trim(),
                    translation = request.translation.trim(),
                    definition = request.definition?.trim(),
                    example = request.example?.trim(),
                    categoryId = request.categoryId,
                    senseId = request.senseId?.trim()?.takeIf { it.isNotEmpty() }
                )

                call.respond(HttpStatusCode.Created, ApiResponse.success(flashcard))
            }

            // GET /api/flashcards - Get all flashcards, optionally in one folder
            get {
                val flashcards = flashcardService.getAllFlashcards(call.userId(), call.categoryFilter())
                call.respond(ApiResponse.success(flashcards))
            }

            // GET /api/flashcards/due - Get all flashcards due for review
            get("/due") {
                val response = flashcardService.getDueFlashcards(call.userId(), call.categoryFilter())
                call.respond(ApiResponse.success(response))
            }

            // GET /api/flashcards/statistics - Get flashcard statistics
            get("/statistics") {
                val stats = flashcardService.getStatistics(call.userId(), call.categoryFilter())
                call.respond(ApiResponse.success(stats))
            }

            // POST /api/flashcards/create - Create flashcard from saved word
            post("/create") {
                val request = call.receive<CreateFlashcardRequest>()
                val flashcard = flashcardService.createFlashcard(call.userId(), request.savedWordId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(flashcard))
            }

            /**
             * POST /api/flashcards/bulk — one card for every saved word in a folder.
             *
             * The clients used to loop over saved words and fire one request each, so filling a
             * 200-word folder meant 200 round trips and a partially built deck whenever one of
             * them failed.
             */
            post("/bulk") {
                val request = runCatching { call.receive<BulkCreateFlashcardsRequest>() }
                    .getOrElse { BulkCreateFlashcardsRequest() }
                val result = flashcardService.createMissingFromCategory(call.userId(), request.categoryId)
                call.respond(ApiResponse.success(result))
            }

            // POST /api/flashcards/review - Review a flashcard (alternative endpoint)
            post("/review") {
                val request = call.receive<ReviewRequest>()
                val response = flashcardService.reviewFlashcard(call.userId(), request)
                call.respond(ApiResponse.success(response))
            }

            // PUT /api/flashcards/{id}/content - Replace what the card says
            put("/{id}/content") {
                val userId = call.userId()
                val cardId = call.cardId()
                val request = call.receive<UpdateFlashcardContentRequest>()

                if (request.translation.isBlank()) {
                    throw BadRequestException("Translation cannot be empty")
                }
                val word = request.word?.trim()?.takeIf { it.isNotBlank() }

                val updated = flashcardService.updateContent(
                    userId = userId,
                    cardId = cardId,
                    word = word,
                    translation = request.translation.trim(),
                    definition = request.definition?.trim()?.takeIf { it.isNotBlank() },
                    example = request.example?.trim()?.takeIf { it.isNotBlank() }
                )
                call.respond(ApiResponse.success(updated))
            }

            // PUT /api/flashcards/{id}/category - Move the card to another folder
            put("/{id}/category") {
                val userId = call.userId()
                val cardId = call.cardId()
                val request = call.receive<SetFlashcardCategoryRequest>()
                val updated = flashcardService.setCategory(userId, cardId, request.categoryId)
                call.respond(ApiResponse.success(updated))
            }

            // PUT /api/flashcards/{id} - Update flashcard progress
            put("/{id}") {
                val userId = call.userId()
                val cardId = call.cardId()
                val request = call.receive<UpdateFlashcardProgressRequest>()

                val response = flashcardService.reviewFlashcard(userId, ReviewRequest(cardId, request.difficulty))
                call.respond(ApiResponse.success(response))
            }

            // DELETE /api/flashcards/{id} - Delete a flashcard
            delete("/{id}") {
                flashcardService.deleteFlashcard(call.userId(), call.cardId())
                call.respond(ApiResponse.success("Flashcard deleted successfully"))
            }
        }
    }
}

internal fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
        ?: throw BadRequestException("Invalid authentication token")

private fun ApplicationCall.cardId(): Int =
    parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Invalid flashcard ID")

/** Absent → every folder; `-1` → the cards in no folder; otherwise the folder id. */
internal fun ApplicationCall.categoryFilter(): Int? {
    val raw = request.queryParameters["categoryId"]?.trim()
    if (raw.isNullOrBlank()) return null
    return raw.toIntOrNull() ?: throw BadRequestException("Invalid categoryId")
}
