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
    val wordCount: Int = 0,

    /**
     * Set when the folder reaches this reader through a group rather than being their own.
     *
     * The words stay the teacher's; the reader may study them and build cards from them, and
     * nothing else. Defaulted so that a client built before groups existed is unaffected.
     */
    val groupId: Int? = null,
    val groupName: String? = null,
    val readOnly: Boolean = false
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
