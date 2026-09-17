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
    val token: String? = null,
    /**
     * Last token of a multi-word selection, inclusive. Omitted or equal to [tokenIndex] for a
     * single word — the ordinary case, and what every client sent before this field existed.
     * Greater than [tokenIndex] asks for the whole span as a phrase: no dictionary sense is
     * matched for it (a sense belongs to a word, not to an arbitrary stretch of text), just a
     * translation of the span in context.
     */
    val tokenEnd: Int? = null
)

fun Route.contextRoutes(service: ContextAnalysisService) {
    route("/api/v2/context") {
        // Standalone tokenisation for a paste-then-tap flow. The search path does not need it:
        // a sentence lookup already carries its tokens, so tapping costs no extra round trip.
        post("/tokenize") {
            val request = call.receive<TokenizeRequest>()
            call.respond(ApiResponse.success(service.tokenize(request.text)))
        }

        /**
         * The fast one: what the word means here, in a couple of seconds.
         *
         * Same request shape as `/analyze`, deliberately — a client swaps one for the other
         * without rebuilding anything. This is what a tap in the reader calls; the full analysis
         * stays a second, explicit step, because nobody reading a page wants to wait ten seconds
         * to learn one word.
         */
        post("/hint") {
            val request = call.receive<ContextAnalyzeRequest>()
            call.respond(
                ApiResponse.success(
                    service.hint(request.text, request.tokenIndex, request.token, request.tokenEnd)
                )
            )
        }

        post("/analyze") {
            val request = call.receive<ContextAnalyzeRequest>()
            call.respond(
                ApiResponse.success(
                    service.analyze(request.text, request.tokenIndex, request.token, request.tokenEnd)
                )
            )
        }
    }
}
