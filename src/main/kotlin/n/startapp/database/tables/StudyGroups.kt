package n.startapp.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * A class: one teacher, many students, some folders and some assignments.
 *
 * Named `study_groups` rather than `groups` because `GROUPS` is a keyword in Postgres and every
 * hand-written query against it would need quoting forever after.
 *
 * There is no role column anywhere. Ownership lives here, membership lives in
 * [StudyGroupMembers], and the two cannot overlap — a role column would allow "the teacher of
 * this group is also listed as its student", a state nothing downstream knows how to render.
 */
object StudyGroups : Table("study_groups") {
    val id = integer("id").autoIncrement()
    val ownerId = integer("owner_id").references(Users.id)
    val name = varchar("name", 120)

    /**
     * The invite link, minted on request and stable afterwards — the same contract as
     * [Categories.shareToken], and for the same reason: a second press of "пригласить" must not
     * invalidate the link already sent to a class.
     */
    val inviteToken = varchar("invite_token", 32).nullable().uniqueIndex()

    /**
     * The typed-in form of the same permission, for a student holding a phone and not a link.
     * Eight characters from an alphabet without lookalikes (see `CategoryRepository.newToken`).
     */
    val joinCode = varchar("join_code", 8).nullable().uniqueIndex()

    val archived = bool("archived").default(false)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, ownerId)
    }
}
