package n.startapp.models.auth

import kotlinx.serialization.Serializable
import java.time.Instant

data class User(
    val id: Int,
    val email: String,
    val login: String?,
    val passwordHash: String,
    val googleId: String? = null,
    val createdAt: Instant,
    val emailVerified: Boolean = true,
    val verificationCode: String? = null,
    val verificationCodeExpiresAt: Instant? = null,
    val deletionRequestedAt: Instant? = null,
    val deletionScheduledFor: Instant? = null
)

@Serializable
data class UserDTO(
    val id: Int,
    val email: String,
    val login: String?,
    val createdAt: String,
    val emailVerified: Boolean,
    val deletionScheduledFor: String? = null
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
data class RegisterResponse(
    val message: String,
    val email: String,
    val requiresVerification: Boolean = true
)

@Serializable
data class VerifyEmailRequest(
    val email: String,
    val code: String
)

@Serializable
data class ResendVerificationRequest(
    val email: String
)

@Serializable
data class RequestDeletionRequest(
    val password: String
)

@Serializable
data class GoogleAuthRequest(
    val idToken: String
)

@Serializable
data class UserStats(
    val wordsSaved: Long,
    val cardsReviewed: Long,
    val wordsMastered: Long = 0L,
    val successRate: Long = 0L,
    val currentStreak: Long = 0L,
    val longestStreak: Long = 0L,
    val aiExamplesGenerated: Long = 0L
)

fun User.toDTO(): UserDTO = UserDTO(
    id = id,
    email = email,
    login = login,
    createdAt = createdAt.toString(),
    emailVerified = emailVerified,
    deletionScheduledFor = deletionScheduledFor?.toString()
)
