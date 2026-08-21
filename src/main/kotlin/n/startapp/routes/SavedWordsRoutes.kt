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
import n.startapp.models.auth.SetWordFoldersRequest
import n.startapp.models.auth.toDTO
import n.startapp.repositories.FlashcardRepository
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.SavedWordRepository
import n.startapp.services.SavedWordEnrichment
import n.startapp.services.group.FolderAccessResolver
import n.startapp.services.group.FolderCatalog
import n.startapp.services.lexical.SenseWording
import org.slf4j.LoggerFactory

fun Route.savedWordsRoutes(lexicalEntries: LexicalEntryRepository) {
    val savedWordRepository = SavedWordRepository()
    val flashcardRepository = FlashcardRepository()
    val folderCatalog = FolderCatalog(FolderAccessResolver(), savedWordRepository)

    authenticate("auth-jwt") {
        route("/api/words") {
            // Save a word - supports both /save and /saved endpoints
            route("/saved") {
                post {
                    val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                    val request = call.receive<SaveWordRequest>()
                    val savedWord = savedWordRepository.saveFrom(request, userId, lexicalEntries, flashcardRepository)

                    call.respond(
                        HttpStatusCode.Created,
                        ApiResponse.success(savedWord.toDTO())
                    )
                }

                // Get all saved words
                get {
                    val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")

                    // Own words first, then whatever the reader's groups lend them.
                    val visible = folderCatalog.wordsFor(userId)
                    val filled = visible.map { it.word }
                        .withCorpusGapsFilled(lexicalEntries, savedWordRepository, userId)

                    call.respond(
                        ApiResponse.success(
                            SavedWordsResponse(
                                words = filled.mapIndexed { index, word ->
                                    val seenAs = visible[index]
                                    word.toDTO(groupId = seenAs.groupId, readOnly = seenAs.readOnly)
                                }
                            )
                        )
                    )
                }

                /**
                 * Removes one entry — one sense — leaving the other senses of that word.
                 *
                 * Two segments, so it cannot collide with `/{word}` below. That route stays
                 * because the app in the Play Store speaks it, and it means what it always
                 * meant: «убрать это слово», all of it.
                 */
                delete("/id/{id}") {
                    val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw BadRequestException("Invalid saved word id")

                    if (!savedWordRepository.deleteEntry(userId, id)) {
                        throw NotFoundException("Word not found in saved words")
                    }
                    call.respond(ApiResponse.success("Word deleted successfully"))
                }

                /** Replaces the folders of one entry. An empty list files it nowhere. */
                put("/id/{id}/folders") {
                    val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw BadRequestException("Invalid saved word id")
                    val request = call.receive<SetWordFoldersRequest>()

                    // ⚠️ Только свои папки. Класть слово в папку группы значило бы писать в
                    // словарь учителя, а это совсем другое действие, чем учить то, что в нём.
                    val folders = FolderAccessResolver()
                    for (categoryId in request.categoryIds.distinct()) {
                        folders.requireOwned(userId, categoryId)
                    }

                    val updated = savedWordRepository.setFolders(userId, id, request.categoryIds)
                        ?: throw NotFoundException("Saved word not found")

                    // Карточка едет за словом, если лежала вне папок, — то же правило, что и у
                    // одиночного переноса. Папок теперь может быть несколько, а карточка одна,
                    // поэтому берётся первая: разложить одно значение по двум расписаниям
                    // нельзя, а выбрать за человека одно из них — можно.
                    runCatching {
                        flashcardRepository.followWordIntoFolder(
                            userId = userId,
                            word = updated.word,
                            categoryId = updated.categoryIds.firstOrNull(),
                            senseId = updated.senseId
                        )
                    }.onFailure { savedWordLogger.warn("Card did not follow '${updated.word}': ${it.message}") }

                    call.respond(ApiResponse.success(updated.toDTO()))
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
                val savedWord = savedWordRepository.saveFrom(request, userId, lexicalEntries, flashcardRepository)

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
    lexicalEntries: LexicalEntryRepository,
    flashcards: FlashcardRepository
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

    val saved = save(
        userId = userId,
        word = word,
        translation = wording?.translation?.takeIf { it.isNotBlank() } ?: request.translation,
        definition = wording?.definition ?: request.definition,
        example = wording?.example,
        senseId = pinned,
        categoryIds = request.categoryIds.orEmpty()
    ) ?: throw Exception("Failed to save word")

    // Карточка этого слова могла быть создана раньше — до того, как человек вообще выбрал
    // значение. Оставить её как есть значило бы, что список слов и повторение спорят друг с
    // другом. ⚠️ Карточку, уже привязанную к *другому* значению, repinToSense не трогает: это
    // карточка другой записи, и переписать её здесь значило бы стереть чужое расписание.
    if (pinned != null && wording != null) {
        runCatching {
            flashcards.repinToSense(
                userId = userId,
                word = word,
                senseId = pinned,
                translation = wording.translation,
                definition = wording.definition,
                example = wording.example,
                phonetic = wording.phonetic,
                audioUrl = wording.audioUrl
            )
        }.onFailure { savedWordLogger.warn("Could not re-point the card for '$word': ${it.message}") }
    }

    return saved
}


/**
 * Closes the gaps [SavedWordEnrichment] finds, and writes them back so the next read is cheap.
 */
private suspend fun List<SavedWord>.withCorpusGapsFilled(
    lexicalEntries: LexicalEntryRepository,
    repository: SavedWordRepository,
    userId: Int
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
            repository.updateContent(saved.id, userId, filled.translation, filled.definition, filled.example)
        }.onFailure { savedWordLogger.warn("Could not fill saved word ${saved.id}: ${it.message}") }
        saved.copy(
            definition = filled.definition,
            translation = filled.translation,
            example = filled.example ?: saved.example
        )
    }
}
