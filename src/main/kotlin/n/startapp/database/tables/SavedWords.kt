package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * SavedWords table definition
 */
object SavedWords : Table("saved_words") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val word = varchar("word", 255)
    val translation = varchar("translation", 500).nullable()
    val definition = text("definition").nullable()
    val example = text("example").nullable()

    /**
     * Which sense of the article the user pinned ("n1", "v2"), or null for "whatever the
     * article puts first". The id is assigned server-side when the article is annotated, so it
     * is stable across re-reads and cannot be fabricated by a client.
     */
    val senseId = varchar("sense_id", 32).nullable()

    val savedAt = timestamp("saved_at").clientDefault { Instant.now() }

    /**
     * ⚠️ Legacy. Folders live in [SavedWordCategories] now; this column is read exactly once,
     * by [n.startapp.database.SavedWordFolderMigration], and never again.
     *
     * It stays declared because that migration has to be able to read it, and because
     * `createMissingTablesAndColumns` cannot drop a column anyway — a definition that pretended
     * the column was gone would just be a lie the schema does not tell.
     */
    val legacyCategoryId = integer("category_id").references(Categories.id).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        /**
         * ⚠️ Здесь стоял `uniqueIndex(userId, word)` — «одно слово, одна строка». Именно он
         * и запрещал сохранить два значения одного слова по отдельности: `resolve` мог быть
         * либо «решать», либо «разлагать», но не тем и другим сразу, хотя это разные слова
         * для всех целей, кроме написания.
         *
         * Ключ теперь (user_id, word, sense_id), и держится он кодом, а не индексом: в
         * Postgres NULL не равен NULL, поэтому слово, сохранённое без выбранного значения,
         * уникальный индекс не защитил бы от второй такой же строки — то есть защищал бы
         * ровно тот случай, который и так под контролем, и молчал в том, который нет.
         * Снятие индекса с уже живой базы — [n.startapp.database.SavedWordFolderMigration].
         */
        index(false, userId, word)
    }
}
