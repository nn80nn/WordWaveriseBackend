package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table

/**
 * Words queued for warming by hand, on top of the built-in B2/C1 list.
 *
 * Only membership is stored, never progress: whether a word is done is answered by the corpus
 * itself, the same rule the bundled list follows. A second record of "done" would be a second
 * source of truth to keep in sync, and the one that drifts is always the copy.
 */
object WarmupQueue : Table("warmup_queue") {
    val id = integer("id").autoIncrement()
    val word = varchar("word", 128).uniqueIndex()
    val addedAt = long("added_at")

    override val primaryKey = PrimaryKey(id)
}
