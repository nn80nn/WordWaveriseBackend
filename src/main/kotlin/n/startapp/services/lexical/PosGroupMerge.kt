package n.startapp.services.lexical

import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense

/**
 * Stitches the per-part-of-speech annotation sections back into one article.
 *
 * The sections are written by independent concurrent calls that each see **all** the raw
 * fragments, so nothing stops two of them describing the same material. That is exactly what
 * happened to `grow up`: sources tag it both `verb` and `phrasal verb`, so it fanned out into
 * two calls, and both — obeying the prompt's own rule that a phrase is one group — came back
 * with a group whose `pos` was `phrasal verb`. The merge was a `flatMap`, so the reader got the
 * article twice, and the two groups even carried the same sense ids (`pv1`, `pv2`, `pv3`).
 *
 * Colliding ids are the serious half: a saved word and a flashcard are keyed by `(word, senseId)`,
 * so an ambiguous id means the bookmark points at two different meanings at once.
 *
 * Pure and side-effect free so every rule here is unit-testable.
 */
object PosGroupMerge {

    /** Two definitions this similar, in two sections nobody coordinated, are one definition. */
    private const val DEFINITION_SIMILARITY = 0.55

    /** Same idea from the Russian side: the sections rarely word the English identically. */
    private const val TRANSLATION_OVERLAP = 0.6

    private val POS_CODES = mapOf(
        "noun" to "n", "verb" to "v", "adjective" to "adj", "adverb" to "adv",
        "pronoun" to "pron", "preposition" to "prep", "conjunction" to "conj",
        "determiner" to "det", "numeral" to "num", "interjection" to "interj",
        "phrase" to "phr", "idiom" to "idm", "phrasal verb" to "pv",
        "prefix" to "pre", "suffix" to "suf", "abbreviation" to "abbr"
    )

    /**
     * Splitting an article by part of speech only pays off when the parts are genuinely separate.
     *
     * A phrase has one part of speech by construction — rule 9 of the prompt says so — so asking
     * for it twice buys nothing and invites both answers to describe the whole phrase. The
     * headword itself is checked as well as [kind]: `kind` comes from the resolver and a
     * multi-word headword is a phrase whatever it was labelled.
     */
    fun shouldSplitByPartOfSpeech(lemma: String, kind: LexicalKind, partsOfSpeech: List<String>): Boolean {
        if (partsOfSpeech.size <= 1) return false
        if (kind != LexicalKind.WORD) return false
        return !lemma.trim().contains(' ')
    }

    /**
     * Merges sections in the order they were requested, dropping what a later section repeats.
     *
     * Deliberately asymmetric: senses inside one section stay untouched, because that model call
     * was told to merge duplicates and did so knowing the whole list. Only a *later* section's
     * sense is tested against what is already kept — no coordination existed there, so that is
     * the only place a duplicate can come from, and the only place it is safe to remove one.
     *
     * Ids are reassigned at the end so they are unique across the finished article.
     */
    fun merge(sections: List<List<PosGroup>>): List<PosGroup> {
        val order = mutableListOf<String>()
        val byPos = LinkedHashMap<String, PosGroup>()

        for (section in sections) {
            for (group in section) {
                val existing = byPos[group.pos]
                if (existing == null) {
                    order += group.pos
                    byPos[group.pos] = group
                    continue
                }
                val fresh = group.senses.filterNot { candidate ->
                    existing.senses.any { duplicates(it, candidate) }
                }
                byPos[group.pos] = existing.copy(
                    senses = existing.senses + fresh,
                    // A section that has the forms table fills one the first section left empty.
                    forms = existing.forms ?: group.forms,
                    pronunciations = existing.pronunciations.ifEmpty { group.pronunciations }
                )
            }
        }

        return order.map { pos -> renumber(byPos.getValue(pos)) }
    }

    private fun renumber(group: PosGroup): PosGroup {
        val code = POS_CODES[group.pos] ?: group.pos.take(3)
        return group.copy(
            senses = group.senses.mapIndexed { index, sense -> sense.copy(id = "$code${index + 1}") }
        )
    }

    /** The same meaning said twice — judged from both sides, because either can be reworded. */
    fun duplicates(a: Sense, b: Sense): Boolean =
        jaccard(stems(a.definitionEn), stems(b.definitionEn)) >= DEFINITION_SIMILARITY ||
            jaccard(normalizedTranslations(a), normalizedTranslations(b)) >= TRANSLATION_OVERLAP

    private fun normalizedTranslations(sense: Sense): Set<String> =
        sense.translationsRu
            .map { it.trim().lowercase().replace(Regex("""[^\p{L}\p{N} ]+"""), "").trim() }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Words reduced to a crude stem, singular and plural collapsed.
     *
     * Without it two renderings of one sense score far apart on nothing but number — "about a
     * place, idea, movement" against "about places, ideas, movements" shares five words out of
     * eighteen and reads as a different sense.
     */
    private fun stems(text: String): Set<String> =
        text.lowercase()
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotBlank() }
            .map { word ->
                when {
                    word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
                    word.length > 4 && word.endsWith("es") -> word.dropLast(2)
                    word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
                    else -> word
                }
            }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return intersection.toDouble() / (a.size + b.size - intersection)
    }
}
