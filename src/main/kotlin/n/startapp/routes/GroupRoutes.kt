package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.ForbiddenException
import n.startapp.exceptions.NotFoundException
import n.startapp.models.ApiResponse
import n.startapp.models.auth.CategoryDTO
import n.startapp.models.auth.SharedWordPreview
import n.startapp.models.group.AssignFolderRequest
import n.startapp.models.group.CreateGroupRequest
import n.startapp.models.group.GroupDTO
import n.startapp.models.group.GroupInvite
import n.startapp.models.group.GroupPreview
import n.startapp.models.group.JoinByCodeRequest
import n.startapp.models.group.MyGroupsResponse
import n.startapp.models.group.UpdateGroupRequest
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.GroupRepository
import n.startapp.repositories.GroupRow
import n.startapp.repositories.SavedWordRepository
import n.startapp.repositories.UserRepository
import n.startapp.services.group.FolderAccessResolver
import n.startapp.utils.EnvConfig
import n.startapp.utils.ShortToken

/**
 * Groups: a teacher, their students, the folders they hand out.
 *
 * Everything here is scoped by one of two checks — [requireOwned] for the teacher's side and
 * [requireMembership] for the student's. There is no role on the user and no role column on the
 * membership; being the owner of a row is the only authority in the feature.
 */
fun Route.groupRoutes() {
    val groups = GroupRepository()
    val users = UserRepository()
    val categories = CategoryRepository()
    val savedWords = SavedWordRepository()
    val folderAccess = FolderAccessResolver()

    /** The group, or 404 — never "403", which would confirm that the id exists. */
    suspend fun requireGroup(groupId: Int): GroupRow =
        groups.findById(groupId) ?: throw NotFoundException("Группа не найдена")

    suspend fun requireOwned(userId: Int, groupId: Int): GroupRow {
        val group = requireGroup(groupId)
        if (group.ownerId != userId) throw NotFoundException("Группа не найдена")
        return group
    }

    suspend fun requireMembership(userId: Int, groupId: Int): GroupRow {
        val group = requireGroup(groupId)
        val allowed = group.ownerId == userId || groups.isMember(userId, groupId)
        if (!allowed) throw NotFoundException("Группа не найдена")
        return group
    }

    suspend fun toDTO(group: GroupRow, viewerId: Int): GroupDTO {
        val isOwner = group.ownerId == viewerId
        return GroupDTO(
            id = group.id,
            name = group.name,
            createdAt = group.createdAt.toString(),
            isOwner = isOwner,
            memberCount = groups.memberCount(group.id),
            folderCount = groups.folderCount(group.id),
            inviteUrl = if (isOwner) group.inviteToken?.let(::inviteUrl) else null,
            joinCode = if (isOwner) group.joinCode else null,
            teacherName = if (isOwner) null else users.findById(group.ownerId)?.login
        )
    }

    authenticate("auth-jwt") {
        route("/api/groups") {

            get {
                val userId = call.userId()
                call.respond(
                    ApiResponse.success(
                        MyGroupsResponse(
                            owned = groups.findOwned(userId).map { toDTO(it, userId) },
                            joined = groups.findJoined(userId).map { toDTO(it, userId) }
                        )
                    )
                )
            }

            post {
                val userId = call.userId()
                val request = call.receive<CreateGroupRequest>()
                val name = request.name.trim()
                if (name.isBlank()) throw BadRequestException("Название группы не может быть пустым")
                if (name.length > 120) throw BadRequestException("Название группы слишком длинное")
                if (groups.countOwned(userId) >= GroupRepository.MAX_OWNED_GROUPS) {
                    throw BadRequestException(
                        "Больше ${GroupRepository.MAX_OWNED_GROUPS} групп на одного преподавателя пока нельзя"
                    )
                }

                val group = groups.create(userId, name)
                call.respond(HttpStatusCode.Created, ApiResponse.success(toDTO(group, userId)))
            }

            /**
             * Joining by the typed code. Kept above `{id}` so a group can never be named `join`
             * into a route collision.
             */
            post("/join") {
                val userId = call.userId()
                val code = call.receive<JoinByCodeRequest>().code.trim().lowercase()
                if (!ShortToken.isWellFormed(code)) throw NotFoundException("Код не подошёл")

                val group = groups.findByJoinCode(code) ?: throw NotFoundException("Код не подошёл")
                joinGroup(groups, group, userId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(toDTO(group, userId)))
            }

            get("/{id}") {
                val userId = call.userId()
                val group = requireMembership(userId, call.groupId())
                call.respond(ApiResponse.success(toDTO(group, userId)))
            }

            put("/{id}") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                val name = call.receive<UpdateGroupRequest>().name.trim()
                if (name.isBlank()) throw BadRequestException("Название группы не может быть пустым")
                groups.rename(userId, group.id, name)
                call.respond(ApiResponse.success(toDTO(group.copy(name = name), userId)))
            }

            delete("/{id}") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                groups.delete(userId, group.id)
                call.respond(ApiResponse.success("Группа удалена"))
            }

            // ── Invitation ────────────────────────────────────────────────

            post("/{id}/invite") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                val (token, code) = groups.mintInvite(userId, group.id)
                    ?: throw NotFoundException("Группа не найдена")
                call.respond(ApiResponse.success(GroupInvite(token, code, inviteUrl(token))))
            }

            get("/{id}/invite") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                val invite = groups.currentInvite(userId, group.id)
                call.respond(
                    ApiResponse.success(
                        invite?.let { (token, code) -> GroupInvite(token, code, inviteUrl(token)) }
                    )
                )
            }

            /** Revoking closes the door behind the link. Everybody already inside stays inside. */
            delete("/{id}/invite") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                groups.revokeInvite(userId, group.id)
                call.respond(ApiResponse.success("Приглашение отключено"))
            }

            // ── Members ───────────────────────────────────────────────────

            get("/{id}/members") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                call.respond(ApiResponse.success(groups.members(group.id)))
            }

            delete("/{id}/members/{userId}") {
                val ownerId = call.userId()
                val group = requireOwned(ownerId, call.groupId())
                val memberId = call.parameters["userId"]?.toIntOrNull()
                    ?: throw BadRequestException("Неверный id ученика")
                groups.removeMember(group.id, memberId)
                call.respond(ApiResponse.success("Ученик убран из группы"))
            }

            /** Leaving is the student's own act, so it is not the same route as being removed. */
            delete("/{id}/membership") {
                val userId = call.userId()
                val group = requireGroup(call.groupId())
                if (group.ownerId == userId) {
                    throw BadRequestException("Свою группу нельзя покинуть — её можно удалить")
                }
                if (!groups.removeMember(group.id, userId)) {
                    throw NotFoundException("Вы не состоите в этой группе")
                }
                call.respond(ApiResponse.success("Вы вышли из группы"))
            }

            // ── Folders ───────────────────────────────────────────────────

            get("/{id}/folders") {
                val userId = call.userId()
                val group = requireMembership(userId, call.groupId())
                val ids = groups.folderIds(group.id)
                val owner = group.ownerId
                val counts = savedWords.countByCategory(owner)
                val named = categories.findByUserId(owner).filter { it.id in ids }
                call.respond(
                    ApiResponse.success(
                        named.map { folder ->
                            CategoryDTO(
                                id = folder.id,
                                name = folder.name,
                                color = folder.color,
                                wordCount = counts[folder.id] ?: 0,
                                groupId = group.id,
                                groupName = group.name,
                                readOnly = group.ownerId != userId
                            )
                        }
                    )
                )
            }

            post("/{id}/folders") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                val categoryId = call.receive<AssignFolderRequest>().categoryId

                // Only the teacher's own folder: handing out somebody else's would publish
                // words the teacher never saw.
                folderAccess.requireOwned(userId, categoryId)

                if (!groups.assignFolder(userId, group.id, categoryId)) {
                    throw BadRequestException("Папка уже выдана этой группе")
                }
                call.respond(HttpStatusCode.Created, ApiResponse.success("Папка выдана группе"))
            }

            delete("/{id}/folders/{categoryId}") {
                val userId = call.userId()
                val group = requireOwned(userId, call.groupId())
                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                    ?: throw BadRequestException("Неверный id папки")
                if (!groups.unassignFolder(group.id, categoryId)) {
                    throw NotFoundException("Папка не выдана этой группе")
                }
                call.respond(ApiResponse.success("Папка снята с группы"))
            }
        }
    }
}

/**
 * The invitation, as the person holding the link meets it.
 *
 * The preview is readable signed out for the same reason a shared folder's is: the link is the
 * permission, and a login wall in front of an invitation makes a teacher's class look like an
 * advert. Joining needs an account, because a membership has to belong to somebody.
 */
fun Route.groupInviteRoutes() {
    val groups = GroupRepository()
    val users = UserRepository()

    // Optional auth: anonymous readers still see the preview, and a signed-in one is told
    // they are already in the class instead of being offered a door they walked through.
    authenticate("auth-jwt", optional = true) {
    get("/api/g/{token}") {
        val token = call.parameters["token"]?.trim().orEmpty()
        val group = groups.findByInviteToken(token)
            ?: throw NotFoundException("Приглашение больше не действует")

        val viewerId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
            ?.payload?.getClaim("userId")?.asInt()

        call.respond(
            ApiResponse.success(
                GroupPreview(
                    name = group.name,
                    teacherName = users.findById(group.ownerId)?.login,
                    memberCount = groups.memberCount(group.id),
                    folderCount = groups.folderCount(group.id),
                    wordCount = groups.wordCount(group.id),
                    sample = groups.sampleWords(group.id, 12).map { (word, translation) ->
                        SharedWordPreview(word = word, translation = translation)
                    },
                    alreadyMember = viewerId != null &&
                        (group.ownerId == viewerId || groups.isMember(viewerId, group.id))
                )
            )
        )
    }
    }

    authenticate("auth-jwt") {
        post("/api/g/{token}/join") {
            val userId = call.userId()
            val token = call.parameters["token"]?.trim().orEmpty()
            val group = groups.findByInviteToken(token)
                ?: throw NotFoundException("Приглашение больше не действует")

            joinGroup(groups, group, userId)
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(
                    GroupDTO(
                        id = group.id,
                        name = group.name,
                        createdAt = group.createdAt.toString(),
                        isOwner = false,
                        memberCount = groups.memberCount(group.id),
                        folderCount = groups.folderCount(group.id),
                        teacherName = users.findById(group.ownerId)?.login
                    )
                )
            )
        }
    }
}

/**
 * Puts the student in the group, or leaves them where they already were.
 *
 * Following the same link twice is not an error worth showing anybody: the outcome they wanted —
 * being in the class — is already true.
 */
private suspend fun joinGroup(groups: GroupRepository, group: GroupRow, userId: Int) {
    if (group.ownerId == userId) {
        throw BadRequestException("Это ваша собственная группа")
    }
    if (groups.isMember(userId, group.id)) return
    if (groups.memberCount(group.id) >= GroupRepository.MAX_MEMBERS) {
        throw ForbiddenException("В группе уже максимум учеников")
    }
    groups.addMember(group.id, userId)
}

/** Same origin the site is served from — see `SITE_URL`. */
private fun inviteUrl(token: String): String =
    "${EnvConfig.siteUrl.trimEnd('/')}/g/$token"

private fun ApplicationCall.groupId(): Int =
    parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Неверный id группы")
