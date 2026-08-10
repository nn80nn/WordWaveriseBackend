package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.services.LookupService
import n.startapp.utils.EnvConfig

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

    route("/api/admin/lexical") {
        // Synchronous annotation with the failure reason attached. Annotation runs in the
        // background on the normal path, so without this a broken provider is invisible.
        get("/diagnose") {
            if (!isAdmin(call.request.headers["X-Admin-Secret"])) {
                call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                return@get
            }
            val query = call.request.queryParameters["query"]
                ?: throw BadRequestException("Query parameter 'query' is required")
            call.respond(ApiResponse.success(lookupService.diagnose(query)))
        }

        // Drops the cached article for a lemma so the next lookup regenerates it — the escape
        // hatch for a bad article, since these entries otherwise never expire.
        post("/invalidate") {
            if (!isAdmin(call.request.headers["X-Admin-Secret"])) {
                call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Nothing>("Unauthorized"))
                return@post
            }
            val lemma = call.request.queryParameters["lemma"]
                ?: throw BadRequestException("Query parameter 'lemma' is required")
            call.respond(ApiResponse.success(mapOf("removed" to lookupService.invalidate(lemma))))
        }
    }
}

private fun isAdmin(secret: String?): Boolean {
    val expected = EnvConfig.adminSecret
    return expected.isNotBlank() && secret == expected
}
