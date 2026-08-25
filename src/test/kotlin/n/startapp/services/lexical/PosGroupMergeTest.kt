package n.startapp.services.lexical

import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cases here are the real `grow up` response, which came back with its article twice.
 * Values are copied from what production served, not invented, so the thresholds are pinned
 * against text a model actually produced rather than against text chosen to pass.
 */
class PosGroupMergeTest {

    private fun sense(id: String, en: String, ru: List<String>) =
        Sense(id = id, definitionEn = en, definitionRu = "…", translationsRu = ru)

    private val sectionA = listOf(
        PosGroup(
            pos = "phrasal verb", posRu = "фразовый глагол",
            senses = listOf(
                sense("pv1", "To gradually develop from a child into an adult; to spend one's childhood in a particular place.", listOf("вырасти", "взрослеть", "расти")),
                sense("pv2", "To tell someone to stop behaving in a childish or silly way; to start acting like an adult.", listOf("повзрослеть", "вести себя по-взрослому", "прекратить ребячиться")),
                sense("pv3", "To develop or emerge gradually (about a place, idea, movement, or relationship).", listOf("возникнуть", "сложиться", "развиться", "вырасти")),
            )
        )
    )

    private val sectionB = listOf(
        PosGroup(
            pos = "phrasal verb", posRu = "фразовый глагол",
            senses = listOf(
                sense("pv1", "To gradually develop from a child into an adult; to spend one's childhood in a particular country.", listOf("вырасти", "повзрослеть", "вырасти (где-либо)")),
                sense("pv2", "To tell someone to stop behaving in a silly or childish way; to act more maturely.", listOf("повзрослеть", "вести себя как взрослый", "прекратить ребячиться")),
                sense("pv3", "To develop or come into existence gradually (about places, ideas, relationships, or movements).", listOf("возникнуть", "сложиться", "развиться", "вырасти")),
            )
        )
    )

    @Test
    fun `two sections describing the same part of speech collapse into one group`() {
        val merged = PosGroupMerge.merge(listOf(sectionA, sectionB))
        assertEquals(1, merged.size)
        assertEquals(3, merged.single().senses.size)
    }

    @Test
    fun `sense ids stay unique across the finished article`() {
        val merged = PosGroupMerge.merge(listOf(sectionA, sectionB))
        val ids = merged.flatMap { it.senses }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf("pv1", "pv2", "pv3"), ids)
    }

    @Test
    fun `a genuinely different part of speech survives`() {
        val interjection = listOf(
            PosGroup(
                pos = "interjection", posRu = "междометие",
                senses = listOf(sense("interj1", "Used to tell someone to stop being childish.", listOf("да повзрослей уже")))
            )
        )
        val merged = PosGroupMerge.merge(listOf(sectionA, interjection))
        assertEquals(listOf("phrasal verb", "interjection"), merged.map { it.pos })
    }

    @Test
    fun `distinct senses inside one section are never removed`() {
        val resolve = listOf(
            PosGroup(
                pos = "verb", posRu = "глагол",
                senses = listOf(
                    sense("v1", "To find a solution to a problem or dispute.", listOf("решать")),
                    sense("v2", "To separate a compound into its constituent parts.", listOf("разлагать")),
                )
            )
        )
        assertEquals(2, PosGroupMerge.merge(listOf(resolve)).single().senses.size)
    }

    @Test
    fun `a later section still contributes what the first one missed`() {
        val extra = listOf(
            PosGroup(
                pos = "phrasal verb", posRu = "фразовый глагол",
                senses = listOf(sense("pv1", "Of a custom or habit, to become established over time.", listOf("укорениться", "прижиться")))
            )
        )
        val merged = PosGroupMerge.merge(listOf(sectionA, extra))
        assertEquals(4, merged.single().senses.size)
        assertEquals("pv4", merged.single().senses.last().id)
    }

    @Test
    fun `a phrase is never split by part of speech`() {
        assertFalse(PosGroupMerge.shouldSplitByPartOfSpeech("grow up", LexicalKind.PHRASE, listOf("verb", "phrasal verb")))
        assertFalse(PosGroupMerge.shouldSplitByPartOfSpeech("grow up", LexicalKind.WORD, listOf("verb", "phrasal verb")))
        assertTrue(PosGroupMerge.shouldSplitByPartOfSpeech("run", LexicalKind.WORD, listOf("noun", "verb")))
        assertFalse(PosGroupMerge.shouldSplitByPartOfSpeech("run", LexicalKind.WORD, listOf("verb")))
    }
}
