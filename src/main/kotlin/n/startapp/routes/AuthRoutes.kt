package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import n.startapp.models.auth.AuthResponse
import n.startapp.models.auth.GoogleAuthRequest
import n.startapp.models.auth.LoginRequest
import n.startapp.models.auth.RegisterRequest
import n.startapp.models.auth.toDTO
import n.startapp.repositories.UserRepository
import n.startapp.utils.EnvConfig
import n.startapp.utils.JwtUtil
import n.startapp.utils.PasswordUtil

private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json() }
}

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class ChangeEmailRequest(val newEmail: String, val password: String)

@Serializable
data class ChangeLoginRequest(val login: String, val password: String)

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

            // Validate and resolve login/nickname
            val resolvedLogin: String? = if (!request.login.isNullOrBlank()) {
                val l = request.login.trim()
                if (l.length < 3 || l.length > 30) throw BadRequestException("Login must be 3–30 characters")
                if (!l.matches(Regex("[a-zA-Z0-9_]+"))) throw BadRequestException("Login may only contain letters, digits and underscores")
                if (userRepository.existsByLogin(l)) throw BadRequestException("This login is already taken")
                l
            } else null

            // Hash password and create user
            val passwordHash = PasswordUtil.hashPassword(request.password)
            val user = userRepository.create(request.email, passwordHash, resolvedLogin)
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

        // Google OAuth endpoint
        post("/google") {
            val request = call.receive<GoogleAuthRequest>()
            if (request.idToken.isBlank()) throw BadRequestException("idToken is required")

            // Verify with Google tokeninfo
            val tokenInfoUrl = "https://oauth2.googleapis.com/tokeninfo?id_token=${request.idToken}"
            val googleResponse = try {
                httpClient.get(tokenInfoUrl)
            } catch (e: Exception) {
                throw BadRequestException("Failed to verify Google token")
            }

            if (googleResponse.status.value != 200) {
                throw UnauthorizedException("Invalid Google token")
            }

            val body = googleResponse.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject
            val email = json["email"]?.jsonPrimitive?.content
                ?: throw BadRequestException("Email not found in Google token")
            val googleId = json["sub"]?.jsonPrimitive?.content
                ?: throw BadRequestException("Subject not found in Google token")

            // Optional: verify aud matches our client id
            val clientId = EnvConfig.googleClientId
            if (clientId.isNotBlank()) {
                val aud = json["aud"]?.jsonPrimitive?.content
                if (aud != clientId) throw UnauthorizedException("Token audience mismatch")
            }

            val user = userRepository.findOrCreateByGoogle(email, googleId)
            val token = JwtUtil.generateToken(user)
            call.respond(ApiResponse.success(AuthResponse(token = token, user = user.toDTO())))
        }

        // Login endpoint
        post("/login") {
            val request = call.receive<LoginRequest>()

            if (request.email.isBlank() || request.password.isBlank()) {
                throw BadRequestException("Email and password are required")
            }

            val user = userRepository.findByEmail(request.email)
                ?: throw UnauthorizedException("Invalid email or password")

            if (!PasswordUtil.verifyPassword(request.password, user.passwordHash)) {
                throw UnauthorizedException("Invalid email or password")
            }

            val token = JwtUtil.generateToken(user)

            call.respond(
                ApiResponse.success(
                    AuthResponse(
                        token = token,
                        user = user.toDTO()
                    )
                )
            )
        }

        // Protected endpoints — require valid JWT
        authenticate("auth-jwt") {
            // Get current user profile
            get("/me") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                call.respond(ApiResponse.success(mapOf("user" to user.toDTO())))
            }

            // Change password
            post("/change-password") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                val request = call.receive<ChangePasswordRequest>()
                if (request.currentPassword.isBlank() || request.newPassword.isBlank()) {
                    throw BadRequestException("Both passwords are required")
                }
                if (request.newPassword.length < 6) {
                    throw BadRequestException("New password must be at least 6 characters")
                }

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                if (!PasswordUtil.verifyPassword(request.currentPassword, user.passwordHash)) {
                    throw UnauthorizedException("Current password is incorrect")
                }

                val newHash = PasswordUtil.hashPassword(request.newPassword)
                userRepository.updatePassword(userId, newHash)

                call.respond(ApiResponse.success(mapOf("message" to "Password changed successfully")))
            }

            // Change email
            post("/change-email") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                val request = call.receive<ChangeEmailRequest>()
                if (request.newEmail.isBlank() || request.password.isBlank()) {
                    throw BadRequestException("Email and password are required")
                }
                if (!isValidEmail(request.newEmail)) {
                    throw BadRequestException("Invalid email format")
                }

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                if (!PasswordUtil.verifyPassword(request.password, user.passwordHash)) {
                    throw UnauthorizedException("Password is incorrect")
                }

                if (userRepository.existsByEmail(request.newEmail)) {
                    throw BadRequestException("Email already in use")
                }

                userRepository.updateEmail(userId, request.newEmail)
                val updatedUser = userRepository.findById(userId)!!
                val newToken = JwtUtil.generateToken(updatedUser)

                call.respond(ApiResponse.success(AuthResponse(token = newToken, user = updatedUser.toDTO())))
            }

            // Change login/nickname
            post("/change-login") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                val request = call.receive<ChangeLoginRequest>()
                if (request.login.isBlank() || request.password.isBlank()) {
                    throw BadRequestException("Login and password are required")
                }

                val newLogin = request.login.trim()
                if (newLogin.length < 3 || newLogin.length > 30) throw BadRequestException("Login must be 3–30 characters")
                if (!newLogin.matches(Regex("[a-zA-Z0-9_]+"))) throw BadRequestException("Login may only contain letters, digits and underscores")

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                if (!PasswordUtil.verifyPassword(request.password, user.passwordHash)) {
                    throw UnauthorizedException("Password is incorrect")
                }

                if (userRepository.existsByLogin(newLogin) && user.login != newLogin) {
                    throw BadRequestException("This login is already taken")
                }

                userRepository.updateLogin(userId, newLogin)
                val updatedUser = userRepository.findById(userId)!!

                call.respond(ApiResponse.success(mapOf("user" to updatedUser.toDTO())))
            }
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
