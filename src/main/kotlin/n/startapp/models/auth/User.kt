package n.startapp.models.auth

import kotlinx.serialization.Serializable
import java.time.Instant

data class User(
    val id: Int,
    val email: String,
    val login: String?,
    val passwordHash: String,
    val googleId: String? = null,
    val createdAt: Instant
)

@Serializable
data class UserDTO(
    val id: Int,
    val email: String,
    val login: String?,
    val createdAt: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val login: String? = null
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDTO
)

@Serializable
data class GoogleAuthRequest(
    val idToken: String
)

fun User.toDTO(): UserDTO = UserDTO(
    id = id,
    email = email,
    login = login,
    createdAt = createdAt.toString()
)
