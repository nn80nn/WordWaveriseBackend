package n.startapp.services.group

import n.startapp.exceptions.ForbiddenException
import n.startapp.exceptions.NotFoundException
import n.startapp.models.exercise.ExerciseRequest
import n.startapp.repositories.AssignmentRepository
import n.startapp.repositories.GroupRepository

/** A prepared session: what to generate, and where the answers should be reported. */
data class PreparedSession(
    val request: ExerciseRequest,
    val groupId: Int? = null,
    val assignmentId: Int? = null
)

/**
 * Turns "practise assignment 7" into a concrete exercise request.
 *
 * The folder and the kinds come from the assignment, not from the client, so the same assignment
 * produces the same session in the browser and on the phone. Both clients then send back the same
 * `groupId`/`assignmentId` they were handed, and neither has to reason about membership to know
 * whether their answers count for anything.
 */
class AssignmentSession(
    private val assignments: AssignmentRepository = AssignmentRepository(),
    private val groups: GroupRepository = GroupRepository()
) {

    suspend fun prepare(userId: Int, request: ExerciseRequest): PreparedSession {
        val assignmentId = request.assignmentId ?: return PreparedSession(request)

        val assignment = assignments.findById(assignmentId)
            ?: throw NotFoundException("Задание не найдено")
        val group = groups.findById(assignment.groupId)
            ?: throw NotFoundException("Задание не найдено")

        val allowed = group.ownerId == userId || groups.isMember(userId, group.id)
        if (!allowed) throw ForbiddenException("Вы не состоите в этой группе")

        return PreparedSession(
            request = request.copy(
                // A null folder on the assignment means "anything this class was given", which is
                // the same thing the absent filter already means.
                categoryId = assignment.categoryId ?: request.categoryId,
                kinds = assignment.kinds.ifEmpty { request.kinds }
            ),
            groupId = group.id,
            assignmentId = assignment.id
        )
    }
}
