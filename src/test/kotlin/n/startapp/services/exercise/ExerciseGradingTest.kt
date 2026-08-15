package n.startapp.services.exercise

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * These tests are the contract the two clients are held to. Web and Android each carry a port of
 * [ExerciseGrading]; if a case here changes, both ports change with it, otherwise the same typed
 * answer starts being right on one platform and wrong on the other.
 */
class ExerciseGradingTest {

    private fun grade(user: String, expected: String) =
        ExerciseGrading.grade(user, listOf(ExerciseGrading.normalize(expected)))

    @Test
    fun `normalisation ignores everything that is not the answer`() {
        assertEquals("resolve", ExerciseGrading.normalize("  Resolve. "))
        assertEquals("resolve", ExerciseGrading.normalize("\"resolve\""))
        assertEquals("make a decision", ExerciseGrading.normalize("make  a   decision"))
        // A leading article is not part of the word being tested.
        assertEquals("sea", ExerciseGrading.normalize("the sea"))
        // ...but an article alone is the answer itself.
        assertEquals("the", ExerciseGrading.normalize("The"))
        // No keyboard agrees on ё, so it cannot be allowed to decide a question.
        assertEquals("еж", ExerciseGrading.normalize("Ёж"))
    }

    @Test
    fun `case and punctuation do not change the verdict`() {
        assertEquals(ExerciseVerdict.CORRECT, grade("Perseverance!", "perseverance"))
        assertEquals(ExerciseVerdict.CORRECT, grade("the sea", "sea"))
    }

    @Test
    fun `a single slip in a long word is a near miss, not a failure`() {
        assertEquals(ExerciseVerdict.ALMOST, grade("recieve", "receive"))
        assertEquals(ExerciseVerdict.ALMOST, grade("perseverence", "perseverance"))
    }

    @Test
    fun `short words get no forgiveness, because one letter is another word`() {
        // `herd` is not a typo for `hard`, and marking it right would teach the wrong word.
        assertEquals(ExerciseVerdict.WRONG, grade("herd", "hard"))
        assertEquals(ExerciseVerdict.WRONG, grade("see", "sea"))
        assertEquals(0, ExerciseGrading.typoBudget("sea"))
    }

    @Test
    fun `a different word entirely is wrong`() {
        assertEquals(ExerciseVerdict.WRONG, grade("abandon", "perseverance"))
        assertEquals(ExerciseVerdict.WRONG, grade("", "anything"))
    }

    /**
     * `massive` for `missive` may be a slip or may be the wrong word — nothing in the string
     * says which. ALMOST is the honest answer to that: it is not CORRECT, the learner is still
     * shown the right word, and the card is scheduled as difficult rather than as known.
     */
    @Test
    fun `an ambiguous near miss is graded as neither right nor plainly wrong`() {
        assertEquals(ExerciseVerdict.ALMOST, grade("massive", "missive"))
    }

    @Test
    fun `any accepted spelling counts`() {
        val accepted = listOf("resolved", "resolve")
        assertEquals(ExerciseVerdict.CORRECT, ExerciseGrading.grade("Resolve", accepted))
        assertEquals(ExerciseVerdict.CORRECT, ExerciseGrading.grade("resolved", accepted))
    }

    @Test
    fun `swapping two neighbouring letters costs one edit, not two`() {
        // The whole reason the near-miss rule is usable at all.
        assertEquals(1, ExerciseGrading.editDistance("receive", "recieve"))
        assertEquals(0, ExerciseGrading.editDistance("same", "same"))
        // Above the limit the exact distance is not needed, only that it exceeds it.
        assertEquals(2, ExerciseGrading.editDistance("abcdefgh", "zzzzzzzz", limit = 1))
    }
}
