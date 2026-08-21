package n.startapp.database

import n.startapp.database.tables.AppSettings
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory

/**
 * Moves a live database from «одно слово — одна папка, одна строка» to the current shape.
 *
 * The project has no migration tool: `createMissingTablesAndColumns` adds what is missing and
 * touches nothing else. It will happily create [SavedWordCategories], and it will just as
 * happily leave the old unique index in place — so the new table would exist while the old
 * constraint still refused the second sense of a word. Both halves have to be done by hand.
 *
 * Written to be safe to run on every boot, because it does run on every boot.
 */
object SavedWordFolderMigration {

    private val logger = LoggerFactory.getLogger(SavedWordFolderMigration::class.java)

    /** Marks the one-way step, so a folder the user has since removed is not re-added. */
    private const val COPIED_KEY = "saved_word_folders_copied"

    fun run() {
        dropLegacyUniqueIndex()
        copyLegacyColumn()
    }

    /**
     * Drops every non-primary unique index on `saved_words`.
     *
     * By shape rather than by name: Exposed has changed how it names indexes between versions,
     * and a `DROP INDEX saved_words_user_id_word` that silently matches nothing would leave the
     * server up, the feature broken, and nothing in the log to say so. The table has exactly one
     * such index — (user_id, word) — so "all of them except the primary key" names it exactly.
     *
     * Postgres-only, on purpose: the tests run on H2, which has no `DO $$` blocks and no legacy
     * database to repair either.
     */
    private fun dropLegacyUniqueIndex() {
        val dialect = TransactionManager.current().db.vendor
        if (!dialect.contains("postgres", ignoreCase = true)) return

        val sql = """
            DO ${'$'}${'$'}
            DECLARE r record;
            BEGIN
              FOR r IN
                SELECT c.conname AS name
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relname = 'saved_words' AND c.contype = 'u'
              LOOP
                EXECUTE format('ALTER TABLE saved_words DROP CONSTRAINT %I', r.name);
              END LOOP;

              FOR r IN
                SELECT i.relname AS name
                FROM pg_index x
                JOIN pg_class i ON i.oid = x.indexrelid
                JOIN pg_class t ON t.oid = x.indrelid
                WHERE t.relname = 'saved_words' AND x.indisunique AND NOT x.indisprimary
              LOOP
                EXECUTE format('DROP INDEX IF EXISTS %I', r.name);
              END LOOP;
            END ${'$'}${'$'};
        """.trimIndent()

        runCatching { TransactionManager.current().exec(sql) }
            .onFailure { logger.error("Could not drop the legacy unique index on saved_words", it) }
            .onSuccess { logger.info("saved_words: legacy unique indexes dropped") }
    }

    /**
     * Copies `saved_words.category_id` into the join table, once.
     *
     * ⚠️ Guarded by a flag rather than by "is the join table empty": the column is not cleared
     * afterwards, so a second pass would put back every folder the user has removed since. The
     * column keeps its values precisely so that rolling the deployment back stays possible —
     * Dokploy rollback is a normal operation here, and a migration that destroys its own input
     * turns one bad deploy into lost data.
     */
    private fun copyLegacyColumn() {
        val alreadyCopied = AppSettings.selectAll()
            .where { AppSettings.key eq COPIED_KEY }
            .empty()
            .not()
        if (alreadyCopied) return

        val legacy = SavedWords
            .select(SavedWords.id, SavedWords.legacyCategoryId)
            .where { SavedWords.legacyCategoryId.isNotNull() }
            .map { it[SavedWords.id] to it[SavedWords.legacyCategoryId]!! }

        val existing = SavedWordCategories.selectAll()
            .map { it[SavedWordCategories.savedWordId] to it[SavedWordCategories.categoryId] }
            .toSet()

        val pending = legacy.filterNot { it in existing }
        if (pending.isNotEmpty()) {
            SavedWordCategories.batchInsert(pending) { (wordId, folderId) ->
                this[SavedWordCategories.savedWordId] = wordId
                this[SavedWordCategories.categoryId] = folderId
            }
        }

        AppSettings.insert {
            it[key] = COPIED_KEY
            it[value] = pending.size.toString()
            it[updatedAt] = System.currentTimeMillis()
        }
        logger.info("saved_words: ${pending.size} folder links moved into saved_word_categories")
    }
}
