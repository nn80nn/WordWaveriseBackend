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
import n.startapp.models.reader.SetPositionRequest
import n.startapp.repositories.BookRepository
import n.startapp.repositories.CategoryRepository
import n.startapp.services.reader.BookImportService

/**
 * The reader's library: import, open, page, remember where you were.
 *
 * Everything is behind JWT and scoped to one user's own rows. There is no public catalogue yet
 * and no sharing — a book here is text somebody uploaded, and the safe default for that is that
 * nobody else can reach it.
 */
fun Route.libraryRoutes(repository: BookRepository, importService: BookImportService) {
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

            delete("/{id}") {
                val userId = readerId(call)
                val bookId = bookId(call)
                if (!repository.delete(userId, bookId)) throw NotFoundException("Книга не найдена")
                call.respond(ApiResponse.success("Книга удалена"))
            }
        }
    }
}

private fun readerId(call: ApplicationCall): Int =
    call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
        ?: throw UnauthorizedException("Invalid token")

private fun bookId(call: ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Invalid book id")
