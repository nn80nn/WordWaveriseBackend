package n.startapp.services.context

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenizerTest {

    @Test
    fun `offsets round-trip back to the original text`() {
        val text = "She was leading the team through a difficult year."
        val result = Tokenizer.tokenize(text)

        assertTrue(result.tokens.isNotEmpty())
        result.tokens.forEach { token ->
            assertEquals(token.text, text.substring(token.start, token.end),
                "offsets for token ${token.index} must select exactly its text")
        }
    }

    @Test
    fun `indices are contiguous from zero`() {
        val result = Tokenizer.tokenize("Old pipes were made of lead.")
        assertEquals(result.tokens.indices.toList(), result.tokens.map { it.index })
    }

    @Test
    fun `trailing punctuation is stripped from the token text`() {
        val result = Tokenizer.tokenize("Old pipes were made of lead.")
        assertEquals("lead", result.tokens.last().text)
    }

    @Test
    fun `internal apostrophes and hyphens stay inside the word`() {
        val result = Tokenizer.tokenize("It's a well-known problem, don't you think?")
        val words = result.tokens.map { it.text }
        assertTrue("It's" in words)
        assertTrue("well-known" in words)
        assertTrue("don't" in words)
    }

    @Test
    fun `quotes around a word are stripped`() {
        val result = Tokenizer.tokenize("""He said "hello" quietly.""")
        assertTrue("hello" in result.tokens.map { it.text })
    }

    @Test
    fun `numerals are not tappable`() {
        val result = Tokenizer.tokenize("He was 42 years old")
        val numeric = result.tokens.first { it.text == "42" }
        assertFalse(numeric.tappable)
        assertTrue(result.tokens.first { it.text == "years" }.tappable)
    }

    @Test
    fun `standalone punctuation produces no token`() {
        val result = Tokenizer.tokenize("yes — no")
        assertEquals(listOf("yes", "no"), result.tokens.map { it.text })
    }

    @Test
    fun `a phrasal verb is grouped so both halves resolve together`() {
        val result = Tokenizer.tokenize("He gave up smoking last year.")
        val gave = result.tokens.first { it.text == "gave" }
        val up = result.tokens.first { it.text == "up" }

        assertEquals(listOf(up.index), gave.groupWith)
        assertEquals(listOf(gave.index), up.groupWith)
    }

    @Test
    fun `an ordinary word before a non-particle is not grouped`() {
        val result = Tokenizer.tokenize("He gave money to charity")
        assertTrue(result.tokens.first { it.text == "gave" }.groupWith.isEmpty())
    }

    @Test
    fun `empty and single token inputs are handled`() {
        assertTrue(Tokenizer.tokenize("").tokens.isEmpty())
        assertTrue(Tokenizer.tokenize("   ").tokens.isEmpty())
        assertEquals(listOf("lead"), Tokenizer.tokenize("lead").tokens.map { it.text })
    }

    @Test
    fun `repeated words get distinct offsets`() {
        val text = "the cat sat on the mat"
        val result = Tokenizer.tokenize(text)
        val thes = result.tokens.filter { it.text == "the" }

        assertEquals(2, thes.size)
        assertTrue(thes[0].start < thes[1].start, "the second occurrence must not reuse the first offset")
        thes.forEach { assertEquals("the", text.substring(it.start, it.end)) }
    }
}
