package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
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
        categoryId = row[SavedWords.categoryId],
        example = row[SavedWords.example],
        senseId = row[SavedWords.senseId]
    )

    /**
     * Save a word for a user, optionally pinned to one sense of its article.
     *
     * A word that is already saved is normally returned untouched — saving twice must not
     * rewrite what is there. A [senseId] is the exception: it is an explicit act of choosing a
     * different meaning, and the row is re-pinned. Without that, the only way to change your
     * mind about which sense you meant would be to delete the word and lose its folder.
     */
    suspend fun save(
        userId: Int,
        word: String,
        translation: String? = null,
        definition: String? = null,
        example: String? = null,
        senseId: String? = null
    ): SavedWord? = dbQuery {
        val existing = SavedWords.selectAll()
            .where { (SavedWords.userId eq userId) and (SavedWords.word eq word) }
            .singleOrNull()

        if (existing != null) {
            if (senseId == null) return@dbQuery resultRowToSavedWord(existing)

            SavedWords.update({ SavedWords.id eq existing[SavedWords.id] }) {
                it[SavedWords.senseId] = senseId
                it[SavedWords.translation] = translation?.take(500)
                it[SavedWords.definition] = definition
                it[SavedWords.example] = example
            }
            SavedWords.selectAll()
                .where { SavedWords.id eq existing[SavedWords.id] }
                .single()
                .let(::resultRowToSavedWord)
        } else {
            val insertStatement = SavedWords.insert {
                it[SavedWords.userId] = userId
                it[SavedWords.word] = word
                it[SavedWords.translation] = translation?.take(500)
                it[SavedWords.definition] = definition
                it[SavedWords.example] = example
                it[SavedWords.senseId] = senseId
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
     * Writes back wording filled in from the corpus.
     *
     * Only ever called with values that were blank on the row, so this cannot overwrite
     * anything a user put there.
     *
     * ⚠️ [userId] is not decoration. This runs while rendering a saved-words list, and that list
     * now includes words reached through a group — which belong to the teacher. Without the
     * guard, opening the list would write into another account's rows.
     */
    suspend fun updateContent(
        id: Int,
        userId: Int,
        translation: String?,
        definition: String?,
        example: String? = null
    ): Boolean = dbQuery {
        SavedWords.update({ (SavedWords.id eq id) and (SavedWords.userId eq userId) }) {
            it[SavedWords.translation] = translation?.take(500)
            it[SavedWords.definition] = definition
            if (example != null) it[SavedWords.example] = example
        } > 0
    }

    /**
     * Delete a saved word
     */
    /**
     * Removes the word and the card made from it.
     *
     * ⚠️ The card goes too. It exists *because* the word was saved, and leaving it behind
     * produced a card for a word that is no longer in the vocabulary — reviewable, editable,
     * and impossible to get rid of from any screen. Deleting a word is the one moment where the
     * user has said they are done with it.
     */
    suspend fun delete(userId: Int, word: String): Boolean = dbQuery {
        val key = word.trim().lowercase()
        Flashcards.deleteWhere {
            (Flashcards.userId eq userId) and (Flashcards.word.lowerCase() eq key)
        }
        SavedWords.deleteWhere {
            (SavedWords.userId eq userId) and (SavedWords.word eq word)
        } > 0
    }

    /**
     * Check if word is saved by user
     */
    /** Every word filed in one folder, in the order it was saved. */
    suspend fun findByCategory(userId: Int, categoryId: Int): List<SavedWord> = dbQuery {
        SavedWords.selectAll()
            .where { (SavedWords.userId eq userId) and (SavedWords.categoryId eq categoryId) }
            .orderBy(SavedWords.savedAt to SortOrder.ASC)
            .map(::resultRowToSavedWord)
    }

    /**
     * Copies somebody else's words into [userId]'s account, filed under [categoryId].
     *
     * ⚠️ A word the user already has is left exactly where it is, and counted rather than moved.
     * Accepting a shared folder must not rearrange the vocabulary they built themselves — and
     * the pinned sense they chose for that word is their decision, not the sharer's.
     *
     * @return how many were added, and how many were already there.
     */
    suspend fun copyInto(userId: Int, categoryId: Int, words: List<SavedWord>): Pair<Int, Int> = dbQuery {
        val existing = SavedWords.selectAll()
            .where { SavedWords.userId eq userId }
            .map { it[SavedWords.word].trim().lowercase() }
            .toSet()

        var added = 0
        var alreadyHad = 0
        for (source in words) {
            val key = source.word.trim().lowercase()
            if (key in existing) {
                alreadyHad++
                continue
            }
            SavedWords.insert {
                it[SavedWords.userId] = userId
                it[word] = key
                it[translation] = source.translation?.take(500)
                it[definition] = source.definition
                it[example] = source.example
                // Выбранное значение переезжает вместе со словом: без него человек получил бы
                // то же слово, но с другим смыслом — то есть не ту папку, которой делились.
                it[senseId] = source.senseId
                it[SavedWords.categoryId] = categoryId
            }
            added++
        }
        added to alreadyHad
    }

    /**
     * Every word this owner has filed in any of [categoryIds].
     *
     * Reads somebody else's rows on purpose: under a group the folder stays the teacher's, and
     * the student reads through to it rather than getting a copy.
     */
    suspend fun findByCategoryIds(ownerId: Int, categoryIds: Collection<Int>): List<SavedWord> = dbQuery {
        if (categoryIds.isEmpty()) return@dbQuery emptyList()
        SavedWords.selectAll()
            .where { (SavedWords.userId eq ownerId) and (SavedWords.categoryId inList categoryIds) }
            .orderBy(SavedWords.savedAt to SortOrder.DESC)
            .map(::resultRowToSavedWord)
    }

    /** How many words each of the user's folders holds. Words filed nowhere are not counted. */
    suspend fun countByCategory(userId: Int): Map<Int, Int> = dbQuery {
        SavedWords
            .select(SavedWords.categoryId, SavedWords.id.count())
            .where { SavedWords.userId eq userId }
            .groupBy(SavedWords.categoryId)
            .mapNotNull { row ->
                row[SavedWords.categoryId]?.let { it to row[SavedWords.id.count()].toInt() }
            }
            .toMap()
    }

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
