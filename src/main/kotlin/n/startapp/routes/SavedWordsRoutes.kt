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
import n.startapp.services.lexical.SenseWording
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
                    val savedWord = savedWordRepository.saveFrom(request, userId, lexicalEntries)

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
                val savedWord = savedWordRepository.saveFrom(request, userId, lexicalEntries)

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
 * One place where a save request becomes a row, so `/saved` and the legacy `/save` cannot drift.
 *
 * When the request pins a sense, the wording is read from the corpus rather than from the body:
 * the client picked a sense id, not a translation, and letting it post its own text back would
 * make the same pin mean different things in the browser and on the phone. If the corpus cannot
 * answer — the article is not annotated yet, or the id is unknown — the client's snapshot is
 * kept, so the pin still works on a word whose article is still being built.
 */
private suspend fun SavedWordRepository.saveFrom(
    request: SaveWordRequest,
    userId: Int,
    lexicalEntries: LexicalEntryRepository
): SavedWord {
    if (request.word.isBlank()) throw BadRequestException("Word cannot be empty")

    val word = request.word.trim().lowercase()
    val pinned = request.senseId?.trim()?.takeIf { it.isNotEmpty() }

    val wording = pinned?.let { senseId ->
        runCatching { lexicalEntries.findLatestByLemma(word) }
            .onFailure { savedWordLogger.warn("Could not read the corpus while pinning $word: ${it.message}") }
            .getOrNull()
            ?.let { SenseWording.of(it, senseId) }
    }

    return save(
        userId = userId,
        word = word,
        translation = wording?.translation?.takeIf { it.isNotBlank() } ?: request.translation,
        definition = wording?.definition ?: request.definition,
        example = wording?.example,
        senseId = pinned
    ) ?: throw Exception("Failed to save word")
}


/**
 * Closes the gaps [SavedWordEnrichment] finds, and writes them back so the next read is cheap.
 */
private suspend fun List<SavedWord>.withCorpusGapsFilled(
    lexicalEntries: LexicalEntryRepository,
    repository: SavedWordRepository
): List<SavedWord> {
    val gaps = filter {
        it.definition.isNullOrBlank() || it.translation.isNullOrBlank() || it.example.isNullOrBlank()
    }
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

        runCatching {
            repository.updateContent(saved.id, filled.translation, filled.definition, filled.example)
        }.onFailure { savedWordLogger.warn("Could not fill saved word ${saved.id}: ${it.message}") }
        saved.copy(
            definition = filled.definition,
            translation = filled.translation,
            example = filled.example ?: saved.example
        )
    }
}
