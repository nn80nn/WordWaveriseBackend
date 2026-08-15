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
import n.startapp.models.auth.SavedWord
import n.startapp.models.auth.SavedWordsResponse
import n.startapp.models.auth.toDTO
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.SavedWordRepository
import n.startapp.services.SavedWordEnrichment
import org.slf4j.LoggerFactory

fun Route.savedWordsRoutes(lexicalEntries: LexicalEntryRepository) {
    val savedWordRepository = SavedWordRepository()

    authenticate("auth-jwt") {
        route("/api/words") {
            // Save a word - supports both /save and /saved endpoints
            route("/saved") {
                post {
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
                        HttpStatusCode.Created,
                        ApiResponse.success(savedWord.toDTO())
                    )
                }

                // Get all saved words
                get {
                    val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")

                    val savedWords = savedWordRepository
                        .findByUserId(userId)
                        .withCorpusGapsFilled(lexicalEntries, savedWordRepository)
                    call.respond(
                        ApiResponse.success(
                            SavedWordsResponse(
                                words = savedWords.map { it.toDTO() }
                            )
                        )
                    )
                }

                // Delete a saved word
                delete("/{word}") {
                    val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                    val word = call.parameters["word"]?.trim()?.lowercase()
                        ?: throw BadRequestException("Word parameter is required")

                    val deleted = savedWordRepository.delete(userId, word)
                    if (!deleted) {
                        throw NotFoundException("Word not found in saved words")
                    }

                    call.respond(ApiResponse.success("Word deleted successfully"))
                }
            }

            // Legacy endpoint for backward compatibility
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

private val savedWordLogger = LoggerFactory.getLogger("SavedWordsRoutes")

/**
 * Closes the gaps [SavedWordEnrichment] finds, and writes them back so the next read is cheap.
 */
private suspend fun List<SavedWord>.withCorpusGapsFilled(
    lexicalEntries: LexicalEntryRepository,
    repository: SavedWordRepository
): List<SavedWord> {
    val gaps = filter { it.definition.isNullOrBlank() || it.translation.isNullOrBlank() }
    if (gaps.isEmpty()) return this

    val entries = try {
        lexicalEntries.findLatestByLemmas(gaps.map { it.word })
    } catch (e: Exception) {
        // A thin card beats a failed list.
        savedWordLogger.warn("Could not read the corpus while filling saved words: ${e.message}")
        return this
    }

    return map { saved ->
        val filled = SavedWordEnrichment.fill(saved, entries[saved.word.trim().lowercase()])
            ?: return@map saved

        runCatching { repository.updateContent(saved.id, filled.translation, filled.definition) }
            .onFailure { savedWordLogger.warn("Could not fill saved word ${saved.id}: ${it.message}") }
        saved.copy(definition = filled.definition, translation = filled.translation)
    }
}
