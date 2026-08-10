package n.startapp.services.query

import org.junit.Test
import kotlin.test.assertTrue

class MorphologyHeuristicsTest {

    private fun assertSuggests(word: String, lemma: String) {
        val candidates = MorphologyHeuristics.candidates(word)
        assertTrue(lemma in candidates, "expected '$lemma' among candidates for '$word', got $candidates")
    }

    @Test fun `doubled consonant is undone`() {
        assertSuggests("running", "run")
        assertSuggests("stopped", "stop")
        assertSuggests("bigger", "big")
        assertSuggests("biggest", "big")
    }

    @Test fun `silent e is restored`() {
        assertSuggests("making", "make")
        assertSuggests("liked", "like")
    }

    @Test fun `plain suffixes are stripped`() {
        assertSuggests("walking", "walk")
        assertSuggests("walked", "walk")
        assertSuggests("boxes", "box")
        assertSuggests("names", "name")
    }

    @Test fun `y-forms are restored`() {
        assertSuggests("flies", "fly")
        assertSuggests("happier", "happy")
        assertSuggests("happiest", "happy")
        assertSuggests("tried", "try")
    }

    @Test fun `adverbs are stripped`() {
        assertSuggests("quickly", "quick")
    }

    @Test fun `words too short to inflect produce nothing`() {
        assertTrue(MorphologyHeuristics.candidates("is").isEmpty())
        assertTrue(MorphologyHeuristics.candidates("a").isEmpty())
    }

    @Test fun `double-s and -us endings are not treated as plurals`() {
        assertTrue("gles" !in MorphologyHeuristics.candidates("glass"))
        assertTrue("statu" !in MorphologyHeuristics.candidates("status"))
    }

    @Test fun `the input itself is never proposed as its own lemma`() {
        listOf("running", "boxes", "flies", "quickly").forEach {
            assertTrue(it !in MorphologyHeuristics.candidates(it))
        }
    }
}
