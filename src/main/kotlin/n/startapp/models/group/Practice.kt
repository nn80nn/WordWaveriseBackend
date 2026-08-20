package n.startapp.models.group

import kotlinx.serialization.Serializable

/**
 * One answer, as the client reports it.
 *
 * [clientAttemptId] is minted at the moment of answering, not at the moment of sending. That is
 * what makes reporting safe to retry: an offline queue that flushes twice, or a session sent both
 * when the page closes and when it finishes, must not count the same answer as two.
 */
@Serializable
data class AttemptDTO(
    val clientAttemptId: String,
    /** "EXERCISE" — a question answered, or "REVIEW" — a flashcard graded. */
    val activity: String,
    /** `ExerciseKind` name; null for a flashcard review. */
    val kind: String? = null,
    val word: String,
    val cardId: Int? = null,
    /** "CORRECT" | "ALMOST" | "WRONG" — the three verdicts both clients already grade to. */
    val verdict: String,
    /** ISO-8601, from the client's clock. Clamped server-side before it is stored. */
    val answeredAt: String
)

/**
 * A finished session, sent in one request rather than per answer.
 *
 * [groupId] is a claim, not a fact: the server checks that the sender is actually in that group
 * before a single row is written. Practice with no group to name has nowhere to be recorded at
 * all, which is the point — see `PracticeAttempts`.
 */
@Serializable
data class ReportAttemptsRequest(
    val groupId: Int,
    val assignmentId: Int? = null,
    val categoryId: Int? = null,
    val attempts: List<AttemptDTO> = emptyList()
)

@Serializable
data class ReportAttemptsResult(
    val accepted: Int,
    /** Already recorded. Not a failure — the reason the client may retry freely. */
    val duplicates: Int,
    val rejected: Int
)

/** The class at a glance, for the teacher. */
@Serializable
data class GroupStats(
    val groupId: Int,
    val name: String,
    val memberCount: Int,
    val folderCount: Int,
    val wordCount: Int,
    val activeAssignments: Int,
    val students: List<StudentSummary>
)

@Serializable
data class StudentSummary(
    val userId: Int,
    val login: String?,
    val email: String,
    val joinedAt: String,
    val attempts7d: Int,
    /** Percent, weighting a near-miss as half right — the same weighting a session shows. */
    val accuracy7d: Int? = null,
    val attemptsTotal: Int,
    val accuracyTotal: Int? = null,
    val lastActiveAt: String? = null,
    /** Cards this student built from the folders this group hands out. */
    val cardsFromGroupFolders: Int,
    val assignmentsDone: Int,
    val assignmentsTotal: Int
)

/** One student in full. Everything here is scoped to this group's material and nothing else. */
@Serializable
data class StudentDetail(
    val student: GroupMemberDTO,
    val assignments: List<StudentAssignmentDTO>,
    val byKind: List<KindStat>,
    /**
     * The words this student gets wrong most often — the part a teacher can actually act on,
     * and the reason individual answers are stored rather than a running total.
     */
    val hardestWords: List<WordStat>,
    val recent: List<AttemptView>
)

@Serializable
data class KindStat(val kind: String, val attempts: Int, val accuracy: Int)

@Serializable
data class WordStat(val word: String, val attempts: Int, val wrong: Int)

@Serializable
data class AttemptView(
    val word: String,
    val kind: String? = null,
    val activity: String,
    val verdict: String,
    val answeredAt: String
)
