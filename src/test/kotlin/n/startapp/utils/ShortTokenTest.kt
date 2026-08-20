package n.startapp.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The join code is read off one screen and typed into another, often out loud across a room.
 * That is the whole reason for the alphabet, so it is worth a test that notices if someone
 * "simplifies" it back to A-Z0-9.
 */
class ShortTokenTest {

    @Test
    fun `the characters people confuse are not in the alphabet`() {
        val sample = (1..200).joinToString("") { ShortToken.generate(8) }
        for (confusable in listOf('l', 'o', '0', '1')) {
            assertFalse(confusable in sample, "'$confusable' must not appear in a code")
        }
    }

    @Test
    fun `codes are the length that was asked for`() {
        assertEquals(8, ShortToken.generate(8).length)
        assertEquals(12, ShortToken.generate(12).length)
    }

    @Test
    fun `a well-formed code is one this alphabet could have produced`() {
        assertTrue(ShortToken.isWellFormed(ShortToken.generate(8)))
        assertTrue(ShortToken.isWellFormed("abc23xyz"))
    }

    @Test
    fun `anything else is rejected before it reaches the database`() {
        // A lookup with a wildcard, an empty string or an uppercase paste is not a near miss —
        // it is not a code at all, and it should cost nothing to say so.
        assertFalse(ShortToken.isWellFormed(""))
        assertFalse(ShortToken.isWellFormed("abc%"))
        assertFalse(ShortToken.isWellFormed("ABC23XYZ"))
        assertFalse(ShortToken.isWellFormed("cool-code"))
    }

    @Test
    fun `two codes in a row are not the same`() {
        val codes = (1..100).map { ShortToken.generate(8) }.toSet()
        assertEquals(100, codes.size)
    }
}
