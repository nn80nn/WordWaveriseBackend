package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Assignments
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PracticeAttempts
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroupMembers
import n.startapp.database.tables.StudyGroups
import n.startapp.database.tables.Users
import n.startapp.models.group.GroupMemberDTO
import n.startapp.services.group.GroupSweep
import n.startapp.utils.ShortToken
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** A group row, before it is shaped for any particular reader. */
data class GroupRow(
    val id: Int,
    val ownerId: Int,
    val name: String,
    val inviteToken: String?,
    val joinCode: String?,
    val createdAt: Instant
)

class GroupRepository {

    /**
     * Bounds, so that one account cannot turn the feature into a load generator. Both are
     * generous for the thing being modelled — a person teaching classes — and both are one
     * constant each to change.
     */
    companion object {
        const val MAX_OWNED_GROUPS = 5
        const val MAX_MEMBERS = 100
    }

    private fun rowToGroup(row: org.jetbrains.exposed.sql.ResultRow) = GroupRow(
        id = row[StudyGroups.id],
        ownerId = row[StudyGroups.ownerId],
        name = row[StudyGroups.name],
        inviteToken = row[StudyGroups.inviteToken],
        joinCode = row[StudyGroups.joinCode],
        createdAt = row[StudyGroups.createdAt]
    )

    // ── Groups ────────────────────────────────────────────────────────────

    suspend fun countOwned(ownerId: Int): Int = dbQuery {
        StudyGroups.selectAll()
            .where { (StudyGroups.ownerId eq ownerId) and (StudyGroups.archived eq false) }
            .count().toInt()
    }

    suspend fun create(ownerId: Int, name: String): GroupRow = dbQuery {
        val stmt = StudyGroups.insert {
            it[StudyGroups.ownerId] = ownerId
            it[StudyGroups.name] = name.trim()
        }
        GroupRow(
            id = stmt[StudyGroups.id],
            ownerId = ownerId,
            name = name.trim(),
            inviteToken = null,
            joinCode = null,
            createdAt = stmt[StudyGroups.createdAt]
        )
    }

    suspend fun findById(groupId: Int): GroupRow? = dbQuery {
        StudyGroups.selectAll()
            .where { (StudyGroups.id eq groupId) and (StudyGroups.archived eq false) }
            .singleOrNull()
            ?.let(::rowToGroup)
    }

    suspend fun findOwned(ownerId: Int): List<GroupRow> = dbQuery {
        StudyGroups.selectAll()
            .where { (StudyGroups.ownerId eq ownerId) and (StudyGroups.archived eq false) }
            .orderBy(StudyGroups.createdAt to SortOrder.ASC)
            .map(::rowToGroup)
    }

    suspend fun findJoined(userId: Int): List<GroupRow> = dbQuery {
        val ids = StudyGroupMembers
            .select(StudyGroupMembers.groupId)
            .where { StudyGroupMembers.userId eq userId }
            .map { it[StudyGroupMembers.groupId] }
        if (ids.isEmpty()) return@dbQuery emptyList()

        StudyGroups.selectAll()
            .where { (StudyGroups.id inList ids) and (StudyGroups.archived eq false) }
            .orderBy(StudyGroups.createdAt to SortOrder.ASC)
            .map(::rowToGroup)
    }

    suspend fun rename(ownerId: Int, groupId: Int, name: String): Boolean = dbQuery {
        StudyGroups.update({ (StudyGroups.id eq groupId) and (StudyGroups.ownerId eq ownerId) }) {
            it[StudyGroups.name] = name.trim()
        } > 0
    }

    /**
     * Removes the group and everything that only existed because of it.
     *
     * Students keep the cards they built — they are the student's own rows, and the work that
     * went into them was theirs. They lose the folder, which was never theirs to begin with.
     */
    suspend fun delete(ownerId: Int, groupId: Int): Boolean = dbQuery {
        val owned = StudyGroups.selectAll()
            .where { (StudyGroups.id eq groupId) and (StudyGroups.ownerId eq ownerId) }
            .count() > 0
        if (!owned) return@dbQuery false

        val members = membersInTx(groupId)
        val folders = folderIdsInTx(groupId)
        for (memberId in members) {
            unfileInTx(memberId, foldersToUnfileInTx(memberId, groupId, folders))
        }

        PracticeAttempts.deleteWhere { PracticeAttempts.groupId eq groupId }
        Assignments.deleteWhere { Assignments.groupId eq groupId }
        StudyGroupFolders.deleteWhere { StudyGroupFolders.groupId eq groupId }
        StudyGroupMembers.deleteWhere { StudyGroupMembers.groupId eq groupId }
        StudyGroups.deleteWhere { StudyGroups.id eq groupId } > 0
    }

    // ── Invitation ────────────────────────────────────────────────────────

    /**
     * The group's invitation, minted on first request and stable afterwards.
     *
     * Stable for the same reason a folder's share link is: a class has already been given the
     * code, and pressing the button again must not lock them out. Revoking is a separate act.
     */
    suspend fun mintInvite(ownerId: Int, groupId: Int): Pair<String, String>? = dbQuery {
        val row = StudyGroups.selectAll()
            .where { (StudyGroups.id eq groupId) and (StudyGroups.ownerId eq ownerId) }
            .singleOrNull()
            ?: return@dbQuery null

        val existingToken = row[StudyGroups.inviteToken]
        val existingCode = row[StudyGroups.joinCode]
        if (existingToken != null && existingCode != null) return@dbQuery existingToken to existingCode

        val token = existingToken ?: uniqueToken()
        val code = existingCode ?: uniqueCode()
        StudyGroups.update({ StudyGroups.id eq groupId }) {
            it[inviteToken] = token
            it[joinCode] = code
        }
        token to code
    }

    suspend fun currentInvite(ownerId: Int, groupId: Int): Pair<String, String>? = dbQuery {
        val row = StudyGroups.selectAll()
            .where { (StudyGroups.id eq groupId) and (StudyGroups.ownerId eq ownerId) }
            .singleOrNull()
            ?: return@dbQuery null
        val token = row[StudyGroups.inviteToken] ?: return@dbQuery null
        val code = row[StudyGroups.joinCode] ?: return@dbQuery null
        token to code
    }

    suspend fun revokeInvite(ownerId: Int, groupId: Int): Boolean = dbQuery {
        StudyGroups.update({ (StudyGroups.id eq groupId) and (StudyGroups.ownerId eq ownerId) }) {
            it[inviteToken] = null
            it[joinCode] = null
        } > 0
    }

    suspend fun findByInviteToken(token: String): GroupRow? = dbQuery {
        StudyGroups.selectAll()
            .where { (StudyGroups.inviteToken eq token) and (StudyGroups.archived eq false) }
            .singleOrNull()
            ?.let(::rowToGroup)
    }

    suspend fun findByJoinCode(code: String): GroupRow? = dbQuery {
        StudyGroups.selectAll()
            .where { (StudyGroups.joinCode eq code.trim().lowercase()) and (StudyGroups.archived eq false) }
            .singleOrNull()
            ?.let(::rowToGroup)
    }

    // ── Membership ────────────────────────────────────────────────────────

    suspend fun isMember(userId: Int, groupId: Int): Boolean = dbQuery {
        StudyGroupMembers.selectAll()
            .where { (StudyGroupMembers.groupId eq groupId) and (StudyGroupMembers.userId eq userId) }
            .count() > 0
    }

    suspend fun memberCount(groupId: Int): Int = dbQuery {
        StudyGroupMembers.selectAll().where { StudyGroupMembers.groupId eq groupId }.count().toInt()
    }

    /** @return false when the student was already in the group — the same outcome, not an error. */
    suspend fun addMember(groupId: Int, userId: Int): Boolean = dbQuery {
        val already = StudyGroupMembers.selectAll()
            .where { (StudyGroupMembers.groupId eq groupId) and (StudyGroupMembers.userId eq userId) }
            .count() > 0
        if (already) return@dbQuery false

        StudyGroupMembers.insert {
            it[StudyGroupMembers.groupId] = groupId
            it[StudyGroupMembers.userId] = userId
        }
        true
    }

    /**
     * Takes the student out of the group, and their cards out of its folders.
     *
     * ⚠️ Only the folders this group alone gave them. A teacher can hand one folder to two of
     * their classes, and leaving the first must not quietly unfile the deck the second is still
     * about — see [GroupSweep].
     */
    suspend fun removeMember(groupId: Int, userId: Int): Boolean = dbQuery {
        val folders = folderIdsInTx(groupId)
        unfileInTx(userId, foldersToUnfileInTx(userId, groupId, folders))
        StudyGroupMembers.deleteWhere {
            (StudyGroupMembers.groupId eq groupId) and (StudyGroupMembers.userId eq userId)
        } > 0
    }

    suspend fun members(groupId: Int): List<GroupMemberDTO> = dbQuery {
        val rows = StudyGroupMembers.selectAll()
            .where { StudyGroupMembers.groupId eq groupId }
            .orderBy(StudyGroupMembers.joinedAt to SortOrder.ASC)
            .map { it[StudyGroupMembers.userId] to it[StudyGroupMembers.joinedAt] }
        if (rows.isEmpty()) return@dbQuery emptyList()

        val users = Users.selectAll()
            .where { Users.id inList rows.map { row -> row.first } }
            .associateBy { it[Users.id] }

        rows.mapNotNull { (userId, joinedAt) ->
            val user = users[userId] ?: return@mapNotNull null
            GroupMemberDTO(
                userId = userId,
                login = user[Users.login],
                email = user[Users.email],
                joinedAt = joinedAt.toString()
            )
        }
    }

    suspend fun memberIds(groupId: Int): List<Int> = dbQuery { membersInTx(groupId) }

    // ── Folders ───────────────────────────────────────────────────────────

    suspend fun folderIds(groupId: Int): List<Int> = dbQuery { folderIdsInTx(groupId) }

    suspend fun folderCount(groupId: Int): Int = dbQuery { folderIdsInTx(groupId).size }

    /** @return false when the folder is not the owner's, or is already assigned. */
    suspend fun assignFolder(ownerId: Int, groupId: Int, categoryId: Int): Boolean = dbQuery {
        val ownsFolder = Categories.selectAll()
            .where { (Categories.id eq categoryId) and (Categories.userId eq ownerId) }
            .count() > 0
        if (!ownsFolder) return@dbQuery false

        val already = StudyGroupFolders.selectAll()
            .where {
                (StudyGroupFolders.groupId eq groupId) and (StudyGroupFolders.categoryId eq categoryId)
            }
            .count() > 0
        if (already) return@dbQuery false

        StudyGroupFolders.insert {
            it[StudyGroupFolders.groupId] = groupId
            it[StudyGroupFolders.categoryId] = categoryId
        }
        true
    }

    suspend fun unassignFolder(groupId: Int, categoryId: Int): Boolean = dbQuery {
        for (memberId in membersInTx(groupId)) {
            unfileInTx(memberId, foldersToUnfileInTx(memberId, groupId, listOf(categoryId)))
        }
        Assignments.update({
            (Assignments.groupId eq groupId) and (Assignments.categoryId eq categoryId)
        }) {
            it[Assignments.categoryId] = null
        }
        StudyGroupFolders.deleteWhere {
            (StudyGroupFolders.groupId eq groupId) and (StudyGroupFolders.categoryId eq categoryId)
        } > 0
    }

    /** Groups that were handed this folder — asked before a folder is deleted. */
    suspend fun groupsUsingFolder(categoryId: Int): List<GroupRow> = dbQuery {
        val groupIds = StudyGroupFolders
            .select(StudyGroupFolders.groupId)
            .where { StudyGroupFolders.categoryId eq categoryId }
            .map { it[StudyGroupFolders.groupId] }
        if (groupIds.isEmpty()) return@dbQuery emptyList()

        StudyGroups.selectAll().where { StudyGroups.id inList groupIds }.map(::rowToGroup)
    }

    /** How many words the group's folders hold in total — for the preview and the group card. */
    suspend fun wordCount(groupId: Int): Int = dbQuery {
        val folders = folderIdsInTx(groupId)
        if (folders.isEmpty()) return@dbQuery 0
        // distinct(): одно слово может лежать в двух папках одной группы, и тогда «сколько
        // слов в классе» посчитало бы его дважды.
        (SavedWordCategories innerJoin SavedWords)
            .select(SavedWordCategories.savedWordId)
            .where { SavedWordCategories.categoryId inList folders }
            .withDistinct()
            .count()
            .toInt()
    }

    suspend fun sampleWords(groupId: Int, limit: Int): List<Pair<String, String?>> = dbQuery {
        val folders = folderIdsInTx(groupId)
        if (folders.isEmpty()) return@dbQuery emptyList()
        val ids = SavedWordCategories
            .select(SavedWordCategories.savedWordId)
            .where { SavedWordCategories.categoryId inList folders }
            .map { it[SavedWordCategories.savedWordId] }
            .distinct()
        if (ids.isEmpty()) return@dbQuery emptyList()
        SavedWords.selectAll()
            .where { SavedWords.id inList ids }
            .orderBy(SavedWords.savedAt to SortOrder.ASC)
            .limit(limit)
            .map { it[SavedWords.word] to it[SavedWords.translation] }
    }

    // ── Inside a transaction ──────────────────────────────────────────────

    private fun membersInTx(groupId: Int): List<Int> =
        StudyGroupMembers
            .select(StudyGroupMembers.userId)
            .where { StudyGroupMembers.groupId eq groupId }
            .map { it[StudyGroupMembers.userId] }

    private fun folderIdsInTx(groupId: Int): List<Int> =
        StudyGroupFolders
            .select(StudyGroupFolders.categoryId)
            .where { StudyGroupFolders.groupId eq groupId }
            .map { it[StudyGroupFolders.categoryId] }

    /** Which of [losing] the student stops being able to reach once they leave [leavingGroupId]. */
    private fun foldersToUnfileInTx(userId: Int, leavingGroupId: Int, losing: List<Int>): Set<Int> {
        if (losing.isEmpty()) return emptySet()

        val otherGroupIds = StudyGroupMembers
            .select(StudyGroupMembers.groupId)
            .where {
                (StudyGroupMembers.userId eq userId) and (StudyGroupMembers.groupId neq leavingGroupId)
            }
            .map { it[StudyGroupMembers.groupId] }

        val stillReachable = if (otherGroupIds.isEmpty()) {
            emptySet()
        } else {
            StudyGroupFolders
                .select(StudyGroupFolders.categoryId)
                .where { StudyGroupFolders.groupId inList otherGroupIds }
                .mapTo(mutableSetOf()) { it[StudyGroupFolders.categoryId] }
        }

        return GroupSweep.foldersToUnfile(losing.toSet(), stillReachable)
    }

    private fun unfileInTx(userId: Int, categoryIds: Set<Int>) {
        if (categoryIds.isEmpty()) return
        Flashcards.update({
            (Flashcards.userId eq userId) and (Flashcards.categoryId inList categoryIds)
        }) {
            it[Flashcards.categoryId] = null
        }
    }

    private fun uniqueToken(): String {
        repeat(5) {
            val candidate = ShortToken.generate(12)
            val taken = StudyGroups.selectAll()
                .where { StudyGroups.inviteToken eq candidate }.count() > 0
            if (!taken) return candidate
        }
        return ShortToken.generate(12)
    }

    private fun uniqueCode(): String {
        repeat(5) {
            val candidate = ShortToken.generate(8)
            val taken = StudyGroups.selectAll()
                .where { StudyGroups.joinCode eq candidate }.count() > 0
            if (!taken) return candidate
        }
        return ShortToken.generate(8)
    }
}
