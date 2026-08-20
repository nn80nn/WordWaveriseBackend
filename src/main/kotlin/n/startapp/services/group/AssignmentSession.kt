package n.startapp.services.group

import n.startapp.exceptions.ForbiddenException
import n.startapp.exceptions.NotFoundException
import n.startapp.models.exercise.ExerciseRequest
import n.startapp.models.auth.UNCATEGORIZED_CATEGORY_ID
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
    private val groups: GroupRepository = GroupRepository(),
    private val folders: FolderAccessResolver = FolderAccessResolver()
) {

    suspend fun prepare(userId: Int, request: ExerciseRequest): PreparedSession {
        val assignmentId = request.assignmentId ?: return withoutAssignment(userId, request)

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

    /**
     * Практика по папке класса без задания — тоже работа по материалу класса.
     *
     * Без этого в статистику попадало бы только то, что задали, а «я сам позанимался по вашей
     * папке» не существовало бы вовсе — учитель видел бы ноль у того, кто занимался больше всех.
     */
    private suspend fun withoutAssignment(userId: Int, request: ExerciseRequest): PreparedSession {
        val categoryId = request.categoryId
        if (categoryId == null || categoryId == UNCATEGORIZED_CATEGORY_ID) {
            return PreparedSession(request)
        }
        val folder = folders.resolve(userId, categoryId) ?: return PreparedSession(request)
        return PreparedSession(request, groupId = folder.groupId)
    }
}
