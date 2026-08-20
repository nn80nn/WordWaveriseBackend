package n.startapp.models.group

import kotlinx.serialization.Serializable
import n.startapp.models.exercise.ExerciseKind

/** A piece of work the teacher set, as anybody in the group sees it. */
@Serializable
data class AssignmentDTO(
    val id: Int,
    val groupId: Int,
    val groupName: String,
    val title: String,
    /** The folder to practise, or null for "anything this group was given". */
    val categoryId: Int? = null,
    val categoryName: String? = null,
    val exerciseTarget: Int? = null,
    val reviewTarget: Int? = null,
    /** Empty means every kind the words support — the same convention as `ExerciseRequest`. */
    val kinds: List<ExerciseKind> = emptyList(),
    val dueAt: String? = null,
    val createdAt: String
)

/**
 * The same assignment with one student's progress against it.
 *
 * Progress is counted in attempts, not in distinct words: "ten exercises" is a unit of work, and
 * answering the same word twice is two of them.
 */
@Serializable
data class StudentAssignmentDTO(
    val assignment: AssignmentDTO,
    val exercisesDone: Int,
    val reviewsDone: Int,
    val percent: Int,
    val completed: Boolean,
    val overdue: Boolean,
    val lastAttemptAt: String? = null
)

@Serializable
data class CreateAssignmentRequest(
    val title: String,
    val categoryId: Int? = null,
    val exerciseTarget: Int? = null,
    val reviewTarget: Int? = null,
    val kinds: List<ExerciseKind> = emptyList(),
    /** ISO-8601. Null means the work has no deadline, not that it is due immediately. */
    val dueAt: String? = null
)
