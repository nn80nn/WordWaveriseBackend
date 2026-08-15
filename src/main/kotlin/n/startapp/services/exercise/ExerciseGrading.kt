package n.startapp.services.exercise

import n.startapp.models.exercise.Exercise

/** What the learner's typed answer turned out to be. */
enum class ExerciseVerdict { CORRECT, ALMOST, WRONG }

/**
 * The reference implementation of answer grading.
 *
 * Free-text answers are graded on the client — the answer travels with the exercise, so a
 * round-trip would only add latency to the moment the learner is waiting on. That makes this
 * object the *specification*: `wordwaveriseweb/src/utils/exerciseGrading.ts` and the Android
 * `ExerciseGrading.kt` are ports of it, and its tests are what stop the two clients from
 * quietly drifting into grading the same answer differently.
 *
 * A typo is not a failure of vocabulary. Marking `recieve` wrong for `receive` teaches nothing
 * and costs the card its place in the rotation, so a near miss is [ExerciseVerdict.ALMOST]:
 * the answer counts, and the card comes back sooner rather than being reset.
 */
object ExerciseGrading {

    private val ARTICLES = setOf("a", "an", "the")

    /**
     * Everything two answers can differ by without differing in meaning: case, spacing,
     * punctuation, a leading article, and the Russian е/ё distinction that no keyboard agrees on.
     */
    fun normalize(raw: String): String {
        val cleaned = raw.trim().lowercase().replace('ё', 'е')
            .map { if (it.isLetterOrDigit() || it == '\'' || it == '-') it else ' ' }
            .joinToString("")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        // "the sea" and "sea" are the same answer; "the" on its own is not an article here.
        val words = if (cleaned.size > 1 && cleaned.first() in ARTICLES) cleaned.drop(1) else cleaned
        return words.joinToString(" ")
    }

    /** Accepted spellings for [exercise], normalised, with duplicates and blanks removed. */
    fun accepted(exercise: Exercise): List<String> =
        (listOf(exercise.answer) + exercise.acceptedAnswers)
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()

    fun grade(exercise: Exercise, userAnswer: String): ExerciseVerdict =
        grade(userAnswer, accepted(exercise))

    fun grade(userAnswer: String, acceptedNormalized: List<String>): ExerciseVerdict {
        val given = normalize(userAnswer)
        if (given.isBlank()) return ExerciseVerdict.WRONG
        if (acceptedNormalized.any { it == given }) return ExerciseVerdict.CORRECT

        val almost = acceptedNormalized.any { expected ->
            val budget = typoBudget(expected)
            budget > 0 && editDistance(given, expected, budget) <= budget
        }
        return if (almost) ExerciseVerdict.ALMOST else ExerciseVerdict.WRONG
    }

    /**
     * How many character slips still count as knowing the word.
     *
     * Short words get none: at four letters, one edit is often a different word altogether
     * (`hard`/`herd`, `sea`/`see`), and forgiving that would mark a wrong answer right.
     */
    fun typoBudget(expected: String): Int = when {
        expected.length < 5 -> 0
        expected.length <= 8 -> 1
        else -> 2
    }

    /**
     * Edit distance that counts a swap of two neighbouring letters as **one** change
     * (Optimal String Alignment), not two.
     *
     * Plain Levenshtein charges 2 for `recieve` → `receive`, which is the single most common
     * typing mistake there is; with a budget of one it would be graded exactly like a word the
     * learner does not know. Bounded: it stops as soon as every path already exceeds [limit].
     */
    fun editDistance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var twoAgo = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowBest = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = minOf(value, twoAgo[j - 2] + 1)
                }
                current[j] = value
                if (value < rowBest) rowBest = value
            }
            if (rowBest > limit) return limit + 1
            val spare = twoAgo; twoAgo = previous; previous = current; current = spare
        }
        return previous[b.length]
    }
}
