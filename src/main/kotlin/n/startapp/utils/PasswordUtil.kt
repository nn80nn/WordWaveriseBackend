package n.startapp.utils

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Utility for password hashing and verification using BCrypt
 */
object PasswordUtil {
    private const val BCRYPT_COST = 12

    /**
     * Hash a password using BCrypt
     */
    fun hashPassword(password: String): String {
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray())
    }

    /**
     * Verify a password against a hash
     */
    fun verifyPassword(password: String, hash: String): Boolean {
        val result = BCrypt.verifyer().verify(password.toCharArray(), hash)
        return result.verified
    }
}
