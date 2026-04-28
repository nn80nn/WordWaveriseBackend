package n.startapp.models.auth

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * User data model
 */
data class User(
    val id: Int,
    val email: String,
    val passwordHash: String,
    val googleId: String? = null,
    val createdAt: Instant
)

/**
 * User DTO for client responses (without password hash)
 */
@Serializable
data class UserDTO(
    val id: Int,
    val email: String,
    val createdAt: String
)

/**
 * Registration request
 */
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String
)

/**
 * Login request
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

/**
 * Auth response with token
 */
@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDTO
)

/**
 * Google OAuth request — id_token from Google Identity Services
 */
@Serializable
data class GoogleAuthRequest(
    val idToken: String
)

/**
 * Convert User to UserDTO
 */
fun User.toDTO(): UserDTO = UserDTO(
    id = id,
    email = email,
    createdAt = createdAt.toString()
)
