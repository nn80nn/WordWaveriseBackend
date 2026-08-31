package n.startapp.services.lexical

import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PRONUNCIATION_VERSION
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.models.lexical.SourceRef
import n.startapp.services.dictionary.AggregatedWord
import n.startapp.services.dictionary.PronunciationVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Homographs, from both directions: `suspect` differs by part of speech, `lead` differs
 * inside one part of speech and can only be told apart by which block a definition came from.
 */
class PronunciationBindingTest {

    private fun uk(ipa: String, mp3: String? = null) =
        listOf(PronunciationEntry(region = "uk", ipa = ipa, audioMp3Url = mp3))

    private fun sense(id: String, definition: String, refs: List<Int>) = Sense(
        id = id, definitionEn = definition, definitionRu = "", sourceRefs = refs
    )

    private fun aggregate(
        perPos: Map<String, List<PronunciationEntry>>,
        variants: List<PronunciationVariant>,
        headword: List<PronunciationEntry> = emptyList()
    ) = AggregatedWord(
        response = WordDetailResponse(
            word = "x",
            phonetic = headword.firstOrNull()?.ipa,
            pronunciations = headword,
            definitions = emptyList()
        ),
        sourceDefinitions = emptyList(),
        perPosPronunciations = perPos,
        pronunciationVariants = variants
    )

    @Test
    fun `each part of speech keeps its own pronunciation`() {
        val entry = LexicalEntry(
            lemma = "suspect",
            posGroups = listOf(
                PosGroup("verb", "глагол", senses = listOf(sense("v1", "to think something is true", emptyList()))),
                PosGroup("noun", "существительное", senses = listOf(sense("n1", "a person believed to be guilty", emptyList())))
            )
        )

        val bound = PronunciationBinding.bind(
            entry,
            aggregate(
                perPos = mapOf("verb" to uk("/səˈspekt/"), "noun" to uk("/ˈsʌs.pekt/")),
                variants = emptyList(),
                headword = uk("/səˈspekt/")
            )
        )

        assertEquals("/səˈspekt/", bound.posGroups.first { it.pos == "verb" }.pronunciations.first().ipa)
        assertEquals("/ˈsʌs.pekt/", bound.posGroups.first { it.pos == "noun" }.pronunciations.first().ipa)
        assertEquals(PRONUNCIATION_VERSION, bound.pronunciationVersion)
    }

    @Test
    fun `a sense printed under another pronunciation carries it`() {
        val guide = "to control a group of people"
        val metal = "a heavy soft grey chemical element"
        val entry = LexicalEntry(
            lemma = "lead",
            sources = listOf(
                SourceRef(1, "CAMBRIDGE", "noun", guide),
                SourceRef(2, "CAMBRIDGE", "noun", metal)
            ),
            posGroups = listOf(
                PosGroup(
                    "noun", "существительное",
                    senses = listOf(sense("n1", guide, listOf(1)), sense("n2", metal, listOf(2)))
                )
            )
        )

        val bound = PronunciationBinding.bind(
            entry,
            aggregate(
                perPos = mapOf("noun" to uk("/liːd/")),
                variants = listOf(
                    PronunciationVariant("CAMBRIDGE", "noun", uk("/liːd/"), setOf(PronunciationVariant.key(guide))),
                    PronunciationVariant(
                        "CAMBRIDGE", "noun", uk("/led/", "https://audio/led.mp3"),
                        setOf(PronunciationVariant.key(metal))
                    )
                )
            )
        )

        val senses = bound.posGroups.single().senses
        // The one that agrees with its group says nothing; the odd one out says so, and out loud.
        assertNull(senses.first { it.id == "n1" }.phonetic)
        assertEquals("/led/", senses.first { it.id == "n2" }.phonetic)
        assertEquals("https://audio/led.mp3", senses.first { it.id == "n2" }.audioUrl)
    }

    @Test
    fun `re-binding clears a sense pronunciation that is no longer different`() {
        val definition = "a heavy soft grey chemical element"
        val entry = LexicalEntry(
            lemma = "lead",
            sources = listOf(SourceRef(1, "CAMBRIDGE", "noun", definition)),
            posGroups = listOf(
                PosGroup(
                    "noun", "существительное",
                    senses = listOf(
                        sense("n1", definition, listOf(1)).copy(phonetic = "/led/", audioUrl = "https://old.mp3")
                    )
                )
            )
        )

        val bound = PronunciationBinding.bind(
            entry,
            aggregate(
                perPos = mapOf("noun" to uk("/led/")),
                variants = listOf(
                    PronunciationVariant("CAMBRIDGE", "noun", uk("/led/"), setOf(PronunciationVariant.key(definition)))
                )
            )
        )

        val sense = bound.posGroups.single().senses.single()
        assertNull(sense.phonetic)
        assertNull(sense.audioUrl)
    }

    @Test
    fun `a sense with no supporting fragment keeps the group pronunciation`() {
        val entry = LexicalEntry(
            lemma = "lead",
            sources = listOf(SourceRef(1, "CAMBRIDGE", "noun", "a heavy soft grey chemical element")),
            posGroups = listOf(
                PosGroup("noun", "существительное", senses = listOf(sense("n1", "invented", emptyList())))
            )
        )

        val bound = PronunciationBinding.bind(
            entry,
            aggregate(
                perPos = mapOf("noun" to uk("/liːd/")),
                variants = listOf(
                    PronunciationVariant(
                        "CAMBRIDGE", "noun", uk("/led/"),
                        setOf(PronunciationVariant.key("a heavy soft grey chemical element"))
                    )
                )
            )
        )

        assertNull(bound.posGroups.single().senses.single().phonetic)
    }
}
