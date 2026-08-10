package n.startapp.services.lexical

import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.models.lexical.SourceRef

/**
 * Mechanically derives an entry from raw aggregated data when annotation is unavailable or
 * failed validation twice.
 *
 * The result carries `degraded = true` and no Russian: it exists so the user still sees the
 * dictionary content instead of an error, and so clients can fall back to the sources view.
 * A degraded entry must never be persisted — the next request should retry the model.
 */
object LexicalEntryFallback {

    private val POS_RU = mapOf(
        "noun" to "существительное", "verb" to "глагол", "adjective" to "прилагательное",
        "adverb" to "наречие", "pronoun" to "местоимение", "preposition" to "предлог",
        "conjunction" to "союз", "determiner" to "определитель", "numeral" to "числительное",
        "interjection" to "междометие", "phrase" to "фраза", "idiom" to "идиома",
        "phrasal verb" to "фразовый глагол", "abbreviation" to "аббревиатура"
    )

    private val POS_CODES = mapOf(
        "noun" to "n", "verb" to "v", "adjective" to "adj", "adverb" to "adv"
    )

    fun fromRaw(
        raw: WordDetailResponse,
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        sources: List<SourceRef>
    ): LexicalEntry {
        val groups = raw.entries.mapNotNull { entry ->
            val pos = entry.partOfSpeech?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val code = POS_CODES[pos] ?: pos.take(3)
            val senses = entry.meanings.mapIndexed { index, meaning ->
                Sense(
                    id = "$code${index + 1}",
                    definitionEn = meaning.definition,
                    definitionRu = "",
                    translationsRu = listOfNotNull(entry.translation?.takeIf { it.isNotBlank() }),
                    examples = emptyList(),
                    // Point at the source fragment carrying this definition, when we can find it.
                    sourceRefs = sources
                        .filter { it.definition.equals(meaning.definition, ignoreCase = true) }
                        .map { it.index },
                    generated = false
                )
            }
            if (senses.isEmpty()) null
            else PosGroup(
                pos = pos,
                posRu = POS_RU[pos] ?: pos,
                pronunciations = entry.pronunciations,
                senses = senses
            )
        }

        return LexicalEntry(
            lemma = lemma,
            queryForm = queryForm,
            kind = kind,
            pronunciations = raw.pronunciations,
            phonetic = raw.phonetic,
            audioUrl = raw.audioUrl,
            posGroups = groups,
            sources = sources,
            degraded = true,
            generatedAt = System.currentTimeMillis()
        )
    }
}
