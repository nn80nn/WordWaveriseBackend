package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.services.flashcard.BulkFill
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWordCategories
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
        categoryId: Int? = null,
        senseId: String? = null,
        phonetic: String? = null,
        audioUrl: String? = null
    ): Flashcard = dbQuery {
        // ⚠️ Одно значение — одна карточка. Прямое создание вставляло безусловно, и второй
        // «в флешкарты» с другого устройства (или после переустановки приложения, где
        // локальная проверка на дубль ничего не знает) заводил вторую карточку на то же
        // слово: в повторении оно приходило дважды, а расписание расходилось между копиями.
        //
        // ⚠️ Ключ — слово *и* значение. По одному написанию «resolve/решать» отменял бы
        // создание карточки для «resolve/разлагать»: человек отметил два значения как два
        // слова, а колода молча оставалась бы с одним.
        Flashcards.select {
            (Flashcards.userId eq userId) and
                (Flashcards.word.lowerCase() eq word.trim().lowercase()) and
                senseMatches(senseId)
        }.firstOrNull()?.let { return@dbQuery rowToFlashcard(it) }

        val now = Instant.now()
        val id = Flashcards.insertAndGetId {
            it[Flashcards.userId] = userId
            it[savedWordId] = null
            it[Flashcards.categoryId] = categoryId
            it[Flashcards.word] = word
            it[Flashcards.translation] = translation
            it[Flashcards.definition] = definition
            it[Flashcards.example] = example
            it[Flashcards.senseId] = senseId
            it[Flashcards.phonetic] = phonetic
            it[Flashcards.audioUrl] = audioUrl
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
        userId: Int? = null,
        /**
         * Pronunciation is written only by the corpus refresh, which passes [rewritePronunciation].
         * The hand editor leaves it alone: a user correcting a translation is not telling us the
         * word sounds different, and blanking the recording would be a silent side effect.
         */
        rewritePronunciation: Boolean = false,
        phonetic: String? = null,
        audioUrl: String? = null
    ): Boolean = dbQuery {
        val target: Op<Boolean> =
            if (userId == null) (Flashcards.id eq cardId)
            else (Flashcards.id eq cardId) and (Flashcards.userId eq userId)

        Flashcards.update({ target }) {
            it[Flashcards.translation] = translation
            it[Flashcards.definition] = definition
            it[Flashcards.example] = example
            if (word != null) it[Flashcards.word] = word
            if (rewritePronunciation) {
                it[Flashcards.phonetic] = phonetic
                it[Flashcards.audioUrl] = audioUrl
            }
            if (customized) {
                it[Flashcards.customized] = true
                it[updatedAt] = Instant.now()
            }
        } > 0
    }

    /**
     * Re-points the card for [word] at another sense, and rewrites what it says.
     *
     * Choosing a different meaning for a saved word has to reach its card, or the two disagree:
     * the list shows "разлагать" while the card still drills "решать", and the next review
     * teaches the meaning the user just rejected.
     *
     * ⚠️ A hand-edited card is left alone. `customized` means the wording is the user's own, and
     * it outranks anything derived — the same rule the corpus refresh follows.
     *
     * The schedule is untouched: this changes what a card says, not what it has learned about
     * the learner.
     *
     * ⚠️ Only a card that is not pinned yet, or is already pinned to [senseId], is touched. A
     * card drilling another sense of the same spelling belongs to another saved entry — saving
     * «resolve/разлагать» must not quietly rewrite the card the person has been reviewing for
     * «resolve/решать».
     */
    suspend fun repinToSense(
        userId: Int,
        word: String,
        senseId: String,
        translation: String?,
        definition: String?,
        example: String?,
        phonetic: String? = null,
        audioUrl: String? = null
    ): Boolean = dbQuery {
        Flashcards.update({
            (Flashcards.userId eq userId) and
                (Flashcards.word.lowerCase() eq word.trim().lowercase()) and
                (Flashcards.senseId.isNull() or (Flashcards.senseId eq senseId)) and
                (Flashcards.customized eq false)
        }) {
            it[Flashcards.senseId] = senseId
            if (!translation.isNullOrBlank()) it[Flashcards.translation] = translation
            it[Flashcards.definition] = definition
            it[Flashcards.example] = example
            // Значение могло переехать в другую часть речи, а у омографов она звучит иначе.
            it[Flashcards.phonetic] = phonetic
            it[Flashcards.audioUrl] = audioUrl
            it[updatedAt] = Instant.now()
        } > 0
    }

    /**
     * Takes a loose card into the folder its word just moved to.
     *
     * Filing a word and filing its card were separate acts, so a word moved into a folder left
     * its card behind in no folder at all. The folder then looked empty, and "создать карточки
     * из папки" answered "карточки для этих слов уже есть" — technically true and useless.
     *
     * ⚠️ Only a *loose* card follows, which is [BulkFill]'s rule applied at the moment the word
     * moves rather than only when a folder is bulk-filled. A card sitting in another real folder
     * was put there by somebody, and filing a word has no business emptying a different folder.
     *
     * @return true when a card actually moved.
     */
    suspend fun followWordIntoFolder(
        userId: Int,
        word: String,
        categoryId: Int?,
        /**
         * Which card. null means «любая карточка этого слова» — what the folder move by
         * headword has always meant, and what it still has to mean for a client that cannot
         * name a sense.
         */
        senseId: String? = null
    ): Boolean = dbQuery {
        val key = word.trim().lowercase()
        val row = Flashcards.select {
            val sameWord = (Flashcards.userId eq userId) and (Flashcards.word.lowerCase() eq key)
            if (senseId == null) sameWord else sameWord and senseMatches(senseId)
        }.firstOrNull() ?: return@dbQuery false

        if (BulkFill.actionFor(row[Flashcards.categoryId], categoryId) != BulkFill.Action.ADOPT) {
            return@dbQuery false
        }

        Flashcards.update({ Flashcards.id eq row[Flashcards.id] }) {
            it[Flashcards.categoryId] = categoryId
            it[updatedAt] = Instant.now()
        } > 0
    }

    /** Moves a card between folders without touching its schedule or its wording. */
    suspend fun setCategory(cardId: Int, userId: Int, categoryId: Int?): Boolean = dbQuery {
        Flashcards.update({ (Flashcards.id eq cardId) and (Flashcards.userId eq userId) }) {
            it[Flashcards.categoryId] = categoryId
            it[updatedAt] = Instant.now()
        } > 0
    }

    /**
     * Makes a card out of a saved word — the reader's own, or one a group lends them.
     *
     * A borrowed word is recorded as `savedWordId = null`, never as a pointer to the row it came
     * from. That row belongs to the teacher, and `SavedWordRepository.delete` only removes the
     * cards of the word's *owner*: a student's card pointing at it would leave the teacher unable
     * to delete their own word.
     *
     * @param reachableFolderIds folders this reader gets through a group. Anything outside them
     *   that is not theirs is simply not found.
     */
    suspend fun createFromSavedWord(
        userId: Int,
        savedWordId: Int,
        reachableFolderIds: Set<Int> = emptySet()
    ): Flashcard? = dbQuery {
        val savedWord = SavedWords.select { SavedWords.id eq savedWordId }
            .firstOrNull()
            ?: return@dbQuery null

        val folders = SavedWordCategories
            .select(SavedWordCategories.categoryId)
            .where { SavedWordCategories.savedWordId eq savedWordId }
            .map { it[SavedWordCategories.categoryId] }

        val ownWord = savedWord[SavedWords.userId] == userId
        val borrowed = !ownWord && folders.any { it in reachableFolderIds }
        if (!ownWord && !borrowed) {
            return@dbQuery null
        }

        // One sense, one card — matched on the word and the sense rather than on the saved row,
        // because a card made from a group folder has no saved row of its own to match against.
        val existing = Flashcards.select {
            (Flashcards.userId eq userId) and
                (Flashcards.word.lowerCase() eq savedWord[SavedWords.word].trim().lowercase()) and
                senseMatches(savedWord[SavedWords.senseId])
        }.firstOrNull()

        if (existing != null) {
            return@dbQuery rowToFlashcard(existing)
        }

        // Create new flashcard
        val now = Instant.now()
        val id = Flashcards.insertAndGetId {
            it[Flashcards.userId] = userId
            it[Flashcards.savedWordId] = if (ownWord) savedWordId else null
            // A card starts in the folder its word lives in: creating cards from a folder and
            // then finding them unfiled would make the folder filter useless the moment it matters.
            //
            // ⚠️ A card has one folder while a word may have several, so the first is taken —
            // for a borrowed word, the first the reader can actually reach. Spreading the card
            // across folders would mean several cards for one meaning, and the whole point of a
            // card is that a meaning is reviewed on one schedule.
            it[categoryId] =
                if (borrowed) folders.firstOrNull { f -> f in reachableFolderIds }
                else folders.firstOrNull()
            it[word] = savedWord[SavedWords.word]
            it[translation] = savedWord[SavedWords.translation] ?: ""
            it[definition] = savedWord[SavedWords.definition]
            // Карточка о том же значении, что и слово: без переноса пина обновление из корпуса
            // на первой же сессии переписало бы её первым значением статьи.
            it[example] = savedWord[SavedWords.example]
            it[senseId] = savedWord[SavedWords.senseId]
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
     * Creates the cards a folder is missing, and pulls in the ones that exist but sit loose.
     *
     * Matching is by word rather than by `savedWordId` because cards created before folders
     * existed, or created by hand from the search screen, carry no saved-word link — going by
     * the id alone would hand the user a second copy of every such word.
     *
     * A word can already have a card that lives outside any folder: it was made before the
     * word was filed. Counting that as "already exists" left the folder empty while the button
     * reported there was nothing to do — the folder stayed unpracticable and the user had no
     * way forward. Such a card is adopted into the folder instead of being duplicated, so its
     * review history survives. A card sitting in a *different* real folder is left alone: that
     * placement was somebody's decision, and filling one folder must not empty another.
     *
     * @return counts of created, adopted and skipped words.
     */
    suspend fun createMissingFromCategory(
        userId: Int,
        categoryId: Int?,
        contentOwnerId: Int = userId
    ): BulkOutcome = dbQuery {
        // Under a group the folder stays the teacher's, so the words are read from their rows
        // while the cards are made for whoever asked.
        val allOwn = SavedWords.select { SavedWords.userId eq contentOwnerId }.toList()
        val foldersByWord = SavedWordCategories.selectAll()
            .where { SavedWordCategories.savedWordId inList allOwn.map { it[SavedWords.id] } }
            .groupBy({ it[SavedWordCategories.savedWordId] }, { it[SavedWordCategories.categoryId] })

        val words = when (categoryId) {
            null -> allOwn
            UNCATEGORIZED_CATEGORY_ID -> allOwn.filter { foldersByWord[it[SavedWords.id]].isNullOrEmpty() }
            else -> allOwn.filter { categoryId in foldersByWord[it[SavedWords.id]].orEmpty() }
        }
        val borrowed = contentOwnerId != userId

        // Карточка на значение может быть только одна, поэтому по ключу держим её папку и id.
        // ⚠️ Ключ — написание и значение: по одному написанию два отмеченных значения одного
        // слова делили бы одну карточку, то есть второе просто не попадало бы в колоду.
        val existing = HashMap<Pair<String, String?>, Pair<Int, Int?>>()
        Flashcards.select { Flashcards.userId eq userId }.forEach {
            existing[it[Flashcards.word].trim().lowercase() to it[Flashcards.senseId]] =
                it[Flashcards.id].value to it[Flashcards.categoryId]
        }

        var created = 0
        var moved = 0
        var skipped = 0
        val now = Instant.now()

        words.forEach { row ->
            val word = row[SavedWords.word]
            val key = word.trim().lowercase() to row[SavedWords.senseId]
            // Filling one named folder files the cards there; filling «все папки» sends each
            // card after its own word, and an unfiled word makes an unfiled card.
            val target = when (categoryId) {
                null -> foldersByWord[row[SavedWords.id]]?.firstOrNull()
                UNCATEGORIZED_CATEGORY_ID -> null
                else -> categoryId
            }
            // Точное значение, иначе — карточка, заведённая до того, как слово вообще было
            // привязано к значению: она про это слово и её расписание надо сохранить, а не
            // завести рядом вторую. Ключ снимается, чтобы её же не «усыновило» и второе
            // значение того же слова.
            val unpinnedKey = key.first to null
            val card = existing[key] ?: existing[unpinnedKey]?.also { existing.remove(unpinnedKey) }
            if (card != null) {
                val (cardId, cardCategory) = card
                if (BulkFill.actionFor(cardCategory, target) == BulkFill.Action.ADOPT) {
                    Flashcards.update({ Flashcards.id eq cardId }) {
                        it[Flashcards.categoryId] = target
                        it[updatedAt] = now
                    }
                    existing[key] = cardId to target
                    moved++
                } else {
                    skipped++
                }
                return@forEach
            }
            val newId = Flashcards.insertAndGetId {
                it[Flashcards.userId] = userId
                it[savedWordId] = if (borrowed) null else row[SavedWords.id]
                it[Flashcards.categoryId] = target
                it[Flashcards.word] = word
                it[translation] = row[SavedWords.translation] ?: ""
                it[definition] = row[SavedWords.definition]
                it[example] = row[SavedWords.example]
                it[senseId] = row[SavedWords.senseId]
                it[easeFactor] = 2.5f
                it[repetitions] = 0
                it[interval] = 0
                it[nextReview] = now
                it[lastReviewed] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            // Одно и то же слово может лежать в папке дважды — без этого второй проход
            // создал бы ему вторую карточку.
            existing[key] = newId.value to target
            created++
        }

        BulkOutcome(created = created, moved = moved, skipped = skipped)
    }

    /** What one bulk fill did, before it is turned into an API response. */
    data class BulkOutcome(val created: Int, val moved: Int, val skipped: Int)

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

    /**
     * «Эта карточка про это значение».
     *
     * A card made before the word was pinned carries no sense id, and it is still the card for
     * whatever sense the word is now pinned to — the alternative is a second card for a word
     * that already has one, which is exactly the duplicate the schedule cannot survive.
     */
    private fun senseMatches(senseId: String?): Op<Boolean> =
        if (senseId == null) Op.build { Flashcards.senseId.isNull() }
        else Op.build { Flashcards.senseId.isNull() or (Flashcards.senseId eq senseId) }

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
        senseId = row[Flashcards.senseId],
        phonetic = row[Flashcards.phonetic],
        audioUrl = row[Flashcards.audioUrl],
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
