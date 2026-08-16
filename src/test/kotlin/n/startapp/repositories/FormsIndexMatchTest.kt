package n.startapp.repositories

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether an annotated entry owns a surface form.
 *
 * This is rung 3 of the resolver, which sits above every correctness check and writes its
 * answer into a table with no TTL — so a wrong match here does not stay a display bug, it
 * becomes a permanent article filed under the wrong headword.
 */
class FormsIndexMatchTest {

    private fun matches(lemma: String, formsIndex: String, needle: String) =
        LexicalEntryRepository.formsContain(lemma, formsIndex, needle)

    @Test
    fun `an inflected form finds its headword`() {
        assertTrue(matches("run", "run ran running runs", "running"))
        assertTrue(matches("run", "run ran running runs", "ran"))
    }

    @Test
    fun `a phrasal verb cannot claim one of its own words`() {
        // What the screenshot showed: searching "take" answered with the article for "take up".
        val index = "take up took up taking up takes up"
        assertFalse(matches("take up", index, "take"))
        assertFalse(matches("take up", index, "up"))
        assertFalse(matches("take up", index, "took"))
    }

    @Test
    fun `a phrase still finds itself through its own forms`() {
        assertTrue(matches("take up", "take up took up taking up", "took up"))
        assertTrue(matches("take up", "take up took up taking up", "taking up"))
    }

    @Test
    fun `a form has to match whole, not as a prefix or a substring`() {
        assertFalse(matches("run", "run ran running runs", "runn"))
        assertFalse(matches("run", "run ran running runs", "unning"))
    }

    @Test
    fun `a word count that disagrees with the headword is never a match`() {
        assertFalse(matches("run", "run ran running", "run fast"))
        assertFalse(matches("give up on", "give up on gave up on", "give up"))
    }

    @Test
    fun `blank input matches nothing`() {
        assertFalse(matches("run", "run ran", ""))
        assertFalse(matches("run", "", "run"))
    }
}
