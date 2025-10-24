package n.startapp.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import n.startapp.models.auth.User
import java.util.*

/**
 * Utility for JWT token generation and validation
 */
object JwtUtil {
    private val algorithm = Algorithm.HMAC256(EnvConfig.jwtSecret)

    /**
     * Generate JWT token for a user
     */
    fun generateToken(user: User): String {
        val expirationTime = Date(System.currentTimeMillis() + EnvConfig.jwtExpirationHours * 60 * 60 * 1000L)

        return JWT.create()
            .withAudience(EnvConfig.jwtAudience)
            .withIssuer(EnvConfig.jwtIssuer)
            .withClaim("userId", user.id)
            .withClaim("email", user.email)
            .withExpiresAt(expirationTime)
            .sign(algorithm)
    }

    /**
     * Verify and decode JWT token
     */
    fun verifyToken(token: String): DecodedJWT? {
        return try {
            JWT.require(algorithm)
                .withAudience(EnvConfig.jwtAudience)
                .withIssuer(EnvConfig.jwtIssuer)
                .build()
                .verify(token)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract user ID from token
     */
    fun getUserIdFromToken(token: String): Int? {
        return verifyToken(token)?.getClaim("userId")?.asInt()
    }

    /**
     * Extract email from token
     */
    fun getEmailFromToken(token: String): String? {
        return verifyToken(token)?.getClaim("email")?.asString()
    }
}
