package n.startapp.services.group

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Categories
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroupMembers
import n.startapp.database.tables.StudyGroups
import n.startapp.exceptions.ForbiddenException
import n.startapp.exceptions.NotFoundException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** How a folder reaches the person asking for it. */
enum class FolderRole {
    /** It is theirs. */
    OWNER,

    /** A teacher handed it to a group they are in. Readable, not editable. */
    GROUP_MEMBER
}

/**
 * A folder someone may look at, and whose rows it actually holds.
 *
 * [contentOwnerId] is the whole point of the type. Under a group, the folder's words belong to
 * the teacher, so every read that used to say `SavedWords.userId eq userId` has to ask this
 * instead — the caller and the owner of the rows are no longer the same person.
 */
data class ResolvedFolder(
    val categoryId: Int,
    val contentOwnerId: Int,
    val role: FolderRole,
    val groupId: Int?,
    val groupName: String?,
    val name: String,
    val color: String?
) {
    val readOnly: Boolean get() = role != FolderRole.OWNER
}

/**
 * The decision, on its own, so it can be read and tested without a database.
 */
object FolderAccessRules {
    /**
     * @param isOwner the folder belongs to the caller
     * @param reachedThroughGroup the folder is assigned to a group the caller is a member of
     * @return how the caller reaches the folder, or null if they cannot see it at all
     *
     * Owning wins over membership. The two should never both be true — a folder can only be
     * assigned to a group by its owner, and the owner is not a member of their own group — but
     * if they ever were, the answer that loses the least is "it is still your folder".
     */
    fun roleFor(isOwner: Boolean, reachedThroughGroup: Boolean): FolderRole? = when {
        isOwner -> FolderRole.OWNER
        reachedThroughGroup -> FolderRole.GROUP_MEMBER
        else -> null
    }
}

/**
 * Answers one question: whose rows does this `categoryId` name, and what may this user do to them.
 *
 * Nothing here is cached. Removing a student from a group has to take their access away on the
 * very next request, and a cache measured in even seconds would leave them practising a folder
 * they are no longer in.
 */
class FolderAccessResolver {

    /** Folder ids the user reaches through a group, mapped to that group. */
    suspend fun groupFolders(userId: Int): Map<Int, GroupRef> = dbQuery { groupFoldersInTx(userId) }

    /** Every folder the user may read: their own first, then the ones groups give them. */
    suspend fun visible(userId: Int): List<ResolvedFolder> = dbQuery {
        val own = Categories.selectAll()
            .where { Categories.userId eq userId }
            .orderBy(Categories.createdAt to SortOrder.ASC)
            .map { row ->
                ResolvedFolder(
                    categoryId = row[Categories.id],
                    contentOwnerId = userId,
                    role = FolderRole.OWNER,
                    groupId = null,
                    groupName = null,
                    name = row[Categories.name],
                    color = row[Categories.color]
                )
            }

        val fromGroups = groupFoldersInTx(userId)
        if (fromGroups.isEmpty()) return@dbQuery own

        val ownIds = own.mapTo(mutableSetOf()) { it.categoryId }
        val borrowed = Categories.selectAll()
            .where { Categories.id inList fromGroups.keys }
            .orderBy(Categories.createdAt to SortOrder.ASC)
            .mapNotNull { row ->
                val id = row[Categories.id]
                if (id in ownIds) return@mapNotNull null
                val group = fromGroups.getValue(id)
                ResolvedFolder(
                    categoryId = id,
                    contentOwnerId = row[Categories.userId],
                    role = FolderRole.GROUP_MEMBER,
                    groupId = group.id,
                    groupName = group.name,
                    name = row[Categories.name],
                    color = row[Categories.color]
                )
            }

        own + borrowed
    }

    /** The folder as this user reaches it, or null when they cannot reach it at all. */
    suspend fun resolve(userId: Int, categoryId: Int): ResolvedFolder? = dbQuery {
        val row = Categories.selectAll()
            .where { Categories.id eq categoryId }
            .singleOrNull()
            ?: return@dbQuery null

        val ownerId = row[Categories.userId]
        val group = if (ownerId == userId) null else groupFoldersInTx(userId)[categoryId]
        val role = FolderAccessRules.roleFor(
            isOwner = ownerId == userId,
            reachedThroughGroup = group != null
        ) ?: return@dbQuery null

        ResolvedFolder(
            categoryId = categoryId,
            contentOwnerId = ownerId,
            role = role,
            groupId = group?.id,
            groupName = group?.name,
            name = row[Categories.name],
            color = row[Categories.color]
        )
    }

    /**
     * A folder a **card** may be filed under: the user's own, or one a group gave them.
     *
     * Cards from a group folder are the student's own rows carrying the teacher's `category_id`,
     * which is what makes every existing folder filter keep working untouched.
     */
    suspend fun requireCardFolder(userId: Int, categoryId: Int?): ResolvedFolder? {
        if (categoryId == null) return null
        return resolve(userId, categoryId)
            ?: throw NotFoundException("Папка не найдена")
    }

    /**
     * A folder the user may put **words** into, or rename, or delete — only their own.
     *
     * Filing a word into a group folder would mean writing into the teacher's vocabulary, which
     * is a different act entirely from studying what is in it.
     */
    suspend fun requireOwned(userId: Int, categoryId: Int?): ResolvedFolder? {
        if (categoryId == null) return null
        val folder = resolve(userId, categoryId) ?: throw NotFoundException("Папка не найдена")
        if (folder.role != FolderRole.OWNER) {
            throw ForbiddenException("Папка группы доступна только для чтения")
        }
        return folder
    }

    /** The group a borrowed folder came from. */
    data class GroupRef(val id: Int, val name: String)

    /**
     * Deliberately a few small indexed lookups rather than one four-table join: membership is a
     * handful of rows, and two of the joins would need explicit conditions that are easy to get
     * subtly wrong in a place where being wrong means showing one student another's words.
     */
    private fun groupFoldersInTx(userId: Int): Map<Int, GroupRef> {
        val groupIds = StudyGroupMembers
            .select(StudyGroupMembers.groupId)
            .where { StudyGroupMembers.userId eq userId }
            .map { it[StudyGroupMembers.groupId] }
        if (groupIds.isEmpty()) return emptyMap()

        val groups = StudyGroups.selectAll()
            .where { (StudyGroups.id inList groupIds) and (StudyGroups.archived eq false) }
            .associate { it[StudyGroups.id] to GroupRef(it[StudyGroups.id], it[StudyGroups.name]) }
        if (groups.isEmpty()) return emptyMap()

        return StudyGroupFolders.selectAll()
            .where { StudyGroupFolders.groupId inList groups.keys }
            .mapNotNull { row ->
                val group = groups[row[StudyGroupFolders.groupId]] ?: return@mapNotNull null
                row[StudyGroupFolders.categoryId] to group
            }
            .toMap()
    }
}
