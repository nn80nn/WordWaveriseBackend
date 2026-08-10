package n.startapp.services.query

import n.startapp.models.query.QueryKind
import n.startapp.models.query.ResolvedQuery
import java.text.Normalizer

/**
 * Classifies the raw search input before any lookup happens.
 *
 * Only the deterministic, network-free rungs of the ladder are implemented here: normalisation,
 * script detection, and token counting. Spelling correction, lemmatisation and the LLM fallback
 * arrive with the query-resolution phase; until then an unrecognised single token is simply
 * treated as a word, which is what the old pipeline did anyway.
 */
class QueryResolver {

    /** Longer than this many tokens is prose, not a headword. */
    private val MAX_PHRASE_TOKENS = 5
    private val SENTENCE_TOKEN_THRESHOLD = 6

    fun resolve(rawInput: String): ResolvedQuery {
        val raw = rawInput
        val hadTerminalPunctuation = raw.trimEnd().lastOrNull() in setOf('.', '!', '?')
        val normalized = normalize(raw)

        if (normalized.isBlank()) {
            return ResolvedQuery(
                raw = raw, normalized = "", language = "unknown",
                kind = QueryKind.UNKNOWN, lemma = null, surface = raw.trim()
            )
        }

        val tokens = normalized.split(' ').filter { it.isNotBlank() }

        if (normalized.any { it in 'Ѐ'..'ӿ' }) {
            val kind = when {
                tokens.size == 1 -> QueryKind.RU_WORD
                tokens.size <= 4 -> QueryKind.RU_PHRASE
                else -> QueryKind.RU_SENTENCE
            }
            return ResolvedQuery(
                raw = raw, normalized = normalized, language = "ru",
                kind = kind, lemma = normalized, surface = normalized
            )
        }

        val looksLikeProse = tokens.size >= SENTENCE_TOKEN_THRESHOLD ||
            (tokens.size >= 4 && hadTerminalPunctuation) ||
            rawInput.contains(", ")

        val kind = when {
            looksLikeProse -> QueryKind.SENTENCE
            tokens.size in 2..MAX_PHRASE_TOKENS -> QueryKind.PHRASE
            else -> QueryKind.WORD
        }

        return ResolvedQuery(
            raw = raw,
            normalized = normalized,
            language = "en",
            kind = kind,
            // A sentence has no single headword; the context pipeline picks the target instead.
            lemma = if (kind == QueryKind.SENTENCE) null else normalized,
            surface = normalized
        )
    }

    /**
     * Trims, unifies Unicode, collapses whitespace and strips wrapping quotes plus trailing
     * sentence punctuation, so `"Running."` and `running` resolve to the same lookup.
     */
    fun normalize(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim('"', '\'', '«', '»', '“', '”', ' ')
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()
            .lowercase()
}
