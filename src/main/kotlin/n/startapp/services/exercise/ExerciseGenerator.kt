package n.startapp.services.exercise

import n.startapp.models.exercise.Exercise
import n.startapp.models.exercise.Exercise.Companion.BLANK
import n.startapp.models.exercise.ExerciseFormat
import n.startapp.models.exercise.ExerciseKind
import n.startapp.models.exercise.ExerciseSource
import n.startapp.models.lexical.LexicalEntry
import kotlin.random.Random

/**
 * One word as it enters practice: what the learner saved, plus the annotated article when the
 * corpus has one.
 */
data class PracticeWord(
    val word: String,
    val translation: String? = null,
    val definition: String? = null,
    val example: String? = null,
    val cardId: Int? = null,
    val savedWordId: Int? = null,
    val entry: LexicalEntry? = null
) {
    /** Every written form of the word, longest first — what a sentence might actually contain. */
    val forms: List<String> by lazy {
        val fromEntry = entry?.posGroups?.mapNotNull { it.forms }?.flatMap { it.all() } ?: emptyList()
        (listOf(word) + fromEntry)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
    }
}

/**
 * Builds questions out of the dictionary the user already has.
 *
 * The point of doing this here rather than in a prompt is that the corpus already holds the
 * things a good exercise is made of — a real definition, a real bilingual example, real
 * collocations, real inflected forms. Asking a model to invent them would be slower, cost a
 * call per question, and produce sentences no dictionary ever vouched for. The model is left
 * with the one kind that genuinely needs it (see [ExerciseKind.CONTEXT_CHOICE] in
 * [ExerciseService]), and everything here is instant, free and repeatable.
 *
 * Pure: no IO, no clock, and randomness arrives as a parameter, so the whole thing is testable.
 */
object ExerciseGenerator {

    /** Kinds this object can produce without any model call. */
    val LOCAL_KINDS = listOf(
        ExerciseKind.MEANING_CHOICE,
        ExerciseKind.WORD_CHOICE,
        ExerciseKind.TRANSLATE_RU_EN,
        ExerciseKind.FILL_BLANK,
        ExerciseKind.COLLOCATION,
        ExerciseKind.WORD_FORM,
        ExerciseKind.SPELLING
    )

    private const val OPTIONS = 4

    /** Every question of [kind] the pool can support, in pool order. */
    fun build(pool: List<PracticeWord>, kind: ExerciseKind, random: Random): List<Exercise> =
        pool.mapNotNull { build(it, pool, kind, random) }

    fun build(
        target: PracticeWord,
        pool: List<PracticeWord>,
        kind: ExerciseKind,
        random: Random
    ): Exercise? = when (kind) {
        ExerciseKind.MEANING_CHOICE -> meaningChoice(target, pool, random)
        ExerciseKind.WORD_CHOICE -> wordChoice(target, pool, random)
        ExerciseKind.TRANSLATE_RU_EN -> translateRuEn(target)
        ExerciseKind.FILL_BLANK -> fillBlank(target)
        ExerciseKind.COLLOCATION -> collocation(target, pool, random)
        ExerciseKind.WORD_FORM -> wordForm(target, random)
        ExerciseKind.SPELLING -> spelling(target)
        ExerciseKind.CONTEXT_CHOICE -> null // model-written; see ExerciseService
    }

    // ── Choice kinds ──────────────────────────────────────────────────────────

    /** «Что значит X?» — the Russian side, because that is how the word was learned. */
    private fun meaningChoice(target: PracticeWord, pool: List<PracticeWord>, random: Random): Exercise? {
        val correct = russianOf(target) ?: return null
        val distractors = pool.asSequence()
            .filter { it.word != target.word }
            .mapNotNull(::russianOf)
            .filter { !sameMeaning(it, correct) }
            .distinct()
            .toList()
            .shuffled(random)
            .take(OPTIONS - 1)
        if (distractors.size < OPTIONS - 1) return null

        val options = (distractors + correct).shuffled(random)
        return Exercise(
            id = id(target, ExerciseKind.MEANING_CHOICE),
            kind = ExerciseKind.MEANING_CHOICE,
            format = ExerciseFormat.CHOICE,
            word = target.word,
            promptRu = "Что означает это слово?",
            question = target.word,
            options = options,
            correctIndex = options.indexOf(correct),
            answer = correct,
            hintRu = target.entry?.posGroups?.firstOrNull()?.posRu,
            explanationRu = target.definition?.let { "Определение: $it" },
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = if (target.entry != null) ExerciseSource.CORPUS else ExerciseSource.CARD
        )
    }

    /** The reverse direction, and deliberately from the English definition rather than the Russian. */
    private fun wordChoice(target: PracticeWord, pool: List<PracticeWord>, random: Random): Exercise? {
        val definition = target.definition?.trim()?.takeIf { it.length > 8 } ?: return null
        val distractors = pool.asSequence()
            .filter { !it.word.equals(target.word, ignoreCase = true) }
            .map { it.word }
            .distinctBy { it.lowercase() }
            .toList()
            .shuffled(random)
            .take(OPTIONS - 1)
        if (distractors.size < OPTIONS - 1) return null

        val options = (distractors + target.word).shuffled(random)
        return Exercise(
            id = id(target, ExerciseKind.WORD_CHOICE),
            kind = ExerciseKind.WORD_CHOICE,
            format = ExerciseFormat.CHOICE,
            word = target.word,
            promptRu = "Какое слово подходит под определение?",
            question = definition,
            questionIsSentence = true,
            options = options,
            correctIndex = options.indexOf(target.word),
            answer = target.word,
            hintRu = null,
            explanationRu = russianOf(target)?.let { "$it — ${target.word}" },
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = if (target.entry != null) ExerciseSource.CORPUS else ExerciseSource.CARD
        )
    }

    /**
     * Which word belongs in this fixed phrase.
     *
     * Collocations are the part of a word most learners never acquire from a definition —
     * knowing *decision* does not tell you it is *made* and not *done* — and the corpus records
     * them per sense, so the phrase is one a dictionary actually attested.
     */
    private fun collocation(target: PracticeWord, pool: List<PracticeWord>, random: Random): Exercise? {
        val entry = target.entry ?: return null
        val candidate = entry.posGroups.asSequence()
            .flatMap { it.senses.asSequence() }
            .flatMap { it.collocations.asSequence() }
            .firstOrNull { collo -> containsAnyForm(collo.pattern, target.forms) }
            ?: return null

        val surface = findForm(candidate.pattern, target.forms) ?: return null
        val question = blankOut(candidate.pattern, surface)

        val distractors = pool.asSequence()
            .filter { !it.word.equals(target.word, ignoreCase = true) }
            .map { it.word }
            .distinctBy { it.lowercase() }
            .toList()
            .shuffled(random)
            .take(OPTIONS - 1)
        if (distractors.size < OPTIONS - 1) return null

        val options = (distractors + surface).shuffled(random)
        return Exercise(
            id = id(target, ExerciseKind.COLLOCATION),
            kind = ExerciseKind.COLLOCATION,
            format = ExerciseFormat.CHOICE,
            word = target.word,
            promptRu = "Какое слово стоит в этом устойчивом сочетании?",
            question = question,
            questionIsSentence = true,
            options = options,
            correctIndex = options.indexOf(surface),
            answer = surface,
            hintRu = candidate.ru,
            explanationRu = "Устойчивое сочетание: ${candidate.pattern}" +
                (candidate.ru?.let { " — $it" } ?: ""),
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = ExerciseSource.CORPUS
        )
    }

    // ── Typed kinds ───────────────────────────────────────────────────────────

    private fun translateRuEn(target: PracticeWord): Exercise? {
        val russian = russianOf(target) ?: return null
        return Exercise(
            id = id(target, ExerciseKind.TRANSLATE_RU_EN),
            kind = ExerciseKind.TRANSLATE_RU_EN,
            format = ExerciseFormat.INPUT,
            word = target.word,
            promptRu = "Напишите английское слово",
            question = russian,
            answer = target.word,
            acceptedAnswers = emptyList(),
            hintRu = letterHint(target.word),
            explanationRu = target.definition,
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = if (target.entry != null) ExerciseSource.CORPUS else ExerciseSource.CARD
        )
    }

    /**
     * A real sentence with the word taken out.
     *
     * The sentence comes from the article's bilingual examples, which means the Russian
     * translation can be shown as the explanation — the learner sees not just that they were
     * wrong but what the sentence said.
     */
    private fun fillBlank(target: PracticeWord): Exercise? {
        val bilingual = target.entry?.posGroups
            ?.flatMap { it.senses }
            ?.flatMap { it.examples }
            ?.firstOrNull { containsAnyForm(it.en, target.forms) }

        val sentence = bilingual?.en ?: target.example?.takeIf { containsAnyForm(it, target.forms) }
        ?: return null
        val surface = findForm(sentence, target.forms) ?: return null

        return Exercise(
            id = id(target, ExerciseKind.FILL_BLANK),
            kind = ExerciseKind.FILL_BLANK,
            format = ExerciseFormat.INPUT,
            word = target.word,
            promptRu = "Вставьте пропущенное слово",
            question = blankOut(sentence, surface),
            questionIsSentence = true,
            // The sentence may need an inflected form; the dictionary form is close enough to
            // count as knowing the word, so both are accepted.
            answer = surface,
            acceptedAnswers = listOf(target.word),
            hintRu = russianOf(target),
            explanationRu = bilingual?.ru?.let { "«$it»" },
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = if (bilingual != null) ExerciseSource.CORPUS else ExerciseSource.CARD
        )
    }

    /** Inflection, drilled from the forms the annotation layer already derived and verified. */
    private fun wordForm(target: PracticeWord, random: Random): Exercise? {
        val group = target.entry?.posGroups?.firstOrNull { it.forms?.all()?.isNotEmpty() == true }
            ?: return null
        val forms = group.forms ?: return null

        val options = listOfNotNull(
            forms.plural?.let { "множественное число" to it },
            forms.past?.let { "прошедшее время" to it },
            forms.pastParticiple?.let { "причастие прошедшего времени" to it },
            forms.presentParticiple?.let { "форма -ing" to it },
            forms.thirdPerson?.let { "3-е лицо единственного числа" to it },
            forms.comparative?.let { "сравнительная степень" to it },
            forms.superlative?.let { "превосходная степень" to it }
        ).filter { !it.second.equals(target.word, ignoreCase = true) }

        val (label, form) = options.randomOrNull(random) ?: return null
        return Exercise(
            id = id(target, ExerciseKind.WORD_FORM),
            kind = ExerciseKind.WORD_FORM,
            format = ExerciseFormat.INPUT,
            word = target.word,
            promptRu = "Поставьте слово в форму: $label",
            question = target.word,
            answer = form,
            hintRu = letterHint(form),
            explanationRu = "${target.word} → $form ($label)",
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = ExerciseSource.CORPUS
        )
    }

    /** Recall plus spelling: the definition is in English, so nothing gives the letters away. */
    private fun spelling(target: PracticeWord): Exercise? {
        val definition = target.definition?.trim()?.takeIf { it.length > 8 } ?: return null
        return Exercise(
            id = id(target, ExerciseKind.SPELLING),
            kind = ExerciseKind.SPELLING,
            format = ExerciseFormat.INPUT,
            word = target.word,
            promptRu = "Напишите слово по этому определению",
            question = definition,
            questionIsSentence = true,
            answer = target.word,
            hintRu = letterHint(target.word),
            explanationRu = russianOf(target),
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = if (target.entry != null) ExerciseSource.CORPUS else ExerciseSource.CARD
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The Russian side of a word: the corpus sense first, the card's own wording second. */
    fun russianOf(word: PracticeWord): String? {
        val fromEntry = word.entry?.posGroups?.firstOrNull()?.senses?.firstOrNull()
            ?.translationsRu?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() }
        return fromEntry ?: word.translation?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Two options must not be the same answer wearing different punctuation. */
    private fun sameMeaning(a: String, b: String): Boolean =
        ExerciseGrading.normalize(a) == ExerciseGrading.normalize(b)

    /** `p·······e` — enough to confirm a half-remembered word, not enough to guess a new one. */
    fun letterHint(word: String): String {
        val trimmed = word.trim()
        if (trimmed.length < 3) return "${trimmed.length} букв"
        return trimmed.first() + "·".repeat(trimmed.length - 2) + trimmed.last() +
            "  (${trimmed.length})"
    }

    private fun wordRegex(form: String): Regex =
        Regex("(?<![\\p{L}])" + Regex.escape(form) + "(?![\\p{L}])", RegexOption.IGNORE_CASE)

    private fun containsAnyForm(text: String, forms: List<String>): Boolean =
        forms.any { wordRegex(it).containsMatchIn(text) }

    /** The form as it is actually written in [text] — capitalisation and all. */
    private fun findForm(text: String, forms: List<String>): String? =
        forms.firstNotNullOfOrNull { wordRegex(it).find(text)?.value }

    private fun blankOut(text: String, surface: String): String =
        wordRegex(surface).replaceFirst(text, BLANK)

    private fun id(target: PracticeWord, kind: ExerciseKind): String =
        "${kind.name.lowercase()}:${target.word.lowercase()}"

    private fun <T> List<T>.randomOrNull(random: Random): T? =
        if (isEmpty()) null else this[random.nextInt(size)]
}
