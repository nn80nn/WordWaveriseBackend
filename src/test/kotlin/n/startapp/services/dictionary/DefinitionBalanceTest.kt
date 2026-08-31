package n.startapp.services.dictionary

import n.startapp.models.dictionary.DetailedDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-source budget is what decides which meanings of a homograph the article can have.
 * Spending it on whichever part of speech the dictionary printed first is not a truncation
 * limit — it is a silent decision that `lead` is only a verb.
 */
class DefinitionBalanceTest {

    private fun def(pos: String, text: String, block: Int? = null) =
        DetailedDefinition(
            partOfSpeech = pos, definition = text, example = null,
            source = "CAMBRIDGE", entryIndex = block
        )

    @Test
    fun `the budget is spread across parts of speech`() {
        val defs = (1..10).map { def("verb", "verb sense $it") } +
            (1..5).map { def("noun", "noun sense $it") }

        val kept = DefinitionBudget.apply(defs, perSourceLimit = 8)

        assertEquals(8, kept.size)
        assertTrue(kept.any { it.partOfSpeech == "noun" }, "the noun must survive the cap")
        assertEquals(4, kept.count { it.partOfSpeech == "noun" })
        assertEquals(4, kept.count { it.partOfSpeech == "verb" })
    }

    @Test
    fun `one part of speech keeps the order it came in`() {
        val defs = (1..4).map { def("noun", "sense $it") }
        val kept = DefinitionBudget.apply(defs, perSourceLimit = 8)
        assertEquals(listOf("sense 1", "sense 2", "sense 3", "sense 4"), kept.map { it.definition })
    }

    @Test
    fun `the same definition twice from one source counts once`() {
        val defs = listOf(def("noun", "a heavy metal"), def("noun", "A heavy metal!"))
        assertEquals(1, DefinitionBudget.apply(defs, perSourceLimit = 8).size)
    }

    @Test
    fun `two blocks of one part of speech both survive`() {
        // `lead` — существительное и в /liːd/, и в /led/. Разложение только по частям речи
        // отдавало всю долю существительного первому блоку, и металл в статью не попадал.
        val defs = (1..6).map { def("noun", "front sense $it", block = 1) } +
            (1..3).map { def("noun", "metal sense $it", block = 3) }

        val kept = DefinitionBudget.apply(defs, perSourceLimit = 6)

        assertEquals(3, kept.count { it.entryIndex == 3 })
        assertEquals(3, kept.count { it.entryIndex == 1 })
    }

    @Test
    fun `a source without blocks is spread by part of speech alone`() {
        // У API блоков нет, и там эти два вопроса — один и тот же вопрос.
        val defs = (1..6).map { def("verb", "verb $it") } + (1..2).map { def("noun", "noun $it") }
        val kept = DefinitionBudget.apply(defs, perSourceLimit = 4)
        assertEquals(2, kept.count { it.partOfSpeech == "noun" })
    }
}
