package n.startapp.services.exercise

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import n.startapp.models.auth.UNCATEGORIZED_CATEGORY_ID
import n.startapp.models.exercise.Exercise
import n.startapp.models.exercise.Exercise.Companion.BLANK
import n.startapp.models.exercise.ExerciseBatch
import n.startapp.models.exercise.ExerciseFormat
import n.startapp.models.exercise.ExerciseKind
import n.startapp.models.exercise.ExerciseKindInfo
import n.startapp.models.exercise.ExerciseKindsResponse
import n.startapp.models.exercise.ExerciseRequest
import n.startapp.models.exercise.ExerciseScope
import n.startapp.models.exercise.ExerciseSource
import n.startapp.repositories.FlashcardRepository
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.LlmCacheRepository
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.SavedWordRepository
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.LlmModelTier
import n.startapp.services.ai.ResponseFormat
import n.startapp.services.group.FolderAccessResolver
import n.startapp.services.group.FolderCatalog
import org.slf4j.LoggerFactory
import kotlin.random.Random

/** What the model is asked for — the one exercise kind the corpus cannot supply by itself. */
@Serializable
private data class ContextDraft(
    val sentence: String = "",
    val sentenceRu: String? = null,
    val distractors: List<String> = emptyList(),
    val whyRu: String? = null
)

/**
 * Assembles a practice session for one user.
 *
 * The session is drawn from a *folder*, not from the whole vocabulary, because that is how
 * people actually revise — the words for one lesson, one text, one exam topic. Everything the
 * clients need to render and grade travels with the batch, so the phone and the browser show
 * the same questions for the same folder.
 *
 * Most kinds are built locally by [ExerciseGenerator] from the annotated corpus. The model is
 * asked for exactly one thing, [ExerciseKind.CONTEXT_CHOICE], and the result is cached forever:
 * a sentence where only the target word fits, plus three near-synonyms that almost do. That is
 * the question a dictionary cannot answer for you, and it is the one worth paying a call for.
 */
class ExerciseService(
    private val llm: LlmClient,
    private val entries: LexicalEntryRepository,
    private val savedWords: SavedWordRepository = SavedWordRepository(),
    private val flashcards: FlashcardRepository = FlashcardRepository(),
    private val cache: LlmCacheRepository? = null,
    /** What the learner may practise: their own words, plus the ones their groups lend them. */
    private val folders: FolderCatalog = FolderCatalog(FolderAccessResolver(), savedWords),
    private val categories: CategoryRepository = CategoryRepository()
) {
    private val logger = LoggerFactory.getLogger(ExerciseService::class.java)
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    companion object {
        /** Bump when [SYSTEM] changes: it is part of the cache key. */
        const val PROMPT_VERSION_CONTEXT = 1

        /** Fresh model calls per request. The rest of the batch must not wait on the model. */
        private const val MAX_FRESH_AI = 3

        /** Cached answers are instant; this bounds only the fresh ones. */
        private const val AI_BUDGET_MS = 30_000L

        private const val MAX_COUNT = 40

        private val SYSTEM = """
            Ты составляешь упражнение для русскоязычного человека, который учит английский.

            Тебе дают английское слово и его значение. Придумай ОДНО предложение,
            в котором пропущено это слово, и три слова-ловушки.

            Требования:
            - sentence: естественное английское предложение, 8–18 слов, с пропуском "$BLANK"
              на месте целевого слова. Контекст должен ОДНОЗНАЧНО указывать на целевое слово.
            - distractors: ровно 3 английских слова, БЛИЗКИХ по смыслу к целевому
              (синонимы или слова того же ряда), которые в это предложение НЕ подходят.
              Той же части речи и в той же грамматической форме, что и пропущенное слово.
              Не используй само целевое слово и его формы.
            - sentenceRu: естественный перевод предложения на русский (с целевым словом).
            - whyRu: 1–2 предложения по-русски, почему подходит именно целевое слово,
              а ловушки — нет. Ссылайся на конкретные слова предложения.

            Формат ответа — строго такой JSON:
            {"sentence":"...","sentenceRu":"...","distractors":["...","...","..."],"whyRu":"..."}

            Только JSON, без пояснений.
        """.trimIndent()

        /** Titles and blurbs live on the server so a new kind needs no client release. */
        private val KIND_TITLES: Map<ExerciseKind, Pair<String, String>> = mapOf(
            ExerciseKind.MEANING_CHOICE to
                ("Значение слова" to "Слово и четыре перевода — выберите верный."),
            ExerciseKind.WORD_CHOICE to
                ("Слово по определению" to "Английское определение и четыре слова."),
            ExerciseKind.TRANSLATE_RU_EN to
                ("Перевод на английский" to "Русское значение — напишите английское слово."),
            ExerciseKind.TRANSLATE_EN_RU to
                ("Перевод на русский" to "Английское слово — напишите значение. Подойдёт любое."),
            ExerciseKind.LISTENING to
                ("На слух" to "Послушайте запись и напишите слово."),
            ExerciseKind.FILL_BLANK to
                ("Пропущенное слово" to "Настоящее предложение из словаря с пропуском."),
            ExerciseKind.CONTEXT_CHOICE to
                ("Оттенок значения" to "Четыре близких синонима — подходит только один."),
            ExerciseKind.COLLOCATION to
                ("Сочетаемость" to "Устойчивое сочетание: какое слово в нём стоит."),
            ExerciseKind.SPELLING to
                ("Написание" to "Определение по-английски — напишите слово по буквам.")
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun generate(userId: Int, request: ExerciseRequest): ExerciseBatch {
        val pool = pool(userId, request.categoryId, request.scope)
        val count = request.count.coerceIn(1, MAX_COUNT)

        if (pool.isEmpty()) {
            return ExerciseBatch(
                exercises = emptyList(),
                wordsAvailable = 0,
                kindsUsed = emptyList(),
                noticeRu = emptyPoolNotice(request)
            )
        }

        val requested = request.kinds.ifEmpty { ExerciseKind.entries.toList() }
        val random = Random.Default

        val byKind = linkedMapOf<ExerciseKind, MutableList<Exercise>>()
        requested.filter { it in ExerciseGenerator.LOCAL_KINDS }.forEach { kind ->
            val built = ExerciseGenerator.build(pool, kind, random)
            if (built.isNotEmpty()) byKind[kind] = built.shuffled(random).toMutableList()
        }

        var aiAttempted = false
        if (request.useAi && ExerciseKind.CONTEXT_CHOICE in requested) {
            aiAttempted = true
            val contextExercises = contextChoices(pool.shuffled(random), count)
            if (contextExercises.isNotEmpty()) {
                byKind[ExerciseKind.CONTEXT_CHOICE] = contextExercises.toMutableList()
            }
        }

        val exercises = interleave(byKind, count)

        return ExerciseBatch(
            exercises = exercises,
            wordsAvailable = pool.size,
            kindsUsed = exercises.map { it.kind }.distinct(),
            noticeRu = notice(pool.size, requested, exercises, aiAttempted)
        )
    }

    /** What this folder could produce, so the picker can grey out what it cannot. */
    suspend fun kinds(userId: Int, categoryId: Int?, scope: ExerciseScope): ExerciseKindsResponse {
        val pool = pool(userId, categoryId, scope)
        val random = Random(0) // counting only; a stable draw keeps the numbers steady

        val infos = ExerciseKind.entries.map { kind ->
            val (title, description) = KIND_TITLES.getValue(kind)
            val available = when (kind) {
                // The model is asked per word and cached, so anything with an article can be asked.
                ExerciseKind.CONTEXT_CHOICE -> pool.count { it.entry != null }
                else -> ExerciseGenerator.build(pool, kind, random).size
            }
            ExerciseKindInfo(
                kind = kind,
                titleRu = title,
                descriptionRu = description,
                format = if (kind.isChoice()) ExerciseFormat.CHOICE else ExerciseFormat.INPUT,
                available = available
            )
        }
        return ExerciseKindsResponse(kinds = infos, wordsAvailable = pool.size)
    }

    // ── Pool ──────────────────────────────────────────────────────────────────

    /**
     * The words in play, each carrying its corpus article when there is one.
     *
     * Saved words are matched back to their card by word, so answering an exercise built on a
     * saved word can still move the spaced-repetition schedule — practice that does not count
     * towards anything is the reason exercise modes get abandoned.
     */
    private suspend fun pool(userId: Int, categoryId: Int?, scope: ExerciseScope): List<PracticeWord> {
        // Названная папка-группа отдаёт и то, что в ней лежит. Карточки расширяет сам запрос
        // (`categoryClause`), а сохранённые слова фильтруются здесь, в памяти, — поэтому набор
        // надо получить явно, иначе одна и та же папка практиковалась бы по-разному в двух
        // режимах: «мои слова» пустая, «карточки» полная.
        val scopeIds = categoryId
            ?.takeIf { it != UNCATEGORIZED_CATEGORY_ID }
            ?.let { categories.selfAndChildren(it).toSet() }

        val cards = when (scope) {
            ExerciseScope.DUE -> flashcards.getDueFlashcards(userId, categoryId)
            ExerciseScope.FLASHCARDS -> flashcards.getAllByUser(userId, categoryId)
            // A word's folder and its card's folder can differ — the card may have been moved,
            // or created before folders existed. Filtering the lookup table by folder too would
            // silently drop the link, and practice in that folder would stop counting towards
            // the schedule of the very cards it is about.
            ExerciseScope.SAVED -> flashcards.getAllByUser(userId, null)
        }
        val cardByWord = cards.associateBy { it.word.trim().lowercase() }

        val raw: List<PracticeWord> = when (scope) {
            // Not `savedWords.findByUserId`: a folder handed out by a group holds the teacher's
            // rows, and reading only the learner's own would make that folder practise as empty.
            //
            // ⚠️ Without a folder filter the class's words stay out. «Все» has to mean the same
            // thing here as it does in the word list: your own vocabulary. A teacher can hand
            // out hundreds of words, and letting them into the unfiltered session would bury
            // the learner's own — they would practise somebody else's list by default and have
            // no way to ask for theirs. A named folder is an explicit request, so it is honoured.
            ExerciseScope.SAVED -> folders.wordsFor(userId)
                .filter { categoryId != null || !it.readOnly }
                .map { it.word }
                .filter { matchesCategory(it.categoryIds, categoryId, scopeIds) }
                .map { saved ->
                    val card = cardByWord[saved.word.trim().lowercase()]
                    PracticeWord(
                        word = saved.word,
                        translation = card?.translation?.takeIf { it.isNotBlank() } ?: saved.translation,
                        definition = card?.definition ?: saved.definition,
                        example = card?.example ?: saved.example,
                        cardId = card?.id,
                        savedWordId = saved.id,
                        // Карточка могла быть перепривязана отдельно от слова, поэтому её
                        // выбор главнее: упражнение обязано спрашивать ровно то, что на ней.
                        senseId = card?.senseId ?: saved.senseId
                    )
                }

            else -> cards.map { card ->
                PracticeWord(
                    word = card.word,
                    translation = card.translation.takeIf { it.isNotBlank() },
                    definition = card.definition,
                    example = card.example,
                    cardId = card.id,
                    savedWordId = card.savedWordId,
                    senseId = card.senseId
                )
            }
        }

        if (raw.isEmpty()) return emptyList()

        // One indexed read for the whole session. Never triggers a scrape or an annotation:
        // a practice session must not turn into a fan-out of dictionary work.
        val articles = runCatching { entries.findLatestByLemmas(raw.map { it.word }) }
            .onFailure { logger.warn("Could not read the corpus for a practice session: ${it.message}") }
            .getOrDefault(emptyMap())

        return raw.map { it.copy(entry = articles[it.word.trim().lowercase()]) }
    }

    private fun matchesCategory(
        wordFolders: List<Int>,
        filter: Int?,
        /** [filter] together with the folders inside it; null for the two special filters. */
        scopeIds: Set<Int>?
    ): Boolean = when (filter) {
        null -> true
        UNCATEGORIZED_CATEGORY_ID -> wordFolders.isEmpty()
        else -> wordFolders.any { it in scopeIds.orEmpty() }
    }

    // ── The one model-written kind ────────────────────────────────────────────

    private suspend fun contextChoices(pool: List<PracticeWord>, count: Int): List<Exercise> {
        val candidates = pool.filter { it.entry != null && ExerciseGenerator.russianOf(it) != null }
            .take(count)
        if (candidates.isEmpty()) return emptyList()

        // Cached words first: they cost a single indexed read and can fill the batch on their own.
        val cached = candidates.mapNotNull { word ->
            cache?.get(cacheKey(word))?.let { payload -> word to payload }
        }
        val cachedWords = cached.map { it.first.word.lowercase() }.toSet()
        val fresh = candidates.filter { it.word.lowercase() !in cachedWords }.take(MAX_FRESH_AI)

        val generated = if (fresh.isEmpty()) emptyList() else
            withTimeoutOrNull(AI_BUDGET_MS) {
                coroutineScope {
                    fresh.map { word -> async { word to generateContext(word) } }.awaitAll()
                }
            }.orEmpty()

        val drafts = cached.map { (word, payload) -> word to parse(payload) } + generated
        return drafts.mapNotNull { (word, draft) -> draft?.let { toExercise(word, it) } }
    }

    private suspend fun generateContext(word: PracticeWord): ContextDraft? {
        val entry = word.entry ?: return null
        // Ровно то значение, которое человек выбрал: «предложение, куда подходит только это
        // слово» для «решать» и для «разлагать» — это два разных предложения.
        val group = senseGroup(word)
        val sense = group?.senses?.firstOrNull { word.senseId.isNullOrBlank() || it.id == word.senseId }
        val synonyms = (sense?.synonyms ?: entry.posGroups.flatMap { it.senses }.flatMap { it.synonyms })
            .distinct().take(6)

        val prompt = buildString {
            appendLine("СЛОВО: ${word.word}")
            group?.let { appendLine("ЧАСТЬ РЕЧИ: ${it.pos}") }
            sense?.let {
                appendLine("ЗНАЧЕНИЕ (en): ${it.definitionEn}")
                if (it.definitionRu.isNotBlank()) appendLine("ЗНАЧЕНИЕ (ru): ${it.definitionRu}")
            }
            ExerciseGenerator.russianOf(word)?.let { appendLine("ПЕРЕВОД: $it") }
            if (synonyms.isNotEmpty()) appendLine("СИНОНИМЫ ИЗ СЛОВАРЯ: ${synonyms.joinToString(", ")}")
        }

        return try {
            val result = llm.complete(
                LlmRequest(
                    task = "exercise_context",
                    system = SYSTEM,
                    user = prompt,
                    tier = LlmModelTier.FAST,
                    maxTokens = 600,
                    temperature = 0.6,
                    responseFormat = ResponseFormat.JsonObject,
                    maxRetries = 1
                )
            )
            val payload = LlmJson.extract(result.content)
            val draft = parse(payload)
            if (draft != null) {
                // Cached without expiry: the sentence is about the word, not about the user,
                // so every learner with this word gets it for free from here on.
                cache?.put(
                    cacheKey(word), "exercise_context", payload,
                    result.model, PROMPT_VERSION_CONTEXT, result.usage
                )
            }
            draft
        } catch (e: Exception) {
            logger.warn("Context exercise failed for '${word.word}': ${e.message}")
            null
        }
    }

    private fun parse(payload: String): ContextDraft? = runCatching {
        json.decodeFromString<ContextDraft>(payload)
    }.getOrNull()

    /**
     * Rejects a draft rather than repairing it. A context question whose sentence has no gap,
     * or whose distractors include the answer, teaches the wrong thing — and the batch has other
     * kinds to fall back on, so dropping one is cheap.
     */
    private fun toExercise(word: PracticeWord, draft: ContextDraft): Exercise? {
        val sentence = draft.sentence.trim()
        if (!sentence.contains(BLANK)) return null

        val forms = word.forms.map { it.lowercase() }.toSet()
        val distractors = draft.distractors
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in forms }
            .distinctBy { it.lowercase() }
            .take(3)
        if (distractors.size < 3) return null

        val options = (distractors + word.word).shuffled()
        return Exercise(
            id = "context_choice:${word.word.lowercase()}",
            kind = ExerciseKind.CONTEXT_CHOICE,
            format = ExerciseFormat.CHOICE,
            word = word.word,
            promptRu = "Какое слово подходит по смыслу?",
            question = sentence,
            questionIsSentence = true,
            options = options,
            correctIndex = options.indexOf(word.word),
            answer = word.word,
            hintRu = ExerciseGenerator.russianOf(word),
            explanationRu = listOfNotNull(
                draft.whyRu?.trim()?.takeIf { it.isNotBlank() },
                draft.sentenceRu?.trim()?.takeIf { it.isNotBlank() }?.let { "«$it»" }
            ).joinToString("\n").takeIf { it.isNotBlank() },
            cardId = word.cardId,
            savedWordId = word.savedWordId,
            source = ExerciseSource.AI
        )
    }

    /**
     * ⚠️ The pinned sense is part of the key. The sentence is cached forever because it is about
     * the word rather than the user — but it is about *one meaning* of the word, and keying on
     * the headword alone would serve the sentence written for "решать" to someone practising
     * "разлагать", permanently and invisibly.
     */
    private fun cacheKey(word: PracticeWord): String {
        val sense = word.senseId?.trim()?.takeIf { it.isNotEmpty() }
        val subject = if (sense == null) word.word.trim().lowercase()
        else "${word.word.trim().lowercase()}#$sense"
        return LlmCacheRepository.key("exercise_context", PROMPT_VERSION_CONTEXT, subject)
    }

    /** The part-of-speech group the pin belongs to, or the article's first. */
    private fun senseGroup(word: PracticeWord): n.startapp.models.lexical.PosGroup? {
        val entry = word.entry ?: return null
        val pinned = word.senseId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return entry.posGroups.firstOrNull()
        return entry.posGroups.firstOrNull { group -> group.senses.any { it.id == pinned } }
    }

    // ── Assembly ──────────────────────────────────────────────────────────────

    /**
     * Round-robin across kinds rather than filling up on the cheapest one.
     *
     * Taking the first N of a flat list would hand back ten questions of whichever kind the
     * folder happens to support best — usually ten multiple-choice items — which is precisely
     * the monotony a mixed session exists to avoid.
     */
    private fun interleave(byKind: Map<ExerciseKind, MutableList<Exercise>>, count: Int): List<Exercise> {
        val result = mutableListOf<Exercise>()
        val usedWords = mutableSetOf<String>()
        val queues = byKind.values.toMutableList()

        // First pass: never ask about the same word twice in one session.
        while (result.size < count && queues.any { it.isNotEmpty() }) {
            var progressed = false
            for (queue in queues) {
                if (result.size >= count) break
                val next = queue.firstOrNull { it.word.lowercase() !in usedWords } ?: continue
                queue.remove(next)
                usedWords += next.word.lowercase()
                result += next
                progressed = true
            }
            if (!progressed) break
        }

        // Second pass: a short folder is better revisited than cut short.
        while (result.size < count && queues.any { it.isNotEmpty() }) {
            var progressed = false
            for (queue in queues) {
                if (result.size >= count) break
                val next = queue.removeFirstOrNull() ?: continue
                if (result.any { it.id == next.id }) continue
                result += next
                progressed = true
            }
            if (!progressed) break
        }

        return result
    }

    private fun emptyPoolNotice(request: ExerciseRequest): String = when {
        request.categoryId == UNCATEGORIZED_CATEGORY_ID -> "Вне папок нет слов"
        request.categoryId != null -> "В этой папке пока нет слов"
        request.scope == ExerciseScope.DUE -> "Сегодня нечего повторять"
        request.scope == ExerciseScope.FLASHCARDS -> "Ещё нет ни одной карточки"
        else -> "Сначала сохраните хотя бы одно слово"
    }

    private fun notice(
        poolSize: Int,
        requested: List<ExerciseKind>,
        exercises: List<Exercise>,
        aiAttempted: Boolean
    ): String? = when {
        exercises.isEmpty() && poolSize < 4 ->
            "Нужно хотя бы 4 слова — сейчас $poolSize"

        exercises.isEmpty() ->
            "Для этих слов пока нет словарных статей: откройте их в поиске, и упражнения появятся"

        poolSize < 4 && requested.any { it.isChoice() } ->
            "Вопросов с выбором нет: для них нужно хотя бы 4 слова"

        aiAttempted && exercises.none { it.kind == ExerciseKind.CONTEXT_CHOICE } ->
            "Упражнения на оттенок значения сейчас недоступны — остальные типы работают"

        else -> null
    }

    private fun ExerciseKind.isChoice(): Boolean = when (this) {
        ExerciseKind.MEANING_CHOICE, ExerciseKind.WORD_CHOICE,
        ExerciseKind.CONTEXT_CHOICE, ExerciseKind.COLLOCATION -> true

        else -> false
    }
}
