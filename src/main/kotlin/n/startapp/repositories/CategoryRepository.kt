package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Assignments
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PracticeAttempts
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.StudyGroupFolders
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
        wordCount = 0
    )

    suspend fun findByUserId(userId: Int): List<CategoryDTO> = dbQuery {
        val filed = SavedWordCategories.savedWordId.count()
        val counts = (SavedWordCategories innerJoin SavedWords)
            .select(SavedWordCategories.categoryId, filed)
            .where { SavedWords.userId eq userId }
            .groupBy(SavedWordCategories.categoryId)
            .associate { it[SavedWordCategories.categoryId] to it[filed].toInt() }

        Categories.selectAll()
            .where { Categories.userId eq userId }
            .orderBy(Categories.createdAt to SortOrder.ASC)
            .map { row ->
                rowToDTO(row).copy(wordCount = counts[row[Categories.id]] ?: 0)
            }
    }

    suspend fun create(userId: Int, name: String, color: String?): CategoryDTO = dbQuery {
        val stmt = Categories.insert {
            it[Categories.userId] = userId
            it[Categories.name] = name.trim()
            it[Categories.color] = color
        }
        val id = stmt[Categories.id]
        CategoryDTO(id = id, name = name.trim(), color = color, wordCount = 0)
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
