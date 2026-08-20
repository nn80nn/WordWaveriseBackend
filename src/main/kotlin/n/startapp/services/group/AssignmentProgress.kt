package n.startapp.services.group

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * How far along a student is, and how accurate they have been.
 *
 * Derived every time it is asked for rather than kept as a counter. A stored total cannot be
 * repaired after a duplicate slips past, cannot be recomputed when the teacher changes the target
 * from ten to twenty, and would need a write on the one path — reporting a finished session —
 * that has to stay cheap.
 */
object AssignmentProgress {

    /**
     * Percent complete, counting each goal only up to its own target.
     *
     * Doing fifteen exercises against a target of ten and none of the five reviews is two thirds
     * of the work, not all of it. Letting the overshoot pay for the untouched half would mark an
     * assignment done that nobody finished.
     */
    fun percent(
        exercisesDone: Int,
        exerciseTarget: Int?,
        reviewsDone: Int,
        reviewTarget: Int?
    ): Int {
        val target = (exerciseTarget ?: 0) + (reviewTarget ?: 0)
        if (target <= 0) return 0

        val done = minOf(exercisesDone, exerciseTarget ?: 0) + minOf(reviewsDone, reviewTarget ?: 0)
        return (100.0 * done / target).roundToInt().coerceIn(0, 100)
    }

    fun isComplete(
        exercisesDone: Int,
        exerciseTarget: Int?,
        reviewsDone: Int,
        reviewTarget: Int?
    ): Boolean {
        val hasGoal = (exerciseTarget ?: 0) > 0 || (reviewTarget ?: 0) > 0
        if (!hasGoal) return false
        return exercisesDone >= (exerciseTarget ?: 0) && reviewsDone >= (reviewTarget ?: 0)
    }

    /**
     * Past the deadline and not finished.
     *
     * An assignment somebody completed is not overdue afterwards, whatever the date says — the
     * work is done, and colouring it red would be a lie about the student rather than a reminder.
     */
    fun isOverdue(dueAt: Instant?, completed: Boolean, now: Instant): Boolean =
        dueAt != null && !completed && now.isAfter(dueAt)

    /**
     * Accuracy as a percent, with a near-miss worth half.
     *
     * The same weighting a practice session shows the learner. `ALMOST` means a typo away from
     * right; scoring it as a miss would make the number disagree with what they just saw, and
     * scoring it as correct would make it meaningless.
     */
    fun accuracy(correct: Int, almost: Int, wrong: Int): Int? {
        val total = correct + almost + wrong
        if (total == 0) return null
        return (100.0 * (correct + almost * 0.5) / total).roundToInt().coerceIn(0, 100)
    }
}

/**
 * The window a reported answer is allowed to claim it happened in.
 *
 * The timestamp comes from the learner's own device, so it is a claim like any other. Left
 * unbounded it would let somebody backdate work into a deadline that has passed, or post-date it
 * out of one that has not — and a clock that is merely wrong (a phone that lost its battery)
 * would scatter a session across a year of history.
 */
object AttemptWindow {
    val PAST: Duration = Duration.ofDays(30)
    val FUTURE: Duration = Duration.ofMinutes(5)

    fun clamp(answeredAt: Instant, now: Instant): Instant = when {
        answeredAt.isBefore(now.minus(PAST)) -> now.minus(PAST)
        answeredAt.isAfter(now.plus(FUTURE)) -> now
        else -> answeredAt
    }
}
