package n.startapp.utils

import n.startapp.models.flashcard.ReviewDifficulty
import kotlin.math.roundToInt

/**
 * SM-2 (SuperMemo 2) Spaced Repetition Algorithm Implementation
 *
 * This algorithm calculates optimal review intervals based on user performance.
 *
 * References:
 * - Original algorithm: https://www.supermemo.com/en/archives1990-2015/english/ol/sm2
 * - Quality ratings: 0-5, where 5 is perfect recall
 */
object SpacedRepetitionAlgorithm {

    /**
     * Result of calculating next review schedule
     */
    data class ReviewResult(
        val easeFactor: Float,      // New ease factor (2.5 is default)
        val repetitions: Int,        // Number of consecutive correct reviews
        val interval: Int            // Days until next review
    )

    /**
     * Calculate next review schedule based on user performance
     *
     * @param currentEaseFactor Current ease factor (default: 2.5)
     * @param currentRepetitions Number of consecutive successful reviews
     * @param currentInterval Current interval in days
     * @param difficulty User's rating of recall difficulty
     * @return ReviewResult with updated values
     */
    fun calculateNextReview(
        currentEaseFactor: Float,
        currentRepetitions: Int,
        currentInterval: Int,
        difficulty: ReviewDifficulty
    ): ReviewResult {
        // Convert difficulty to quality rating (0-5 scale)
        val quality = when (difficulty) {
            ReviewDifficulty.AGAIN -> 0  // Complete failure
            ReviewDifficulty.HARD -> 3   // Difficult recall
            ReviewDifficulty.GOOD -> 4   // Good recall
            ReviewDifficulty.EASY -> 5   // Perfect recall
        }

        // Calculate new ease factor
        // EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        val newEaseFactor = calculateEaseFactor(currentEaseFactor, quality)

        // Determine new repetitions and interval
        val (newRepetitions, newInterval) = if (quality < 3) {
            // If quality < 3, start over
            0 to 1 // Review again tomorrow
        } else {
            // Successful recall
            val reps = currentRepetitions + 1
            val interval = calculateInterval(reps, currentInterval, newEaseFactor)
            reps to interval
        }

        return ReviewResult(
            easeFactor = newEaseFactor,
            repetitions = newRepetitions,
            interval = newInterval
        )
    }

    /**
     * Calculate new ease factor based on quality rating
     * Minimum ease factor is 1.3 to prevent intervals from becoming too short
     */
    private fun calculateEaseFactor(currentEF: Float, quality: Int): Float {
        val newEF = currentEF + (0.1f - (5 - quality) * (0.08f + (5 - quality) * 0.02f))
        return maxOf(1.3f, newEF) // Minimum EF is 1.3
    }

    /**
     * Calculate interval based on repetition number
     *
     * - First repetition: 1 day
     * - Second repetition: 6 days
     * - Subsequent repetitions: previous interval * ease factor
     */
    private fun calculateInterval(repetitions: Int, previousInterval: Int, easeFactor: Float): Int {
        return when (repetitions) {
            1 -> 1  // Review tomorrow
            2 -> 6  // Review in 6 days
            else -> (previousInterval * easeFactor).roundToInt()
        }
    }

    /**
     * Get human-readable message for review result
     */
    fun getReviewMessage(interval: Int, difficulty: ReviewDifficulty): String {
        return when {
            difficulty == ReviewDifficulty.AGAIN -> "Keep practicing! You'll see this card again tomorrow."
            interval == 1 -> "Good! Review again tomorrow."
            interval < 7 -> "Nice! Next review in $interval days."
            interval < 30 -> "Great! Next review in ${interval / 7} week(s)."
            else -> "Excellent! Next review in ${interval / 30} month(s)."
        }
    }
}
