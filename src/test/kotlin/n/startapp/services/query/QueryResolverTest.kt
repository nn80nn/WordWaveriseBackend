package n.startapp.services.query

import n.startapp.models.query.QueryKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryResolverTest {

    private val resolver = QueryResolver()

    @Test
    fun `single english token is a word`() {
        val r = resolver.resolve("lead")
        assertEquals(QueryKind.WORD, r.kind)
        assertEquals("lead", r.lemma)
        assertEquals("en", r.language)
    }

    @Test
    fun `normalizes case, whitespace, quotes and trailing punctuation`() {
        assertEquals("running", resolver.resolve("  \"Running.\"  ").normalized)
        assertEquals("kick the bucket", resolver.resolve("kick   the    bucket").normalized)
    }

    @Test
    fun `short multiword input is a phrase`() {
        assertEquals(QueryKind.PHRASE, resolver.resolve("kick the bucket").kind)
        assertEquals(QueryKind.PHRASE, resolver.resolve("once in a blue moon").kind)
    }

    @Test
    fun `long input is a sentence and carries no headword`() {
        val r = resolver.resolve("She was leading the team through a difficult year")
        assertEquals(QueryKind.SENTENCE, r.kind)
        assertNull(r.lemma, "a sentence has no single headword to look up")
    }

    @Test
    fun `four tokens ending in a full stop is a sentence, not a phrase`() {
        assertEquals(QueryKind.SENTENCE, resolver.resolve("The weather is nice.").kind)
    }

    @Test
    fun `a comma marks prose even when short`() {
        assertEquals(QueryKind.SENTENCE, resolver.resolve("well, maybe not").kind)
    }

    @Test
    fun `cyrillic input is routed to the russian pipeline`() {
        assertEquals(QueryKind.RU_WORD, resolver.resolve("замок").kind)
        assertEquals("ru", resolver.resolve("замок").language)
        assertEquals(QueryKind.RU_PHRASE, resolver.resolve("бить баклуши").kind)
        assertEquals(QueryKind.RU_SENTENCE, resolver.resolve("она вела команду через трудный год").kind)
    }

    @Test
    fun `blank input resolves to unknown rather than throwing`() {
        val r = resolver.resolve("   ")
        assertEquals(QueryKind.UNKNOWN, r.kind)
        assertNull(r.lemma)
    }
}
