package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.exceptions.BadRequestException
import n.startapp.exceptions.ForbiddenException
import n.startapp.exceptions.NotFoundException
import n.startapp.models.ApiResponse
import n.startapp.models.group.AssignmentDTO
import n.startapp.models.group.AttemptView
import n.startapp.models.group.CreateAssignmentRequest
import n.startapp.models.group.GroupStats
import n.startapp.models.group.KindStat
import n.startapp.models.group.ReportAttemptsRequest
import n.startapp.models.group.ReportAttemptsResult
import n.startapp.models.group.StudentAssignmentDTO
import n.startapp.models.group.StudentDetail
import n.startapp.models.group.StudentSummary
import n.startapp.models.group.WordStat
import n.startapp.repositories.AssignmentRepository
import n.startapp.repositories.AssignmentRow
import n.startapp.repositories.AssignmentTally
import n.startapp.repositories.AttemptToRecord
import n.startapp.repositories.CategoryRepository
import n.startapp.repositories.GroupRepository
import n.startapp.repositories.GroupRow
import n.startapp.repositories.PracticeAttemptRepository
import n.startapp.repositories.VerdictTally
import n.startapp.services.group.AssignmentProgress
import n.startapp.services.group.AttemptWindow
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** Verdicts the clients grade to. Anything else is a client bug, not a new outcome. */
private val VERDICTS = setOf("CORRECT", "ALMOST", "WRONG")
private val ACTIVITIES = setOf("EXERCISE", "REVIEW")

/** One session's worth. Beyond this the sender is not reporting practice. */
private const val MAX_ATTEMPTS_PER_REPORT = 200

/**
 * Work the teacher set, and the evidence of it being done.
 *
 * The two live together because the second only exists to answer the first: nothing is recorded
 * about a learner's own study, and a teacher can only ever read what happened on their own
 * group's material — see [PracticeAttemptRepository].
 */
fun Route.assignmentRoutes() {
    val groups = GroupRepository()
    val assignments = AssignmentRepository()
    val attempts = PracticeAttemptRepository()
    val categories = CategoryRepository()

    suspend fun requireGroup(groupId: Int): GroupRow =
        groups.findById(groupId) ?: throw NotFoundException("Группа не найдена")

    suspend fun requireOwned(userId: Int, groupId: Int): GroupRow {
        val group = requireGroup(groupId)
        if (group.ownerId != userId) throw NotFoundException("Группа не найдена")
        return group
    }

    suspend fun requireMembership(userId: Int, groupId: Int): GroupRow {
        val group = requireGroup(groupId)
        val allowed = group.ownerId == userId || groups.isMember(userId, groupId)
        if (!allowed) throw NotFoundException("Группа не найдена")
        return group
    }

    /** Folder names, looked up once per group rather than once per assignment. */
    suspend fun folderNames(ownerId: Int): Map<Int, String> =
        categories.findByUserId(ownerId).associate { it.id to it.name }

    fun toDTO(row: AssignmentRow, group: GroupRow, names: Map<Int, String>) = AssignmentDTO(
        id = row.id,
        groupId = row.groupId,
        groupName = group.name,
        title = row.title,
        categoryId = row.categoryId,
        categoryName = row.categoryId?.let { names[it] },
        exerciseTarget = row.exerciseTarget,
        reviewTarget = row.reviewTarget,
        kinds = row.kinds,
        dueAt = row.dueAt?.toString(),
        createdAt = row.createdAt.toString()
    )

    fun withProgress(dto: AssignmentDTO, row: AssignmentRow, tally: AssignmentTally): StudentAssignmentDTO {
        val completed = AssignmentProgress.isComplete(
            tally.exercises, row.exerciseTarget, tally.reviews, row.reviewTarget
        )
        return StudentAssignmentDTO(
            assignment = dto,
            exercisesDone = tally.exercises,
            reviewsDone = tally.reviews,
            percent = AssignmentProgress.percent(
                tally.exercises, row.exerciseTarget, tally.reviews, row.reviewTarget
            ),
            completed = completed,
            overdue = AssignmentProgress.isOverdue(row.dueAt, completed, Instant.now()),
            lastAttemptAt = tally.lastAt?.toString()
        )
    }

    authenticate("auth-jwt") {

        /** Everything set for me, across every class I am in. */
        get("/api/assignments") {
            val userId = call.userId()
            val groupFilter = call.request.queryParameters["groupId"]?.toIntOrNull()
            val rows = assignments.findForStudent(userId, groupFilter)
            if (rows.isEmpty()) {
                call.respond(ApiResponse.success(emptyList<StudentAssignmentDTO>()))
                return@get
            }

            val tallies = attempts.tallyFor(userId, rows.map { it.id })
            val byGroup = rows.map { it.groupId }.distinct()
                .mapNotNull { id -> groups.findById(id)?.let { id to it } }
                .toMap()
            val names = byGroup.values.map { it.ownerId }.distinct()
                .fold(emptyMap<Int, String>()) { acc, ownerId -> acc + folderNames(ownerId) }

            call.respond(
                ApiResponse.success(
                    rows.mapNotNull { row ->
                        val group = byGroup[row.groupId] ?: return@mapNotNull null
                        withProgress(
                            toDTO(row, group, names),
                            row,
                            tallies[row.id] ?: AssignmentTally()
                        )
                    }
                )
            )
        }

        route("/api/groups/{id}/assignments") {

            /** The owner sees the assignments; a student sees their own progress on each. */
            get {
                val userId = call.userId()
                val group = requireMembership(userId, call.pathGroupId())
                val rows = assignments.findByGroup(group.id)
                val names = folderNames(group.ownerId)

                if (group.ownerId == userId) {
                    call.respond(ApiResponse.success(rows.map { toDTO(it, group, names) }))
                    return@get
                }
                val tallies = attempts.tallyFor(userId, rows.map { it.id })
                call.respond(
                    ApiResponse.success(
                        rows.map { row ->
                            withProgress(toDTO(row, group, names), row, tallies[row.id] ?: AssignmentTally())
                        }
                    )
                )
            }

            post {
                val userId = call.userId()
                val group = requireOwned(userId, call.pathGroupId())
                val request = call.receive<CreateAssignmentRequest>()
                validate(request, group, groups)

                val row = assignments.create(
                    groupId = group.id,
                    title = request.title,
                    categoryId = request.categoryId,
                    exerciseTarget = request.exerciseTarget,
                    reviewTarget = request.reviewTarget,
                    kinds = request.kinds,
                    dueAt = request.dueAt.toInstantOrNull()
                )
                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(toDTO(row, group, folderNames(group.ownerId)))
                )
            }
        }

        route("/api/assignments/{id}") {

            put {
                val userId = call.userId()
                val row = call.requireAssignment(assignments)
                val group = requireOwned(userId, row.groupId)
                val request = call.receive<CreateAssignmentRequest>()
                validate(request, group, groups)

                assignments.update(
                    id = row.id,
                    title = request.title,
                    categoryId = request.categoryId,
                    exerciseTarget = request.exerciseTarget,
                    reviewTarget = request.reviewTarget,
                    kinds = request.kinds,
                    dueAt = request.dueAt.toInstantOrNull()
                )
                val fresh = assignments.findById(row.id) ?: throw NotFoundException("Задание не найдено")
                call.respond(ApiResponse.success(toDTO(fresh, group, folderNames(group.ownerId))))
            }

            delete {
                val userId = call.userId()
                val row = call.requireAssignment(assignments)
                requireOwned(userId, row.groupId)
                assignments.archive(row.id)
                call.respond(ApiResponse.success("Задание убрано"))
            }
        }

        /**
         * A finished session, in one request.
         *
         * Sent at the end rather than per answer: the results are already in memory, and a request
         * per question would double the traffic of practising for data nobody reads live. A
         * session abandoned halfway still counts — a student who closes the tab at question eight
         * of ten getting zero would teach them not to trust the progress bar.
         */
        post("/api/practice/attempts") {
            val userId = call.userId()
            val request = call.receive<ReportAttemptsRequest>()

            if (request.attempts.size > MAX_ATTEMPTS_PER_REPORT) {
                throw BadRequestException("Слишком много ответов в одном отчёте")
            }

            // The group is a claim. Everything else is validated against it, and a sender who is
            // not in it gets nothing written at all.
            val group = requireGroup(request.groupId)
            val member = group.ownerId == userId || groups.isMember(userId, request.groupId)
            if (!member) throw ForbiddenException("Вы не состоите в этой группе")

            // A folder must be one this group actually hands out; a stale assignment id is
            // dropped instead, because losing a whole session to it would be worse.
            request.categoryId?.let {
                if (it !in groups.folderIds(group.id)) {
                    throw ForbiddenException("Эта папка не выдана группе")
                }
            }
            val assignmentId = request.assignmentId
                ?.let { assignments.findById(it) }
                ?.takeIf { it.groupId == group.id }
                ?.id

            val now = Instant.now()
            var rejected = 0
            val clean = request.attempts.mapNotNull { attempt ->
                val activity = attempt.activity.uppercase()
                val verdict = attempt.verdict.uppercase()
                val answeredAt = attempt.answeredAt.toInstantOrNull()
                if (activity !in ACTIVITIES || verdict !in VERDICTS ||
                    attempt.clientAttemptId.isBlank() || attempt.word.isBlank() || answeredAt == null
                ) {
                    rejected++
                    return@mapNotNull null
                }
                AttemptToRecord(
                    clientAttemptId = attempt.clientAttemptId,
                    activity = activity,
                    kind = attempt.kind,
                    word = attempt.word,
                    cardId = attempt.cardId,
                    verdict = verdict,
                    answeredAt = AttemptWindow.clamp(answeredAt, now)
                )
            }

            val (accepted, duplicates) = attempts.record(
                userId = userId,
                groupId = group.id,
                assignmentId = assignmentId,
                categoryId = request.categoryId,
                attempts = clean
            )
            call.respond(ApiResponse.success(ReportAttemptsResult(accepted, duplicates, rejected)))
        }

        /** The class at a glance. Owner only. */
        get("/api/groups/{id}/stats") {
            val userId = call.userId()
            val group = requireOwned(userId, call.pathGroupId())
            call.respond(ApiResponse.success(buildStats(userId, group, groups, assignments, attempts)))
        }

        get("/api/groups/{id}/stats.csv") {
            val userId = call.userId()
            val group = requireOwned(userId, call.pathGroupId())
            val stats = buildStats(userId, group, groups, assignments, attempts)
            call.respondText(stats.toCsv(), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
        }

        /** One student in full — still only their work on this group's material. */
        get("/api/groups/{id}/students/{userId}") {
            val ownerId = call.userId()
            val group = requireOwned(ownerId, call.pathGroupId())
            val studentId = call.parameters["userId"]?.toIntOrNull()
                ?: throw BadRequestException("Неверный id ученика")
            val student = groups.members(group.id).firstOrNull { it.userId == studentId }
                ?: throw NotFoundException("Ученик не состоит в этой группе")

            val rows = assignments.findByGroup(group.id)
            val names = folderNames(group.ownerId)
            val tallies = attempts.tallyFor(studentId, rows.map { it.id })

            val kinds = attempts.byKind(ownerId, group.id, studentId)
            val words = attempts.byWord(ownerId, group.id, studentId)

            call.respond(
                ApiResponse.success(
                    StudentDetail(
                        student = student,
                        assignments = rows.map { row ->
                            withProgress(toDTO(row, group, names), row, tallies[row.id] ?: AssignmentTally())
                        },
                        byKind = kinds.entries
                            .sortedByDescending { it.value.total }
                            .map { (kind, tally) ->
                                KindStat(
                                    kind = kind,
                                    attempts = tally.total,
                                    accuracy = AssignmentProgress.accuracy(
                                        tally.correct, tally.almost, tally.wrong
                                    ) ?: 0
                                )
                            },
                        hardestWords = words.entries
                            .filter { it.value.wrong > 0 }
                            .sortedWith(
                                compareByDescending<Map.Entry<String, VerdictTally>> { it.value.wrong }
                                    .thenByDescending { it.value.total }
                            )
                            .take(20)
                            .map { (word, tally) ->
                                WordStat(word = word, attempts = tally.total, wrong = tally.wrong)
                            },
                        recent = attempts.recent(ownerId, group.id, studentId, 50).map {
                            AttemptView(
                                word = it.word,
                                kind = it.kind,
                                activity = it.activity,
                                verdict = it.verdict,
                                answeredAt = it.answeredAt.toString()
                            )
                        }
                    )
                )
            )
        }
    }
}

private suspend fun buildStats(
    ownerId: Int,
    group: GroupRow,
    groups: GroupRepository,
    assignments: AssignmentRepository,
    attempts: PracticeAttemptRepository
): GroupStats {
    val members = groups.members(group.id)
    val rows = assignments.findByGroup(group.id)
    val week = Instant.now().minus(Duration.ofDays(7))

    val total = attempts.verdictsByStudent(ownerId, group.id)
    val recent = attempts.verdictsByStudent(ownerId, group.id, since = week)
    val perAssignment = attempts.tallyByStudent(ownerId, group.id)
    val cards = attempts.cardsFromGroupFolders(ownerId, group.id)

    return GroupStats(
        groupId = group.id,
        name = group.name,
        memberCount = members.size,
        folderCount = groups.folderCount(group.id),
        wordCount = groups.wordCount(group.id),
        activeAssignments = rows.size,
        students = members.map { member ->
            val allTime = total[member.userId] ?: VerdictTally()
            val lastWeek = recent[member.userId] ?: VerdictTally()
            val theirs = perAssignment[member.userId].orEmpty()
            StudentSummary(
                userId = member.userId,
                login = member.login,
                email = member.email,
                joinedAt = member.joinedAt,
                attempts7d = lastWeek.total,
                accuracy7d = AssignmentProgress.accuracy(
                    lastWeek.correct, lastWeek.almost, lastWeek.wrong
                ),
                attemptsTotal = allTime.total,
                accuracyTotal = AssignmentProgress.accuracy(
                    allTime.correct, allTime.almost, allTime.wrong
                ),
                lastActiveAt = allTime.lastAt?.toString(),
                cardsFromGroupFolders = cards[member.userId] ?: 0,
                assignmentsDone = rows.count { row ->
                    val tally = theirs[row.id] ?: AssignmentTally()
                    AssignmentProgress.isComplete(
                        tally.exercises, row.exerciseTarget, tally.reviews, row.reviewTarget
                    )
                },
                assignmentsTotal = rows.size
            )
        }
    )
}

/** Excel opens UTF-8 CSV correctly only with a BOM, and a mangled class list is worse than none. */
private fun GroupStats.toCsv(): String = buildString {
    append('﻿')
    append("Ученик,Email,В группе с,Ответов за 7 дней,Точность 7 дней %,Ответов всего,")
    appendLine("Точность всего %,Последняя активность,Карточек из папок группы,Заданий выполнено,Заданий всего")
    for (s in students) {
        append(csv(s.login ?: "")); append(',')
        append(csv(s.email)); append(',')
        append(csv(s.joinedAt)); append(',')
        append(s.attempts7d); append(',')
        append(s.accuracy7d?.toString() ?: ""); append(',')
        append(s.attemptsTotal); append(',')
        append(s.accuracyTotal?.toString() ?: ""); append(',')
        append(csv(s.lastActiveAt ?: "")); append(',')
        append(s.cardsFromGroupFolders); append(',')
        append(s.assignmentsDone); append(',')
        appendLine(s.assignmentsTotal)
    }
}

private fun csv(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

private suspend fun validate(
    request: CreateAssignmentRequest,
    group: GroupRow,
    groups: GroupRepository
) {
    if (request.title.isBlank()) throw BadRequestException("У задания должно быть название")
    if (request.title.length > 200) throw BadRequestException("Название задания слишком длинное")

    val exercises = request.exerciseTarget ?: 0
    val reviews = request.reviewTarget ?: 0
    // The schema cannot express "at least one goal" — `createMissingTablesAndColumns` will not
    // add a CHECK to a table that already exists, and a constraint only new deployments have is
    // worse than none. So it is checked here, where the row is written.
    if (exercises <= 0 && reviews <= 0) {
        throw BadRequestException("Задание без цели ничего не просит — укажите упражнения или карточки")
    }
    if (exercises < 0 || reviews < 0) throw BadRequestException("Цель не может быть отрицательной")
    if (exercises > 500 || reviews > 500) throw BadRequestException("Слишком большая цель")

    request.dueAt?.let {
        if (it.toInstantOrNull() == null) throw BadRequestException("Неверная дата дедлайна")
    }

    // A folder the class was never given cannot be the thing to practise: the students would
    // open the assignment and find nothing there.
    request.categoryId?.let {
        if (it !in groups.folderIds(group.id)) {
            throw BadRequestException("Эта папка не выдана группе")
        }
    }
}

private fun String?.toInstantOrNull(): Instant? =
    this?.takeIf { it.isNotBlank() }?.let {
        try {
            Instant.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    }

private suspend fun ApplicationCall.requireAssignment(repository: AssignmentRepository): AssignmentRow {
    val id = parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Неверный id задания")
    return repository.findById(id) ?: throw NotFoundException("Задание не найдено")
}

private fun ApplicationCall.pathGroupId(): Int =
    parameters["id"]?.toIntOrNull() ?: throw BadRequestException("Неверный id группы")
