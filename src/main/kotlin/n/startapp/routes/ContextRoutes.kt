package n.startapp.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.models.ApiResponse
import n.startapp.services.context.ContextAnalysisService

@Serializable
data class TokenizeRequest(val text: String)

@Serializable
data class ContextAnalyzeRequest(
    val text: String,
    /** Preferred: refers to the tokenisation the server itself produced. */
    val tokenIndex: Int? = null,
    /** Fallback for callers that only have the word; resolves to its first tappable occurrence. */
    val token: String? = null
)

fun Route.contextRoutes(service: ContextAnalysisService) {
    route("/api/v2/context") {
        // Standalone tokenisation for a paste-then-tap flow. The search path does not need it:
        // a sentence lookup already carries its tokens, so tapping costs no extra round trip.
        post("/tokenize") {
            val request = call.receive<TokenizeRequest>()
            call.respond(ApiResponse.success(service.tokenize(request.text)))
        }

        post("/analyze") {
            val request = call.receive<ContextAnalyzeRequest>()
            call.respond(
                ApiResponse.success(service.analyze(request.text, request.tokenIndex, request.token))
            )
        }
    }
}
