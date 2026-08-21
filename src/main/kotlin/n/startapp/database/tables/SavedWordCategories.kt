package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table

/**
 * Which folders a saved word is filed under.
 *
 * A word used to carry a single `category_id`, which quietly made two different questions —
 * «в какой папке это слово» and «какие слова в этой папке» — into one. They are not the same
 * question: одно слово честно принадлежит и уроку, и теме к экзамену, and a single column
 * forced the person to pick which of the two folders would be wrong.
 *
 * ⚠️ Both ends own rows here, so both deletions have to reach it — удаление слова и удаление
 * папки. A pair left behind points at a row that no longer exists, and folder ids are handed
 * out by a sequence: the next folder to reuse the id inherits somebody else's words.
 */
object SavedWordCategories : Table("saved_word_categories") {
    val savedWordId = integer("saved_word_id").references(SavedWords.id)
    val categoryId = integer("category_id").references(Categories.id)

    /** A word is in a folder or it is not; there is no second time. */
    override val primaryKey = PrimaryKey(savedWordId, categoryId)

    init {
        // «Что лежит в этой папке» is the read that renders every folder chip and every
        // practice session, and it arrives with the category, not with the word.
        index(false, categoryId)
    }
}
