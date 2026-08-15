package n.startapp.database.tables

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Table for storing flashcards with spaced repetition data
 * Uses SM-2 algorithm for optimal review scheduling
 */
object Flashcards : IntIdTable("flashcards") {
    val userId = integer("user_id").references(Users.id)
    val savedWordId = integer("saved_word_id").references(SavedWords.id).nullable()

    /**
     * The folder the card belongs to. Inherited from the saved word at creation, and editable
     * afterwards — a card can be moved without moving the word it came from.
     */
    val categoryId = integer("category_id").references(Categories.id).nullable()

    // Card content
    val word = varchar("word", 100)
    val translation = varchar("translation", 255)
    val definition = text("definition").nullable()
    val example = text("example").nullable()

    // SM-2 Algorithm fields
    val easeFactor = float("ease_factor").default(2.5f) // Initial ease factor
    val repetitions = integer("repetitions").default(0) // Number of consecutive correct reviews
    val interval = integer("interval").default(0) // Days until next review
    val nextReview = timestamp("next_review") // When card is due for review
    val lastReviewed = timestamp("last_reviewed").nullable()

    /**
     * The wording was edited by hand, so the corpus must stop overwriting it.
     *
     * Cards are re-read from the dictionary on every due list (see
     * `FlashcardService.refreshFromCorpus`), which is what keeps a scraped typo from living on
     * a card forever. Without this flag that same mechanism would silently undo every manual
     * correction the user makes — the feature and the fix would cancel each other out.
     */
    val customized = bool("customized").default(false)

    // Metadata
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
