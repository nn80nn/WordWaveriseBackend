package n.startapp.models.auth

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * SavedWord data model
 */
data class SavedWord(
    val id: Int,
    val userId: Int,
    val word: String,
    val translation: String?,
    val definition: String?,
    val savedAt: Instant,
    val categoryId: Int? = null,
    val example: String? = null,
    /** The sense of the article the user pinned, or null when they saved the word as a whole. */
    val senseId: String? = null
)

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
    val categoryId: Int? = null,
    val example: String? = null,
    val senseId: String? = null,

    /**
     * Set when the word reaches this reader through a group folder instead of being their own.
     *
     * ⚠️ A read-only word must not offer delete or "move to folder" in any client: both of those
     * are keyed by the headword, so they would land on the reader's own row for the same word.
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
     * that. Saving a word that is already saved with a different sense re-pins it.
     */
    val senseId: String? = null
)

/**
 * Request to move word to a category
 */
@Serializable
data class SetWordCategoryRequest(
    val categoryId: Int?  // null = remove from category
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
    categoryId = categoryId,
    example = example,
    senseId = senseId,
    groupId = groupId,
    readOnly = readOnly
)
