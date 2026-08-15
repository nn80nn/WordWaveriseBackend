package n.startapp.models.auth

import kotlinx.serialization.Serializable

/**
 * The `categoryId` value meaning "not in any folder".
 *
 * Every endpoint that filters by folder uses the same three-way convention — parameter absent
 * means "all folders", this value means "no folder", anything else is a folder id — so a client
 * never has to special-case one endpoint against another.
 */
const val UNCATEGORIZED_CATEGORY_ID = -1

@Serializable
data class CategoryDTO(
    val id: Int,
    val name: String,
    val color: String?,
    val wordCount: Int = 0
)

@Serializable
data class CreateCategoryRequest(
    val name: String,
    val color: String? = null
)

@Serializable
data class UpdateCategoryRequest(
    val name: String
)
