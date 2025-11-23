package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.models.flashcard.Flashcard
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import java.time.Instant

/**
 * Repository for flashcard database operations
 */
class FlashcardRepository {

    /**
     * Create a flashcard from a saved word
     */
    suspend fun createFromSavedWord(userId: Int, savedWordId: Int): Flashcard? = dbQuery {
        // Get saved word details
        val savedWord = SavedWords.select { SavedWords.id eq savedWordId }
            .firstOrNull()
            ?: return@dbQuery null

        // Check if user owns this saved word
        if (savedWord[SavedWords.userId] != userId) {
            return@dbQuery null
        }

        // Check if flashcard already exists for this word
        val existing = Flashcards.select {
            (Flashcards.userId eq userId) and (Flashcards.savedWordId eq savedWordId)
        }.firstOrNull()

        if (existing != null) {
            return@dbQuery rowToFlashcard(existing)
        }

        // Create new flashcard
        val now = Instant.now()
        val id = Flashcards.insertAndGetId {
            it[Flashcards.userId] = userId
            it[Flashcards.savedWordId] = savedWordId
            it[word] = savedWord[SavedWords.word]
            it[translation] = savedWord[SavedWords.translation] ?: ""
            it[definition] = savedWord[SavedWords.definition]
            it[example] = null
            it[easeFactor] = 2.5f
            it[repetitions] = 0
            it[interval] = 0
            it[nextReview] = now // Due immediately
            it[lastReviewed] = null
            it[createdAt] = now
            it[updatedAt] = now
        }

        Flashcards.select { Flashcards.id eq id }
            .first()
            .let { rowToFlashcard(it) }
    }

    /**
     * Get all flashcards due for review for a user
     */
    suspend fun getDueFlashcards(userId: Int): List<Flashcard> = dbQuery {
        val now = Instant.now()
        Flashcards.select {
            (Flashcards.userId eq userId) and (Flashcards.nextReview lessEq now)
        }
            .orderBy(Flashcards.nextReview to SortOrder.ASC)
            .map { rowToFlashcard(it) }
    }

    /**
     * Get a flashcard by ID
     */
    suspend fun getById(cardId: Int, userId: Int): Flashcard? = dbQuery {
        Flashcards.select {
            (Flashcards.id eq cardId) and (Flashcards.userId eq userId)
        }
            .firstOrNull()
            ?.let { rowToFlashcard(it) }
    }

    /**
     * Update flashcard after review
     */
    suspend fun updateAfterReview(
        cardId: Int,
        userId: Int,
        easeFactor: Float,
        repetitions: Int,
        interval: Int,
        nextReview: Instant
    ): Boolean = dbQuery {
        val updated = Flashcards.update({
            (Flashcards.id eq cardId) and (Flashcards.userId eq userId)
        }) {
            it[Flashcards.easeFactor] = easeFactor
            it[Flashcards.repetitions] = repetitions
            it[Flashcards.interval] = interval
            it[Flashcards.nextReview] = nextReview
            it[lastReviewed] = Instant.now()
            it[updatedAt] = Instant.now()
        }
        updated > 0
    }

    /**
     * Get all flashcards for a user
     */
    suspend fun getAllByUser(userId: Int): List<Flashcard> = dbQuery {
        Flashcards.select { Flashcards.userId eq userId }
            .orderBy(Flashcards.createdAt to SortOrder.DESC)
            .map { rowToFlashcard(it) }
    }

    /**
     * Delete a flashcard
     */
    suspend fun delete(cardId: Int, userId: Int): Boolean = dbQuery {
        Flashcards.deleteWhere {
            (id eq cardId) and (Flashcards.userId eq userId)
        } > 0
    }

    /**
     * Get count of due flashcards
     */
    suspend fun countDue(userId: Int): Int = dbQuery {
        val now = Instant.now()
        Flashcards.select {
            (Flashcards.userId eq userId) and (Flashcards.nextReview lessEq now)
        }.count().toInt()
    }

    /**
     * Convert database row to Flashcard model
     */
    private fun rowToFlashcard(row: ResultRow) = Flashcard(
        id = row[Flashcards.id].value,
        userId = row[Flashcards.userId],
        savedWordId = row[Flashcards.savedWordId],
        word = row[Flashcards.word],
        translation = row[Flashcards.translation],
        definition = row[Flashcards.definition],
        example = row[Flashcards.example],
        easeFactor = row[Flashcards.easeFactor],
        repetitions = row[Flashcards.repetitions],
        interval = row[Flashcards.interval],
        nextReview = row[Flashcards.nextReview],
        lastReviewed = row[Flashcards.lastReviewed],
        createdAt = row[Flashcards.createdAt],
        updatedAt = row[Flashcards.updatedAt]
    )
}
