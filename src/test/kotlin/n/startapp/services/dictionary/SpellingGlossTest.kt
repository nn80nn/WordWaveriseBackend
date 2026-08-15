package n.startapp.services.dictionary

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every string here is a real gloss the aggregator returned from production. */
class SpellingGlossTest {

    @Test
    fun `an entry that is nothing but a misspelling gloss names its target`() {
        assertEquals("occurred", SpellingGloss.redirectTarget(listOf("Misspelling of occurred .")))
    }

    @Test
    fun `a language code is not a sense, so it cannot rescue a typo`() {
        // "teh" is why the presence of an entry could not settle this on its own: Wiktionary
        // files the Tehuelche language code under the same headword.
        val target = SpellingGloss.redirectTarget(
            listOf(
                "ISO 639-3 language code for Tehuelche.",
                "Deliberate misspelling of the, for humorous, sarcastic, or facetious effect ."
            )
        )
        assertEquals("the", target)
    }

    @Test
    fun `the majority target wins over the first one listed`() {
        assertEquals(
            "a lot",
            SpellingGloss.redirectTarget(
                listOf(
                    "Misspelling of allot .",
                    "Alternative form of a lot (compare to awhile).",
                    "Alternative form of a lot."
                )
            )
        )
    }

    @Test
    fun `a real word that people also mistype keeps its own article`() {
        // "wich" is a Cheshire salt town as well as a way of mistyping "which".
        assertNull(
            SpellingGloss.redirectTarget(
                listOf(
                    "Alternative spelling of wych (“brine spring or well”).",
                    "A wich town, particularly one of several former salt mining towns in Cheshire.",
                    "Misspelling of which ."
                )
            )
        )
    }

    @Test
    fun `a rare word with real senses is never a pointer`() {
        assertNull(
            SpellingGloss.redirectTarget(
                listOf(
                    "an official, formal, or long letter",
                    "a letter, especially a long or an official one",
                    "A written message; a letter, note or memo."
                )
            )
        )
    }

    @Test
    fun `a spelling that is merely dead is followed too`() {
        // "untill" is the whole reason the marker cannot be the word "misspelling": Wiktionary
        // calls it obsolete, not wrong, and it has no other sense.
        assertEquals("until", SpellingGloss.redirectTarget(listOf("Obsolete spelling of until .")))
    }

    @Test
    fun `an alternative spelling alone is a word, not a mistake`() {
        // "colour" points at "color" without anyone having got anything wrong.
        assertNull(SpellingGloss.redirectTarget(listOf("Alternative spelling of color.")))
    }

    @Test
    fun `no definitions at all is not a redirect`() {
        assertNull(SpellingGloss.redirectTarget(emptyList()))
        assertNull(SpellingGloss.redirectTarget(listOf("   ")))
    }
}
