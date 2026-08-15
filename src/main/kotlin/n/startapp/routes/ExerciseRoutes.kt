package n.startapp.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.models.exercise.ExerciseRequest
import n.startapp.models.exercise.ExerciseScope
import n.startapp.services.exercise.ExerciseService

/**
 * Practice sessions.
 *
 * Both endpoints take the same folder filter as the flashcard routes: absent means all folders,
 * `-1` means the words in no folder.
 */
fun Route.exerciseRoutes(exerciseService: ExerciseService) {
    authenticate("auth-jwt") {
        route("/api/exercises") {

            /** What this folder can currently produce, and how many of each. */
            get("/kinds") {
                val scope = call.scope()
                val response = exerciseService.kinds(call.userId(), call.categoryFilter(), scope)
                call.respond(ApiResponse.success(response))
            }

            /** A whole session in one round trip — questions, answers and explanations. */
            post("/generate") {
                val request = runCatching { call.receive<ExerciseRequest>() }
                    .getOrElse { throw BadRequestException("Invalid exercise request") }

                val batch = exerciseService.generate(call.userId(), request)
                call.respond(ApiResponse.success(batch))
            }
        }
    }
}

private fun ApplicationCall.scope(): ExerciseScope {
    val raw = request.queryParameters["scope"]?.trim()?.uppercase() ?: return ExerciseScope.SAVED
    return ExerciseScope.entries.firstOrNull { it.name == raw }
        ?: throw BadRequestException("Unknown scope '$raw'")
}
