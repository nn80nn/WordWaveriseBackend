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
    val categoryId: Int? = null,
    val word: String,
    val translation: String,
    val definition: String?,
    val example: String?,
    /** The sense of the article this card is about, inherited from the saved word. */
    val senseId: String? = null,
    val easeFactor: Float,
    val repetitions: Int,
    val interval: Int, // Days until next review
    @Serializable(with = InstantSerializer::class)
    val nextReview: Instant,
    @Serializable(with = InstantSerializer::class)
    val lastReviewed: Instant?,
    /** Hand-edited: the corpus refresh leaves this card's wording alone. */
    val customized: Boolean = false,
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
    /** Which sense of the article the card is about, when the user pinned one. */
    val senseId: String? = null,
    val categoryId: Int? = null,
    val customized: Boolean = false,
    /** Consecutive correct reviews — what the clients render as a progress dot row. */
    val repetitions: Int = 0,
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

/**
 * Request to create a flashcard directly without saved word
 */
@Serializable
data class CreateFlashcardDirectRequest(
    val word: String,
    val translation: String,
    val definition: String? = null,
    val example: String? = null,
    val categoryId: Int? = null,
    /**
     * The sense the card is about. Carried here as well as on the saved word because a client
     * can create a card in the same breath as saving — without it that card would be born
     * unpinned and the corpus refresh would rewrite it from the article's first sense.
     */
    val senseId: String? = null
)

/**
 * Replaces what the card says.
 *
 * [word] is optional because changing it is a different act from fixing a translation: it
 * re-points the card at another headword, and the corpus link goes with it.
 */
@Serializable
data class UpdateFlashcardContentRequest(
    val word: String? = null,
    val translation: String,
    val definition: String? = null,
    val example: String? = null
)

@Serializable
data class SetFlashcardCategoryRequest(
    /** null removes the card from every folder. */
    val categoryId: Int? = null
)

/** Fill a folder with cards in one action, from the words already saved in it. */
@Serializable
data class BulkCreateFlashcardsRequest(
    /** null = every saved word; -1 = only the words in no folder. */
    val categoryId: Int? = null
)

@Serializable
data class BulkCreateFlashcardsResult(
    val created: Int,
    val skipped: Int,
    /** Cards that already existed outside any folder and were pulled into this one. */
    val moved: Int = 0
)

/**
 * Request to update flashcard progress
 */
@Serializable
data class UpdateFlashcardProgressRequest(
    val difficulty: ReviewDifficulty
)
