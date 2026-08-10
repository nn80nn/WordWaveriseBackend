package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table

/**
 * The annotated dictionary corpus.
 *
 * Unlike every other cache in the project this has no TTL: an annotated entry is the expensive
 * asset the whole overhaul exists to build, it is identical for every user, and regenerating it
 * costs a full model call. Invalidation is by version — [schemaVersion] and [promptVersion] are
 * part of [cacheKey], so a bump orphans old rows instead of serving stale shapes.
 */
object LexicalEntries : Table("lexical_entries") {
    val id = long("id").autoIncrement()

    /** "{lang}|{lemma}|{kind}|s{schemaVersion}|p{promptVersion}|{model}" */
    val cacheKey = varchar("cache_key", 512).uniqueIndex()
    val lemma = varchar("lemma", 255).index()
    val lang = varchar("lang", 8).default("en")
    val kind = varchar("kind", 32)

    val schemaVersion = integer("schema_version")
    val promptVersion = integer("prompt_version")
    val model = varchar("model", 96)

    /** sha256 of the grounding fragments, so a re-scrape can flag an entry as worth refreshing. */
    val sourceFingerprint = varchar("source_fingerprint", 64)

    val entryJson = text("entry_json")
    /** The aggregate the entry was built from — powers the sources view without re-scraping. */
    val rawJson = text("raw_json")
    /** Lemma plus inflected forms, lowercase and space separated, for form → lemma lookup. */
    val formsIndex = text("forms_index").default("")

    val aiGenerated = bool("ai_generated").default(false)
    val promptTokens = integer("prompt_tokens").default(0)
    val completionTokens = integer("completion_tokens").default(0)
    val latencyMs = integer("latency_ms").default(0)

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val hitCount = long("hit_count").default(0)

    override val primaryKey = PrimaryKey(id)
}
