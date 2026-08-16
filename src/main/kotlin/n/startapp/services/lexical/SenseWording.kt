package n.startapp.services.lexical

import n.startapp.models.lexical.LexicalEntry
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
        val example: String?
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

    fun of(entry: LexicalEntry, senseId: String?): Wording? =
        senseOf(entry, senseId)?.let { sense ->
            Wording(
                senseId = sense.id,
                translation = sense.translationsRu.joinToString(", ").trim(),
                // Английское определение — то, что эти карточки показывали всегда; русское
                // объяснение идёт запасным, иначе у слова без английской строки не осталось бы
                // ничего, кроме заголовка.
                definition = sense.definitionEn.takeIf { it.isNotBlank() }
                    ?: sense.definitionRu.takeIf { it.isNotBlank() },
                example = sense.examples.firstOrNull()?.en?.takeIf { it.isNotBlank() }
            )
        }
}
