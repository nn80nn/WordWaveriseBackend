package n.startapp.database

import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.SavedWordRepository
import n.startapp.services.lexical.SenseBackfill
import n.startapp.services.lexical.SenseWording
import org.slf4j.LoggerFactory

/**
 * Gives every saved word an explicit sense.
 *
 * A word could be saved two ways: the headword star, which recorded no choice, and the bookmark
 * on a sense, which recorded one. The first was the weaker of the two and quietly undermined the
 * second — a row with no sense could be filled in by the *next* save of that word, so a choice
 * the user never made could be made for them later. The star is gone from the clients; this
 * closes the same door on the rows they already wrote.
 *
 * ⚠️ Nothing is deleted, nothing is moved, and no existing pin is touched. A row already
 * carrying a sense is skipped, and [SavedWordRepository.pinSense] refuses it a second time at
 * the SQL level — so a re-run, a concurrent read-path fill, and a rollback all stay harmless.
 *
 * ⚠️ Runs on every boot rather than behind a flag, on purpose. There is nothing here to undo
 * and nothing that a user could have since changed back; what there is, is a long tail — an
 * older app still posting bare saves, and words whose article was not written yet when the last
 * boot looked. Those are exactly the rows a once-only migration would abandon.
 */
object SavedWordSenseMigration {

    private val logger = LoggerFactory.getLogger(SavedWordSenseMigration::class.java)

    /** What one pass did, for an operator who asked for it by hand. */
    data class Outcome(val pinned: Int, val leftAlone: Int)

    suspend fun run(saved: SavedWordRepository, lexicalEntries: LexicalEntryRepository): Outcome {
        val rows = runCatching { saved.rowsNeedingSense() }
            .onFailure { logger.warn("Sense migration could not read saved words: ${it.message}") }
            .getOrDefault(emptyList())
        if (rows.isEmpty()) return Outcome(0, 0)

        val unpinned = rows.filter { it.senseId == null }
        val entries = runCatching { lexicalEntries.findLatestByLemmas(unpinned.map { it.word }.distinct()) }
            .onFailure { logger.warn("Sense migration could not read the corpus: ${it.message}") }
            .getOrDefault(emptyMap())

        // What each user already holds for each word — grown as we go, so two unpinned rows of
        // one word for one user get two different senses rather than the same one twice.
        val taken = rows
            .mapNotNull { row -> row.senseId?.let { (row.userId to row.word) to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.toMutableSet() }
            .toMutableMap()

        var pinned = 0
        var noArticle = 0
        for (row in unpinned) {
            val key = row.userId to row.word
            val held = taken.getOrPut(key) { mutableSetOf() }
            val entry = entries[row.word]
            val senseId = SenseBackfill.choose(entry, held)
            if (senseId == null) {
                noArticle++
                continue
            }

            val wording = entry?.let { SenseWording.of(it, senseId) }
            val ok = runCatching {
                saved.pinSense(
                    id = row.id,
                    userId = row.userId,
                    senseId = senseId,
                    translation = row.translation ?: wording?.translation,
                    definition = row.definition ?: wording?.definition,
                    example = row.example ?: wording?.example
                )
            }.onFailure { logger.warn("Sense migration failed on row ${row.id}: ${it.message}") }
                .getOrDefault(false)

            if (ok) {
                held += senseId
                pinned++
            }
        }

        logger.info(
            "Sense migration: {} saved words pinned, {} left as they are (no article yet)",
            pinned, noArticle
        )
        return Outcome(pinned = pinned, leftAlone = noArticle)
    }
}
