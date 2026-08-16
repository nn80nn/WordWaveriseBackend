package n.startapp.services.exercise

import n.startapp.models.exercise.Exercise
import n.startapp.models.exercise.Exercise.Companion.BLANK
import n.startapp.models.exercise.ExerciseFormat
import n.startapp.models.exercise.ExerciseKind
import n.startapp.models.exercise.ExerciseSource
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
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
    /**
     * The sense the user pinned when they saved this word, if they did.
     *
     * Without it every question about `resolve` was built from the article's first sense, so
     * someone who deliberately saved "разлагать" was drilled on "решать" — the exercise quietly
     * disagreed with their own card.
     */
    val senseId: String? = null,
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
 * collocations, real inflected forms, a recording. Asking a model to invent them would be
 * slower, cost a call per question, and produce sentences no dictionary ever vouched for. The
 * model is left with the one kind that genuinely needs it (see [ExerciseKind.CONTEXT_CHOICE] in
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
        ExerciseKind.TRANSLATE_EN_RU,
        ExerciseKind.FILL_BLANK,
        ExerciseKind.COLLOCATION,
        ExerciseKind.WORD_FORM,
        ExerciseKind.SPELLING,
        ExerciseKind.LISTENING
    )

    private const val OPTIONS = 4

    /** Definition overlap above which two words count as neighbours rather than strangers. */
    private const val NEIGHBOUR_OVERLAP = 0.2

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
        ExerciseKind.TRANSLATE_RU_EN -> translateRuEn(target, pool)
        ExerciseKind.TRANSLATE_EN_RU -> translateEnRu(target)
        ExerciseKind.FILL_BLANK -> fillBlank(target)
        ExerciseKind.COLLOCATION -> collocation(target, pool, random)
        ExerciseKind.WORD_FORM -> wordForm(target, random)
        ExerciseKind.SPELLING -> spelling(target)
        ExerciseKind.LISTENING -> listening(target)
        ExerciseKind.CONTEXT_CHOICE -> null // model-written; see ExerciseService
    }

    // ── Choice kinds ──────────────────────────────────────────────────────────

    /** «Что значит X?» — the Russian side, because that is how the word was learned. */
    private fun meaningChoice(target: PracticeWord, pool: List<PracticeWord>, random: Random): Exercise? {
        val correct = russianOf(target) ?: return null

        // Every sense of the target, not only the one on show. A second sense of the same word
        // is a *true* answer wearing a distractor's clothes, and offering it makes the question
        // unanswerable — the one failure mode a vocabulary exercise cannot afford.
        val ownMeanings = allRussianOf(target).map(ExerciseGrading::normalize).toSet()

        val distractors = rankedPeers(target, pool, random)
            .mapNotNull(::russianOf)
            .filter { ExerciseGrading.normalize(it) !in ownMeanings }
            .distinctBy(ExerciseGrading::normalize)
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
            hintRu = groupOf(target)?.posRu,
            explanationRu = target.definition?.let { "Определение: $it" },
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = if (target.entry != null) ExerciseSource.CORPUS else ExerciseSource.CARD
        )
    }

    /** The reverse direction, and deliberately from the English definition rather than the Russian. */
    private fun wordChoice(target: PracticeWord, pool: List<PracticeWord>, random: Random): Exercise? {
        val definition = target.definition?.trim()?.takeIf { it.length > 8 } ?: return null
        val distractors = rankedPeers(target, pool, random)
            .map { it.word }
            .distinctBy { it.lowercase() }
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
        if (target.entry == null) return null
        // Коллокации принадлежат значению: «resolve a dispute» — это не то же слово, что
        // «resolve into components», и брать чужую пару значило бы спрашивать о другом смысле.
        val candidate = sensesOf(target).asSequence()
            .flatMap { it.collocations.asSequence() }
            .firstOrNull { collo -> containsAnyForm(collo.pattern, target.forms) }
            ?: return null

        val surface = findForm(candidate.pattern, target.forms) ?: return null
        val question = blankOut(candidate.pattern, surface)

        val distractors = rankedPeers(target, pool, random)
            .map { it.word }
            .distinctBy { it.lowercase() }
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

    /**
     * Russian meaning in, English word out.
     *
     * A folder can easily hold two words for one Russian meaning — *resolve* and *settle* are
     * both «решать». The question shows the meaning and cannot say which of them it wants, so
     * every pool word that carries that meaning is accepted. Insisting on one of two right
     * answers is the fastest way to teach someone that the exercise is not to be trusted.
     */
    private fun translateRuEn(target: PracticeWord, pool: List<PracticeWord>): Exercise? {
        val russian = russianOf(target) ?: return null
        val shown = splitMeanings(russian).map(ExerciseGrading::normalize).toSet()

        val alsoRight = pool.asSequence()
            .filter { !it.word.equals(target.word, ignoreCase = true) }
            .filter { peer ->
                allRussianOf(peer).any { ExerciseGrading.normalize(it) in shown }
            }
            .map { it.word }
            .distinctBy { it.lowercase() }
            .toList()

        return Exercise(
            id = id(target, ExerciseKind.TRANSLATE_RU_EN),
            kind = ExerciseKind.TRANSLATE_RU_EN,
            format = ExerciseFormat.INPUT,
            word = target.word,
            promptRu = "Напишите английское слово",
            question = russian,
            answer = target.word,
            acceptedAnswers = alsoRight,
            hintRu = letterHint(target.word),
            explanationRu = target.definition,
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = if (target.entry != null) ExerciseSource.CORPUS else ExerciseSource.CARD
        )
    }

    /**
     * English word in, Russian meaning out — the direction nothing else tests.
     *
     * Every equivalent the article records counts, across every sense: a word with several
     * meanings has several right answers, and «разрешать» for *resolve* is not a lesser answer
     * than «решать».
     */
    private fun translateEnRu(target: PracticeWord): Exercise? {
        val all = allRussianOf(target)
        // Показываем и засчитываем первым то значение, которое человек выбрал; остальные
        // значения статьи всё равно принимаются — см. `acceptedAnswers` ниже.
        val primary = primaryRussianOf(target) ?: all.firstOrNull() ?: return null

        return Exercise(
            id = id(target, ExerciseKind.TRANSLATE_EN_RU),
            kind = ExerciseKind.TRANSLATE_EN_RU,
            format = ExerciseFormat.INPUT,
            word = target.word,
            promptRu = "Напишите значение по-русски",
            question = target.word,
            answer = primary,
            acceptedAnswers = all.filterNot {
                ExerciseGrading.normalize(it) == ExerciseGrading.normalize(primary)
            },
            hintRu = groupOf(target)?.posRu,
            explanationRu = listOfNotNull(
                all.takeIf { it.size > 1 }?.joinToString(", ")?.let { "Подходит любое: $it" },
                target.definition
            ).joinToString("\n").takeIf { it.isNotBlank() },
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
        val bilingual = sensesOf(target)
            .flatMap { it.examples }
            .firstOrNull { containsAnyForm(it.en, target.forms) }

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
        // Слово без пина берёт первую группу, у которой вообще есть формы; с пином —
        // только свою: у существительного множественное число, у глагола прошедшее время.
        val group = if (target.senseId.isNullOrBlank())
            target.entry?.posGroups?.firstOrNull { it.forms?.all()?.isNotEmpty() == true }
        else groupOf(target)
        val forms = group?.forms?.takeIf { it.all().isNotEmpty() } ?: return null

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

    /**
     * Hear it, write it.
     *
     * The recording is already in the article — scraped beside the IPA and never model-written
     * — so this is the cheapest exercise there is and the only one that practises the link
     * English breaks most often: what a word sounds like against how it is spelled.
     *
     * The question carries no text at all. Showing the meaning would turn it back into a
     * translation exercise with a sound effect.
     */
    private fun listening(target: PracticeWord): Exercise? {
        val audio = audioOf(target) ?: return null
        return Exercise(
            id = id(target, ExerciseKind.LISTENING),
            kind = ExerciseKind.LISTENING,
            format = ExerciseFormat.INPUT,
            word = target.word,
            promptRu = "Послушайте и напишите слово",
            question = "",
            audioUrl = audio,
            answer = target.word,
            hintRu = letterHint(target.word),
            explanationRu = listOfNotNull(russianOf(target), target.definition)
                .joinToString(" — ").takeIf { it.isNotBlank() },
            cardId = target.cardId,
            savedWordId = target.savedWordId,
            source = ExerciseSource.CORPUS
        )
    }

    // ── Which sense a question is about ───────────────────────────────────────

    /**
     * The senses a question may be built from: the pinned one alone, or the whole article.
     *
     * ⚠️ A pin the article no longer carries yields **nothing**, not the first sense. Dropping
     * back would build the question from a meaning the user never chose while their own card
     * still shows the one they did — the two would contradict each other, and nothing on screen
     * would explain why. The card's stored wording still feeds the kinds that need only text,
     * so such a word keeps practising; it just stops borrowing another sense's material.
     */
    private fun sensesOf(word: PracticeWord): List<Sense> {
        val entry = word.entry ?: return emptyList()
        val pinned = word.senseId?.takeIf { it.isNotBlank() }
            ?: return entry.posGroups.flatMap { it.senses }
        return entry.posGroups.asSequence()
            .flatMap { it.senses.asSequence() }
            .filter { it.id == pinned }
            .toList()
    }

    /** The sense a question is *primarily* about: the pinned one, else the article's first. */
    private fun primarySense(word: PracticeWord): Sense? =
        if (word.senseId.isNullOrBlank()) word.entry?.posGroups?.firstOrNull()?.senses?.firstOrNull()
        else sensesOf(word).firstOrNull()

    /**
     * The part-of-speech group the question belongs to.
     *
     * Homographs make this load-bearing: `resolve` the noun takes a plural, `resolve` the verb
     * takes a past tense, and drilling one against the other's forms is simply wrong.
     */
    private fun groupOf(word: PracticeWord): PosGroup? {
        val entry = word.entry ?: return null
        val pinned = word.senseId?.takeIf { it.isNotBlank() } ?: return entry.posGroups.firstOrNull()
        return entry.posGroups.firstOrNull { group -> group.senses.any { it.id == pinned } }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The Russian side of a word: the corpus sense first, the card's own wording second. */
    fun russianOf(word: PracticeWord): String? {
        val fromEntry = primarySense(word)
            ?.translationsRu?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() }
        return fromEntry ?: word.translation?.trim()?.takeIf { it.isNotBlank() }
    }

    /** One equivalent, not a list — what a typed answer is graded against first. */
    private fun primaryRussianOf(word: PracticeWord): String? =
        russianOf(word)?.let { splitMeanings(it).firstOrNull() ?: it }

    /**
     * Every Russian equivalent the article records, across every part of speech and every sense
     * — in article order, so the first one is still the primary meaning.
     */
    fun allRussianOf(word: PracticeWord): List<String> {
        val fromEntry = word.entry?.posGroups.orEmpty()
            .flatMap { it.senses }
            .flatMap { it.translationsRu }
        return (fromEntry + splitMeanings(word.translation.orEmpty()))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(ExerciseGrading::normalize)
    }

    /** A stored translation is often several equivalents in one string. */
    private fun splitMeanings(value: String): List<String> =
        value.split(',', ';', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * The other words in the folder, closest in meaning first.
     *
     * Random distractors make a choice question a formality: *transportir* is never mistaken
     * for *come across*, so the learner picks the only plausible option without knowing the
     * word. The article already says which words are close — its `synonyms`, its part of
     * speech, the wording of its definition — and a near-miss is both harder and the only
     * version that teaches anything. Ties are broken randomly so the same folder does not
     * produce the same four options every time.
     */
    private fun rankedPeers(
        target: PracticeWord,
        pool: List<PracticeWord>,
        random: Random
    ): List<PracticeWord> {
        val targetSynonyms = synonymsOf(target)
        val targetPos = posOf(target)
        val targetWords = significantWords(target.definition.orEmpty())

        return pool.asSequence()
            .filter { !it.word.equals(target.word, ignoreCase = true) }
            .toList()
            .shuffled(random)
            .sortedByDescending { peer ->
                val synonymLinked = peer.word.lowercase() in targetSynonyms ||
                    target.word.lowercase() in synonymsOf(peer)
                val samePos = targetPos != null && posOf(peer) == targetPos
                val overlap = overlapRatio(targetWords, significantWords(peer.definition.orEmpty()))
                when {
                    synonymLinked -> 3
                    samePos && overlap >= NEIGHBOUR_OVERLAP -> 2
                    samePos -> 1
                    else -> 0
                }
            }
    }

    private fun synonymsOf(word: PracticeWord): Set<String> =
        sensesOf(word)
            .flatMap { it.synonyms }
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun posOf(word: PracticeWord): String? =
        groupOf(word)?.pos?.trim()?.lowercase()

    /** The recording, wherever the article happens to keep it. */
    private fun audioOf(word: PracticeWord): String? {
        val entry = word.entry ?: return null
        entry.audioUrl?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        entry.pronunciations.firstNotNullOfOrNull { it.audioMp3Url?.takeIf { url -> url.isNotBlank() } }
            ?.let { return it }
        return entry.posGroups
            .flatMap { it.pronunciations }
            .firstNotNullOfOrNull { it.audioMp3Url?.takeIf { url -> url.isNotBlank() } }
    }

    /** `p·····e` — enough to confirm a half-remembered word, not enough to guess a new one. */
    fun letterHint(word: String): String {
        val trimmed = word.trim()
        if (trimmed.length < 3) return "${trimmed.length} букв"
        return trimmed.first() + "·".repeat(trimmed.length - 2) + trimmed.last() +
            "  (${trimmed.length})"
    }

    private val STOP_WORDS = setOf(
        "a", "an", "the", "of", "to", "or", "and", "in", "on", "for", "with",
        "that", "this", "is", "are", "be", "as", "by", "at", "from", "it", "its",
        "something", "someone", "make", "made", "used"
    )

    private fun significantWords(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    private fun overlapRatio(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
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
