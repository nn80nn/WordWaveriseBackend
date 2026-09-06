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
    val deletionScheduledFor: String? = null,
    /**
     * Whether this account has a password at all.
     *
     * An account created through Google has none, and every screen that re-asks "is this really
     * you" has to know which credential to ask for. Without this the delete-account dialog can
     * only offer a password field, and a Google user stands in front of a form they can never
     * fill in. Defaulted true so a client built before this field is unaffected.
     */
    val hasPassword: Boolean = true
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

/**
 * Confirmation for an operation that needs the account owner to prove they are present.
 *
 * Exactly one of the two arrives: the password for an account that has one, a fresh Google id
 * token for an account created through Google. See `requireReauth` in AuthRoutes.
 */
@Serializable
data class RequestDeletionRequest(
    val password: String? = null,
    val googleIdToken: String? = null
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
    deletionScheduledFor = deletionScheduledFor?.toString(),
    hasPassword = passwordHash.isNotBlank()
)
