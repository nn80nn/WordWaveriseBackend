package n.startapp.services.group

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Leaving a group takes its folders away. The whole difficulty is that "its folders" is not the
 * same as "the folders it hands out" — a teacher can give one folder to two of their classes, and
 * a student can be in both.
 *
 * Getting this wrong unfiles a deck the learner is in the middle of, with nothing on screen to
 * say why, which is why the rule is arithmetic in its own file rather than a WHERE clause.
 */
class GroupSweepTest {

    @Test
    fun `leaving a group gives up the folders it alone provided`() {
        val folders = mapOf(
            1 to setOf(10, 11),
            2 to setOf(20)
        )
        assertEquals(
            setOf(10, 11),
            GroupSweep.foldersToUnfile(folders, memberships = setOf(1, 2), leaving = 1)
        )
    }

    @Test
    fun `a folder both groups hand out survives leaving one of them`() {
        val folders = mapOf(
            1 to setOf(10, 11),
            2 to setOf(11, 12)
        )
        // 11 is still reachable through group 2, so only 10 is given up.
        assertEquals(
            setOf(10),
            GroupSweep.foldersToUnfile(folders, memberships = setOf(1, 2), leaving = 1)
        )
    }

    @Test
    fun `the last group leaves nothing behind`() {
        val folders = mapOf(1 to setOf(10, 11))
        assertEquals(
            setOf(10, 11),
            GroupSweep.foldersToUnfile(folders, memberships = setOf(1), leaving = 1)
        )
    }

    @Test
    fun `a group that handed out nothing takes nothing away`() {
        val folders = mapOf(1 to emptySet<Int>(), 2 to setOf(20))
        assertEquals(
            emptySet(),
            GroupSweep.foldersToUnfile(folders, memberships = setOf(1, 2), leaving = 1)
        )
    }

    @Test
    fun `memberships the student does not have cannot rescue a folder`() {
        val folders = mapOf(
            1 to setOf(10),
            2 to setOf(10)
        )
        // Group 2 also has folder 10, but this student is not in group 2.
        assertEquals(
            setOf(10),
            GroupSweep.foldersToUnfile(folders, memberships = setOf(1), leaving = 1)
        )
    }

    @Test
    fun `the plain set form is the same rule`() {
        assertEquals(setOf(10), GroupSweep.foldersToUnfile(setOf(10, 11), setOf(11, 12)))
        assertEquals(emptySet(), GroupSweep.foldersToUnfile(emptySet(), setOf(11)))
    }
}
