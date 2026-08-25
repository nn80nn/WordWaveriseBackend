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
    /**
     * Words this folder holds — **including its children's**, when it is a group.
     *
     * The same number the folder filter produces, deliberately: a group showing "0 слов" next to
     * a working «практиковать» button is a number nobody can act on.
     */
    val wordCount: Int = 0,

    /**
     * The folder this one is filed under, or null when it stands on its own.
     *
     * One level: a folder with a parent can never be one. Clients render the tree from this
     * field alone — there is no separate children list to keep in step with it.
     */
    val parentId: Int? = null,

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
    val color: String? = null,
    /** Creates the folder inside this group. Must be a folder of the caller's, and a root one. */
    val parentId: Int? = null
)

@Serializable
data class UpdateCategoryRequest(
    val name: String
)

/**
 * Moves a folder into a group, or back out of one (`parentId = null`).
 *
 * Its own endpoint rather than a field on [UpdateCategoryRequest]: "rename" sends the whole
 * request, and a nullable field there could not tell "move to the root" apart from "leave the
 * parent alone" — the two look identical on the wire, and one of them silently ungroups folders.
 */
@Serializable
data class SetParentRequest(
    val parentId: Int? = null
)
