package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.SavedWords
import n.startapp.models.auth.SavedWord
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

/**
 * Repository for SavedWord CRUD operations
 */
class SavedWordRepository {

    /**
     * Convert ResultRow to SavedWord model
     */
    private fun resultRowToSavedWord(row: ResultRow): SavedWord = SavedWord(
        id = row[SavedWords.id],
        userId = row[SavedWords.userId],
        word = row[SavedWords.word],
        translation = row[SavedWords.translation],
        definition = row[SavedWords.definition],
        savedAt = row[SavedWords.savedAt],
        categoryId = row[SavedWords.categoryId]
    )

    /**
     * Save a word for a user
     */
    suspend fun save(userId: Int, word: String, translation: String? = null, definition: String? = null): SavedWord? = dbQuery {
        // Check if word already saved
        val existing = SavedWords.selectAll()
            .where { (SavedWords.userId eq userId) and (SavedWords.word eq word) }
            .singleOrNull()

        if (existing != null) {
            // Already saved, return existing
            resultRowToSavedWord(existing)
        } else {
            // Insert new saved word
            val insertStatement = SavedWords.insert {
                it[SavedWords.userId] = userId
                it[SavedWords.word] = word
                it[SavedWords.translation] = translation
                it[SavedWords.definition] = definition
            }
            insertStatement.resultedValues?.singleOrNull()?.let(::resultRowToSavedWord)
        }
    }

    /**
     * Get all saved words for a user
     */
    suspend fun findByUserId(userId: Int): List<SavedWord> = dbQuery {
        SavedWords.selectAll()
            .where { SavedWords.userId eq userId }
            .orderBy(SavedWords.savedAt to SortOrder.DESC)
            .map(::resultRowToSavedWord)
    }

    /**
     * Delete a saved word
     */
    suspend fun delete(userId: Int, word: String): Boolean = dbQuery {
        SavedWords.deleteWhere {
            (SavedWords.userId eq userId) and (SavedWords.word eq word)
        } > 0
    }

    /**
     * Check if word is saved by user
     */
    suspend fun exists(userId: Int, word: String): Boolean = dbQuery {
        SavedWords.selectAll()
            .where { (SavedWords.userId eq userId) and (SavedWords.word eq word) }
            .count() > 0
    }

    /**
     * Delete all saved words for a user
     */
    suspend fun deleteAllByUserId(userId: Int): Boolean = dbQuery {
        SavedWords.deleteWhere { SavedWords.userId eq userId } > 0
    }

    /**
     * Set or clear category for a saved word
     */
    suspend fun setCategory(userId: Int, word: String, categoryId: Int?): Boolean = dbQuery {
        SavedWords.update({ (SavedWords.userId eq userId) and (SavedWords.word eq word) }) {
            it[SavedWords.categoryId] = categoryId
        } > 0
    }
}
