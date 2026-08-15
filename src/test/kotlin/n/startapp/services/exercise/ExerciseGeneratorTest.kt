package n.startapp.services.exercise

import n.startapp.models.exercise.Exercise.Companion.BLANK
import n.startapp.models.exercise.ExerciseKind
import n.startapp.models.lexical.BilingualExample
import n.startapp.models.lexical.Collocation
import n.startapp.models.lexical.InflectedForms
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExerciseGeneratorTest {

    private val random = Random(42)

    private fun entry(
        lemma: String,
        translations: List<String> = listOf("перевод"),
        definition: String = "a definition long enough to be usable",
        examples: List<BilingualExample> = emptyList(),
        collocations: List<Collocation> = emptyList(),
        forms: InflectedForms? = null
    ) = LexicalEntry(
        lemma = lemma,
        posGroups = listOf(
            PosGroup(
                pos = "verb",
                posRu = "глагол",
                forms = forms,
                senses = listOf(
                    Sense(
                        id = "v1",
                        definitionEn = definition,
                        definitionRu = "определение",
                        translationsRu = translations,
                        examples = examples,
                        collocations = collocations
                    )
                )
            )
        )
    )

    private fun word(
        w: String,
        translation: String? = null,
        definition: String? = null,
        entry: LexicalEntry? = null,
        example: String? = null
    ) = PracticeWord(
        word = w,
        translation = translation,
        definition = definition,
        example = example,
        entry = entry
    )

    /** Four words with four distinct meanings — the minimum any choice question needs. */
    private fun pool() = listOf(
        word("resolve", translation = "решать"),
        word("abandon", translation = "покидать"),
        word("gather", translation = "собирать"),
        word("mention", translation = "упоминать")
    )

    @Test
    fun `a fill-in-the-blank uses a real example and hides the form that is in it`() {
        val target = word(
            "resolve",
            entry = entry(
                "resolve",
                examples = listOf(
                    BilingualExample(
                        en = "They resolved the dispute without going to court.",
                        ru = "Они разрешили спор, не доводя дело до суда."
                    )
                ),
                forms = InflectedForms(past = "resolved", presentParticiple = "resolving")
            )
        )

        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.FILL_BLANK, random)
        )

        assertTrue(exercise.question.contains(BLANK), "the gap must be marked with $BLANK")
        assertTrue(
            !exercise.question.contains("resolved", ignoreCase = true),
            "the answer must not still be sitting in the sentence: ${exercise.question}"
        )
        assertEquals("resolved", exercise.answer)
        // The sentence needs the past form, but knowing the word is what is being tested.
        assertTrue("resolve" in exercise.acceptedAnswers)
        // The bilingual example is what makes a wrong answer worth something.
        assertTrue(exercise.explanationRu!!.contains("разрешили"))
    }

    @Test
    fun `a word the example does not actually contain produces no question`() {
        val target = word(
            "resolve",
            entry = entry("resolve", examples = listOf(BilingualExample(en = "Nothing here.", ru = "Ничего.")))
        )
        assertNull(ExerciseGenerator.build(target, listOf(target), ExerciseKind.FILL_BLANK, random))
    }

    @Test
    fun `a collocation question blanks out the target inside the attested phrase`() {
        val target = word(
            "decision",
            entry = entry(
                "decision",
                collocations = listOf(Collocation(pattern = "make a decision", ru = "принять решение"))
            )
        )
        val pool = pool() + target

        val exercise = assertNotNull(
            ExerciseGenerator.build(target, pool, ExerciseKind.COLLOCATION, random)
        )
        assertEquals("make a $BLANK", exercise.question)
        assertEquals("decision", exercise.answer)
        assertEquals("decision", exercise.options[exercise.correctIndex!!])
        assertEquals(4, exercise.options.size)
    }

    @Test
    fun `a choice question needs four different meanings`() {
        val pool = pool()
        val exercise = assertNotNull(
            ExerciseGenerator.build(pool.first(), pool, ExerciseKind.MEANING_CHOICE, random)
        )
        assertEquals(4, exercise.options.size)
        assertEquals("решать", exercise.options[exercise.correctIndex!!])

        // Two words are not enough to build one, and an unanswerable question is worse than none.
        val thin = pool.take(2)
        assertNull(ExerciseGenerator.build(thin.first(), thin, ExerciseKind.MEANING_CHOICE, random))
    }

    @Test
    fun `two words that mean the same thing never end up as two options`() {
        val duplicates = listOf(
            word("resolve", translation = "решать"),
            word("settle", translation = "Решать."),   // the same answer in other clothes
            word("gather", translation = "собирать"),
            word("mention", translation = "упоминать"),
            word("abandon", translation = "покидать")
        )
        val exercise = assertNotNull(
            ExerciseGenerator.build(duplicates.first(), duplicates, ExerciseKind.MEANING_CHOICE, random)
        )
        val normalized = exercise.options.map(ExerciseGrading::normalize)
        assertEquals(normalized.size, normalized.distinct().size, "options: ${exercise.options}")
    }

    @Test
    fun `word forms are drilled from the forms the article already carries`() {
        val target = word(
            "run",
            entry = entry("run", forms = InflectedForms(past = "ran", presentParticiple = "running"))
        )
        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.WORD_FORM, random)
        )
        assertEquals("run", exercise.question)
        assertTrue(exercise.answer in listOf("ran", "running"))
        // Asking for the form the headword already is would be a free point.
        assertTrue(!exercise.answer.equals("run", ignoreCase = true))
    }

    @Test
    fun `a word with no article still produces the questions a card can support`() {
        val pool = pool()
        val plain = word("resolve", translation = "решать", definition = "to settle a dispute firmly")

        assertNotNull(ExerciseGenerator.build(plain, pool, ExerciseKind.TRANSLATE_RU_EN, random))
        assertNotNull(ExerciseGenerator.build(plain, pool, ExerciseKind.SPELLING, random))
        // ...but the corpus-only kinds honestly decline.
        assertNull(ExerciseGenerator.build(plain, pool, ExerciseKind.COLLOCATION, random))
        assertNull(ExerciseGenerator.build(plain, pool, ExerciseKind.WORD_FORM, random))
    }

    @Test
    fun `a letter hint confirms a half-remembered word without giving away a new one`() {
        assertTrue(ExerciseGenerator.letterHint("resolve").startsWith("r"))
        assertTrue(ExerciseGenerator.letterHint("resolve").contains("·"))
        assertTrue(!ExerciseGenerator.letterHint("resolve").contains("resolve"))
    }
}
