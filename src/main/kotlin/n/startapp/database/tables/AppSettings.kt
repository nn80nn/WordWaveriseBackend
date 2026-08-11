package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table

/**
 * Settings changed at runtime, overriding the environment.
 *
 * Only overrides live here — a key absent from this table means "whatever the environment says",
 * which is what makes reverting a setting possible at all. Storing every value would erase the
 * distinction between "deliberately set to the default" and "never touched", and the deployed
 * environment would stop being the source of truth the moment the panel was opened once.
 */
object AppSettings : Table("app_settings") {
    val id = integer("id").autoIncrement()
    val key = varchar("key", 96).uniqueIndex()
    val value = text("value")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}
