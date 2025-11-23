package n.startapp.models.flashcard

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Flashcard model for spaced repetition learning
 */
@Serializable
data class Flashcard(
    val id: Int,
    val userId: Int,
    val savedWordId: Int?,
    val word: String,
    val translation: String,
    val definition: String?,
    val example: String?,
    val easeFactor: Float,
    val repetitions: Int,
    val interval: Int, // Days until next review
    @Serializable(with = InstantSerializer::class)
    val nextReview: Instant,
    @Serializable(with = InstantSerializer::class)
    val lastReviewed: Instant?,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant
)

/**
 * Simplified flashcard for client responses
 */
@Serializable
data class FlashcardDto(
    val id: Int,
    val word: String,
    val translation: String,
    val definition: String?,
    val example: String?,
    @Serializable(with = InstantSerializer::class)
    val nextReview: Instant,
    val daysUntilReview: Int
)

/**
 * Response for due flashcards endpoint
 */
@Serializable
data class DueFlashcardsResponse(
    val cards: List<FlashcardDto>,
    val totalDue: Int
)

/**
 * Request body for reviewing a flashcard
 */
@Serializable
data class ReviewRequest(
    val cardId: Int,
    val difficulty: ReviewDifficulty
)

/**
 * Review difficulty levels matching SM-2 algorithm
 */
@Serializable
enum class ReviewDifficulty {
    AGAIN,  // Complete blackout, incorrect response (0)
    HARD,   // Correct response with difficulty (3)
    GOOD,   // Correct response with some hesitation (4)
    EASY    // Perfect response (5)
}

/**
 * Response after reviewing a flashcard
 */
@Serializable
data class ReviewResponse(
    val cardId: Int,
    @Serializable(with = InstantSerializer::class)
    val nextReview: Instant,
    val interval: Int, // Days until next review
    val message: String
)

/**
 * Request to create a flashcard from a saved word
 */
@Serializable
data class CreateFlashcardRequest(
    val savedWordId: Int
)
