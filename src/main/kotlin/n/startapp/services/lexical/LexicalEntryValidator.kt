package n.startapp.services.lexical

import n.startapp.models.lexical.BilingualExample
import n.startapp.models.lexical.Collocation
import n.startapp.models.lexical.DraftEntry
import n.startapp.models.lexical.DraftSense
import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.models.lexical.SourceRef
import n.startapp.models.lexical.parseRegister

data class ValidationResult(
    val posGroups: List<PosGroup>,
    val issues: List<String>,
    val fatal: Boolean
)

/**
 * Turns a model [DraftEntry] into trusted [PosGroup]s.
 *
 * The schema constrains shape; this constrains truthfulness. Two rules carry most of the weight:
 * a sense cannot claim grounding it does not have (out-of-range refs are dropped, and a sense
 * left with none is forcibly marked `generated`), and a model that ignores the supplied sources
 * wholesale is rejected outright rather than served.
 *
 * Pure and side-effect free so every rule is unit-testable.
 */
object LexicalEntryValidator {

    /** Above this share of invented senses, with plenty of sources available, the model ignored them. */
    private const val MAX_GENERATED_SHARE = 0.6
    private const val GENERATED_SHARE_MIN_SOURCES = 5

    /** Keys the model is not permitted to emit; the schema has no slot for them. */
    private val FORBIDDEN_KEY_PATTERN =
        Regex("\"(ipa|phonetic|phonetics|pronunciation|pronunciations|audio|audioUrl|audioMp3Url|url|href|source|sources)\"\\s*:", RegexOption.IGNORE_CASE)

    private val POS_CODES = mapOf(
        "noun" to "n", "verb" to "v", "adjective" to "adj", "adverb" to "adv",
        "pronoun" to "pron", "preposition" to "prep", "conjunction" to "conj",
        "determiner" to "det", "numeral" to "num", "interjection" to "interj",
        "phrase" to "phr", "idiom" to "idm", "phrasal verb" to "pv",
        "prefix" to "pre", "suffix" to "suf", "abbreviation" to "abbr"
    )

    private val POS_ORDER = listOf(
        "noun", "verb", "adjective", "adverb", "phrasal verb", "idiom", "phrase",
        "pronoun", "preposition", "conjunction", "determiner", "numeral",
        "interjection", "prefix", "suffix", "abbreviation"
    )

    fun validate(
        draft: DraftEntry,
        sources: List<SourceRef>,
        lemma: String,
        kind: LexicalKind,
        rawJson: String? = null
    ): ValidationResult {
        val issues = mutableListOf<String>()

        rawJson?.let { raw ->
            FORBIDDEN_KEY_PATTERN.findAll(raw).map { it.groupValues[1] }.distinct().toList()
                .takeIf { it.isNotEmpty() }
                ?.let { issues += "модель попыталась вернуть запрещённые поля: ${it.joinToString(", ")} (отброшены)" }
        }

        val forms = draft.posGroups.mapNotNull { it.forms }.flatMap { it.toModel().all() }
        val groups = draft.posGroups.mapNotNull { group ->
            val pos = group.pos.trim().lowercase()
            if (pos !in ALLOWED_POS) {
                issues += "часть речи «${group.pos}» вне схемы — группа отброшена"
                return@mapNotNull null
            }
            val code = POS_CODES[pos] ?: pos.take(3)

            val senses = group.senses
                .mapNotNull { sanitizeSense(it, sources, lemma, kind, forms, issues) }
                .mapIndexed { index, sense -> sense.copy(id = "$code${index + 1}") }

            if (senses.isEmpty()) {
                issues += "часть речи «$pos» осталась без значений — группа отброшена"
                null
            } else {
                PosGroup(
                    pos = pos,
                    posRu = group.posRu.trim().ifBlank { pos },
                    forms = group.forms?.toModel(),
                    senses = senses
                )
            }
        }.sortedBy { group ->
            POS_ORDER.indexOf(group.pos).takeIf { it >= 0 } ?: POS_ORDER.size
        }

        val allSenses = groups.flatMap { it.senses }
        var fatal = false

        if (groups.isEmpty() || allSenses.isEmpty()) {
            issues += "в ответе не осталось ни одного значения"
            fatal = true
        } else if (sources.size >= GENERATED_SHARE_MIN_SOURCES) {
            val generatedShare = allSenses.count { it.generated }.toDouble() / allSenses.size
            if (generatedShare > MAX_GENERATED_SHARE) {
                issues += "модель проигнорировала источники: ${(generatedShare * 100).toInt()}% значений " +
                    "помечены как выдуманные при ${sources.size} доступных фрагментах"
                fatal = true
            }
        }

        if (draft.lemma.isNotBlank() && !lemmaLooksRight(draft.lemma, lemma)) {
            issues += "модель вернула лемму «${draft.lemma}» вместо «$lemma» — заменена на запрошенную"
        }

        return ValidationResult(groups, issues, fatal)
    }

    private fun sanitizeSense(
        draft: DraftSense,
        sources: List<SourceRef>,
        lemma: String,
        kind: LexicalKind,
        forms: List<String>,
        issues: MutableList<String>
    ): Sense? {
        if (draft.definitionRu.isBlank() || draft.translationsRu.none { it.isNotBlank() }) {
            issues += "значение «${draft.definitionEn.take(40)}» без русского перевода — отброшено"
            return null
        }

        val validRefs = draft.sourceRefs.filter { it in 1..sources.size }.distinct()
        if (validRefs.size != draft.sourceRefs.distinct().size) {
            issues += "значение «${draft.definitionEn.take(40)}» ссылалось на несуществующие фрагменты"
        }
        // A sense with no surviving reference is invented, whatever the model claimed.
        val generated = draft.generated || validRefs.isEmpty()

        val examples = sanitizeExamples(draft, sources, lemma, kind, forms, issues)

        return Sense(
            id = "",   // assigned by the caller once the sense order within its POS is known
            definitionEn = draft.definitionEn.trim(),
            definitionRu = draft.definitionRu.trim(),
            translationsRu = draft.translationsRu.map { it.trim() }.filter { it.isNotBlank() }.take(4),
            register = parseRegister(draft.register),
            cefr = draft.cefr?.trim()?.takeIf { it.isNotBlank() },
            domain = draft.domain?.trim()?.takeIf { it.isNotBlank() },
            examples = examples,
            collocations = draft.collocations
                .filter { it.pattern.isNotBlank() }
                .map { Collocation(it.pattern.trim(), it.ru?.trim()?.takeIf { ru -> ru.isNotBlank() }) }
                .take(5),
            synonyms = draft.synonyms.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(6),
            antonyms = draft.antonyms.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(6),
            sourceRefs = if (generated) emptyList() else validRefs,
            generated = generated,
            usageNote = draft.usageNote?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun sanitizeExamples(
        draft: DraftSense,
        sources: List<SourceRef>,
        lemma: String,
        kind: LexicalKind,
        forms: List<String>,
        issues: MutableList<String>
    ): List<BilingualExample> {
        val cleaned = draft.examples
            .filter { it.en.isNotBlank() && it.ru.isNotBlank() }
            .map { ex ->
                BilingualExample(
                    en = ex.en.trim(),
                    ru = ex.ru.trim(),
                    sourceRef = ex.sourceRef?.takeIf { it in 1..sources.size }
                )
            }

        // Only single words have a reliable surface form to look for; phrases get reworded.
        if (kind != LexicalKind.WORD || cleaned.size <= 1) return cleaned.take(3)

        val onTopic = cleaned.filter { mentions(it.en, lemma, forms) }
        return when {
            onTopic.isEmpty() -> {
                issues += "ни один пример к «${draft.definitionEn.take(40)}» не содержит заголовочное слово"
                cleaned.take(3)
            }
            onTopic.size < cleaned.size -> onTopic.take(3)
            else -> cleaned.take(3)
        }
    }

    /** Matches on a stem so regular inflections in the example still count. */
    private fun mentions(sentence: String, lemma: String, forms: List<String>): Boolean {
        val haystack = sentence.lowercase()
        val stem = lemma.trim().lowercase().let { it.take(maxOf(4, it.length - 2)) }
        if (stem.isNotBlank() && haystack.contains(stem)) return true
        return forms.any { it.isNotBlank() && haystack.contains(it.trim().lowercase()) }
    }

    private fun lemmaLooksRight(returned: String, requested: String): Boolean {
        val a = returned.trim().lowercase()
        val b = requested.trim().lowercase()
        if (a == b) return true
        return a.take(3) == b.take(3)
    }
}
