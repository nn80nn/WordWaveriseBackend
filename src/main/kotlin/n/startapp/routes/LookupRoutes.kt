package n.startapp.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.services.LookupService

/**
 * v2 lookup. Separate from `/api/words/details` because [n.startapp.models.lookup.LookupResponse]
 * is a genuinely different shape — the legacy endpoints keep their contract byte for byte.
 */
fun Route.lookupRoutes(lookupService: LookupService) {
    route("/api/v2/words") {
        get("/lookup") {
            val query = call.request.queryParameters["query"]
                ?: throw BadRequestException("Query parameter 'query' is required")
            call.respond(ApiResponse.success(lookupService.lookup(query)))
        }
    }
}
