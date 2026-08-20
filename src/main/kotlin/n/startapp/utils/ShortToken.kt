package n.startapp.utils

import java.security.SecureRandom

/**
 * Random strings that a person can read off one screen and type into another.
 *
 * One alphabet for every such token in the app, because the reason for its shape is the same
 * everywhere: `l`/`1` and `o`/`0` are the pairs people get wrong, and a code that cannot be
 * dictated over a classroom is not a code.
 */
object ShortToken {
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    fun generate(length: Int): String =
        (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** True when every character could have come out of [generate]. */
    fun isWellFormed(value: String): Boolean =
        value.isNotEmpty() && value.all { it in ALPHABET }
}
