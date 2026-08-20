package n.startapp.services.group

/**
 * What a student stops being able to reach when they leave a group.
 *
 * Pulled out as arithmetic on sets because the interesting case has nothing to do with SQL: a
 * teacher can hand the same folder to two of their classes, and a student can be in both. Leaving
 * one of them must take away only what the other does not still provide. Getting this wrong
 * unfiles a deck the learner is in the middle of, silently, with nothing on screen to explain it.
 */
object GroupSweep {

    /** @return the folders in [losing] that nothing else still gives the student. */
    fun foldersToUnfile(losing: Set<Int>, stillReachable: Set<Int>): Set<Int> = losing - stillReachable

    /**
     * The same rule stated over the whole picture, which is the form worth testing.
     *
     * @param groupFolders folders each group hands out, by group id
     * @param memberships every group the student belongs to, including [leaving]
     * @param leaving the group being left
     */
    fun foldersToUnfile(
        groupFolders: Map<Int, Set<Int>>,
        memberships: Set<Int>,
        leaving: Int
    ): Set<Int> {
        val losing = groupFolders[leaving].orEmpty()
        val stillReachable = memberships
            .asSequence()
            .filter { it != leaving }
            .flatMap { groupFolders[it].orEmpty().asSequence() }
            .toSet()
        return foldersToUnfile(losing, stillReachable)
    }
}
