package n.startapp.services.lexical

import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense

/**
 * Turns one sense of an article into the two lines a saved word and a card show.
 *
 * A word means nothing on its own — `resolve` is "решать" and "разрешать" and "рассасываться",
 * and which one the user cared about is knowable only at the moment they saved it. That choice
 * travels as a sense id, and everything downstream (the saved list, the card, the corpus
 * refresh) has to read the *same* sense back, or the pin would quietly drift to the first one.
 *
 * ⚠️ An unknown [senseId] returns null rather than falling back to the first sense. Falling back
 * would be the worst of both worlds: the user's stored wording gets overwritten with a sense
 * they never picked, and nothing on screen says so. Null means "leave what is stored alone".
 */
object SenseWording {

    /** What one sense contributes to a saved word or a card. */
    data class Wording(
        val senseId: String,
        val translation: String,
        val definition: String?,
        val example: String?,
        /** IPA for *this* sense's part of speech — see [pronunciationFor]. */
        val phonetic: String? = null,
        val audioUrl: String? = null
    )

    /**
     * The sense [senseId] names, or — when it is null — the first sense of the first part of
     * speech, which is what an unpinned word has always shown.
     */
    fun senseOf(entry: LexicalEntry, senseId: String?): Sense? {
        if (senseId.isNullOrBlank()) return entry.posGroups.firstOrNull()?.senses?.firstOrNull()
        return entry.posGroups.asSequence()
            .flatMap { it.senses.asSequence() }
            .firstOrNull { it.id == senseId }
    }

    /** The part-of-speech group a sense belongs to. */
    fun groupOf(entry: LexicalEntry, senseId: String?): PosGroup? {
        if (senseId.isNullOrBlank()) return entry.posGroups.firstOrNull()
        return entry.posGroups.firstOrNull { group -> group.senses.any { it.id == senseId } }
    }

    /**
     * How the word sounds in this sense.
     *
     * ⚠️ Taken from the sense's own part-of-speech group before the entry-wide fields, because
     * that is the whole reason the annotation layer keeps pronunciations per group: `lead`,
     * `bow`, `read`, `live` and `close` are different words to the ear. A card that says
     * "resolve" the noun must not play the verb's recording.
     *
     * UK first only to be deterministic — a card shows one pronunciation, and picking a
     * different one on each refresh would make the card look like it kept changing.
     */
    private fun pronunciationFor(entry: LexicalEntry, group: PosGroup?): PronunciationEntry? {
        val ordered = listOf("uk", "us")
        fun pick(list: List<PronunciationEntry>): PronunciationEntry? =
            ordered.firstNotNullOfOrNull { region ->
                list.firstOrNull { it.region.equals(region, ignoreCase = true) && !it.ipa.isNullOrBlank() }
            } ?: list.firstOrNull { !it.ipa.isNullOrBlank() }
                ?: list.firstOrNull { !it.audioMp3Url.isNullOrBlank() }

        return pick(group?.pronunciations.orEmpty()) ?: pick(entry.pronunciations)
    }

    fun of(entry: LexicalEntry, senseId: String?): Wording? =
        senseOf(entry, senseId)?.let { sense ->
            val group = groupOf(entry, senseId)
            val pronunciation = pronunciationFor(entry, group)
            Wording(
                senseId = sense.id,
                translation = sense.translationsRu.joinToString(", ").trim(),
                // Английское определение — то, что эти карточки показывали всегда; русское
                // объяснение идёт запасным, иначе у слова без английской строки не осталось бы
                // ничего, кроме заголовка.
                definition = sense.definitionEn.takeIf { it.isNotBlank() }
                    ?: sense.definitionRu.takeIf { it.isNotBlank() },
                example = sense.examples.firstOrNull()?.en?.takeIf { it.isNotBlank() },
                phonetic = pronunciation?.ipa?.takeIf { it.isNotBlank() }
                    ?: entry.phonetic?.takeIf { it.isNotBlank() },
                audioUrl = pronunciation?.audioMp3Url?.takeIf { it.isNotBlank() }
                    ?: entry.audioUrl?.takeIf { it.isNotBlank() }
            )
        }
}
