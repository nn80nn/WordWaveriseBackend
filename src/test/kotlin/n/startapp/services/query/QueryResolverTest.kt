package n.startapp.services.query

import kotlinx.coroutines.runBlocking
import n.startapp.models.query.QueryKind
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.LlmResult
import n.startapp.services.ai.LlmUsage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-written fakes: only two collaborators need faking, so a mocking library earns nothing. */
private class FakeOracle(
    private val known: Set<String> = emptySet(),
    private val suggestions: Map<String, List<String>> = emptyMap(),
    /** Occurrences per million. Absent means the corpus has never seen the string at all. */
    private val frequencies: Map<String, Double> = emptyMap()
) : WordOracle {
    var existsCalls = 0
    override suspend fun exists(word: String): Boolean {
        existsCalls++
        return word.lowercase() in known
    }
    override suspend fun spellingSuggestions(word: String, max: Int): List<String> =
        suggestions[word.lowercase()].orEmpty().take(max)
    override suspend fun frequency(word: String): Double? = frequencies[word.lowercase()]
}

private class CountingLlm : LlmClient {
    var calls = 0
    override suspend fun complete(request: LlmRequest): LlmResult {
        calls++
        return LlmResult("""{"kind":"WORD","lemma":"${'$'}fallback","alternatives":[],"confidence":0.5}""",
            LlmUsage(), "fake", 1)
    }
    override fun close() = Unit
}

class QueryResolverTest {

    private fun resolver(
        known: Set<String> = emptySet(),
        suggestions: Map<String, List<String>> = emptyMap(),
        corpus: Map<String, String> = emptyMap(),
        frequencies: Map<String, Double> = emptyMap(),
        llm: LlmClient? = null
    ) = QueryResolver(
        oracle = FakeOracle(known, suggestions, frequencies),
        knownForm = { form -> corpus[form.lowercase()] },
        llm = llm,
        llmCache = null
    )

    // ── «Искать точно»: the user overriding the resolver ────────────────────

    @Test
    fun `exact search keeps the query even when the corpus files it under something else`() {
        // The bug this exists for: "take" came back as the phrasal verb "take up", and pressing
        // «Искать точно» re-ran the same ladder and produced the same answer.
        val r = resolver(corpus = mapOf("take" to "take up")).resolveExact("take")

        assertEquals("take", r.lemma)
        assertEquals(QueryKind.WORD, r.kind)
        assertEquals("exact", r.resolvedBy)
    }

    @Test
    fun `exact search never corrects spelling and never offers a fallback`() {
        val r = resolver(
            known = setOf("receive"),
            suggestions = mapOf("recieve" to listOf("receive")),
            frequencies = mapOf("receive" to 40.0, "recieve" to 0.3)
        ).resolveExact("recieve")

        assertEquals("recieve", r.lemma)
        assertFalse(r.correctionApplied)
        assertNull(r.fallback)
        assertNull(r.correctedFrom)
    }

    @Test
    fun `exact search still normalises case, quotes and whitespace`() {
        // Exact means "do not substitute another word", not "do not tidy the input": case and
        // spacing are not characters the dictionary is filed under.
        assertEquals("take up", resolver().resolveExact("  Take   Up  ").lemma)
        assertEquals("take up", resolver().resolveExact("\"take up\"").lemma)
        assertEquals(QueryKind.PHRASE, resolver().resolveExact("Take Up").kind)
    }

    @Test
    fun `quotes and sentence punctuation come off in either nesting order`() {
        val r = resolver()

        assertEquals("take up", r.normalize("\"take up\"."))
        assertEquals("take up", r.normalize("\"take up.\""))
        assertEquals("running", r.normalize("«Running!»"))
        assertEquals("running", r.normalize("Running."))
    }

    @Test
    fun `exact search consults nothing that could change its mind`() {
        val oracle = FakeOracle(known = setOf("take"))
        val llm = CountingLlm()
        val resolver = QueryResolver(oracle = oracle, knownForm = { "take up" }, llm = llm, llmCache = null)

        resolver.resolveExact("take")

        assertEquals(0, oracle.existsCalls)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `exact search sends a long string to the dictionary rather than the context pipeline`() {
        // Routing it to SENTENCE would be one more substitution, which is the thing being opted out of.
        val r = resolver().resolveExact("a bird in the hand is worth two")

        assertEquals(QueryKind.PHRASE, r.kind)
        assertEquals("a bird in the hand is worth two", r.lemma)
    }

    // ── Rungs that must never reach the model ───────────────────────────────

    @Test
    fun `cyrillic input goes to the russian pipeline without an llm call`() = runBlocking {
        val llm = CountingLlm()
        val r = resolver(llm = llm)
        assertEquals(QueryKind.RU_WORD, r.resolve("замок").kind)
        assertEquals(QueryKind.RU_PHRASE, r.resolve("бить баклуши").kind)
        assertEquals(QueryKind.RU_SENTENCE, r.resolve("она вела команду через трудный год").kind)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `prose is a sentence and carries no headword, without an llm call`() = runBlocking {
        val llm = CountingLlm()
        val r = resolver(llm = llm)
        val out = r.resolve("She was leading the team through a difficult year")
        assertEquals(QueryKind.SENTENCE, out.kind)
        assertNull(out.lemma)
        assertEquals(QueryKind.SENTENCE, r.resolve("The weather is nice.").kind)
        assertEquals(QueryKind.SENTENCE, r.resolve("well, maybe not").kind)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `short multiword input is a phrase, without an llm call`() = runBlocking {
        val llm = CountingLlm()
        val r = resolver(llm = llm)
        assertEquals(QueryKind.PHRASE, r.resolve("kick the bucket").kind)
        assertEquals(QueryKind.PHRASE, r.resolve("once in a blue moon").kind)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `a known word resolves against the oracle, without an llm call`() = runBlocking {
        val llm = CountingLlm()
        val out = resolver(known = setOf("lead"), llm = llm).resolve("lead")
        assertEquals(QueryKind.WORD, out.kind)
        assertEquals("lead", out.lemma)
        assertEquals("datamuse", out.resolvedBy)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `an already annotated form resolves from the corpus with no network at all`() = runBlocking {
        val llm = CountingLlm()
        val oracle = FakeOracle()
        val r = QueryResolver(oracle, knownForm = { if (it == "running") "run" else null }, llm = llm)
        val out = r.resolve("running")
        assertEquals(QueryKind.INFLECTION, out.kind)
        assertEquals("run", out.lemma)
        assertEquals("cache", out.resolvedBy)
        assertEquals(0, llm.calls)
        assertEquals(0, oracle.existsCalls, "the corpus must be consulted before the oracle")
    }

    @Test
    fun `a regular inflection is lemmatised when its stem is a real word`() = runBlocking {
        val llm = CountingLlm()
        val out = resolver(known = setOf("run"), llm = llm).resolve("running")
        assertEquals(QueryKind.INFLECTION, out.kind)
        assertEquals("run", out.lemma)
        assertEquals("morphology", out.resolvedBy)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `a swapped letter pair is corrected locally, without an llm call`() = runBlocking {
        val llm = CountingLlm()
        // The provider is deliberately unhelpful here: its own suggestion for "recieve" is
        // "relieve", which is what shipped before transpositions were generated locally.
        val out = resolver(
            known = setOf("receive", "relieve"),
            suggestions = mapOf("recieve" to listOf("relieve")),
            llm = llm
        ).resolve("recieve")

        assertEquals(QueryKind.MISSPELLING, out.kind)
        assertEquals("receive", out.lemma)
        assertTrue(out.correctionApplied)
        assertEquals("recieve", out.correctedFrom)
        assertEquals("transposition", out.resolvedBy)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `a non-transposition typo falls through to the provider's suggestions`() = runBlocking {
        val llm = CountingLlm()
        val out = resolver(
            known = setOf("necessary"),
            suggestions = mapOf("neccessary" to listOf("necessary", "necessarily")),
            llm = llm
        ).resolve("neccessary")

        assertEquals(QueryKind.MISSPELLING, out.kind)
        assertEquals("necessary", out.lemma)
        assertEquals("datamuse", out.resolvedBy)
        assertEquals(listOf("necessarily"), out.alternatives.map { it.form })
        assertEquals(0, llm.calls)
    }

    @Test
    fun `a transposition that is not a real word is not proposed`() = runBlocking {
        // "helol" swaps to "hello", but nothing here says "hello" exists, so no correction.
        val out = resolver(known = emptySet()).resolve("helol")
        assertFalse(out.correctionApplied)
    }

    @Test
    fun `a real word is never corrected into a different real word`() = runBlocking {
        val out = resolver(
            known = setOf("bare", "bear"),
            suggestions = mapOf("bare" to listOf("bear", "barn"))
        ).resolve("bare")

        assertEquals(QueryKind.WORD, out.kind)
        assertEquals("bare", out.lemma)
        assertFalse(out.correctionApplied)
    }

    @Test
    fun `a word is never corrected into its own inflected form`() = runBlocking {
        // Reported from the field: "intertwine" came back as "intertwined", so the article was
        // built for the past participle and read as a verb wearing "-ed". The provider really
        // does offer the inflection, and it really is one edit away — distance cannot catch this.
        val out = resolver(
            known = setOf("intertwine", "intertwined", "intestine"),
            suggestions = mapOf("intertwine" to listOf("intertwined", "intestine")),
            frequencies = mapOf("intertwine" to 0.168, "intertwined" to 1.963)
        ).resolve("intertwine")

        assertFalse(out.correctionApplied, "adding -d is inflection, not a misspelling")
        assertEquals("intertwine", out.lemma)
    }

    @Test
    fun `a rare word is not corrected into a merely more common neighbour`() = runBlocking {
        // Real corrections are buried by their target; a rare word beside a common one is not.
        val out = resolver(
            known = setOf("meander", "meaner"),
            suggestions = mapOf("meander" to listOf("meaner")),
            frequencies = mapOf("meander" to 0.444, "meaner" to 1.2)
        ).resolve("meander")

        assertFalse(out.correctionApplied)
        assertEquals("meander", out.lemma)
    }

    @Test
    fun `a genuine typo is still corrected once frequencies are known`() = runBlocking {
        val out = resolver(
            known = setOf("necessary"),
            suggestions = mapOf("neccessary" to listOf("necessary")),
            frequencies = mapOf("neccessary" to 0.036, "necessary" to 155.8)
        ).resolve("neccessary")

        assertEquals(QueryKind.MISSPELLING, out.kind)
        assertEquals("necessary", out.lemma)
        assertTrue(out.correctionApplied)
    }

    @Test
    fun `a real word outranks its commoner neighbour, which becomes a fallback`() = runBlocking {
        // Reported from the field: searching "missive" returned the article for "massive".
        // The margin guard could not stop it — 18.2 is 66x 0.27, past the 50x bar — because no
        // ratio separates a rare word from a typo. So a word that exists is simply never
        // overridden; the neighbour rides along and only [LookupService] may use it, and only
        // once every dictionary has confirmed it has no entry for what was typed.
        val out = resolver(
            known = setOf("missive", "massive", "missile"),
            suggestions = mapOf("missive" to listOf("massive", "missile")),
            frequencies = mapOf("missive" to 0.274, "massive" to 18.235, "missile" to 5.705)
        ).resolve("missive")

        assertEquals(QueryKind.WORD, out.kind)
        assertEquals("missive", out.lemma)
        assertFalse(out.correctionApplied)
        assertEquals("massive", out.fallback?.form, "still offered, no longer imposed")
        assertEquals(QueryKind.MISSPELLING, out.fallback?.kind)
    }

    @Test
    fun `a rare word is not lemmatised into a shorter word it merely ends like`() = runBlocking {
        // "waver" is not an inflection of "wave", but the -er rule cannot tell, and the word is
        // too rare to have stopped at the common-word rung. Existing as typed is what saves it.
        val out = resolver(
            known = setOf("waver", "wave"),
            frequencies = mapOf("waver" to 0.51, "wave" to 42.0)
        ).resolve("waver")

        assertEquals(QueryKind.WORD, out.kind)
        assertEquals("waver", out.lemma)
        assertEquals("wave", out.fallback?.form)
        assertEquals(QueryKind.INFLECTION, out.fallback?.kind, "so the notice reads as a form, not a typo")
    }

    @Test
    fun `a typo the corpus has seen is still handed a correction to fall back on`() = runBlocking {
        // "teh" is in the frequency corpus (0.40/M) and commoner there than plenty of real
        // words, so it now resolves to itself. What makes it a typo is that no dictionary has
        // an entry — a question only the lookup can answer, so all the resolver owes is a
        // usable second guess.
        val out = resolver(
            known = setOf("teh", "the"),
            suggestions = mapOf("teh" to listOf("the")),
            frequencies = mapOf("teh" to 0.405, "the" to 56271.0)
        ).resolve("teh")

        assertEquals("teh", out.lemma)
        assertEquals("the", out.fallback?.form)
    }

    @Test
    fun `a word with no plausible neighbour carries no fallback at all`() = runBlocking {
        val out = resolver(
            known = setOf("serendipity"),
            frequencies = mapOf("serendipity" to 0.29)
        ).resolve("serendipity")

        assertEquals(QueryKind.WORD, out.kind)
        assertNull(out.fallback, "nothing to second-guess, so no notice")
    }

    @Test
    fun `a correction further than two edits away is not applied`() = runBlocking {
        val llm = CountingLlm()
        val out = resolver(suggestions = mapOf("qwrtplk" to listOf("waterlike")), llm = llm).resolve("qwrtplk")
        assertFalse(out.correctionApplied)
        assertEquals(1, llm.calls, "an unrecognised token is exactly what the model is for")
    }

    @Test
    fun `latin typed in a russian layout is recovered`() = runBlocking {
        // "ckjdj" is "слово" typed while the layout was still English.
        val out = resolver().resolve("ckjdj")
        assertEquals("ru", out.language)
        assertEquals("слово", out.lemma)
        assertTrue(out.correctionApplied)
        assertEquals("layout", out.resolvedBy)
    }

    @Test
    fun `cyrillic typed in an english layout is recovered`() = runBlocking {
        // "цщквы" is "words" typed while the layout was still Russian.
        val out = resolver(known = setOf("words")).resolve("цщквы")
        assertEquals("en", out.language)
        assertEquals("words", out.lemma)
        assertTrue(out.correctionApplied)
    }

    // ── Normalisation ───────────────────────────────────────────────────────

    @Test
    fun `normalizes case, whitespace, quotes and trailing punctuation`() = runBlocking {
        val r = resolver()
        assertEquals("running", r.resolve("  \"Running.\"  ").normalized)
        assertEquals("kick the bucket", r.resolve("kick   the    bucket").normalized)
    }

    @Test
    fun `blank input resolves to unknown rather than throwing`() = runBlocking {
        val out = resolver().resolve("   ")
        assertEquals(QueryKind.UNKNOWN, out.kind)
        assertNull(out.lemma)
    }

    @Test
    fun `without an llm an unrecognised token still resolves to itself`() = runBlocking {
        val out = resolver().resolve("zzxqv")
        assertEquals(QueryKind.WORD, out.kind)
        assertEquals("zzxqv", out.lemma)
    }
}
