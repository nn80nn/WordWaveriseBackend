package n.startapp.services.group

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The number a student watches while they work, and the one their teacher reads afterwards.
 *
 * Derived on every read rather than kept as a counter, so it can be repaired by re-reading and
 * survives the teacher changing a target after the fact. These cases are what "derived" has to
 * mean in practice.
 */
class AssignmentProgressTest {

    @Test
    fun `one goal counts straightforwardly`() {
        assertEquals(0, AssignmentProgress.percent(0, 10, 0, null))
        assertEquals(50, AssignmentProgress.percent(5, 10, 0, null))
        assertEquals(100, AssignmentProgress.percent(10, 10, 0, null))
    }

    @Test
    fun `two goals share the total between them`() {
        // Ten exercises and five reviews is fifteen units of work.
        assertEquals(67, AssignmentProgress.percent(10, 10, 0, 5))
        assertEquals(33, AssignmentProgress.percent(0, 10, 5, 5))
        assertEquals(100, AssignmentProgress.percent(10, 10, 5, 5))
    }

    @Test
    fun `overshooting one goal does not pay for the other`() {
        // Fifteen exercises against a target of ten, and none of the five reviews: two thirds of
        // the work. Letting the surplus cover the untouched half would mark an assignment done
        // that nobody finished.
        assertEquals(67, AssignmentProgress.percent(15, 10, 0, 5))
        assertEquals(100, AssignmentProgress.percent(50, 10, 0, null))
    }

    @Test
    fun `an assignment with no goal reads as no progress rather than as finished`() {
        assertEquals(0, AssignmentProgress.percent(9, null, 9, null))
        assertEquals(0, AssignmentProgress.percent(0, 0, 0, 0))
        assertFalse(AssignmentProgress.isComplete(9, null, 9, null))
    }

    @Test
    fun `complete means every goal met, not the total reached`() {
        assertTrue(AssignmentProgress.isComplete(10, 10, 5, 5))
        assertFalse(AssignmentProgress.isComplete(15, 10, 0, 5))
        assertTrue(AssignmentProgress.isComplete(11, 10, 6, 5))
    }

    @Test
    fun `finished work is never overdue`() {
        val now = Instant.parse("2026-08-20T12:00:00Z")
        val yesterday = now.minus(Duration.ofDays(1))

        assertTrue(AssignmentProgress.isOverdue(yesterday, completed = false, now = now))
        // The work is done. Colouring it red afterwards would be a claim about the student,
        // not a reminder about the date.
        assertFalse(AssignmentProgress.isOverdue(yesterday, completed = true, now = now))
        assertFalse(AssignmentProgress.isOverdue(null, completed = false, now = now))
    }

    @Test
    fun `a near miss is worth half, the same as it looks in the session`() {
        assertEquals(100, AssignmentProgress.accuracy(correct = 10, almost = 0, wrong = 0))
        assertEquals(0, AssignmentProgress.accuracy(correct = 0, almost = 0, wrong = 10))
        assertEquals(50, AssignmentProgress.accuracy(correct = 0, almost = 10, wrong = 0))
        assertEquals(75, AssignmentProgress.accuracy(correct = 5, almost = 5, wrong = 0))
    }

    @Test
    fun `no answers is not zero accuracy`() {
        // Nothing attempted and everything wrong are different states, and a class list that
        // shows "0%" against a student who has not started is simply false.
        assertNull(AssignmentProgress.accuracy(0, 0, 0))
    }

    @Test
    fun `a clock that is wrong cannot move work into or out of a deadline`() {
        val now = Instant.parse("2026-08-20T12:00:00Z")

        val honest = now.minus(Duration.ofMinutes(3))
        assertEquals(honest, AttemptWindow.clamp(honest, now))

        // Backdating into a deadline that has passed.
        val ancient = now.minus(Duration.ofDays(400))
        assertEquals(now.minus(AttemptWindow.PAST), AttemptWindow.clamp(ancient, now))

        // A phone whose clock ran ahead — recorded as "now", not as next year.
        val future = now.plus(Duration.ofDays(30))
        assertEquals(now, AttemptWindow.clamp(future, now))

        // A little ahead is ordinary clock drift, and is left alone.
        val drift = now.plus(Duration.ofMinutes(2))
        assertEquals(drift, AttemptWindow.clamp(drift, now))
    }
}
