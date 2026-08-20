package n.startapp.models.group

import kotlinx.serialization.Serializable
import n.startapp.models.auth.SharedWordPreview

/**
 * A group as one particular person sees it.
 *
 * [inviteUrl] and [joinCode] are present only for the owner — they are the permission to join,
 * and a student who could read them off their own group screen could hand the class to anyone.
 */
@Serializable
data class GroupDTO(
    val id: Int,
    val name: String,
    val createdAt: String,
    val isOwner: Boolean,
    val memberCount: Int,
    val folderCount: Int,
    val assignmentCount: Int = 0,
    val inviteUrl: String? = null,
    val joinCode: String? = null,
    /**
     * The teacher's login, or null when they have not set one.
     *
     * Never their email. An invite link travels further than the person it was sent to, and a
     * group screen is not a reason to disclose the address behind it.
     */
    val teacherName: String? = null
)

@Serializable
data class MyGroupsResponse(
    val owned: List<GroupDTO>,
    val joined: List<GroupDTO>
)

@Serializable
data class CreateGroupRequest(val name: String)

@Serializable
data class UpdateGroupRequest(val name: String)

/** The invitation, handed back to the teacher to pass on. */
@Serializable
data class GroupInvite(
    val token: String,
    val code: String,
    val url: String
)

@Serializable
data class JoinByCodeRequest(val code: String)

/**
 * A group as somebody sees it before deciding to join, without being signed in.
 *
 * Says what the class is and how big it is, so the decision can be made — and nothing about who
 * is in it, because the people already there did not agree to be listed to whoever holds a link.
 */
@Serializable
data class GroupPreview(
    val name: String,
    val teacherName: String? = null,
    val memberCount: Int,
    val folderCount: Int,
    val wordCount: Int,
    val sample: List<SharedWordPreview>,
    /** True when the viewer is already in this group, so the page can say so instead of offering. */
    val alreadyMember: Boolean = false
)

/** A student, as their teacher sees them. */
@Serializable
data class GroupMemberDTO(
    val userId: Int,
    val login: String?,
    val email: String,
    val joinedAt: String
)

@Serializable
data class AssignFolderRequest(val categoryId: Int)
