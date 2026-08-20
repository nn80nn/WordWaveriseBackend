package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.NotFoundException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import n.startapp.models.auth.CreateCategoryRequest
import n.startapp.models.auth.ImportResult
import n.startapp.models.auth.ShareLink
import n.startapp.models.auth.SharedFolderPreview
import n.startapp.models.auth.SharedWordPreview
import n.startapp.utils.EnvConfig
import n.startapp.models.auth.SetWordCategoryRequest
import n.startapp.models.auth.UpdateCategoryRequest
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.FlashcardRepository
import n.startapp.repositories.SavedWordRepository
import n.startapp.services.group.FolderAccessResolver
import n.startapp.services.group.FolderCatalog

private val categoryLogger = org.slf4j.LoggerFactory.getLogger("CategoryRoutes")

fun Route.categoryRoutes() {
    val categoryRepository = CategoryRepository()
    val savedWordRepository = SavedWordRepository()
    val flashcardRepository = FlashcardRepository()
    val folderCatalog = FolderCatalog(FolderAccessResolver(), savedWordRepository)

    authenticate("auth-jwt") {
        route("/api/categories") {

            // Own folders, then the ones groups lend. A borrowed folder is marked readOnly and
            // counts the words its owner has in it, not the reader's.
            get {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                call.respond(ApiResponse.success(folderCatalog.foldersFor(userId)))
            }

            // Create a new category
            post {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val request = call.receive<CreateCategoryRequest>()
                if (request.name.isBlank()) throw BadRequestException("Category name cannot be empty")
                val category = categoryRepository.create(userId, request.name, request.color)
                call.respond(HttpStatusCode.Created, ApiResponse.success(category))
            }

            /**
             * POST /api/categories/{id}/share — mint (or return) the folder's share link.
             *
             * Stable across presses: a second tap must not invalidate a link already sent.
             */
            post("/{id}/share") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val categoryId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid category id")

                val token = categoryRepository.shareToken(userId, categoryId)
                    ?: throw NotFoundException("Category not found")

                call.respond(ApiResponse.success(ShareLink(token = token, url = shareUrl(token))))
            }

            /** The current link, if the folder has one — so the UI can show its state. */
            get("/{id}/share") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val categoryId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid category id")

                val token = categoryRepository.currentShareToken(userId, categoryId)
                call.respond(
                    ApiResponse.success(token?.let { ShareLink(token = it, url = shareUrl(it)) })
                )
            }

            /**
             * Stops the link working. Copies already taken are untouched — they are the other
             * person's words now, and reaching into their account is not what "unshare" means.
             */
            delete("/{id}/share") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val categoryId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid category id")

                if (!categoryRepository.revokeShare(userId, categoryId)) {
                    throw NotFoundException("Category not found")
                }
                call.respond(ApiResponse.success("Share link revoked"))
            }

            // Rename a category
            put("/{id}") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val categoryId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid category id")
                val request = call.receive<UpdateCategoryRequest>()
                if (request.name.isBlank()) throw BadRequestException("Category name cannot be empty")
                val updated = categoryRepository.update(userId, categoryId, request.name)
                if (!updated) throw NotFoundException("Category not found")
                call.respond(ApiResponse.success("Updated"))
            }

            // Delete a category (words moved to uncategorized)
            delete("/{id}") {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val categoryId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid category id")
                val deleted = categoryRepository.delete(userId, categoryId)
                if (!deleted) throw NotFoundException("Category not found")
                call.respond(ApiResponse.success("Deleted"))
            }
        }

        // Move a saved word to a category
        route("/api/words/saved/{word}/category") {
            put {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val word = call.parameters["word"]?.trim()?.lowercase()
                    ?: throw BadRequestException("Word parameter is required")
                val request = call.receive<SetWordCategoryRequest>()

                // Validate category belongs to user (if setting one)
                if (request.categoryId != null && !categoryRepository.exists(userId, request.categoryId)) {
                    throw NotFoundException("Category not found")
                }

                val updated = savedWordRepository.setCategory(userId, word, request.categoryId)
                if (!updated) throw NotFoundException("Saved word not found")

                // Карточка едет за словом, если лежала вне папок. Иначе папка выглядит пустой,
                // а «создать карточки из папки» отвечает «они уже есть» — и то и другое правда,
                // и вместе они бесполезны.
                runCatching { flashcardRepository.followWordIntoFolder(userId, word, request.categoryId) }
                    .onFailure { categoryLogger.warn("Card did not follow '$word': ${it.message}") }

                call.respond(ApiResponse.success("Category updated"))
            }
        }
    }
}


/**
 * Where a shared folder is read and taken.
 *
 * The preview is public because the link *is* the permission — asking someone to sign up before
 * they can see what they were sent turns a shared folder into an advert. Taking a copy needs an
 * account, because a copy has to land somewhere.
 */
fun Route.sharedFolderRoutes() {
    val categoryRepository = CategoryRepository()
    val savedWordRepository = SavedWordRepository()

    get("/api/share/{token}") {
        val token = call.parameters["token"]?.trim().orEmpty()
        val folder = categoryRepository.findByShareToken(token)
            ?: throw NotFoundException("This folder is not shared")

        val words = savedWordRepository.findByCategory(folder.ownerId, folder.id)
        call.respond(
            ApiResponse.success(
                SharedFolderPreview(
                    name = folder.name,
                    wordCount = words.size,
                    sample = words.take(12).map {
                        SharedWordPreview(word = it.word, translation = it.translation)
                    }
                )
            )
        )
    }

    authenticate("auth-jwt") {
        post("/api/share/{token}/import") {
            val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
            val token = call.parameters["token"]?.trim().orEmpty()

            val folder = categoryRepository.findByShareToken(token)
                ?: throw NotFoundException("This folder is not shared")

            val words = savedWordRepository.findByCategory(folder.ownerId, folder.id)
            if (words.isEmpty()) throw BadRequestException("This folder has no words")

            // Своя же папка: копия была бы дубликатом всего, что у человека уже есть.
            if (folder.ownerId == userId) throw BadRequestException("This folder is already yours")

            val name = categoryRepository.freeName(userId, folder.name)
            val created = categoryRepository.create(userId, name, null)
            val (added, alreadyHad) = savedWordRepository.copyInto(userId, created.id, words)

            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(
                    ImportResult(
                        categoryId = created.id,
                        name = created.name,
                        added = added,
                        alreadyHad = alreadyHad
                    )
                )
            )
        }
    }
}

/** The address a link is shown as. Same origin the site is served from — see `SITE_URL`. */
private fun shareUrl(token: String): String =
    "${EnvConfig.siteUrl.trimEnd('/')}/f/$token"

private fun getUserIdFromPrincipal(call: ApplicationCall): Int? {
    val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
    return principal?.payload?.getClaim("userId")?.asInt()
}
