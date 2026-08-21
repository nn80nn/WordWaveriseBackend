package n.startapp.models.auth

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One saved word — meaning one *sense* of one word.
 *
 * The row is identified by (user, word, sense), not by the spelling alone. `resolve` as
 * «решать» and `resolve` as «разлагать» are two entries here, because they are two things to
 * learn: different definition, different example, different forms, and — often — a different
 * part of speech and a different sound.
 */
data class SavedWord(
    val id: Int,
    val userId: Int,
    val word: String,
    val translation: String?,
    val definition: String?,
    val savedAt: Instant,
    /** Every folder this entry is filed under; empty means it is filed nowhere. */
    val categoryIds: List<Int> = emptyList(),
    val example: String? = null,
    /** The sense of the article the user pinned, or null when they saved the word as a whole. */
    val senseId: String? = null
) {
    /**
     * The one folder a client built before folders were a list can understand.
     *
     * Derived, never stored: two places holding «the folder» is how the list and the folder
     * chips start disagreeing about the same word.
     */
    val categoryId: Int? get() = categoryIds.firstOrNull()
}

/**
 * SavedWord DTO for client responses
 */
@Serializable
data class SavedWordDTO(
    val id: Int,
    val word: String,
    val translation: String?,
    val definition: String?,
    val savedAt: String,

    /**
     * The first folder, for clients that predate multi-folder saving.
     *
     * Kept because the Android app in the Play Store reads this field and would otherwise show
     * every word as unfiled. New code reads [categoryIds].
     */
    val categoryId: Int? = null,
    val categoryIds: List<Int> = emptyList(),

    val example: String? = null,
    val senseId: String? = null,

    /**
     * Set when the word reaches this reader through a group folder instead of being their own.
     *
     * ⚠️ A read-only word must not offer delete or "move to folder" in any client: the row is
     * the teacher's, and the reader has no business rearranging somebody else's vocabulary.
     */
    val groupId: Int? = null,
    val readOnly: Boolean = false
)

/**
 * Request to save a word
 */
@Serializable
data class SaveWordRequest(
    val word: String,
    val translation: String? = null,
    val definition: String? = null,
    /**
     * Pins the word to one sense of its article. The wording is then taken from the corpus
     * server-side and the [translation]/[definition] sent alongside are ignored: two clients
     * that pick the same sense must end up with the same card, and only the server can promise
     * that.
     *
     * ⚠️ Saving the same word under a *different* sense now creates a second entry rather than
     * re-pinning the first. Ticking two senses in an article is a request for two words, which
     * is what they are. The one exception is a word saved with no sense at all: pinning that
     * one fills it in, because «слово целиком» was never a choice of meaning, it was the
     * absence of one.
     */
    val senseId: String? = null,

    /** File it straight away. Absent means «никуда», which is not the same as an empty list. */
    val categoryIds: List<Int>? = null
)

/**
 * Request to move word to a category
 */
@Serializable
data class SetWordCategoryRequest(
    val categoryId: Int?  // null = remove from category
)

/** Replaces the whole folder set of one saved entry. An empty list files it nowhere. */
@Serializable
data class SetWordFoldersRequest(
    val categoryIds: List<Int> = emptyList()
)

/**
 * Response for saved words list
 */
@Serializable
data class SavedWordsResponse(
    val words: List<SavedWordDTO>
)

/**
 * Convert SavedWord to SavedWordDTO
 */
fun SavedWord.toDTO(groupId: Int? = null, readOnly: Boolean = false): SavedWordDTO = SavedWordDTO(
    id = id,
    word = word,
    translation = translation,
    definition = definition,
    savedAt = savedAt.toString(),
    categoryId = categoryIds.firstOrNull(),
    categoryIds = categoryIds,
    example = example,
    senseId = senseId,
    groupId = groupId,
    readOnly = readOnly
)
