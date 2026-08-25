package n.startapp.services.group

import n.startapp.models.auth.CategoryDTO
import n.startapp.models.auth.SavedWord
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.SavedWordRepository

/** A word as one particular reader meets it, and how it reached them. */
data class VisibleWord(
    val word: SavedWord,
    val groupId: Int?,
    val readOnly: Boolean
)

/**
 * What one person can see of the vocabulary: their own folders and words, plus whatever their
 * groups lend them.
 *
 * Both lists are assembled here rather than in the two routes, because the same two rules have to
 * hold in both: a borrowed folder is marked read-only, and a borrowed word never displaces one of
 * the reader's own.
 */
class FolderCatalog(
    private val resolver: FolderAccessResolver,
    private val savedWords: SavedWordRepository,
    private val categories: CategoryRepository = CategoryRepository()
) {

    suspend fun foldersFor(userId: Int): List<CategoryDTO> {
        val visible = resolver.visible(userId)
        if (visible.isEmpty()) return emptyList()

        // One count query per owner, not per folder.
        val countsByOwner = visible
            .map { it.contentOwnerId }
            .distinct()
            .associateWith { savedWords.countByCategory(it) }

        val reachable = visible.mapTo(mutableSetOf()) { it.categoryId }

        val flat = visible.map { folder ->
            CategoryDTO(
                id = folder.categoryId,
                name = folder.name,
                color = folder.color,
                wordCount = countsByOwner[folder.contentOwnerId]?.get(folder.categoryId) ?: 0,
                // ⚠️ Родитель, до которого читатель не дотягивается, — это не родитель.
                // Учитель может выдать классу один урок без модуля, в котором тот лежит;
                // назвать модуль значило бы и рассказать о его существовании, и заставить
                // клиент рисовать ветку, которой у него нет.
                parentId = folder.parentId?.takeIf { it in reachable },
                groupId = folder.groupId,
                groupName = folder.groupName,
                readOnly = folder.readOnly
            )
        }

        // Считается на собранном списке, потому что складывать можно только видимое: дети,
        // до которых читатель не дотянулся, не должны попадать в число рядом с кнопкой.
        val counts = flat.associate { it.id to it.wordCount }
        return categories.withGroupCounts(flat, counts)
    }

    /**
     * The reader's own words, then the ones their groups lend them.
     *
     * ⚠️ A borrowed word is dropped when the reader has already saved that *sense* of it. Their
     * row carries their folders and — once they have a card — their review history, and a
     * second copy of the same meaning would only give them the same word to learn twice. A
     * different sense of the same spelling is not the same word, so it stays: the class folder
     * shows what the teacher put in it.
     *
     * A borrowed word is also trimmed to the folders this reader can actually reach. Its row
     * may be filed under several of the teacher's folders, and naming the ones the reader was
     * never given would leak what else the teacher keeps that word in.
     */
    suspend fun wordsFor(userId: Int): List<VisibleWord> {
        val own = savedWords.findByUserId(userId)
            .map { VisibleWord(it, groupId = null, readOnly = false) }

        val borrowedFolders = resolver.groupFolders(userId)
        if (borrowedFolders.isEmpty()) return own

        val taken = own.mapTo(mutableSetOf()) { it.word.word.trim().lowercase() to it.word.senseId }

        // Grouped by owner so each teacher's words are read in one query.
        val byOwner = resolver.visible(userId)
            .filter { it.readOnly }
            .groupBy { it.contentOwnerId }

        val borrowed = mutableListOf<VisibleWord>()
        for ((ownerId, folders) in byOwner) {
            val ids = folders.map { it.categoryId }
            for (word in savedWords.findByCategoryIds(ownerId, ids)) {
                val key = word.word.trim().lowercase() to word.senseId
                if (!taken.add(key)) continue
                val reachable = word.categoryIds.filter { it in borrowedFolders }
                borrowed += VisibleWord(
                    word = word.copy(categoryIds = reachable),
                    groupId = reachable.firstNotNullOfOrNull { borrowedFolders[it] }?.id,
                    readOnly = true
                )
            }
        }

        return own + borrowed
    }
}
