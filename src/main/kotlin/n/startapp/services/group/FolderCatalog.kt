package n.startapp.services.group

import n.startapp.models.auth.CategoryDTO
import n.startapp.models.auth.SavedWord
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
    private val savedWords: SavedWordRepository
) {

    suspend fun foldersFor(userId: Int): List<CategoryDTO> {
        val visible = resolver.visible(userId)
        if (visible.isEmpty()) return emptyList()

        // One count query per owner, not per folder.
        val countsByOwner = visible
            .map { it.contentOwnerId }
            .distinct()
            .associateWith { savedWords.countByCategory(it) }

        return visible.map { folder ->
            CategoryDTO(
                id = folder.categoryId,
                name = folder.name,
                color = folder.color,
                wordCount = countsByOwner[folder.contentOwnerId]?.get(folder.categoryId) ?: 0,
                groupId = folder.groupId,
                groupName = folder.groupName,
                readOnly = folder.readOnly
            )
        }
    }

    /**
     * The reader's own words, then the ones their groups lend them.
     *
     * ⚠️ A headword is never returned twice. Android stores saved words keyed by the headword
     * itself, so a second row for the same spelling does not appear beside the first — it
     * overwrites it, and whichever arrived last wins the folder. When the reader already has a
     * word of their own, theirs is the one that survives: it carries their pinned sense and
     * their folder, and losing that to a borrowed copy would be a real loss rather than a
     * cosmetic one.
     */
    suspend fun wordsFor(userId: Int): List<VisibleWord> {
        val own = savedWords.findByUserId(userId)
            .map { VisibleWord(it, groupId = null, readOnly = false) }

        val borrowedFolders = resolver.groupFolders(userId)
        if (borrowedFolders.isEmpty()) return own

        val taken = own.mapTo(mutableSetOf()) { it.word.word.trim().lowercase() }

        // Grouped by owner so each teacher's words are read in one query.
        val byOwner = resolver.visible(userId)
            .filter { it.readOnly }
            .groupBy { it.contentOwnerId }

        val borrowed = mutableListOf<VisibleWord>()
        for ((ownerId, folders) in byOwner) {
            val ids = folders.map { it.categoryId }
            for (word in savedWords.findByCategoryIds(ownerId, ids)) {
                val key = word.word.trim().lowercase()
                if (!taken.add(key)) continue
                borrowed += VisibleWord(
                    word = word,
                    groupId = borrowedFolders[word.categoryId]?.id,
                    readOnly = true
                )
            }
        }

        return own + borrowed
    }
}
