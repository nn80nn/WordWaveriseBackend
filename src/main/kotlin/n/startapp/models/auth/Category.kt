package n.startapp.models.auth

import kotlinx.serialization.Serializable

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
