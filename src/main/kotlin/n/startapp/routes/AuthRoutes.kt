package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import n.startapp.models.auth.AuthResponse
import n.startapp.models.auth.LoginRequest
import n.startapp.models.auth.RegisterRequest
import n.startapp.models.auth.toDTO
import n.startapp.repositories.UserRepository
import n.startapp.utils.JwtUtil
import n.startapp.utils.PasswordUtil

fun Route.authRoutes() {
    val userRepository = UserRepository()

    route("/api/auth") {
        // Register endpoint
        post("/register") {
            val request = call.receive<RegisterRequest>()

            // Validate input
            if (request.email.isBlank() || request.password.isBlank()) {
                throw BadRequestException("Email and password are required")
            }

            if (!isValidEmail(request.email)) {
                throw BadRequestException("Invalid email format")
            }

            if (request.password.length < 6) {
                throw BadRequestException("Password must be at least 6 characters long")
            }

            // Check if user already exists
            if (userRepository.existsByEmail(request.email)) {
                throw BadRequestException("User with this email already exists")
            }

            // Hash password and create user
            val passwordHash = PasswordUtil.hashPassword(request.password)
            val user = userRepository.create(request.email, passwordHash)
                ?: throw Exception("Failed to create user")

            // Generate JWT token
            val token = JwtUtil.generateToken(user)

            // Return response
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(
                    AuthResponse(
                        token = token,
                        user = user.toDTO()
                    )
                )
            )
        }

        // Login endpoint
        post("/login") {
            val request = call.receive<LoginRequest>()

            // Validate input
            if (request.email.isBlank() || request.password.isBlank()) {
                throw BadRequestException("Email and password are required")
            }

            // Find user by email
            val user = userRepository.findByEmail(request.email)
                ?: throw UnauthorizedException("Invalid email or password")

            // Verify password
            if (!PasswordUtil.verifyPassword(request.password, user.passwordHash)) {
                throw UnauthorizedException("Invalid email or password")
            }

            // Generate JWT token
            val token = JwtUtil.generateToken(user)

            // Return response
            call.respond(
                ApiResponse.success(
                    AuthResponse(
                        token = token,
                        user = user.toDTO()
                    )
                )
            )
        }
    }
}

/**
 * Simple email validation
 */
private fun isValidEmail(email: String): Boolean {
    val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
    return email.matches(emailRegex)
}
