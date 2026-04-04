package n.startapp.repositories

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Categories
import n.startapp.database.tables.SavedWords
import n.startapp.models.auth.CategoryDTO
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class CategoryRepository {

    private fun rowToDTO(row: ResultRow) = CategoryDTO(
        id = row[Categories.id],
        name = row[Categories.name],
        color = row[Categories.color],
        wordCount = 0
    )

    suspend fun findByUserId(userId: Int): List<CategoryDTO> = dbQuery {
        val counts = SavedWords
            .select(SavedWords.categoryId, SavedWords.id.count())
            .where { SavedWords.userId eq userId }
            .groupBy(SavedWords.categoryId)
            .associate { it[SavedWords.categoryId] to it[SavedWords.id.count()].toInt() }

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

    suspend fun delete(userId: Int, categoryId: Int): Boolean = dbQuery {
        // Unassign words from this category first
        SavedWords.update({ (SavedWords.userId eq userId) and (SavedWords.categoryId eq categoryId) }) {
            it[SavedWords.categoryId] = null
        }
        Categories.deleteWhere { (Categories.id eq categoryId) and (Categories.userId eq userId) } > 0
    }

    suspend fun exists(userId: Int, categoryId: Int): Boolean = dbQuery {
        Categories.selectAll()
            .where { (Categories.id eq categoryId) and (Categories.userId eq userId) }
            .count() > 0
    }
}
