package n.startapp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import n.startapp.models.HealthStatus
import n.startapp.models.auth.UserStats
import n.startapp.routes.adminRoutes
import n.startapp.routes.aiRoutes
import n.startapp.routes.authRoutes
import n.startapp.routes.categoryRoutes
import n.startapp.routes.flashcardRoutes
import n.startapp.routes.savedWordsRoutes
import n.startapp.services.AiService
import n.startapp.services.DictionaryService
import n.startapp.services.SuggestService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Application.configureRouting() {
    val dictionaryService = DictionaryService()
    val suggestService = SuggestService()
    val aiService = AiService()

    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        // Health check endpoint - supports both GET and HEAD methods
        route("/api/health") {
            get {
                val healthStatus = HealthStatus(
                    status = "ok",
                    version = "1.0.0"
                )
                call.respond(ApiResponse.success(healthStatus))
            }

            head {
                // HEAD request - return only headers, no body
                // Used by monitoring services like UptimeRobot
                call.respond(HttpStatusCode.OK)
            }
        }

        // Word search endpoints
        route("/api/words") {
            // Legacy endpoint for backward compatibility
            get("/search") {
                val query = call.request.queryParameters["query"]
                    ?: throw BadRequestException("Query parameter 'query' is required")

                if (query.isBlank()) {
                    throw BadRequestException("Query parameter 'query' cannot be empty")
                }

                val result = dictionaryService.searchWord(query)
                call.respond(ApiResponse.success(result))
            }

            // Enhanced endpoint with multi-source aggregation
            // ?quick=true returns only API data (~1-2s); full mode also runs web scrapers (~5-10s)
            get("/details") {
                val query = call.request.queryParameters["query"]
                    ?: throw BadRequestException("Query parameter 'query' is required")

                if (query.isBlank()) {
                    throw BadRequestException("Query parameter 'query' cannot be empty")
                }

                val quick = call.request.queryParameters["quick"]?.lowercase() == "true"
                val result = if (quick) dictionaryService.searchWordQuick(query)
                             else dictionaryService.searchWordEnhanced(query)
                call.respond(ApiResponse.success(result))
            }

            // Spelling suggestions (English) or translation candidates (Russian input)
            // prefix=true → autocomplete while typing English (uses DataMuse wildcard)
            get("/suggest") {
                val query = call.request.queryParameters["query"]
                    ?: throw BadRequestException("Query parameter 'query' is required")

                if (query.isBlank()) {
                    throw BadRequestException("Query parameter 'query' cannot be empty")
                }

                val prefix = call.request.queryParameters["prefix"]?.lowercase() == "true"
                val result = suggestService.getSuggestions(query, prefix = prefix)
                call.respond(ApiResponse.success(result))
            }
        }

        // Cache management endpoints
        route("/api/cache") {
            get("/stats") {
                val stats = dictionaryService.getCacheStats()
                call.respond(ApiResponse.success(stats))
            }

            post("/clear") {
                dictionaryService.clearCache()
                call.respond(ApiResponse.success("Cache cleared successfully"))
            }
        }

        // Auth routes
        authRoutes()

        // Saved words routes (protected)
        savedWordsRoutes()

        // Flashcard routes (protected)
        flashcardRoutes()

        // Category routes (protected)
        categoryRoutes()

        // User stats (protected)
        authenticate("auth-jwt") {
            get("/api/user/stats") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                val stats = dbQuery {
                    val wordsSaved = SavedWords.selectAll()
                        .where { SavedWords.userId eq userId }
                        .count()
                    val cardsReviewed = Flashcards.selectAll()
                        .where { (Flashcards.userId eq userId) and Flashcards.lastReviewed.isNotNull() }
                        .count()
                    UserStats(
                        wordsSaved = wordsSaved,
                        cardsReviewed = cardsReviewed
                    )
                }
                call.respond(ApiResponse.success(stats))
            }
        }
    }

    // AI routes (protected)
    aiRoutes(aiService)

    // Admin routes
    adminRoutes()
}
