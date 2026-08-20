package n.startapp.services.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who may see a folder, and as what. Pulled out of the database query so the decision can be read
 * in one place — every other check in the feature is a plain `owner_id = ?`, and this is the only
 * spot where the answer is not obvious.
 */
class FolderAccessRulesTest {

    @Test
    fun `your own folder is yours`() {
        assertEquals(
            FolderRole.OWNER,
            FolderAccessRules.roleFor(isOwner = true, reachedThroughGroup = false)
        )
    }

    @Test
    fun `a folder a group hands you is readable, not yours`() {
        assertEquals(
            FolderRole.GROUP_MEMBER,
            FolderAccessRules.roleFor(isOwner = false, reachedThroughGroup = true)
        )
    }

    @Test
    fun `a folder that reaches you neither way is invisible`() {
        // Null rather than a role: the caller turns this into a 404, which does not confirm that
        // the id exists at all.
        assertNull(FolderAccessRules.roleFor(isOwner = false, reachedThroughGroup = false))
    }

    @Test
    fun `owning wins over membership`() {
        // Should not arise — a folder is assigned by its owner, and an owner is not a member of
        // their own group — but if it ever did, the answer that loses the least is "still yours".
        assertEquals(
            FolderRole.OWNER,
            FolderAccessRules.roleFor(isOwner = true, reachedThroughGroup = true)
        )
    }

    @Test
    fun `only a borrowed folder is read-only`() {
        fun folder(role: FolderRole) = ResolvedFolder(
            categoryId = 1,
            contentOwnerId = 2,
            role = role,
            groupId = null,
            groupName = null,
            name = "Unit 5",
            color = null
        )
        assertEquals(false, folder(FolderRole.OWNER).readOnly)
        assertEquals(true, folder(FolderRole.GROUP_MEMBER).readOnly)
    }
}
