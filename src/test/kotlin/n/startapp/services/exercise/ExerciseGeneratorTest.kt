package n.startapp.services.exercise

import n.startapp.models.exercise.Exercise.Companion.BLANK
import n.startapp.models.exercise.ExerciseKind
import n.startapp.models.lexical.BilingualExample
import n.startapp.models.lexical.Collocation
import n.startapp.models.lexical.InflectedForms
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.models.dictionary.PronunciationEntry
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
        forms: InflectedForms? = null,
        synonyms: List<String> = emptyList(),
        pos: String = "verb",
        extraSenses: List<Sense> = emptyList(),
        audioUrl: String? = null,
        pronunciations: List<PronunciationEntry> = emptyList()
    ) = LexicalEntry(
        lemma = lemma,
        audioUrl = audioUrl,
        pronunciations = pronunciations,
        posGroups = listOf(
            PosGroup(
                pos = pos,
                posRu = "глагол",
                forms = forms,
                senses = listOf(
                    Sense(
                        id = "v1",
                        definitionEn = definition,
                        definitionRu = "определение",
                        translationsRu = translations,
                        examples = examples,
                        collocations = collocations,
                        synonyms = synonyms
                    )
                ) + extraSenses
            )
        )
    )

    private fun sense(id: String, definition: String, translations: List<String>) = Sense(
        id = id,
        definitionEn = definition,
        definitionRu = "определение",
        translationsRu = translations
    )

    private fun word(
        w: String,
        translation: String? = null,
        definition: String? = null,
        entry: LexicalEntry? = null,
        example: String? = null,
        senseId: String? = null
    ) = PracticeWord(
        word = w,
        translation = translation,
        definition = definition,
        example = example,
        senseId = senseId,
        entry = entry
    )

    /**
     * One headword, two meanings that share nothing — a noun and a verb, each with its own
     * examples, collocations and inflections. Exactly the shape a pin exists for.
     */
    private fun twoSenseEntry() = LexicalEntry(
        lemma = "resolve",
        posGroups = listOf(
            PosGroup(
                pos = "noun",
                posRu = "существительное",
                forms = InflectedForms(plural = "resolves"),
                senses = listOf(
                    Sense(
                        id = "n1",
                        definitionEn = "firm determination to do something",
                        definitionRu = "твёрдая решимость",
                        translationsRu = listOf("решимость"),
                        examples = listOf(
                            BilingualExample(
                                en = "The setback only strengthened her resolve.",
                                ru = "Неудача лишь укрепила её решимость."
                            )
                        ),
                        collocations = listOf(Collocation(pattern = "strengthen one's resolve")),
                        synonyms = listOf("determination")
                    )
                )
            ),
            PosGroup(
                pos = "verb",
                posRu = "глагол",
                forms = InflectedForms(past = "resolved", presentParticiple = "resolving"),
                senses = listOf(
                    Sense(
                        id = "v1",
                        definitionEn = "to find a solution to a problem",
                        definitionRu = "найти решение",
                        translationsRu = listOf("решать"),
                        examples = listOf(
                            BilingualExample(
                                en = "They resolved the dispute without going to court.",
                                ru = "Они разрешили спор, не доводя дело до суда."
                            )
                        ),
                        collocations = listOf(Collocation(pattern = "resolve a dispute")),
                        synonyms = listOf("settle")
                    )
                )
            )
        )
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
    fun `a word with no article still produces the questions a card can support`() {
        val pool = pool()
        val plain = word("resolve", translation = "решать", definition = "to settle a dispute firmly")

        assertNotNull(ExerciseGenerator.build(plain, pool, ExerciseKind.TRANSLATE_RU_EN, random))
        assertNotNull(ExerciseGenerator.build(plain, pool, ExerciseKind.SPELLING, random))
        // ...but the corpus-only kinds honestly decline.
        assertNull(ExerciseGenerator.build(plain, pool, ExerciseKind.COLLOCATION, random))
    }

    @Test
    fun `a letter hint confirms a half-remembered word without giving away a new one`() {
        assertTrue(ExerciseGenerator.letterHint("resolve").startsWith("r"))
        assertTrue(ExerciseGenerator.letterHint("resolve").contains("·"))
        assertTrue(!ExerciseGenerator.letterHint("resolve").contains("resolve"))
    }

    // ── Distractors that are actually worth getting wrong ─────────────────────

    @Test
    fun `a word listed as a synonym is preferred over an unrelated one`() {
        val target = word(
            "resolve",
            translation = "решать",
            definition = "to settle a dispute or a problem firmly",
            entry = entry("resolve", translations = listOf("решать"), synonyms = listOf("settle"))
        )
        val settle = word(
            "settle",
            translation = "улаживать",
            entry = entry("settle", translations = listOf("улаживать"))
        )
        // Padding, so the question can be built at all — but none of it is close in meaning.
        val strangers = listOf(
            word("protractor", translation = "транспортир", entry = entry("protractor", listOf("транспортир"), pos = "noun")),
            word("cliff", translation = "обрыв", entry = entry("cliff", listOf("обрыв"), pos = "noun")),
            word("heavy", translation = "тяжёлый", entry = entry("heavy", listOf("тяжёлый"), pos = "adjective"))
        )
        val pool = listOf(target, settle) + strangers

        val exercise = assertNotNull(
            ExerciseGenerator.build(target, pool, ExerciseKind.MEANING_CHOICE, random)
        )
        // The near-miss must be on offer; a question whose only plausible option is the answer
        // can be passed without knowing the word.
        assertTrue(
            "улаживать" in exercise.options,
            "the synonym's meaning should be one of the traps: ${exercise.options}"
        )
    }

    @Test
    fun `another sense of the same word is never offered as a wrong answer`() {
        // `come across` means both "meet by chance" and "give an impression"; both are right,
        // so putting the second one among the traps makes the question unanswerable.
        val target = word(
            "come across",
            entry = entry(
                "come across",
                translations = listOf("наткнуться"),
                extraSenses = listOf(sense("v2", "to give an impression", listOf("производить впечатление")))
            )
        )
        val pool = listOf(
            target,
            word("protractor", translation = "производить впечатление"),
            word("cliff", translation = "обрыв"),
            word("heavy", translation = "тяжёлый"),
            word("gather", translation = "собирать")
        )

        val exercise = assertNotNull(
            ExerciseGenerator.build(target, pool, ExerciseKind.MEANING_CHOICE, random)
        )
        assertTrue(
            "производить впечатление" !in exercise.options,
            "a second sense of the target is a true answer, not a trap: ${exercise.options}"
        )
    }

    // ── Both directions of translation ───────────────────────────────────────

    @Test
    fun `two words sharing one Russian meaning are both accepted`() {
        val resolve = word("resolve", entry = entry("resolve", translations = listOf("решать")))
        val settle = word(
            "settle",
            entry = entry("settle", translations = listOf("улаживать", "решать"))
        )
        val pool = listOf(resolve, settle)

        val exercise = assertNotNull(
            ExerciseGenerator.build(resolve, pool, ExerciseKind.TRANSLATE_RU_EN, random)
        )
        assertEquals("решать", exercise.question)
        // The prompt cannot say which of the two it wants, so it must not punish either.
        assertEquals(
            ExerciseVerdict.CORRECT,
            ExerciseGrading.grade(exercise, "settle"),
            "accepted: ${ExerciseGrading.accepted(exercise)}"
        )
        assertEquals(ExerciseVerdict.CORRECT, ExerciseGrading.grade(exercise, "resolve"))
    }

    @Test
    fun `a word with several meanings accepts any of them in the Russian direction`() {
        val target = word(
            "resolve",
            entry = entry(
                "resolve",
                translations = listOf("решать", "разрешать"),
                extraSenses = listOf(sense("v2", "to decide firmly", listOf("твёрдо решить")))
            )
        )
        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.TRANSLATE_EN_RU, random)
        )
        assertEquals("resolve", exercise.question)
        assertEquals(ExerciseVerdict.CORRECT, ExerciseGrading.grade(exercise, "решать"))
        assertEquals(ExerciseVerdict.CORRECT, ExerciseGrading.grade(exercise, "разрешать"))
        // ...including a sense the prompt never showed.
        assertEquals(ExerciseVerdict.CORRECT, ExerciseGrading.grade(exercise, "твёрдо решить"))
        assertEquals(ExerciseVerdict.WRONG, ExerciseGrading.grade(exercise, "собирать"))
    }

    @Test
    fun `a stored translation holding several equivalents counts as all of them`() {
        // Cards store "решать, улаживать" in one string; the second half is no less correct.
        val target = word("resolve", translation = "решать, улаживать")
        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.TRANSLATE_EN_RU, random)
        )
        assertEquals(ExerciseVerdict.CORRECT, ExerciseGrading.grade(exercise, "улаживать"))
    }

    // ── Listening ────────────────────────────────────────────────────────────

    @Test
    fun `a listening question carries the recording and no text at all`() {
        val target = word(
            "resolve",
            translation = "решать",
            entry = entry("resolve", audioUrl = "https://audio.example/resolve.mp3")
        )
        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.LISTENING, random)
        )
        assertEquals("https://audio.example/resolve.mp3", exercise.audioUrl)
        assertEquals("resolve", exercise.answer)
        // Showing the meaning would turn it into a translation exercise with a sound effect.
        assertTrue(exercise.question.isBlank(), "question was: '${exercise.question}'")
    }

    @Test
    fun `the recording is found wherever the article keeps it`() {
        val target = word(
            "resolve",
            entry = entry(
                "resolve",
                pronunciations = listOf(PronunciationEntry(region = "uk", audioMp3Url = "https://a/uk.mp3"))
            )
        )
        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.LISTENING, random)
        )
        assertEquals("https://a/uk.mp3", exercise.audioUrl)
    }

    @Test
    fun `a word with no recording produces no listening question`() {
        val target = word("resolve", translation = "решать", entry = entry("resolve"))
        assertNull(ExerciseGenerator.build(target, listOf(target), ExerciseKind.LISTENING, random))
    }

    // ── The pinned sense ──────────────────────────────────────────────────────

    @Test
    fun `a pinned word is asked about the sense the user chose`() {
        val target = word("resolve", entry = twoSenseEntry(), senseId = "n1")

        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.TRANSLATE_EN_RU, random)
        )

        assertEquals("решимость", exercise.answer)
        // Второе значение статьи всё равно засчитывается: у слова несколько верных переводов,
        // и настаивать на одном — быстрейший способ научить не доверять упражнению.
        assertTrue(exercise.acceptedAnswers.contains("решать"))
    }

    @Test
    fun `an unpinned word still leads with the article's first sense`() {
        val target = word("resolve", entry = twoSenseEntry())

        val exercise = assertNotNull(
            ExerciseGenerator.build(target, listOf(target), ExerciseKind.TRANSLATE_EN_RU, random)
        )

        assertEquals("решимость", exercise.answer)
    }

    @Test
    fun `a pinned sense borrows neither examples nor collocations from the other one`() {
        val target = word("resolve", entry = twoSenseEntry(), senseId = "n1")
        val pool = listOf(target) + pool().filter { it.word != "resolve" }

        val blank = assertNotNull(
            ExerciseGenerator.build(target, pool, ExerciseKind.FILL_BLANK, random)
        )
        assertTrue(
            blank.question.contains("setback"),
            "the sentence must come from the pinned sense: ${blank.question}"
        )

        val collocation = assertNotNull(
            ExerciseGenerator.build(target, pool, ExerciseKind.COLLOCATION, random)
        )
        assertTrue(
            collocation.question.contains("strengthen"),
            "the collocation must belong to the pinned sense: ${collocation.question}"
        )
    }

    @Test
    fun `a pin the article no longer carries borrows nothing from the corpus`() {
        // Откат на первое значение спрашивал бы о смысле, которого человек не выбирал, тогда
        // как его собственная карточка показывает другой. Текст карточки при этом работает.
        val target = word(
            "resolve",
            translation = "разлагать",
            definition = "to separate into constituent parts",
            entry = twoSenseEntry(),
            senseId = "v9"
        )
        val pool = listOf(target) + pool().filter { it.word != "resolve" }

        assertNull(ExerciseGenerator.build(target, pool, ExerciseKind.FILL_BLANK, random))
        assertNull(ExerciseGenerator.build(target, pool, ExerciseKind.COLLOCATION, random))

        val typed = assertNotNull(
            ExerciseGenerator.build(target, pool, ExerciseKind.TRANSLATE_EN_RU, random)
        )
        assertEquals("разлагать", typed.answer)
    }
}
