package n.startapp.services.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The splitter decides what a tap sends to the model, so its mistakes are not cosmetic: a bad
 * cut asks about half a clause and gets a confident answer about a sentence nobody wrote.
 */
class SentenceSplitterTest {

    private fun texts(input: String) = SentenceSplitter.split(input).map { it.text }

    @Test
    fun `offsets select exactly the sentence they describe`() {
        val text = "He opened the door. The room was empty. Nothing had moved."
        for (sentence in SentenceSplitter.split(text)) {
            assertEquals(
                sentence.text,
                text.substring(sentence.start, sentence.end),
                "offsets for sentence ${sentence.index} must select its own text"
            )
        }
    }

    @Test
    fun `indices are contiguous from zero`() {
        val result = SentenceSplitter.split("One. Two. Three.")
        assertEquals(result.indices.toList(), result.map { it.index })
    }

    @Test
    fun `a title before a name does not end the sentence`() {
        assertEquals(
            listOf("Mr. Holmes went out with Dr. Watson before dawn."),
            texts("Mr. Holmes went out with Dr. Watson before dawn.")
        )
    }

    @Test
    fun `initials stay with the name they belong to`() {
        assertEquals(
            listOf("The book was written by J. R. R. Tolkien in Oxford."),
            texts("The book was written by J. R. R. Tolkien in Oxford.")
        )
    }

    @Test
    fun `a decimal point is not a full stop`() {
        assertEquals(
            listOf("It cost 3.50 and weighed 1.2 kilograms."),
            texts("It cost 3.50 and weighed 1.2 kilograms.")
        )
    }

    @Test
    fun `dialogue attribution stays with its line`() {
        // A lowercase word after a terminator is almost always "he said", and cutting there
        // would hand the model a fragment with no subject.
        assertEquals(
            listOf("\"Stop!\" he said, and the horse stopped."),
            texts("\"Stop!\" he said, and the horse stopped.")
        )
    }

    @Test
    fun `a closing quote belongs to the sentence it closes`() {
        val result = texts("\"Go home.\" She turned away.")
        assertEquals(listOf("\"Go home.\"", "She turned away."), result)
    }

    @Test
    fun `an ellipsis is one terminator, not three`() {
        assertEquals(listOf("He waited...", "Then he left."), texts("He waited... Then he left."))
    }

    @Test
    fun `abbreviations inside a sentence do not split it`() {
        assertEquals(
            listOf("Bring paper, pens, etc. before the class begins."),
            texts("Bring paper, pens, etc. before the class begins.")
        )
    }

    @Test
    fun `a paragraph with no terminator is still one sentence`() {
        // Headings, verse and dash-punctuated dialogue. Returning nothing here would leave
        // their words untappable, which is the one outcome the reader cannot afford.
        val result = SentenceSplitter.split("Chapter One")
        assertEquals(1, result.size)
        assertEquals("Chapter One", result.first().text)
    }

    @Test
    fun `blank text yields nothing`() {
        assertTrue(SentenceSplitter.split("   ").isEmpty())
    }

    @Test
    fun `question and exclamation marks end sentences`() {
        assertEquals(
            listOf("Where is it?", "Find it!", "Now."),
            texts("Where is it? Find it! Now.")
        )
    }
}
