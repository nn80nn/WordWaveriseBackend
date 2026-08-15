package n.startapp.services.dictionary

/**
 * Reads the dictionary's own verdict on whether a form is a word or a pointer to one.
 *
 * Wiktionary documents misspellings as entries — "occured" resolves to "Misspelling of
 * occurred", "teh" to "Deliberate misspelling of the" — which is why "does anything have an
 * entry for this?" is not on its own enough to tell a typo from a rare word. But the entries
 * say exactly which they are, and that is a far better signal than any frequency comparison:
 * the lexicographer already made the judgement, in the text, naming the target.
 *
 * The rule is deliberately all-or-nothing. "wich" carries "Misspelling of which" *and* two real
 * senses about Cheshire salt towns, so it is a word that people also mistype, and it keeps its
 * own article. Only a form whose entry is nothing but pointers is treated as a pointer.
 */
object SpellingGloss {

    /**
     * Glosses that say the form itself is wrong or dead: an error, or a spelling no longer in
     * use. These are what license a correction.
     *
     * "Alternative spelling of" is deliberately not among them — "colour" points at "color"
     * without anyone having got anything wrong, and telling a British user they made a typo
     * would be its own bug.
     */
    private val CORRECTABLE = Regex(
        """^\s*(?:a\s+)?(?:\w+\s+){0,2}?(?:misspelling|(?:obsolete|archaic|nonstandard|informal)\s+(?:spelling|form)|eye\s+dialect)\s+of\s+([\p{L}][\p{L}'’\- ]*)""",
        RegexOption.IGNORE_CASE
    )

    /** Any pointer at all: "Alternative form of a lot", "Obsolete spelling of music". */
    private val REDIRECT = Regex(
        """^\s*(?:\w+\s+){0,2}?(?:spelling|form|dialect)\s+of\s+([\p{L}][\p{L}'’\- ]*)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Translingual bookkeeping that Wiktionary files under the same headword.
     *
     * Not a sense of an English word, so it must not count as one — it is the whole reason "teh"
     * looked like a real entry. Ignored rather than treated as a pointer: it names a language,
     * not a spelling.
     */
    private val NOT_A_SENSE = Regex("""^\s*ISO\s+639\S*\s+language\s+code\b""", RegexOption.IGNORE_CASE)

    /**
     * The word this form merely points at, or null if it stands on its own.
     *
     * Returns the most frequently named target rather than the first: "alot" is glossed once as
     * a misspelling of "allot" and twice as an alternative form of "a lot", and the majority is
     * the better answer. At least one gloss must call the form wrong or dead — a form that is
     * only ever an *alternative* spelling ("colour") is a real word, not a mistake.
     */
    fun redirectTarget(definitions: List<String>): String? {
        val senses = definitions.map { it.trim() }
            .filter { it.isNotBlank() && !NOT_A_SENSE.containsMatchIn(it) }
        if (senses.isEmpty()) return null

        val targets = senses.map { sense ->
            val correctable = CORRECTABLE.find(sense)
            val pointer = correctable ?: REDIRECT.find(sense) ?: return null
            clean(pointer.groupValues[1]) to (correctable != null)
        }
        if (targets.none { it.second }) return null

        return targets.map { it.first }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    /** Glosses trail off into the sentence they were cut from: "of the, for humorous effect". */
    private fun clean(target: String): String =
        target.trim().substringBefore(',').substringBefore('(').trim().trim('.', ' ').lowercase()
}
