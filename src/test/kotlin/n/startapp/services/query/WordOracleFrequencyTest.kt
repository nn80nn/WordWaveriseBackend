package n.startapp.services.query

import kotlinx.coroutines.runBlocking
import n.startapp.models.query.QueryKind
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.LlmResult
import n.startapp.services.ai.LlmUsage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the behaviour that broke the misspelling path in production.
 *
 * DataMuse's `sp=` is a pattern matcher over its vocabulary, and common misspellings are in that
 * vocabulary — "recieve" is returned for `sp=recieve`. A self-match therefore proves nothing;
 * only frequency separates a word from corpus noise.
 */
class WordOracleFrequencyTest {

    /** Frequencies here are the real values DataMuse returns for these strings. */
    private class FrequencyOracle(private val frequencies: Map<String, Double>) : WordOracle {
        private val minFrequency = 1.0
        override suspend fun exists(word: String): Boolean {
            val frequency = frequencies[word.lowercase()] ?: return false
            return frequency >= minFrequency
        }
        override suspend fun spellingSuggestions(word: String, max: Int): List<String> =
            frequencies.entries
                .filter { it.key != word.lowercase() && it.value >= minFrequency }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(max)
    }

    private class NeverCalledLlm : LlmClient {
        var calls = 0
        override suspend fun complete(request: LlmRequest): LlmResult {
            calls++
            return LlmResult("{}", LlmUsage(), "fake", 1)
        }
        override fun close() = Unit
    }

    @Test
    fun `a misspelling present in the corpus is still corrected`() = runBlocking {
        val llm = NeverCalledLlm()
        val resolver = QueryResolver(
            oracle = FrequencyOracle(mapOf("recieve" to 0.033083, "receive" to 47.926155)),
            llm = llm
        )

        val out = resolver.resolve("recieve")

        assertEquals(QueryKind.MISSPELLING, out.kind)
        assertEquals("receive", out.lemma)
        assertTrue(out.correctionApplied)
        assertEquals(0, llm.calls, "the speller must settle this without a model call")
    }

    /**
     * The transposition case, which plain Levenshtein gets confidently wrong: "recieve" is two
     * substitutions from "receive" but only one from "relieve".
     */
    @Test
    fun `a swapped letter pair outranks a same-distance substitution`() = runBlocking {
        val resolver = QueryResolver(
            oracle = FrequencyOracle(
                mapOf("recieve" to 0.033083, "relieve" to 5.0, "receive" to 47.926155)
            )
        )

        assertEquals("receive", resolver.resolve("recieve").lemma)
    }

    @Test
    fun `the closest candidate wins over a merely more common one`() = runBlocking {
        // "helo" is one insertion from "hello" and two edits from "helps"; frequency must not
        // drag the answer to the further word.
        val resolver = QueryResolver(
            oracle = FrequencyOracle(mapOf("helo" to 0.01, "hello" to 30.0, "helps" to 90.0))
        )

        assertEquals("hello", resolver.resolve("helo").lemma)
    }

    @Test
    fun `a genuinely rare word is not treated as a typo`() = runBlocking {
        val resolver = QueryResolver(
            oracle = FrequencyOracle(mapOf("serendipity" to 1.4, "serenity" to 8.0))
        )

        val out = resolver.resolve("serendipity")

        assertEquals(QueryKind.WORD, out.kind)
        assertEquals("serendipity", out.lemma)
    }

    @Test
    fun `one misspelling is never offered in place of another`() = runBlocking {
        val resolver = QueryResolver(
            oracle = FrequencyOracle(
                mapOf("neccessary" to 0.036314, "necessery" to 0.01, "necessary" to 155.824246)
            )
        )

        val out = resolver.resolve("neccessary")

        assertEquals("necessary", out.lemma)
        assertTrue(out.alternatives.none { it.form == "necessery" })
    }
}
