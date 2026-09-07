package n.startapp.services.lexical

import kotlinx.coroutines.runBlocking
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.LexicalKind
import n.startapp.services.ai.OpenAiCompatibleLlmClient
import n.startapp.services.dictionary.DictionaryAggregationService
import n.startapp.utils.EnvConfig
import java.io.File
import kotlin.test.Test

/**
 * Measures the two article stages against a real provider, and writes both articles out to be read.
 *
 * The question this answers cannot be answered by a unit test: whether the draft actually lands
 * in the seconds it promises, and whether what lands is worth showing. Both depend on the model
 * named in the environment, so the answer belongs to a deployment rather than to the code.
 *
 * Skipped unless `DRAFT_BENCH=1` and the AI provider is configured — it spends real tokens and
 * takes minutes. Put credentials in `WordWaveriseBackend/.env` (gitignored) and run:
 *
 *     DRAFT_BENCH=1 ./gradlew test --tests "*DraftArticleBenchmark*"
 *
 * The report lands in `build/draft-benchmark.md`; standard output is swallowed by the test task.
 *
 * ⚠️ Deliberately no database. Both stages here read the **quick** aggregate — API sources only —
 * which is exactly what a draft is built from, and what the scraper cache would otherwise need
 * Postgres for. The real full article additionally waits on the scrapers; that part is measured
 * by the `Cold '<word>' ready in ...` log line in production, not here.
 */
class DraftArticleBenchmark {

    /**
     * `DRAFT_BENCH_WORDS=lead,concise` narrows the run.
     *
     * Worth having: a provider with a shared quota answers `503 All accounts limited` after a
     * few full runs, and at that point the benchmark measures the quota rather than the stage.
     */
    private val words = System.getenv("DRAFT_BENCH_WORDS")
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf(
            "resolve",       // several parts of speech, many senses — the expensive shape
            "lead",          // homograph: the split that a thin article is most likely to lose
            "perseverance",  // one part of speech, long definitions
            "grow up",       // phrase: one group, no per-POS fan-out
            "concise"        // ordinary B2 adjective, the common case
        )

    @Test
    fun compareDraftAndFullArticles() {
        if (System.getenv("DRAFT_BENCH") != "1") return
        if (EnvConfig.aiDomen.isBlank() || EnvConfig.aiApiKey.isBlank()) {
            println("DraftArticleBenchmark: AI_DOMEN/AI_API not set — skipping")
            return
        }

        val llm = OpenAiCompatibleLlmClient()
        val aggregation = DictionaryAggregationService()
        val annotation = LexicalAnnotationService(llm)
        val report = StringBuilder()

        report.appendLine("# Быстрая статья против полной")
        report.appendLine()
        report.appendLine("- основная модель: `${EnvConfig.aiModel}`")
        report.appendLine("- модель черновика: `${EnvConfig.aiModelDraft.ifBlank { "<не задана>" }}`")
        report.appendLine("- стадия включена: ${EnvConfig.fastArticleEnabled}")
        report.appendLine()

        try {
            runBlocking {
                // ⚠️ One throwaway lookup first. The urgent budget is 2.5s per source, and the
                // very first request also pays DNS and three TLS handshakes — so on a cold
                // client every source misses, the word comes back "not found", and the health
                // breaker then silences the sources for the rest of the run. That is a property
                // of a laptop starting up, not of the pipeline being measured.
                runCatching { aggregation.aggregateDetailed("warm", skipScrapers = true) }

                for (word in words) {
                    val kind = if (word.contains(' ')) LexicalKind.PHRASE else LexicalKind.WORD

                    val aggregateStarted = System.currentTimeMillis()
                    val aggregate = runCatching {
                        aggregation.aggregateDetailed(
                            word, skipScrapers = true, isPhrase = kind == LexicalKind.PHRASE, urgent = true
                        )
                    }.recoverCatching {
                        // Say so rather than skipping: a source that misses the urgent budget is
                        // exactly what production would also have missed.
                        report.appendLine("_(urgent-бюджет не успел, взят полный)_")
                        aggregation.aggregateDetailed(
                            word, skipScrapers = true, isPhrase = kind == LexicalKind.PHRASE
                        )
                    }.getOrElse {
                        report.appendLine("## $word\n\nисточники не ответили: ${it.message}\n")
                        continue
                    }
                    val aggregateMs = System.currentTimeMillis() - aggregateStarted

                    val draftStarted = System.currentTimeMillis()
                    val draft = annotation.annotate(
                        word, word, kind, aggregate, profile = AnnotationProfile.DRAFT
                    )
                    val draftMs = System.currentTimeMillis() - draftStarted

                    val fullStarted = System.currentTimeMillis()
                    val full = annotation.annotate(word, word, kind, aggregate)
                    val fullMs = System.currentTimeMillis() - fullStarted

                    report.appendLine("## $word")
                    report.appendLine()
                    report.appendLine(
                        "быстрый агрегат ${aggregateMs}мс, фрагментов ${aggregate.sourceDefinitions.size} · " +
                            "черновик **${draftMs}мс** (${draft.entry.tokens()}, " +
                            "${draft.usage.completionTokens} ток., в модели ${draft.usage.latencyMs}мс) · " +
                            "полная **${fullMs}мс** (${full.entry.tokens()}, " +
                            "${full.usage.completionTokens} ток., в модели ${full.usage.latencyMs}мс)"
                    )
                    report.appendLine()
                    report.appendLine("до первого экрана с черновиком: **${aggregateMs + draftMs}мс**")
                    report.appendLine()
                    report.appendLine("### черновик${if (draft.entry.degraded) " (ДЕГРАДИРОВАЛ: ${draft.reason})" else ""}")
                    report.appendLine(render(draft.entry))
                    report.appendLine("### полная${if (full.entry.degraded) " (ДЕГРАДИРОВАЛА: ${full.reason})" else ""}")
                    report.appendLine(render(full.entry))
                }
            }
        } finally {
            llm.close()
            aggregation.close()
        }

        val out = File("build/draft-benchmark.md")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println("DraftArticleBenchmark: report written to ${out.absolutePath}")
    }

    private fun LexicalEntry.tokens(): String =
        "${posGroups.size} гр. / ${posGroups.sumOf { it.senses.size }} зн."

    /** The article as a reader would meet it: sense, Russian, example. */
    private fun render(entry: LexicalEntry): String = buildString {
        if (entry.posGroups.isEmpty()) appendLine("_(пусто)_")
        entry.posGroups.forEach { group ->
            appendLine()
            appendLine("**${group.posRu.ifBlank { group.pos }}**" +
                group.pronunciations.firstOrNull { !it.ipa.isNullOrBlank() }
                    ?.let { " ${it.ipa}" }.orEmpty())
            group.senses.forEach { sense ->
                appendLine("- `${sense.id}` ${sense.definitionEn}")
                appendLine("  - ru: ${sense.definitionRu}")
                appendLine("  - ${sense.translationsRu.joinToString(", ")}" +
                    (sense.cefr?.let { " · $it" } ?: "") +
                    (if (sense.generated) " · ИИ" else " · ист. ${sense.sourceRefs}"))
                sense.examples.firstOrNull()?.let { appendLine("  - «${it.en}» — ${it.ru}") }
            }
        }
        appendLine()
    }
}
