package n.startapp.services

import n.startapp.exceptions.NotFoundException
import n.startapp.models.flashcard.*
import n.startapp.services.lexical.SenseWording
import n.startapp.repositories.FlashcardRepository
import n.startapp.repositories.LexicalEntryRepository
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
    private val lexicalEntries = LexicalEntryRepository()

    /**
     * Create a flashcard directly
     */
    suspend fun createFlashcardDirect(
        userId: Int,
        word: String,
        translation: String,
        definition: String?,
        example: String?,
        categoryId: Int? = null,
        senseId: String? = null
    ): FlashcardDto {
        logger.info("Creating flashcard directly for user $userId: word='$word'")

        val flashcard = repository.create(
            userId = userId,
            word = word,
            translation = translation,
            definition = definition,
            example = example,
            categoryId = categoryId,
            senseId = senseId
        )

        return flashcardToDto(flashcard)
    }

    /**
     * Replaces what a card says, and marks it as hand-edited so the corpus refresh stops
     * rewriting it. Losing a correction on the next study session would make the editor
     * pointless — see [refreshFromCorpus].
     */
    suspend fun updateContent(
        userId: Int,
        cardId: Int,
        word: String?,
        translation: String,
        definition: String?,
        example: String?
    ): FlashcardDto {
        repository.getById(cardId, userId) ?: throw NotFoundException("Flashcard not found")

        val updated = repository.updateContent(
            cardId = cardId,
            translation = translation,
            definition = definition,
            example = example,
            word = word,
            customized = true,
            userId = userId
        )
        if (!updated) throw NotFoundException("Flashcard not found")

        val fresh = repository.getById(cardId, userId) ?: throw NotFoundException("Flashcard not found")
        logger.info("User $userId edited card $cardId ('${fresh.word}')")
        return flashcardToDto(fresh)
    }

    suspend fun setCategory(userId: Int, cardId: Int, categoryId: Int?): FlashcardDto {
        val moved = repository.setCategory(cardId, userId, categoryId)
        if (!moved) throw NotFoundException("Flashcard not found")
        val fresh = repository.getById(cardId, userId) ?: throw NotFoundException("Flashcard not found")
        return flashcardToDto(fresh)
    }

    /** Creates the cards a folder is missing, in one action. */
    suspend fun createMissingFromCategory(userId: Int, categoryId: Int?): BulkCreateFlashcardsResult {
        val outcome = repository.createMissingFromCategory(userId, categoryId)
        logger.info(
            "User $userId filled folder $categoryId: created=${outcome.created}, " +
                "moved=${outcome.moved}, skipped=${outcome.skipped}"
        )
        return BulkCreateFlashcardsResult(
            created = outcome.created,
            skipped = outcome.skipped,
            moved = outcome.moved,
        )
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
    suspend fun getDueFlashcards(userId: Int, categoryId: Int? = null): DueFlashcardsResponse {
        logger.info("Fetching due flashcards for user $userId (folder=$categoryId)")

        val dueCards = refreshFromCorpus(repository.getDueFlashcards(userId, categoryId))
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
    suspend fun getAllFlashcards(userId: Int, categoryId: Int? = null): List<FlashcardDto> {
        logger.info("Fetching all flashcards for user $userId (folder=$categoryId)")
        val flashcards = refreshFromCorpus(repository.getAllByUser(userId, categoryId))
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
    suspend fun getStatistics(userId: Int, categoryId: Int? = null): FlashcardStatistics {
        val allCards = repository.getAllByUser(userId, categoryId)
        val dueCount = repository.countDue(userId, categoryId)

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
            senseId = flashcard.senseId,
            categoryId = flashcard.categoryId,
            customized = flashcard.customized,
            repetitions = flashcard.repetitions,
            nextReview = flashcard.nextReview,
            daysUntilReview = daysUntilReview
        )
    }

    // ── Keeping a card in step with the dictionary ─────────────────────────

    /**
     * A card copies its wording from the saved word at creation and then never
     * looks again, so a definition scraped with a typo — "on which ojects are
     * placed" — stays on the card long after the dictionary itself is correct.
     *
     * This re-reads the wording from the corpus that is **already stored**:
     * [LexicalEntryRepository.findLatestByLemma] is a single indexed lookup and
     * never triggers a scrape or an LLM call, so a study session cannot turn
     * into a fan-out of network work. A word with no article yet is simply left
     * as it is and picked up on a later session.
     *
     * Applied to the due list rather than to every list: it is bounded by what
     * the user is about to see, and that is exactly the text that matters.
     */
    private suspend fun refreshFromCorpus(cards: List<Flashcard>): List<Flashcard> {
        if (cards.isEmpty()) return cards

        // A card the user edited by hand is theirs. Re-reading it from the dictionary here
        // would undo the edit on the very next study session, which is exactly the moment they
        // would see it — so hand-edited cards are excluded before any lookup happens.
        val refreshable = cards.filterNot { it.customized }
        if (refreshable.isEmpty()) return cards

        val entries = try {
            lexicalEntries.findLatestByLemmas(refreshable.map { it.word })
        } catch (e: Exception) {
            // Stale wording is not worth failing a study session over.
            logger.warn("Could not read the corpus while refreshing cards: ${e.message}")
            return cards
        }

        return cards.map { card ->
            if (card.customized) return@map card
            // По значению, к которому карточку привязали, а не по первому в статье: без этого
            // обновление из корпуса молча переписывало бы выбранный человеком смысл на другой.
            val fresh = entries[card.word.trim().lowercase()]?.let { SenseWording.of(it, card.senseId) }
                ?: return@map card

            val definitionChanged = fresh.definition != null && fresh.definition != card.definition
            val translationChanged = fresh.translation.isNotBlank() && fresh.translation != card.translation
            if (!definitionChanged && !translationChanged) return@map card

            val translation = if (translationChanged) fresh.translation else card.translation
            val definition = if (definitionChanged) fresh.definition else card.definition
            val example = fresh.example ?: card.example

            try {
                repository.updateContent(card.id, translation, definition, example)
                logger.info("Refreshed card ${card.id} ('${card.word}') from the corpus")
                card.copy(translation = translation, definition = definition, example = example)
            } catch (e: Exception) {
                logger.warn("Could not refresh card ${card.id}: ${e.message}")
                card
            }
        }
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
