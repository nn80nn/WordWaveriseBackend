package n.startapp.services

import n.startapp.exceptions.NotFoundException
import n.startapp.models.flashcard.*
import n.startapp.repositories.FlashcardRepository
import n.startapp.utils.SpacedRepetitionAlgorithm
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Service for managing flashcards and spaced repetition
 */
class FlashcardService {
    private val logger = LoggerFactory.getLogger(FlashcardService::class.java)
    private val repository = FlashcardRepository()

    /**
     * Create a flashcard directly
     */
    suspend fun createFlashcardDirect(
        userId: Int,
        word: String,
        translation: String,
        definition: String?,
        example: String?
    ): FlashcardDto {
        logger.info("Creating flashcard directly for user $userId: word='$word'")

        val flashcard = repository.create(
            userId = userId,
            word = word,
            translation = translation,
            definition = definition,
            example = example
        )

        return flashcardToDto(flashcard)
    }

    /**
     * Create a flashcard from a saved word
     */
    suspend fun createFlashcard(userId: Int, savedWordId: Int): FlashcardDto {
        logger.info("Creating flashcard for user $userId from saved word $savedWordId")

        val flashcard = repository.createFromSavedWord(userId, savedWordId)
            ?: throw NotFoundException("Saved word not found or doesn't belong to user")

        return flashcardToDto(flashcard)
    }

    /**
     * Get all flashcards due for review
     */
    suspend fun getDueFlashcards(userId: Int): DueFlashcardsResponse {
        logger.info("Fetching due flashcards for user $userId")

        val dueCards = repository.getDueFlashcards(userId)
        val dtos = dueCards.map { flashcardToDto(it) }

        return DueFlashcardsResponse(
            cards = dtos,
            totalDue = dtos.size
        )
    }

    /**
     * Review a flashcard and update its schedule using SM-2 algorithm
     */
    suspend fun reviewFlashcard(userId: Int, request: ReviewRequest): ReviewResponse {
        logger.info("User $userId reviewing flashcard ${request.cardId} with difficulty ${request.difficulty}")

        // Get flashcard
        val flashcard = repository.getById(request.cardId, userId)
            ?: throw NotFoundException("Flashcard not found")

        // Calculate next review using SM-2 algorithm
        val result = SpacedRepetitionAlgorithm.calculateNextReview(
            currentEaseFactor = flashcard.easeFactor,
            currentRepetitions = flashcard.repetitions,
            currentInterval = flashcard.interval,
            difficulty = request.difficulty
        )

        // Calculate next review date
        val nextReview = Instant.now().plus(result.interval.toLong(), ChronoUnit.DAYS)

        // Update flashcard in database
        val updated = repository.updateAfterReview(
            cardId = request.cardId,
            userId = userId,
            easeFactor = result.easeFactor,
            repetitions = result.repetitions,
            interval = result.interval,
            nextReview = nextReview
        )

        if (!updated) {
            throw Exception("Failed to update flashcard")
        }

        logger.info(
            "Flashcard ${request.cardId} updated: " +
                    "repetitions=${result.repetitions}, " +
                    "interval=${result.interval} days, " +
                    "nextReview=$nextReview"
        )

        return ReviewResponse(
            cardId = request.cardId,
            nextReview = nextReview,
            interval = result.interval,
            message = SpacedRepetitionAlgorithm.getReviewMessage(result.interval, request.difficulty)
        )
    }

    /**
     * Get all flashcards for a user
     */
    suspend fun getAllFlashcards(userId: Int): List<FlashcardDto> {
        logger.info("Fetching all flashcards for user $userId")
        val flashcards = repository.getAllByUser(userId)
        return flashcards.map { flashcardToDto(it) }
    }

    /**
     * Delete a flashcard
     */
    suspend fun deleteFlashcard(userId: Int, cardId: Int): Boolean {
        logger.info("User $userId deleting flashcard $cardId")
        val deleted = repository.delete(cardId, userId)
        if (!deleted) {
            throw NotFoundException("Flashcard not found")
        }
        return true
    }

    /**
     * Get statistics about user's flashcards
     */
    suspend fun getStatistics(userId: Int): FlashcardStatistics {
        val allCards = repository.getAllByUser(userId)
        val dueCount = repository.countDue(userId)
        val now = Instant.now()

        return FlashcardStatistics(
            totalCards = allCards.size,
            dueCards = dueCount,
            learnedCards = allCards.count { it.repetitions > 2 },
            newCards = allCards.count { it.repetitions == 0 },
            reviewingCards = allCards.count { it.repetitions in 1..2 }
        )
    }

    /**
     * Convert Flashcard to FlashcardDto
     */
    private fun flashcardToDto(flashcard: Flashcard): FlashcardDto {
        val now = Instant.now()
        val daysUntilReview = ChronoUnit.DAYS.between(now, flashcard.nextReview).toInt()

        return FlashcardDto(
            id = flashcard.id,
            word = flashcard.word,
            translation = flashcard.translation,
            definition = flashcard.definition,
            example = flashcard.example,
            nextReview = flashcard.nextReview,
            daysUntilReview = daysUntilReview
        )
    }
}

/**
 * Flashcard statistics for user dashboard
 */
@kotlinx.serialization.Serializable
data class FlashcardStatistics(
    val totalCards: Int,
    val dueCards: Int,
    val learnedCards: Int,
    val newCards: Int,
    val reviewingCards: Int
)
