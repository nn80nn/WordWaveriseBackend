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
import n.startapp.models.auth.SetWordCategoryRequest
import n.startapp.models.auth.UpdateCategoryRequest
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.SavedWordRepository

fun Route.categoryRoutes() {
    val categoryRepository = CategoryRepository()
    val savedWordRepository = SavedWordRepository()

    authenticate("auth-jwt") {
        route("/api/categories") {

            // List all categories for the user
            get {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val categories = categoryRepository.findByUserId(userId)
                call.respond(ApiResponse.success(categories))
            }

            // Create a new category
            post {
                val userId = getUserIdFromPrincipal(call) ?: throw UnauthorizedException("Invalid token")
                val request = call.receive<CreateCategoryRequest>()
                if (request.name.isBlank()) throw BadRequestException("Category name cannot be empty")
                val category = categoryRepository.create(userId, request.name, request.color)
                call.respond(HttpStatusCode.Created, ApiResponse.success(category))
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
                call.respond(ApiResponse.success("Category updated"))
            }
        }
    }
}

private fun getUserIdFromPrincipal(call: ApplicationCall): Int? {
    val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
    return principal?.payload?.getClaim("userId")?.asInt()
}
