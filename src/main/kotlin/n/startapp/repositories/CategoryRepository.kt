package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Assignments
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PracticeAttempts
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.NotFoundException
import n.startapp.models.auth.CategoryDTO
import n.startapp.utils.ShortToken
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/** A folder reached through its share link: who owns it, and what it is called. */
data class SharedFolder(val id: Int, val ownerId: Int, val name: String)

class CategoryRepository {

    private fun rowToDTO(row: ResultRow) = CategoryDTO(
        id = row[Categories.id],
        name = row[Categories.name],
        color = row[Categories.color],
        wordCount = 0,
        parentId = row[Categories.parentId]
    )

    suspend fun findByUserId(userId: Int): List<CategoryDTO> = dbQuery {
        val filed = SavedWordCategories.savedWordId.count()
        val counts = (SavedWordCategories innerJoin SavedWords)
            .select(SavedWordCategories.categoryId, filed)
            .where { SavedWords.userId eq userId }
            .groupBy(SavedWordCategories.categoryId)
            .associate { it[SavedWordCategories.categoryId] to it[filed].toInt() }

        val rows = Categories.selectAll()
            .where { Categories.userId eq userId }
            .orderBy(Categories.createdAt to SortOrder.ASC)
            .map { rowToDTO(it) }

        withGroupCounts(rows, counts)
    }

    /**
     * A group's word count is its own plus its children's.
     *
     * The number has to match what the folder filter returns, and that filter reaches children —
     * otherwise a group reads "0 слов" beside a practice button that works.
     */
    fun withGroupCounts(folders: List<CategoryDTO>, counts: Map<Int, Int>): List<CategoryDTO> {
        val childrenOf = folders.filter { it.parentId != null }.groupBy { it.parentId!! }
        return folders.map { folder ->
            val own = counts[folder.id] ?: 0
            val fromChildren = childrenOf[folder.id].orEmpty().sumOf { counts[it.id] ?: 0 }
            folder.copy(wordCount = own + fromChildren)
        }
    }

    suspend fun create(
        userId: Int,
        name: String,
        color: String?,
        parentId: Int? = null
    ): CategoryDTO = dbQuery {
        val parent = parentId?.let { requireUsableParentInTx(userId, it) }
        val stmt = Categories.insert {
            it[Categories.userId] = userId
            it[Categories.name] = name.trim()
            it[Categories.color] = color
            it[Categories.parentId] = parent
        }
        val id = stmt[Categories.id]
        CategoryDTO(id = id, name = name.trim(), color = color, wordCount = 0, parentId = parent)
    }

    // ── Groups of folders ─────────────────────────────────────────────────

    /**
     * Files [categoryId] under [parentId], or takes it back out to the root when that is null.
     *
     * @throws BadRequestException when the move would build something deeper than one level, or
     *   point a folder at itself. Both are refused rather than silently flattened: a request the
     *   server "fixes" leaves the user looking at a tree they did not ask for and cannot explain.
     */
    suspend fun setParent(userId: Int, categoryId: Int, parentId: Int?): Boolean = dbQuery {
        val own = Categories.selectAll()
            .where { (Categories.id eq categoryId) and (Categories.userId eq userId) }
            .singleOrNull()
            ?: return@dbQuery false

        if (parentId == null) {
            Categories.update({ Categories.id eq categoryId }) { it[Categories.parentId] = null }
            return@dbQuery true
        }

        if (parentId == categoryId) throw BadRequestException("Папка не может лежать в самой себе")

        // A folder that is already a group cannot also be a member of one — that is the second
        // level, and it is the level where "which words does this filter reach" stops having an
        // answer everybody agrees on.
        val hasChildren = Categories.selectAll()
            .where { Categories.parentId eq categoryId }
            .count() > 0
        if (hasChildren) {
            throw BadRequestException("В этой папке уже лежат другие папки — её нельзя вложить")
        }
        // The row is read for its side effect: it refuses a parent that is not the caller's, or
        // that is itself filed under someone.
        requireUsableParentInTx(userId, parentId)

        Categories.update({ Categories.id eq categoryId }) { it[Categories.parentId] = parentId }
        own[Categories.id] > 0
    }

    /** The folders filed under [categoryId] — empty for a plain folder. */
    suspend fun childIds(categoryId: Int): List<Int> = dbQuery { childIdsInTx(categoryId) }

    /** The folders filed under [categoryId], oldest first — the order they were made in. */
    suspend fun children(categoryId: Int): List<CategoryDTO> = dbQuery {
        Categories.selectAll()
            .where { Categories.parentId eq categoryId }
            .orderBy(Categories.createdAt to SortOrder.ASC)
            .map { rowToDTO(it) }
    }

    /**
     * [categoryId] together with whatever is filed under it.
     *
     * The one shape every folder filter wants: for a plain folder it is just the folder, so
     * callers need no branch of their own.
     */
    suspend fun selfAndChildren(categoryId: Int): List<Int> =
        dbQuery { listOf(categoryId) + childIdsInTx(categoryId) }

    private fun childIdsInTx(categoryId: Int): List<Int> =
        Categories.select(Categories.id)
            .where { Categories.parentId eq categoryId }
            .map { it[Categories.id] }

    private fun requireUsableParentInTx(userId: Int, parentId: Int): Int {
        val parent = Categories.selectAll()
            .where { (Categories.id eq parentId) and (Categories.userId eq userId) }
            .singleOrNull()
            ?: throw NotFoundException("Папка-группа не найдена")
        if (parent[Categories.parentId] != null) {
            throw BadRequestException("Папки вкладываются только на один уровень")
        }
        return parentId
    }

    suspend fun update(userId: Int, categoryId: Int, name: String): Boolean = dbQuery {
        Categories.update({ (Categories.id eq categoryId) and (Categories.userId eq userId) }) {
            it[Categories.name] = name.trim()
        } > 0
    }

    /**
     * Empties the folder of everything that points at it, then removes it.
     *
     * Every one of these is a foreign key to `categories.id`, and leaving any of them behind
     * fails the delete outright rather than failing quietly. Nothing here is scoped to [userId]
     * except the delete itself: once the folder is gone it is gone for everyone who could see it,
     * so a class that was given it loses it too.
     *
     * Cards survive, unfiled. A folder is where a card lives, not why it exists — and for a
     * student the deck they built from a class folder is their own work.
     */
    suspend fun delete(userId: Int, categoryId: Int): Boolean = dbQuery {
        val owned = Categories.selectAll()
            .where { (Categories.id eq categoryId) and (Categories.userId eq userId) }
            .count() > 0
        if (!owned) return@dbQuery false

        // Дети переживают родителя и становятся корневыми. Группа — это способ разложить
        // папки, а не владение ими: удалить полку не значит выбросить всё, что на ней стояло.
        // ⚠️ И это шестая ссылка на categories.id — собственная. Оставленная, она валит
        // удаление внешним ключом, а не «просто не работает».
        Categories.update({ Categories.parentId eq categoryId }) {
            it[Categories.parentId] = null
        }

        // Пятая внешняя ссылка на categories.id. Пропущенная означает не «фича не
        // работает», а 500 без объяснений при удалении папки.
        SavedWordCategories.deleteWhere { SavedWordCategories.categoryId eq categoryId }
        Flashcards.update({ Flashcards.categoryId eq categoryId }) {
            it[Flashcards.categoryId] = null
        }
        Assignments.update({ Assignments.categoryId eq categoryId }) {
            it[Assignments.categoryId] = null
        }
        PracticeAttempts.update({ PracticeAttempts.categoryId eq categoryId }) {
            it[PracticeAttempts.categoryId] = null
        }
        StudyGroupFolders.deleteWhere { StudyGroupFolders.categoryId eq categoryId }

        Categories.deleteWhere { (Categories.id eq categoryId) and (Categories.userId eq userId) } > 0
    }

    // ── Sharing ───────────────────────────────────────────────────────────

    /**
     * The folder's share link, minted on first request and stable afterwards.
     *
     * Stable on purpose: a second press of "поделиться" must not invalidate the link already
     * sent to somebody. Revoking is a separate, deliberate act ([revokeShare]).
     */
    suspend fun shareToken(userId: Int, categoryId: Int): String? = dbQuery {
        val row = Categories.selectAll()
            .where { (Categories.id eq categoryId) and (Categories.userId eq userId) }
            .singleOrNull()
            ?: return@dbQuery null

        row[Categories.shareToken] ?: run {
            val token = newToken()
            Categories.update({ Categories.id eq categoryId }) { it[shareToken] = token }
            token
        }
    }

    suspend fun revokeShare(userId: Int, categoryId: Int): Boolean = dbQuery {
        Categories.update({ (Categories.id eq categoryId) and (Categories.userId eq userId) }) {
            it[shareToken] = null
        } > 0
    }

    suspend fun currentShareToken(userId: Int, categoryId: Int): String? = dbQuery {
        Categories.selectAll()
            .where { (Categories.id eq categoryId) and (Categories.userId eq userId) }
            .singleOrNull()
            ?.get(Categories.shareToken)
    }

    /** The folder a share link points at, or null when the link was revoked or never existed. */
    suspend fun findByShareToken(token: String): SharedFolder? = dbQuery {
        Categories.selectAll()
            .where { Categories.shareToken eq token }
            .singleOrNull()
            ?.let { SharedFolder(id = it[Categories.id], ownerId = it[Categories.userId], name = it[Categories.name]) }
    }

    /**
     * A name that does not collide with what the user already has.
     *
     * Two folders with the same name are indistinguishable in every picker in the app, and
     * importing the same shared folder twice is an ordinary thing to do by accident.
     */
    suspend fun freeName(userId: Int, wanted: String): String = dbQuery {
        val taken = Categories.selectAll()
            .where { Categories.userId eq userId }
            .map { it[Categories.name].trim().lowercase() }
            .toSet()

        val base = wanted.trim().take(90)
        if (base.lowercase() !in taken) return@dbQuery base
        generateSequence(2) { it + 1 }
            .map { "$base ($it)" }
            .first { it.lowercase() !in taken }
    }

    private fun newToken(): String = ShortToken.generate(12)

    suspend fun exists(userId: Int, categoryId: Int): Boolean = dbQuery {
        Categories.selectAll()
            .where { (Categories.id eq categoryId) and (Categories.userId eq userId) }
            .count() > 0
    }
}
