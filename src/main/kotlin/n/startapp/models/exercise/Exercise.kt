package n.startapp.models.exercise

import kotlinx.serialization.Serializable
import n.startapp.models.auth.UNCATEGORIZED_CATEGORY_ID

/**
 * The exercise contract, owned by the server.
 *
 * Web and Android used to each build their own exercises out of whatever the client happened
 * to have locally, so the same folder produced different questions on the phone and in the
 * browser, and only one of them could ever be improved at a time. Everything a client needs to
 * *render* and *grade* a question is in [Exercise]; a client that adds no logic of its own is
 * automatically identical to the other one.
 *
 * The Russian instruction text ships with the exercise for the same reason — a new kind must
 * not require a release on both clients before it can be shown.
 */
@Serializable
enum class ExerciseKind {
    /** Word → four meanings. */
    MEANING_CHOICE,

    /** Meaning → four words. */
    WORD_CHOICE,

    /** Russian equivalent → type the English word. */
    TRANSLATE_RU_EN,

    /**
     * English word → type the Russian.
     *
     * The direction the other kinds never test: recognising a word is not the same as being
     * able to say what it means. Accepts every translation the article records, across all
     * senses — a word with several meanings has several right answers, and marking one of them
     * wrong teaches the learner to distrust the exercise.
     */
    TRANSLATE_EN_RU,

    /** Real example sentence with the word removed → type it back. */
    FILL_BLANK,

    /** Sentence with a gap and four *near-synonyms*: the one kind that tests nuance. */
    CONTEXT_CHOICE,

    /** Which word goes with this one — `make/do a decision`. */
    COLLOCATION,

    /** Put the word into the grammatical form the sentence asks for. */
    WORD_FORM,

    /** Definition (and audio, if any) → spell the word. */
    SPELLING,

    /**
     * Hear the word, write it down.
     *
     * The only kind that practises the sound-to-spelling link, which is where English is at its
     * most treacherous. Costs nothing to produce: the audio was already scraped into the
     * article alongside the IPA.
     */
    LISTENING
}

/** How the client renders the answer control. Nothing else about a kind matters to layout. */
@Serializable
enum class ExerciseFormat { CHOICE, INPUT }

/** Where the question's content came from — shown as a small badge, and useful in logs. */
@Serializable
enum class ExerciseSource {
    /** Built from the annotated corpus: real definitions, examples and collocations. */
    CORPUS,

    /** Built from the card/saved word alone, because the corpus has no article yet. */
    CARD,

    /** Written by the model for this word and cached forever. */
    AI
}

/** Which words to draw on. */
@Serializable
enum class ExerciseScope {
    /** Every saved word (optionally in one folder). */
    SAVED,

    /** Every flashcard. */
    FLASHCARDS,

    /** Only the cards due for review — practice that moves the schedule forward. */
    DUE
}

@Serializable
data class Exercise(
    val id: String,
    val kind: ExerciseKind,
    val format: ExerciseFormat,
    /** The word being practised. Never shown for kinds where it *is* the answer. */
    val word: String,
    /** Russian instruction, e.g. «Выберите значение». */
    val promptRu: String,
    /** The question body: a word, a definition, or a sentence containing [BLANK]. */
    val question: String,
    /** True when [question] is a sentence and should be set as running text, not as a headword. */
    val questionIsSentence: Boolean = false,
    val options: List<String> = emptyList(),
    val correctIndex: Int? = null,
    /** The canonical answer, shown after a wrong attempt. */
    val answer: String,
    /** Everything else accepted as correct, already normalised by [ExerciseGrading.normalize]. */
    val acceptedAnswers: List<String> = emptyList(),
    /**
     * Recording of the word, when the article has one. Present only for kinds that need it —
     * a client shows a play control exactly when this is set, and never guesses from [kind].
     */
    val audioUrl: String? = null,
    val hintRu: String? = null,
    /** Why this answer — shown after answering, which is where the learning actually happens. */
    val explanationRu: String? = null,
    /** Set when the exercise is built on a flashcard, so the answer can feed spaced repetition. */
    val cardId: Int? = null,
    val savedWordId: Int? = null,
    val source: ExerciseSource
) {
    companion object {
        /** The gap marker inside [question]. Both clients highlight exactly this string. */
        const val BLANK = "_____"
    }
}

@Serializable
data class ExerciseRequest(
    /** null = every folder; [UNCATEGORIZED] = only words with no folder. */
    val categoryId: Int? = null,
    val scope: ExerciseScope = ExerciseScope.SAVED,
    /** Empty = every kind the available words support. */
    val kinds: List<ExerciseKind> = emptyList(),
    val count: Int = 10,
    /** false keeps the batch entirely local: instant, free, and offline-friendly. */
    val useAi: Boolean = true,

    /**
     * Practising towards work a teacher set.
     *
     * When present the server takes the folder and the kinds from the assignment and ignores
     * whatever was sent alongside — that is what makes the same assignment produce the same
     * session in the browser and on the phone, rather than each client assembling its own.
     */
    val assignmentId: Int? = null
) {
    companion object {
        /** Folder filter meaning "words that are in no folder". Same value in both clients. */
        const val UNCATEGORIZED = UNCATEGORIZED_CATEGORY_ID
    }
}

@Serializable
data class ExerciseBatch(
    val exercises: List<Exercise>,
    /** How many words the filter matched, before kinds were applied. */
    val wordsAvailable: Int,
    val kindsUsed: List<ExerciseKind>,
    /** Russian explanation when the batch is smaller or narrower than asked for. */
    val noticeRu: String? = null,

    /**
     * Where to report the answers, when this session is towards a teacher's assignment.
     *
     * Echoed back rather than left for the client to work out: whether an answer counts towards a
     * class is a question about membership, and the client should not have to reason about it.
     */
    val groupId: Int? = null,
    val assignmentId: Int? = null
)

/** What a kind needs before it can be offered, so the client can explain an empty batch. */
@Serializable
data class ExerciseKindInfo(
    val kind: ExerciseKind,
    val titleRu: String,
    val descriptionRu: String,
    val format: ExerciseFormat,
    /** How many questions of this kind the current selection could actually produce. */
    val available: Int
)

@Serializable
data class ExerciseKindsResponse(
    val kinds: List<ExerciseKindInfo>,
    val wordsAvailable: Int
)
