package n.startapp.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.NotFoundException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import n.startapp.models.reader.ImportTextRequest
import n.startapp.models.reader.OfflineStatusDto
import n.startapp.models.reader.RenameBookRequest
import n.startapp.models.reader.SetBookmarkRequest
import n.startapp.models.reader.SetPositionRequest
import n.startapp.repositories.BookRepository
import n.startapp.repositories.CategoryRepository
import n.startapp.services.reader.BookImportService
import n.startapp.services.reader.BookOfflineService
import n.startapp.utils.EnvConfig

/**
 * The reader's library: import, open, page, remember where you were.
 *
 * Everything is behind JWT and scoped to one user's own rows. There is no public catalogue yet
 * and no sharing — a book here is text somebody uploaded, and the safe default for that is that
 * nobody else can reach it.
 */
fun Route.libraryRoutes(
    repository: BookRepository,
    importService: BookImportService,
    offlineService: BookOfflineService
) {
    val categories = CategoryRepository()

    authenticate("auth-jwt") {
        route("/api/v2/library/books") {

            get {
                val userId = readerId(call)
                call.respond(ApiResponse.success(repository.listFor(userId)))
            }

            /**
             * Multipart upload. The file part is read whole — the parsers need the archive's
             * directory, which is at its end, so streaming would buy nothing.
             */
            post {
                val userId = readerId(call)
                var fileName: String? = null
                var bytes: ByteArray? = null

                val multipart = call.receiveMultipart()
                while (true) {
                    val part = multipart.readPart() ?: break
                    when (part) {
                        is PartData.FileItem -> {
                            fileName = part.originalFileName
                            bytes = part.provider().readRemaining().readByteArray()
                        }
                        else -> {}
                    }
                    part.dispose()
                    if (bytes != null) break
                }

                val content = bytes ?: throw BadRequestException("Файл не приложен")
                val result = importService.importFile(userId, fileName, content)
                call.respond(
                    if (result.alreadyExisted) HttpStatusCode.OK else HttpStatusCode.Created,
                    ApiResponse.success(result)
                )
            }

            /** Pasted text: the shortest path there is from "some English" to reading it. */
            post("/text") {
                val userId = readerId(call)
                val request = call.receive<ImportTextRequest>()
                val result = importService.importText(userId, request)
                call.respond(
                    if (result.alreadyExisted) HttpStatusCode.OK else HttpStatusCode.Created,
                    ApiResponse.success(result)
                )
            }

            get("/{id}") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val detail = repository.detail(userId, bookId) ?: throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(detail))
            }

            /**
             * A window of blocks, addressed by ordinal.
             *
             * Paged by block rather than by chapter because a chapter is not a size: a novel's is
             * two screens and a treatise's is forty, and a reader that has to load the larger one
             * before showing its first line stalls exactly where somebody is waiting to read.
             */
            get("/{id}/blocks") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val from = call.request.queryParameters["from"]?.toIntOrNull() ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 40
                val tokens = call.request.queryParameters["tokens"]?.lowercase() != "false"

                val page = repository.blocks(userId, bookId, from, limit, tokens)
                    ?: throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(page))
            }

            /**
             * Renames the book. If it already has a folder (see `POST /{id}/folder`), the folder's
             * name is carried along — it was only ever a snapshot of the title at creation time.
             */
            put("/{id}") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val request = call.receive<RenameBookRequest>()
                val book = repository.rename(userId, bookId, request.title)
                    ?: throw NotFoundException("Книга не найдена")
                categories.renameForBook(userId, bookId, request.title)
                call.respond(ApiResponse.success(book))
            }

            /**
             * Where this reader is now.
             *
             * Last write wins, and the response carries `updatedAt` so a client can ask rather
             * than jump: somebody who deliberately turned back on the phone must not be dragged
             * forward the moment the tablet syncs.
             */
            put("/{id}/position") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val request = call.receive<SetPositionRequest>()
                val position = repository.setPosition(userId, bookId, request.ordinal)
                    ?: throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(position))
            }

            /**
             * The folder this book's words are filed into, if it has one yet.
             *
             * Null rather than 404 for a book nobody has saved from: the folder's absence is the
             * answer, and it is the answer for most books most of the time.
             */
            get("/{id}/folder") {
                val userId = readerId(call)
                val bookId = bookId(call)
                if (repository.detail(userId, bookId) == null) throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(categories.findFolderForBook(userId, bookId)))
            }

            /**
             * The folder, created if this is the first word saved from the book.
             *
             * Idempotent: the reader taps a word, and the folder either exists already or comes
             * into being — either way the caller gets the id it needs to file the word in one
             * request, without a "does it exist" round trip that two quick taps would both lose.
             */
            post("/{id}/folder") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val folder = categories.folderForBook(userId, bookId)
                    ?: throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(folder))
            }

            /**
             * Ставит книгу на прогрев для офлайн-чтения — только Android, и только тап без сети.
             *
             * Идемпотентно на уже идущий джоб: второй `POST` во время прогрева не заводит второй,
             * а просто возвращает тот же статус — ту же кнопку можно нажимать сколько угодно раз.
             * Дневной лимит считается **читателем**, а не книгой: сам прогрев ничего не знает о
             * том, кто его попросил, и может уже быть тёплым от другого читателя той же книги.
             */
            post("/{id}/offline/start") {
                val userId = readerId(call)
                val bookId = bookId(call)
                if (repository.detail(userId, bookId) == null) throw NotFoundException("Книга не найдена")

                val allowed = repository.registerOfflineDownload(userId, bookId, EnvConfig.bookOfflineDailyLimit)
                if (!allowed) {
                    // ⚠️ Код, а не фраза — тем же приёмом, что `book_limit_reached`: клиент
                    // матчится на строку и показывает свой русский текст для неё.
                    throw BadRequestException("offline_daily_limit_reached")
                }

                if (!offlineService.start(bookId)) throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(offlineStatus(repository, offlineService, userId, bookId)))
            }

            get("/{id}/offline/status") {
                val userId = readerId(call)
                val bookId = bookId(call)
                if (repository.detail(userId, bookId) == null) throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(offlineStatus(repository, offlineService, userId, bookId)))
            }

            /** Готовые подсказки, окном блоков — тем же контрактом, что `/blocks`. */
            get("/{id}/offline/bundle") {
                val userId = readerId(call)
                val bookId = bookId(call)
                if (repository.detail(userId, bookId) == null) throw NotFoundException("Книга не найдена")
                val from = call.request.queryParameters["from"]?.toIntOrNull() ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 40
                val page = offlineService.bundle(bookId, from, limit) ?: throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(page))
            }

            /**
             * Закладки — «сюда я хочу вернуться», в отличие от позиции, которая отвечает
             * «где я сейчас». Их сколько угодно, и переписывать друг друга они не должны.
             */
            get("/{id}/bookmarks") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val marks = repository.bookmarks(userId, bookId)
                    ?: throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(marks))
            }

            post("/{id}/bookmarks") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val request = call.receive<SetBookmarkRequest>()
                val mark = repository.addBookmark(userId, bookId, request.ordinal)
                    ?: throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success(mark))
            }

            delete("/{id}/bookmarks/{ordinal}") {
                val userId = readerId(call)
                val bookId = bookId(call)
                val ordinal = call.parameters["ordinal"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid ordinal")
                if (!repository.removeBookmark(userId, bookId, ordinal)) {
                    throw NotFoundException("Закладка не найдена")
                }
                call.respond(ApiResponse.success("Закладка убрана"))
            }

            delete("/{id}") {
                val userId = readerId(call)
                val bookId = bookId(call)
                if (!repository.delete(userId, bookId)) throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success("Книга удалена"))
            }
        }
    }
}

/**
 * Merges the job's own numbers (per book, no notion of who is asking) with this reader's daily
 * quota (per reader, no notion of which book) into the one answer the client actually needs.
 */
private suspend fun offlineStatus(
    repository: BookRepository,
    offlineService: BookOfflineService,
    userId: Int,
    bookId: Int
): OfflineStatusDto {
    val snapshot = offlineService.status(bookId)
    return OfflineStatusDto(
        bookId = bookId,
        running = snapshot?.running ?: false,
        totalTokens = snapshot?.totalTokens ?: 0,
        processedTokens = snapshot?.processedTokens ?: 0,
        failed = snapshot?.failed ?: 0,
        startedAt = snapshot?.startedAt,
        finishedAt = snapshot?.finishedAt,
        downloadsToday = repository.offlineDownloadsToday(userId),
        downloadsPerDay = EnvConfig.bookOfflineDailyLimit
    )
}

private fun readerId(call: ApplicationCall): Int =
    call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
        ?: throw UnauthorizedException("Invalid token")

private fun bookId(call: ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Invalid book id")
