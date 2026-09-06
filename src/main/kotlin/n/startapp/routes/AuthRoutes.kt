package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.UnauthorizedException
import n.startapp.models.ApiResponse
import n.startapp.models.auth.AuthResponse
import n.startapp.models.auth.GoogleAuthRequest
import n.startapp.models.auth.LoginRequest
import n.startapp.models.auth.RegisterRequest
import n.startapp.models.auth.RegisterResponse
import n.startapp.models.auth.RequestDeletionRequest
import n.startapp.models.auth.ResendVerificationRequest
import n.startapp.models.auth.VerifyEmailRequest
import n.startapp.models.auth.toDTO
import n.startapp.repositories.UserRepository
import n.startapp.services.EmailService
import n.startapp.services.GoogleIdentityService
import n.startapp.utils.EnvConfig
import n.startapp.utils.JwtUtil
import n.startapp.utils.PasswordUtil
import java.time.Instant
import java.time.temporal.ChronoUnit

private fun generateVerificationCode(): String = (100000..999999).random().toString()

/**
 * ⚠️ `currentPassword` is absent for an account created through Google — it has no password to
 * name. For those the endpoint *sets* a first password, and `googleIdToken` is what proves the
 * owner is present.
 */
@Serializable
data class ChangePasswordRequest(
    val newPassword: String,
    val currentPassword: String? = null,
    val googleIdToken: String? = null
)

@Serializable
data class ChangeEmailRequest(
    val newEmail: String,
    val password: String? = null,
    val googleIdToken: String? = null
)

@Serializable
data class ChangeLoginRequest(
    val login: String,
    val password: String? = null,
    val googleIdToken: String? = null
)

/**
 * The message a client gets when it sent a password for an account that has none.
 *
 * A code rather than prose: the app has to react to it by launching Google sign-in, and it
 * cannot do that by matching on a sentence that translation would change.
 */
const val GOOGLE_REAUTH_REQUIRED = "google_reauth_required"

/**
 * Proves the account owner is the one asking, whichever way they signed up.
 *
 * ⚠️ An account created through Google lives with an empty `password_hash` (`UserRepository`
 * writes `""`), and bcrypt has nothing to verify against — so every one of these endpoints used
 * to answer "password is incorrect" to the only password that could ever be right, which is
 * none. Deleting the account was among them, and Google Play requires that to work. For such an
 * account the proof is a fresh id token whose `sub` matches the one on file: the address alone
 * would not do, since two accounts can share an address across providers.
 */
private suspend fun requireReauth(
    user: n.startapp.models.auth.User,
    password: String?,
    googleIdToken: String?
) {
    if (user.passwordHash.isBlank()) {
        val token = googleIdToken?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException(GOOGLE_REAUTH_REQUIRED)
        val identity = GoogleIdentityService.verify(token)
        if (user.googleId == null || identity.googleId != user.googleId) {
            throw UnauthorizedException("This Google account does not own the profile")
        }
    } else {
        val given = password?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("Password is required")
        if (!PasswordUtil.verifyPassword(given, user.passwordHash)) {
            throw UnauthorizedException("Password is incorrect")
        }
    }
}

fun Route.authRoutes() {
    val userRepository = UserRepository()
    val emailService = EmailService()

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
            val sameEmail = userRepository.findByEmail(request.email)
            if (sameEmail != null) {
                // Тот же адрес уже занят входом через Google: предлагать «уже есть аккаунт,
                // войдите» бесполезно — пароля у этого аккаунта нет, и вход по паролю
                // отправит человека по кругу.
                if (sameEmail.passwordHash.isBlank() && sameEmail.googleId != null) {
                    throw BadRequestException("This email is already signed up with Google")
                }
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

            // Hash password, generate a verification code and create the (unverified) user
            val passwordHash = PasswordUtil.hashPassword(request.password)
            val code = generateVerificationCode()
            val expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES)
            val user = userRepository.create(request.email, passwordHash, resolvedLogin, code, expiresAt)
                ?: throw Exception("Failed to create user")

            emailService.sendVerificationCode(user.email, code)

            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(
                    RegisterResponse(
                        message = "Verification code sent",
                        email = user.email
                    )
                )
            )
        }

        // Verify email with the 6-digit code
        post("/verify-email") {
            val request = call.receive<VerifyEmailRequest>()
            if (request.email.isBlank() || request.code.isBlank()) {
                throw BadRequestException("Email and code are required")
            }

            val user = userRepository.findByEmail(request.email)
                ?: throw BadRequestException("Invalid email or code")

            if (user.emailVerified) throw BadRequestException("Email already verified")

            val expiresAt = user.verificationCodeExpiresAt
            if (user.verificationCode != request.code || expiresAt == null || Instant.now().isAfter(expiresAt)) {
                throw BadRequestException("Invalid or expired code")
            }

            userRepository.verifyEmail(user.id)
            val verifiedUser = userRepository.findById(user.id)!!
            val token = JwtUtil.generateToken(verifiedUser)

            call.respond(ApiResponse.success(AuthResponse(token = token, user = verifiedUser.toDTO())))
        }

        // Resend a fresh verification code
        post("/resend-verification") {
            val request = call.receive<ResendVerificationRequest>()
            if (request.email.isBlank()) throw BadRequestException("Email is required")

            val user = userRepository.findByEmail(request.email)
                ?: throw BadRequestException("Invalid email")

            if (user.emailVerified) throw BadRequestException("Email already verified")

            val code = generateVerificationCode()
            val expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES)
            userRepository.setVerificationCode(user.id, code, expiresAt)
            emailService.sendVerificationCode(user.email, code)

            call.respond(ApiResponse.success(mapOf("message" to "Verification code resent")))
        }

        // Google OAuth endpoint
        post("/google") {
            val request = call.receive<GoogleAuthRequest>()
            val identity = GoogleIdentityService.verify(request.idToken)
            val user = userRepository.findOrCreateByGoogle(identity.email, identity.googleId)
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

            // Аккаунт, заведённый через Google, живёт с пустым хешем. Bcrypt на нём просто
            // не сойдётся, и человек получил бы «неверный пароль» на пароль, которого у него
            // никогда не было, — с единственным выходом «восстановить» несуществующий.
            if (user.passwordHash.isBlank()) {
                throw UnauthorizedException("This account uses Google Sign-In")
            }

            if (!PasswordUtil.verifyPassword(request.password, user.passwordHash)) {
                throw UnauthorizedException("Invalid email or password")
            }

            if (!user.emailVerified) {
                throw UnauthorizedException("Email not verified")
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
            /**
             * Trades a still-valid token for a fresh one — a sliding session.
             *
             * The token lives 30 days and nothing renewed it, so somebody who used the app every
             * day was still signed out on day 31, mid-session, with no warning and no way to tell
             * it apart from a bug. Clients call this when their token is past roughly two thirds
             * of its life, which keeps an active user signed in indefinitely while an abandoned
             * token still expires on schedule.
             *
             * An expired token cannot be renewed here — it no longer passes the verifier, which
             * is the point: this extends a live session, it does not resurrect a dead one.
             */
            post("/refresh") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                call.respond(
                    ApiResponse.success(
                        AuthResponse(token = JwtUtil.generateToken(user), user = user.toDTO())
                    )
                )
            }

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
                if (request.newPassword.isBlank()) {
                    throw BadRequestException("New password is required")
                }
                if (request.newPassword.length < 6) {
                    throw BadRequestException("New password must be at least 6 characters")
                }

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                // For a Google account this endpoint sets a first password rather than changing
                // one, so what it asks for is a Google token — there is no old password to name.
                requireReauth(user, request.currentPassword, request.googleIdToken)

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
                if (request.newEmail.isBlank()) {
                    throw BadRequestException("Email is required")
                }
                if (!isValidEmail(request.newEmail)) {
                    throw BadRequestException("Invalid email format")
                }

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                requireReauth(user, request.password, request.googleIdToken)

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
                if (request.login.isBlank()) {
                    throw BadRequestException("Login is required")
                }

                val newLogin = request.login.trim()
                if (newLogin.length < 3 || newLogin.length > 30) throw BadRequestException("Login must be 3–30 characters")
                if (!newLogin.matches(Regex("[a-zA-Z0-9_]+"))) throw BadRequestException("Login may only contain letters, digits and underscores")

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                requireReauth(user, request.password, request.googleIdToken)

                if (userRepository.existsByLogin(newLogin) && user.login != newLogin) {
                    throw BadRequestException("This login is already taken")
                }

                userRepository.updateLogin(userId, newLogin)
                val updatedUser = userRepository.findById(userId)!!

                call.respond(ApiResponse.success(mapOf("user" to updatedUser.toDTO())))
            }

            // Request account deletion (delayed)
            post("/request-deletion") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                val request = call.receive<RequestDeletionRequest>()

                val user = userRepository.findById(userId)
                    ?: throw UnauthorizedException("User not found")

                requireReauth(user, request.password, request.googleIdToken)

                val scheduledFor = Instant.now().plus(EnvConfig.accountDeletionGraceDays.toLong(), ChronoUnit.DAYS)
                userRepository.requestDeletion(userId, scheduledFor)
                emailService.sendDeletionScheduledEmail(user.email, scheduledFor)

                val updatedUser = userRepository.findById(userId)!!
                call.respond(ApiResponse.success(mapOf("user" to updatedUser.toDTO())))
            }

            // Cancel a pending account deletion
            post("/cancel-deletion") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: throw UnauthorizedException("Invalid token")

                userRepository.cancelDeletion(userId)
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
