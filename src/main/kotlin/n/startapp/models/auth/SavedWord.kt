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
    val categoryId: Int? = null
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
    val categoryId: Int? = null
)

/**
 * Request to save a word
 */
@Serializable
data class SaveWordRequest(
    val word: String,
    val translation: String? = null,
    val definition: String? = null
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
fun SavedWord.toDTO(): SavedWordDTO = SavedWordDTO(
    id = id,
    word = word,
    translation = translation,
    definition = definition,
    savedAt = savedAt.toString(),
    categoryId = categoryId
)
