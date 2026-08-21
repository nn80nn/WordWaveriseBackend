package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import n.startapp.models.auth.SavedWord
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

/**
 * Repository for SavedWord CRUD operations.
 *
 * The unit of storage is a *sense*, not a headword: (user, word, sense) identifies a row, and
 * the folders it is filed under live in [SavedWordCategories]. Both of those used to be single
 * — one row per word, one folder per row — and both limits were the same mistake made twice:
 * treating «слово» as the thing being learned, when the thing being learned is a meaning, and
 * the place it is being learned for is a lesson, of which there can be several.
 */
class SavedWordRepository {

    /**
     * Convert ResultRow to SavedWord model. Folders arrive separately — see [withFolders].
     */
    private fun resultRowToSavedWord(row: ResultRow): SavedWord = SavedWord(
        id = row[SavedWords.id],
        userId = row[SavedWords.userId],
        word = row[SavedWords.word],
        translation = row[SavedWords.translation],
        definition = row[SavedWords.definition],
        savedAt = row[SavedWords.savedAt],
        example = row[SavedWords.example],
        senseId = row[SavedWords.senseId]
    )

    /**
     * Attaches each word's folders in one extra query rather than one per word.
     *
     * Must be called inside a transaction.
     */
    private fun List<SavedWord>.withFolders(): List<SavedWord> {
        if (isEmpty()) return this
        val byWord = SavedWordCategories.selectAll()
            .where { SavedWordCategories.savedWordId inList map { it.id } }
            .groupBy({ it[SavedWordCategories.savedWordId] }, { it[SavedWordCategories.categoryId] })
        return map { it.copy(categoryIds = byWord[it.id].orEmpty()) }
    }

    /** Files [savedWordId] under [categoryIds], adding only what is not already there. */
    private fun link(savedWordId: Int, categoryIds: Collection<Int>) {
        if (categoryIds.isEmpty()) return
        val already = SavedWordCategories.selectAll()
            .where { SavedWordCategories.savedWordId eq savedWordId }
            .mapTo(mutableSetOf()) { it[SavedWordCategories.categoryId] }
        for (categoryId in categoryIds.distinct()) {
            if (!already.add(categoryId)) continue
            SavedWordCategories.insert {
                it[SavedWordCategories.savedWordId] = savedWordId
                it[SavedWordCategories.categoryId] = categoryId
            }
        }
    }

    /**
     * Save one sense of a word, optionally filed into folders straight away.
     *
     * Three outcomes, and the difference between them is what makes ticking two senses in an
     * article produce two words rather than one word that keeps changing its mind:
     *
     * - a row with this exact sense already exists → it is returned, untouched. Saving twice
     *   must not rewrite what is there;
     * - the word is saved with **no** sense and a sense is now given → that row is filled in.
     *   «Слово целиком» was never a choice of meaning; it was the absence of one, and turning
     *   it into a second entry would leave the person with a duplicate they never asked for;
     * - otherwise → a new row. A different sense is a different word to learn.
     */
    suspend fun save(
        userId: Int,
        word: String,
        translation: String? = null,
        definition: String? = null,
        example: String? = null,
        senseId: String? = null,
        categoryIds: Collection<Int> = emptyList()
    ): SavedWord? = dbQuery {
        val rows = SavedWords.selectAll()
            .where { (SavedWords.userId eq userId) and (SavedWords.word eq word) }
            .toList()

        val exact = rows.firstOrNull { it[SavedWords.senseId] == senseId }
        val unpinned = if (senseId != null) rows.firstOrNull { it[SavedWords.senseId] == null } else null
        val target = exact ?: unpinned

        val id = if (target != null) {
            val existingId = target[SavedWords.id]
            // Only the "fill in the unpinned row" case rewrites anything.
            if (exact == null) {
                SavedWords.update({ SavedWords.id eq existingId }) {
                    it[SavedWords.senseId] = senseId
                    it[SavedWords.translation] = translation?.take(500)
                    it[SavedWords.definition] = definition
                    it[SavedWords.example] = example
                }
            }
            existingId
        } else {
            SavedWords.insert {
                it[SavedWords.userId] = userId
                it[SavedWords.word] = word
                it[SavedWords.translation] = translation?.take(500)
                it[SavedWords.definition] = definition
                it[SavedWords.example] = example
                it[SavedWords.senseId] = senseId
            }[SavedWords.id]
        }

        link(id, categoryIds)

        SavedWords.selectAll()
            .where { SavedWords.id eq id }
            .map(::resultRowToSavedWord)
            .withFolders()
            .singleOrNull()
    }

    /**
     * Get all saved words for a user
     */
    suspend fun findByUserId(userId: Int): List<SavedWord> = dbQuery {
        SavedWords.selectAll()
            .where { SavedWords.userId eq userId }
            .orderBy(SavedWords.savedAt to SortOrder.DESC)
            .map(::resultRowToSavedWord)
            .withFolders()
    }

    /** One entry of one user, or null when the id is somebody else's. */
    suspend fun findById(userId: Int, id: Int): SavedWord? = dbQuery {
        SavedWords.selectAll()
            .where { (SavedWords.id eq id) and (SavedWords.userId eq userId) }
            .map(::resultRowToSavedWord)
            .withFolders()
            .singleOrNull()
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
     * Removes every sense of [word], and the cards made from them.
     *
     * ⚠️ The cards go too. A card exists *because* the word was saved, and leaving it behind
     * produced a card for a word that is no longer in the vocabulary — reviewable, editable,
     * and impossible to get rid of from any screen.
     *
     * Still keyed by the headword because that is what the endpoint has taken since before
     * senses existed. Deleting one sense and keeping the others is [deleteEntry].
     */
    suspend fun delete(userId: Int, word: String): Boolean = dbQuery {
        val key = word.trim().lowercase()
        val ids = SavedWords
            .select(SavedWords.id)
            .where { (SavedWords.userId eq userId) and (SavedWords.word eq word) }
            .map { it[SavedWords.id] }

        Flashcards.deleteWhere {
            (Flashcards.userId eq userId) and (Flashcards.word.lowerCase() eq key)
        }
        if (ids.isNotEmpty()) {
            SavedWordCategories.deleteWhere { savedWordId inList ids }
        }
        SavedWords.deleteWhere {
            (SavedWords.userId eq userId) and (SavedWords.word eq word)
        } > 0
    }

    /**
     * Removes one sense, leaving the other senses of the same word alone.
     *
     * ⚠️ The card that goes with it is the one pinned to the same sense — plus, when this was
     * the *last* sense of the word, any card that was never pinned at all. A card made before
     * the word was pinned carries no sense id, and it is still this word's card: skipping it
     * would leave exactly the orphan the whole rule exists to prevent.
     */
    suspend fun deleteEntry(userId: Int, id: Int): Boolean = dbQuery {
        val row = SavedWords.selectAll()
            .where { (SavedWords.id eq id) and (SavedWords.userId eq userId) }
            .singleOrNull()
            ?: return@dbQuery false

        val key = row[SavedWords.word].trim().lowercase()
        val sense = row[SavedWords.senseId]
        val siblings = SavedWords.selectAll()
            .where {
                (SavedWords.userId eq userId) and
                    (SavedWords.word eq row[SavedWords.word]) and
                    (SavedWords.id neq id)
            }
            .count()

        Flashcards.deleteWhere {
            val sameWord = (Flashcards.userId eq userId) and (Flashcards.word.lowerCase() eq key)
            when {
                siblings == 0L -> sameWord
                sense == null -> sameWord and Op.build { Flashcards.senseId.isNull() }
                else -> sameWord and (Flashcards.senseId eq sense)
            }
        }

        SavedWordCategories.deleteWhere { savedWordId eq id }
        SavedWords.deleteWhere { SavedWords.id eq id } > 0
    }

    /** Every word filed in one folder, in the order it was saved. */
    suspend fun findByCategory(userId: Int, categoryId: Int): List<SavedWord> = dbQuery {
        val ids = SavedWordCategories
            .select(SavedWordCategories.savedWordId)
            .where { SavedWordCategories.categoryId eq categoryId }
            .map { it[SavedWordCategories.savedWordId] }
        if (ids.isEmpty()) return@dbQuery emptyList()

        SavedWords.selectAll()
            .where { (SavedWords.userId eq userId) and (SavedWords.id inList ids) }
            .orderBy(SavedWords.savedAt to SortOrder.ASC)
            .map(::resultRowToSavedWord)
            .withFolders()
    }

    /**
     * Copies somebody else's words into [userId]'s account, filed under [categoryId].
     *
     * ⚠️ A word the recipient already has is **not** moved: the folder is added to it, and it
     * is counted as one they already had. Accepting a shared folder must not rearrange the
     * vocabulary somebody built themselves — and now that a word can be in several folders,
     * respecting that no longer costs the folder its completeness. It used to: the word had to
     * be either theirs or the sharer's, and choosing correctly meant handing over an incomplete
     * copy of the folder they were sent.
     *
     * Sameness is judged by (word, sense), because that is what a saved entry is. The same
     * spelling under a meaning they have not saved is a word they do not have.
     *
     * @return how many were added, and how many were already there.
     */
    suspend fun copyInto(userId: Int, categoryId: Int, words: List<SavedWord>): Pair<Int, Int> = dbQuery {
        val existing = SavedWords.selectAll()
            .where { SavedWords.userId eq userId }
            .associateBy(
                { it[SavedWords.word].trim().lowercase() to it[SavedWords.senseId] },
                { it[SavedWords.id] }
            )

        var added = 0
        var alreadyHad = 0
        for (source in words) {
            val key = source.word.trim().lowercase() to source.senseId
            val mine = existing[key]
            if (mine != null) {
                link(mine, listOf(categoryId))
                alreadyHad++
                continue
            }
            val id = SavedWords.insert {
                it[SavedWords.userId] = userId
                it[word] = key.first
                it[translation] = source.translation?.take(500)
                it[definition] = source.definition
                it[example] = source.example
                // Выбранное значение переезжает вместе со словом: без него человек получил бы
                // то же слово, но с другим смыслом — то есть не ту папку, которой делились.
                it[senseId] = source.senseId
            }[SavedWords.id]
            link(id, listOf(categoryId))
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
        val ids = SavedWordCategories
            .select(SavedWordCategories.savedWordId)
            .where { SavedWordCategories.categoryId inList categoryIds }
            .map { it[SavedWordCategories.savedWordId] }
            .distinct()
        if (ids.isEmpty()) return@dbQuery emptyList()

        SavedWords.selectAll()
            .where { (SavedWords.userId eq ownerId) and (SavedWords.id inList ids) }
            .orderBy(SavedWords.savedAt to SortOrder.DESC)
            .map(::resultRowToSavedWord)
            .withFolders()
    }

    /** How many words each of the user's folders holds. Words filed nowhere are not counted. */
    suspend fun countByCategory(userId: Int): Map<Int, Int> = dbQuery {
        val count = SavedWordCategories.savedWordId.count()
        (SavedWordCategories innerJoin SavedWords)
            .select(SavedWordCategories.categoryId, count)
            .where { SavedWords.userId eq userId }
            .groupBy(SavedWordCategories.categoryId)
            .associate { it[SavedWordCategories.categoryId] to it[count].toInt() }
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
        val ids = SavedWords
            .select(SavedWords.id)
            .where { SavedWords.userId eq userId }
            .map { it[SavedWords.id] }
        if (ids.isNotEmpty()) {
            SavedWordCategories.deleteWhere { savedWordId inList ids }
        }
        SavedWords.deleteWhere { SavedWords.userId eq userId } > 0
    }

    /**
     * Replaces the folders of one saved entry.
     *
     * @return the entry as it now stands, or null when the id is not this user's.
     */
    suspend fun setFolders(userId: Int, id: Int, categoryIds: Collection<Int>): SavedWord? = dbQuery {
        val owned = SavedWords.selectAll()
            .where { (SavedWords.id eq id) and (SavedWords.userId eq userId) }
            .empty()
            .not()
        if (!owned) return@dbQuery null

        val wanted = categoryIds.distinct()
        SavedWordCategories.deleteWhere {
            (savedWordId eq id) and Op.build { categoryId notInList wanted.ifEmpty { listOf(-1) } }
        }
        link(id, wanted)

        SavedWords.selectAll()
            .where { SavedWords.id eq id }
            .map(::resultRowToSavedWord)
            .withFolders()
            .singleOrNull()
    }

    /**
     * Set or clear category for a saved word, by headword.
     *
     * ⚠️ Legacy, and destructive on purpose: it replaces the whole folder set of *every* sense
     * of the word. A client that speaks in single folders cannot say "add this one", and
     * guessing that it meant "add" would leave a word in a folder the user just moved it out
     * of. New clients use [setFolders].
     */
    suspend fun setCategory(userId: Int, word: String, categoryId: Int?): Boolean = dbQuery {
        val ids = SavedWords
            .select(SavedWords.id)
            .where { (SavedWords.userId eq userId) and (SavedWords.word eq word) }
            .map { it[SavedWords.id] }
        if (ids.isEmpty()) return@dbQuery false

        SavedWordCategories.deleteWhere { savedWordId inList ids }
        if (categoryId != null) ids.forEach { link(it, listOf(categoryId)) }
        true
    }
}
