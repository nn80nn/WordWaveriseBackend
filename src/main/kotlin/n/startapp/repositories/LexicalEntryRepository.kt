package n.startapp.repositories

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.LexicalEntries
import n.startapp.models.admin.CorpusEntriesPage
import n.startapp.models.admin.CorpusEntrySummary
import n.startapp.models.admin.CorpusStats
import n.startapp.models.admin.NamedCount
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.lexical.LEXICAL_SCHEMA_VERSION
import n.startapp.models.lexical.LexicalEntry
import n.startapp.services.ai.LlmUsage
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.avg
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** An entry plus the raw aggregate it was built from. */
data class StoredEntry(
    val entry: LexicalEntry,
    val raw: WordDetailResponse?,
    val sourceFingerprint: String
)

class LexicalEntryRepository {
    private val logger = LoggerFactory.getLogger(LexicalEntryRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun find(cacheKey: String): StoredEntry? = dbQuery {
        val row = LexicalEntries.selectAll().where { LexicalEntries.cacheKey eq cacheKey }
            .firstOrNull() ?: return@dbQuery null

        LexicalEntries.update({ LexicalEntries.cacheKey eq cacheKey }) {
            it[hitCount] = row[LexicalEntries.hitCount] + 1
        }

        try {
            StoredEntry(
                entry = json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson]),
                raw = row[LexicalEntries.rawJson].takeIf { it.isNotBlank() }
                    ?.let { runCatching { json.decodeFromString<WordDetailResponse>(it) }.getOrNull() },
                sourceFingerprint = row[LexicalEntries.sourceFingerprint]
            )
        } catch (e: Exception) {
            // A row that cannot be read is worse than no row: drop it so the next request rebuilds.
            logger.warn("Unreadable lexical entry '$cacheKey', discarding: ${e.message}")
            LexicalEntries.deleteWhere { LexicalEntries.cacheKey eq cacheKey }
            null
        }
    }

    /**
     * Persists an annotated entry. Never persists a degraded one — that would pin a failed
     * annotation forever, when the whole point is that the next request retries the model.
     */
    suspend fun save(
        cacheKey: String,
        entry: LexicalEntry,
        raw: WordDetailResponse,
        sourceFingerprint: String,
        usage: LlmUsage = LlmUsage()
    ) {
        if (entry.degraded) {
            logger.debug("Not persisting degraded entry for '${entry.lemma}'")
            return
        }
        runCatching {
            dbQuery {
                val now = System.currentTimeMillis()
                val entryJson = json.encodeToString(entry)
                val rawJson = json.encodeToString(raw)
                val exists = LexicalEntries.selectAll()
                    .where { LexicalEntries.cacheKey eq cacheKey }.any()

                if (exists) {
                    LexicalEntries.update({ LexicalEntries.cacheKey eq cacheKey }) {
                        it[LexicalEntries.entryJson] = entryJson
                        it[LexicalEntries.rawJson] = rawJson
                        it[LexicalEntries.sourceFingerprint] = sourceFingerprint
                        it[formsIndex] = entry.formsIndex()
                        it[aiGenerated] = entry.aiGenerated
                        it[promptTokens] = usage.promptTokens
                        it[completionTokens] = usage.completionTokens
                        it[latencyMs] = usage.latencyMs.toInt()
                        it[updatedAt] = now
                    }
                } else {
                    LexicalEntries.insert {
                        it[LexicalEntries.cacheKey] = cacheKey
                        it[lemma] = entry.lemma.take(255)
                        it[lang] = entry.language
                        it[kind] = entry.kind.name
                        it[schemaVersion] = entry.schemaVersion
                        it[promptVersion] = entry.promptVersion
                        it[model] = entry.model.take(96)
                        it[LexicalEntries.sourceFingerprint] = sourceFingerprint
                        it[LexicalEntries.entryJson] = entryJson
                        it[LexicalEntries.rawJson] = rawJson
                        it[formsIndex] = entry.formsIndex()
                        it[aiGenerated] = entry.aiGenerated
                        it[promptTokens] = usage.promptTokens
                        it[completionTokens] = usage.completionTokens
                        it[latencyMs] = usage.latencyMs.toInt()
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
        }.onFailure { logger.warn("Failed to persist lexical entry '$cacheKey': ${it.message}") }
    }

    /**
     * Finds the lemma an already-annotated entry files this surface form under.
     *
     * Matches the headword first, then the recorded inflected forms, which is what lets
     * "running" resolve to "run" without a spelling oracle or a model call once "run" is known.
     */
    suspend fun findLemmaByForm(form: String): String? = dbQuery {
        val needle = form.trim().lowercase()
        if (needle.isBlank()) return@dbQuery null

        LexicalEntries.selectAll().where { LexicalEntries.lemma eq needle }
            .firstOrNull()?.let { return@dbQuery it[LexicalEntries.lemma] }

        LexicalEntries
            .selectAll()
            .where { LexicalEntries.formsIndex like "%$needle%" }
            .limit(20)
            .firstOrNull { row ->
                // `like` is a coarse prefilter; confirm on a whole-token match.
                row[LexicalEntries.formsIndex].split(' ').any { it == needle }
            }
            ?.get(LexicalEntries.lemma)
    }

    /**
     * Most recently written article for a lemma, whatever schema or prompt version produced it.
     *
     * Used to enrich the legacy endpoints, where any annotated Russian beats the context-free
     * word-level translation they would otherwise generate — so being strict about versions
     * would only mean falling back to the worse answer.
     */
    suspend fun findLatestByLemma(lemma: String): LexicalEntry? = dbQuery {
        LexicalEntries.selectAll()
            .where { LexicalEntries.lemma eq lemma.trim().lowercase() }
            .orderBy(LexicalEntries.updatedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                runCatching { json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson]) }.getOrNull()
            }
    }

    suspend fun deleteByLemma(lemma: String): Int = dbQuery {
        LexicalEntries.deleteWhere { LexicalEntries.lemma eq lemma.trim().lowercase() }
    }

    // ── Admin views ─────────────────────────────────────────────────────────

    /** Every headword the corpus answers for, for set arithmetic against a word list. */
    suspend fun allLemmas(): Set<String> = dbQuery {
        LexicalEntries.select(LexicalEntries.lemma).withDistinct()
            .map { it[LexicalEntries.lemma] }
            .toSet()
    }

    suspend fun browse(search: String?, page: Int, pageSize: Int): CorpusEntriesPage = dbQuery {
        val needle = search?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val condition: Op<Boolean> =
            if (needle == null) Op.TRUE else (LexicalEntries.lemma like "%$needle%")

        val total = LexicalEntries.selectAll().where(condition).count()
        val rows = LexicalEntries
            .selectAll()
            .where(condition)
            .orderBy(LexicalEntries.updatedAt to SortOrder.DESC)
            .limit(pageSize, offset = ((page - 1).toLong() * pageSize))
            .map { row ->
                // The body is decoded only for the rows on screen; the table itself stores no
                // sense count, and denormalising one would be a second thing to keep in sync.
                val entry = runCatching {
                    json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson])
                }.getOrNull()

                CorpusEntrySummary(
                    lemma = row[LexicalEntries.lemma],
                    kind = row[LexicalEntries.kind],
                    model = row[LexicalEntries.model],
                    posCount = entry?.posGroups?.size ?: 0,
                    senseCount = entry?.posGroups?.sumOf { it.senses.size } ?: 0,
                    sources = entry?.sources?.map { it.source }?.distinct()?.sorted().orEmpty(),
                    aiGenerated = row[LexicalEntries.aiGenerated],
                    hitCount = row[LexicalEntries.hitCount],
                    tokens = row[LexicalEntries.promptTokens] + row[LexicalEntries.completionTokens],
                    latencyMs = row[LexicalEntries.latencyMs],
                    createdAt = row[LexicalEntries.createdAt],
                    updatedAt = row[LexicalEntries.updatedAt]
                )
            }

        CorpusEntriesPage(entries = rows, total = total, page = page, pageSize = pageSize)
    }

    suspend fun stats(warmupWords: List<String>, queueWords: List<String>): CorpusStats = dbQuery {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L

        val idCount = LexicalEntries.id.count()
        val total = LexicalEntries.selectAll().count()

        val byKind = LexicalEntries.select(LexicalEntries.kind, idCount)
            .groupBy(LexicalEntries.kind)
            .map { NamedCount(it[LexicalEntries.kind], it[idCount]) }
            .sortedByDescending { it.count }

        val byModel = LexicalEntries.select(LexicalEntries.model, idCount)
            .groupBy(LexicalEntries.model)
            .map { NamedCount(it[LexicalEntries.model].ifBlank { "—" }, it[idCount]) }
            .sortedByDescending { it.count }

        val topByHits = LexicalEntries
            .select(LexicalEntries.lemma, LexicalEntries.hitCount)
            .orderBy(LexicalEntries.hitCount to SortOrder.DESC)
            .limit(15)
            .map { NamedCount(it[LexicalEntries.lemma], it[LexicalEntries.hitCount]) }
            .filter { it.count > 0 }

        val promptSum = LexicalEntries.promptTokens.sum()
        val completionSum = LexicalEntries.completionTokens.sum()
        val hitSum = LexicalEntries.hitCount.sum()
        val latencyAvg = LexicalEntries.latencyMs.avg()
        val totals = LexicalEntries.select(promptSum, completionSum, hitSum, latencyAvg).first()

        val lemmas = LexicalEntries.select(LexicalEntries.lemma).withDistinct()
            .map { it[LexicalEntries.lemma] }
            .toSet()

        CorpusStats(
            totalEntries = total,
            aiGenerated = LexicalEntries.selectAll()
                .where { LexicalEntries.aiGenerated eq true }.count(),
            // An article whose grounding is empty is one the sources never actually supported.
            withoutSources = LexicalEntries.selectAll()
                .where { LexicalEntries.formsIndex eq "" }.count(),
            added24h = LexicalEntries.selectAll()
                .where { LexicalEntries.createdAt greater (now - day) }.count(),
            added7d = LexicalEntries.selectAll()
                .where { LexicalEntries.createdAt greater (now - 7 * day) }.count(),
            totalHits = totals[hitSum] ?: 0L,
            promptTokens = totals[promptSum]?.toLong() ?: 0L,
            completionTokens = totals[completionSum]?.toLong() ?: 0L,
            avgLatencyMs = totals[latencyAvg]?.toLong() ?: 0L,
            byKind = byKind,
            byModel = byModel,
            topByHits = topByHits,
            warmupListSize = warmupWords.size,
            warmupListCovered = warmupWords.count { it in lemmas },
            queueSize = queueWords.size,
            queueCovered = queueWords.count { it in lemmas }
        )
    }

    companion object {
        fun cacheKey(lemma: String, kind: String, promptVersion: Int, model: String, lang: String = "en"): String =
            "$lang|${lemma.trim().lowercase()}|$kind|s$LEXICAL_SCHEMA_VERSION|p$promptVersion|$model"

        /** Identifies the grounding material, so a changed scrape can be detected later. */
        fun fingerprint(fragments: List<String>): String =
            sha256(fragments.joinToString("\n"))

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
