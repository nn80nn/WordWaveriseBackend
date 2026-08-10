package n.startapp.services.lexical

import n.startapp.models.lexical.DraftEntry
import n.startapp.models.lexical.DraftExample
import n.startapp.models.lexical.DraftPosGroup
import n.startapp.models.lexical.DraftSense
import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.SourceRef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LexicalEntryValidatorTest {

    private fun sources(n: Int) = (1..n).map {
        SourceRef(index = it, source = "CAMBRIDGE", partOfSpeech = "noun", definition = "definition $it")
    }

    private fun sense(
        definitionEn: String = "a heavy metal",
        definitionRu: String = "тяжёлый металл",
        translationsRu: List<String> = listOf("свинец"),
        sourceRefs: List<Int> = listOf(1),
        generated: Boolean = false,
        examples: List<DraftExample> = listOf(DraftExample("Lead pipes are dangerous.", "Свинцовые трубы опасны."))
    ) = DraftSense(
        definitionEn = definitionEn,
        definitionRu = definitionRu,
        translationsRu = translationsRu,
        sourceRefs = sourceRefs,
        generated = generated,
        examples = examples
    )

    private fun draft(vararg senses: DraftSense, pos: String = "noun") =
        DraftEntry(lemma = "lead", posGroups = listOf(DraftPosGroup(pos = pos, posRu = "существительное", senses = senses.toList())))

    @Test
    fun `keeps a well formed sense and assigns a pos-scoped id`() {
        val result = LexicalEntryValidator.validate(draft(sense()), sources(3), "lead", LexicalKind.WORD)

        assertFalse(result.fatal)
        assertEquals(1, result.posGroups.size)
        assertEquals("n1", result.posGroups[0].senses[0].id)
        assertEquals(listOf(1), result.posGroups[0].senses[0].sourceRefs)
    }

    @Test
    fun `ids are numbered within each part of speech`() {
        val entry = DraftEntry(
            lemma = "lead",
            posGroups = listOf(
                DraftPosGroup("noun", "существительное", senses = listOf(sense(), sense(definitionEn = "first place"))),
                DraftPosGroup("verb", "глагол", senses = listOf(sense(definitionEn = "to guide")))
            )
        )
        val result = LexicalEntryValidator.validate(entry, sources(3), "lead", LexicalKind.WORD)

        assertEquals(listOf("n1", "n2"), result.posGroups.first { it.pos == "noun" }.senses.map { it.id })
        assertEquals(listOf("v1"), result.posGroups.first { it.pos == "verb" }.senses.map { it.id })
    }

    @Test
    fun `drops source refs outside the available range`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(sourceRefs = listOf(1, 99, 0, -3))), sources(3), "lead", LexicalKind.WORD
        )

        assertEquals(listOf(1), result.posGroups[0].senses[0].sourceRefs)
        assertTrue(result.issues.any { it.contains("несуществующие фрагменты") })
    }

    @Test
    fun `a sense left with no valid ref is forced to generated`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(sourceRefs = listOf(42), generated = false)), sources(3), "lead", LexicalKind.WORD
        )

        val out = result.posGroups[0].senses[0]
        assertTrue(out.generated, "a sense that cites nothing real must be marked generated")
        assertTrue(out.sourceRefs.isEmpty())
    }

    @Test
    fun `empty source refs imply generated even when the model said otherwise`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(sourceRefs = emptyList(), generated = false)), sources(3), "lead", LexicalKind.WORD
        )

        assertTrue(result.posGroups[0].senses[0].generated)
    }

    @Test
    fun `drops a sense with no russian`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(definitionRu = "  "), sense()), sources(3), "lead", LexicalKind.WORD
        )

        assertEquals(1, result.posGroups[0].senses.size)
        assertTrue(result.issues.any { it.contains("без русского перевода") })
    }

    @Test
    fun `drops a sense with no translations`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(translationsRu = emptyList()), sense()), sources(3), "lead", LexicalKind.WORD
        )

        assertEquals(1, result.posGroups[0].senses.size)
    }

    @Test
    fun `drops a part of speech outside the schema`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(), pos = "существительное"), sources(3), "lead", LexicalKind.WORD
        )

        assertTrue(result.fatal, "nothing survived, so the attempt must be retried")
        assertTrue(result.issues.any { it.contains("вне схемы") })
    }

    @Test
    fun `an empty result is fatal`() {
        val result = LexicalEntryValidator.validate(DraftEntry(lemma = "lead"), sources(3), "lead", LexicalKind.WORD)
        assertTrue(result.fatal)
    }

    @Test
    fun `mostly invented senses with plenty of sources is fatal`() {
        val invented = (1..5).map { sense(definitionEn = "invented $it", sourceRefs = emptyList(), generated = true) }
        val grounded = sense()
        val result = LexicalEntryValidator.validate(
            draft(*(invented + grounded).toTypedArray()), sources(8), "lead", LexicalKind.WORD
        )

        assertTrue(result.fatal, "ignoring 8 supplied fragments must not be served to the user")
        assertTrue(result.issues.any { it.contains("проигнорировала источники") })
    }

    @Test
    fun `invented senses are acceptable when there was little to ground them on`() {
        val invented = (1..5).map { sense(definitionEn = "invented $it", sourceRefs = emptyList(), generated = true) }
        val result = LexicalEntryValidator.validate(
            draft(*invented.toTypedArray()), sources(2), "lead", LexicalKind.WORD
        )

        assertFalse(result.fatal)
    }

    @Test
    fun `drops an example that never mentions the headword`() {
        val result = LexicalEntryValidator.validate(
            draft(
                sense(
                    examples = listOf(
                        DraftExample("Lead pipes are dangerous.", "Свинцовые трубы опасны."),
                        DraftExample("The weather is nice today.", "Сегодня хорошая погода.")
                    )
                )
            ),
            sources(3), "lead", LexicalKind.WORD
        )

        val examples = result.posGroups[0].senses[0].examples
        assertEquals(1, examples.size)
        assertTrue(examples[0].en.contains("Lead"))
    }

    @Test
    fun `never strips the only example even when it looks off topic`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(examples = listOf(DraftExample("The weather is nice.", "Хорошая погода.")))),
            sources(3), "lead", LexicalKind.WORD
        )

        assertEquals(1, result.posGroups[0].senses[0].examples.size)
    }

    @Test
    fun `keeps examples for phrases where the surface form is reworded`() {
        val entry = DraftEntry(
            lemma = "kick the bucket",
            posGroups = listOf(
                DraftPosGroup(
                    "idiom", "идиома",
                    senses = listOf(
                        sense(
                            definitionEn = "to die",
                            examples = listOf(
                                DraftExample("He kicked the bucket last winter.", "Он умер прошлой зимой."),
                                DraftExample("Everyone kicks it eventually.", "Все рано или поздно умирают.")
                            )
                        )
                    )
                )
            )
        )
        val result = LexicalEntryValidator.validate(entry, sources(3), "kick the bucket", LexicalKind.IDIOM)

        assertEquals(2, result.posGroups[0].senses[0].examples.size)
    }

    @Test
    fun `nulls out an example source ref outside the range`() {
        val result = LexicalEntryValidator.validate(
            draft(sense(examples = listOf(DraftExample("Lead is heavy.", "Свинец тяжёлый.", sourceRef = 77)))),
            sources(3), "lead", LexicalKind.WORD
        )

        assertEquals(null, result.posGroups[0].senses[0].examples[0].sourceRef)
    }

    @Test
    fun `reports forbidden keys smuggled into the raw payload`() {
        val raw = """{"lemma":"lead","ipa":"/liːd/","audioUrl":"https://example.com/a.mp3","posGroups":[]}"""
        val result = LexicalEntryValidator.validate(draft(sense()), sources(3), "lead", LexicalKind.WORD, raw)

        assertTrue(result.issues.any { it.contains("запрещённые поля") })
        // The parsed draft never carried them, so nothing to strip — the point is that we notice.
        assertFalse(result.fatal)
    }

    @Test
    fun `flags a lemma that does not resemble the request`() {
        val entry = draft(sense()).copy(lemma = "banana")
        val result = LexicalEntryValidator.validate(entry, sources(3), "lead", LexicalKind.WORD)

        assertTrue(result.issues.any { it.contains("вместо") })
    }

    @Test
    fun `orders parts of speech with the common ones first`() {
        val entry = DraftEntry(
            lemma = "lead",
            posGroups = listOf(
                DraftPosGroup("adverb", "наречие", senses = listOf(sense(definitionEn = "adv"))),
                DraftPosGroup("verb", "глагол", senses = listOf(sense(definitionEn = "verb"))),
                DraftPosGroup("noun", "существительное", senses = listOf(sense(definitionEn = "noun")))
            )
        )
        val result = LexicalEntryValidator.validate(entry, sources(3), "lead", LexicalKind.WORD)

        assertEquals(listOf("noun", "verb", "adverb"), result.posGroups.map { it.pos })
    }
}
