package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.models.auth.UNCATEGORIZED_CATEGORY_ID
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
     * Create a flashcard directly
     */
    suspend fun create(
        userId: Int,
        word: String,
        translation: String,
        definition: String?,
        example: String?,
        categoryId: Int? = null
    ): Flashcard = dbQuery {
        val now = Instant.now()
        val id = Flashcards.insertAndGetId {
            it[Flashcards.userId] = userId
            it[savedWordId] = null
            it[Flashcards.categoryId] = categoryId
            it[Flashcards.word] = word
            it[Flashcards.translation] = translation
            it[Flashcards.definition] = definition
            it[Flashcards.example] = example
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
     * Rewrites what a card *says* and leaves what the scheduler *knows* alone —
     * ease factor, repetitions and the next due date are untouched, so a card
     * whose wording is corrected does not lose its place in the rotation.
     *
     * [customized] separates the two callers. The corpus refresh passes false and must never
     * claim a card as hand-edited; the edit endpoint passes true, which is what afterwards keeps
     * the refresh away from this row.
     */
    suspend fun updateContent(
        cardId: Int,
        translation: String,
        definition: String?,
        example: String?,
        word: String? = null,
        customized: Boolean = false,
        userId: Int? = null
    ): Boolean = dbQuery {
        val target: Op<Boolean> =
            if (userId == null) (Flashcards.id eq cardId)
            else (Flashcards.id eq cardId) and (Flashcards.userId eq userId)

        Flashcards.update({ target }) {
            it[Flashcards.translation] = translation
            it[Flashcards.definition] = definition
            it[Flashcards.example] = example
            if (word != null) it[Flashcards.word] = word
            if (customized) {
                it[Flashcards.customized] = true
                it[updatedAt] = Instant.now()
            }
        } > 0
    }

    /** Moves a card between folders without touching its schedule or its wording. */
    suspend fun setCategory(cardId: Int, userId: Int, categoryId: Int?): Boolean = dbQuery {
        Flashcards.update({ (Flashcards.id eq cardId) and (Flashcards.userId eq userId) }) {
            it[Flashcards.categoryId] = categoryId
            it[updatedAt] = Instant.now()
        } > 0
    }

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
            // A card starts in the folder its word lives in: creating cards from a folder and
            // then finding them unfiled would make the folder filter useless the moment it matters.
            it[categoryId] = savedWord[SavedWords.categoryId]
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
     * Creates a card for every saved word in [categoryId] that does not have one yet.
     *
     * Matching is by word rather than by `savedWordId` because cards created before folders
     * existed, or created by hand from the search screen, carry no saved-word link — going by
     * the id alone would hand the user a second copy of every such word.
     *
     * @return created count to skipped count.
     */
    suspend fun createMissingFromCategory(userId: Int, categoryId: Int?): Pair<Int, Int> = dbQuery {
        val words = SavedWords.select {
            (SavedWords.userId eq userId) and savedWordCategoryClause(categoryId)
        }.toList()

        val existing = Flashcards.select { Flashcards.userId eq userId }
            .map { it[Flashcards.word].trim().lowercase() }
            .toHashSet()

        var created = 0
        var skipped = 0
        val now = Instant.now()

        words.forEach { row ->
            val word = row[SavedWords.word]
            if (!existing.add(word.trim().lowercase())) {
                skipped++
                return@forEach
            }
            Flashcards.insertAndGetId {
                it[Flashcards.userId] = userId
                it[savedWordId] = row[SavedWords.id]
                it[Flashcards.categoryId] = row[SavedWords.categoryId]
                it[Flashcards.word] = word
                it[translation] = row[SavedWords.translation] ?: ""
                it[definition] = row[SavedWords.definition]
                it[example] = null
                it[easeFactor] = 2.5f
                it[repetitions] = 0
                it[interval] = 0
                it[nextReview] = now
                it[lastReviewed] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            created++
        }

        created to skipped
    }

    /**
     * Get all flashcards due for review for a user
     */
    suspend fun getDueFlashcards(userId: Int, categoryId: Int? = null): List<Flashcard> = dbQuery {
        val now = Instant.now()
        Flashcards.select {
            (Flashcards.userId eq userId) and (Flashcards.nextReview lessEq now) and
                categoryClause(categoryId)
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
    suspend fun getAllByUser(userId: Int, categoryId: Int? = null): List<Flashcard> = dbQuery {
        Flashcards.select { (Flashcards.userId eq userId) and categoryClause(categoryId) }
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
    suspend fun countDue(userId: Int, categoryId: Int? = null): Int = dbQuery {
        val now = Instant.now()
        Flashcards.select {
            (Flashcards.userId eq userId) and (Flashcards.nextReview lessEq now) and
                categoryClause(categoryId)
        }.count().toInt()
    }

    /** How many cards each folder holds, plus the unfiled count under [UNCATEGORIZED_CATEGORY_ID]. */
    suspend fun countByCategory(userId: Int): Map<Int, Int> = dbQuery {
        Flashcards.select { Flashcards.userId eq userId }
            .groupingBy { it[Flashcards.categoryId] ?: UNCATEGORIZED_CATEGORY_ID }
            .eachCount()
    }

    /**
     * Absent means every folder, [UNCATEGORIZED_CATEGORY_ID] means the cards in no folder.
     * The same three-way reading applies to every endpoint that takes `categoryId`.
     */
    private fun categoryClause(categoryId: Int?): Op<Boolean> = when (categoryId) {
        null -> Op.TRUE
        UNCATEGORIZED_CATEGORY_ID -> Op.build { Flashcards.categoryId.isNull() }
        else -> Op.build { Flashcards.categoryId eq categoryId }
    }

    private fun savedWordCategoryClause(categoryId: Int?): Op<Boolean> = when (categoryId) {
        null -> Op.TRUE
        UNCATEGORIZED_CATEGORY_ID -> Op.build { SavedWords.categoryId.isNull() }
        else -> Op.build { SavedWords.categoryId eq categoryId }
    }

    /**
     * Convert database row to Flashcard model
     */
    private fun rowToFlashcard(row: ResultRow) = Flashcard(
        id = row[Flashcards.id].value,
        userId = row[Flashcards.userId],
        savedWordId = row[Flashcards.savedWordId],
        categoryId = row[Flashcards.categoryId],
        word = row[Flashcards.word],
        translation = row[Flashcards.translation],
        definition = row[Flashcards.definition],
        example = row[Flashcards.example],
        easeFactor = row[Flashcards.easeFactor],
        repetitions = row[Flashcards.repetitions],
        interval = row[Flashcards.interval],
        nextReview = row[Flashcards.nextReview],
        lastReviewed = row[Flashcards.lastReviewed],
        customized = row[Flashcards.customized],
        createdAt = row[Flashcards.createdAt],
        updatedAt = row[Flashcards.updatedAt]
    )
}
