package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.services.LookupService
import n.startapp.services.query.RuEnTranslationService
import n.startapp.services.warmup.WarmupService
import n.startapp.services.warmup.WarmupStartResponse

/**
 * v2 lookup. Separate from `/api/words/details` because [n.startapp.models.lookup.LookupResponse]
 * is a genuinely different shape — the legacy endpoints keep their contract byte for byte.
 */
fun Route.lookupRoutes(
    lookupService: LookupService,
    ruEnTranslationService: RuEnTranslationService,
    warmupService: WarmupService
) {
    route("/api/v2/words") {
        get("/lookup") {
            val query = call.request.queryParameters["query"]
                ?: throw BadRequestException("Query parameter 'query' is required")
            call.respond(ApiResponse.success(lookupService.lookup(query)))
        }
    }

    route("/api/v2/translate") {
        // GET so the result is cacheable and linkable; the query is always short.
        get("/ru-en") {
            val query = call.request.queryParameters["query"]
                ?: throw BadRequestException("Query parameter 'query' is required")
            call.respond(ApiResponse.success(ruEnTranslationService.translate(query)))
        }
    }

    // Corpus warm-up: builds articles ahead of demand, on the reserve pool.
    route("/api/admin/warmup") {
        get("/status") {
            if (call.rejectedAsNonAdmin()) return@get
            call.respond(ApiResponse.success(warmupService.status()))
        }

        post("/start") {
            if (call.rejectedAsNonAdmin()) return@post
            // limit=0 means the whole list; start small to see how a slice behaves first.
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 0
            // perHour=0 defers to the environment. Overridable here because retuning the pace
            // through a redeploy would restart the run being retuned.
            val perHour = call.request.queryParameters["perHour"]?.toIntOrNull() ?: 0
            val started = warmupService.start(limit, perHour)
            call.respond(
                ApiResponse.success(
                    WarmupStartResponse(
                        started = started,
                        message = if (started) "warm-up running" else "already running",
                        status = warmupService.status()
                    )
                )
            )
        }

        post("/stop") {
            if (call.rejectedAsNonAdmin()) return@post
            warmupService.stop()
            call.respond(ApiResponse.success(warmupService.status()))
        }
    }

    route("/api/admin/lexical") {
        // Synchronous annotation with the failure reason attached. Annotation runs in the
        // background on the normal path, so without this a broken provider is invisible.
        get("/diagnose") {
            if (call.rejectedAsNonAdmin()) return@get
            val query = call.request.queryParameters["query"]
                ?: throw BadRequestException("Query parameter 'query' is required")
            call.respond(ApiResponse.success(lookupService.diagnose(query)))
        }

        // Drops the cached article for a lemma so the next lookup regenerates it — the escape
        // hatch for a bad article, since these entries otherwise never expire.
        post("/invalidate") {
            if (call.rejectedAsNonAdmin()) return@post
            val lemma = call.request.queryParameters["lemma"]
                ?: throw BadRequestException("Query parameter 'lemma' is required")
            call.respond(ApiResponse.success(mapOf("removed" to lookupService.invalidate(lemma))))
        }
    }
}
