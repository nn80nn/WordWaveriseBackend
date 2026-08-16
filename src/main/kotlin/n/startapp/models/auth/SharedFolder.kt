package n.startapp.models.auth

import kotlinx.serialization.Serializable

/**
 * A folder as somebody else sees it before deciding to take it.
 *
 * Deliberately says nothing about who made it: a share link travels further than the person who
 * sent it, and the folder's contents are the only thing the recipient needs in order to choose.
 * Adding an author would turn every shared link into a small disclosure of who is studying what.
 */
@Serializable
data class SharedFolderPreview(
    val name: String,
    val wordCount: Int,
    /** The first words, so the recipient can tell what kind of folder this is. */
    val sample: List<SharedWordPreview>
)

@Serializable
data class SharedWordPreview(
    val word: String,
    val translation: String? = null
)

/** The link itself, handed back to the owner to send on. */
@Serializable
data class ShareLink(
    val token: String,
    val url: String
)

/**
 * What taking a copy actually did.
 *
 * ⚠️ [alreadyHad] is not a failure: a word the recipient had already saved is left exactly where
 * it was. Pulling it into the new folder would rearrange the vocabulary they built themselves —
 * accepting somebody's folder must not silently reorganise your own.
 */
@Serializable
data class ImportResult(
    val categoryId: Int,
    val name: String,
    val added: Int,
    val alreadyHad: Int
)
