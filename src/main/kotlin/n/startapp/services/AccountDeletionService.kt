package n.startapp.services

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Assignments
import n.startapp.database.tables.Categories
import n.startapp.database.tables.ContentReports
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PracticeAttempts
import n.startapp.database.tables.PushSubscriptions
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroupMembers
import n.startapp.database.tables.StudyGroups
import n.startapp.database.tables.TestingRequests
import n.startapp.database.tables.Users
import n.startapp.repositories.UserRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

class AccountDeletionService {
    private val logger = LoggerFactory.getLogger(AccountDeletionService::class.java)
    private val userRepository = UserRepository()

    suspend fun purgeDueAccounts() {
        val due = userRepository.findDueForDeletion(Instant.now())
        for (user in due) {
            purgeUser(user.id)
            logger.info("Purged account scheduled for deletion: userId={}", user.id)
        }
    }

    /**
     * Removes every row that names this user, in an order the foreign keys accept.
     *
     * One function rather than a block copied into the admin route, because the two copies had
     * already drifted from the schema: both deleted cards, words and folders, and neither touched
     * `push_subscriptions` or `testing_requests` — which also reference `users.id`, so a user who
     * had ever granted notifications could not be deleted at all.
     *
     * ⚠️ A teacher's account reaches rows that are not theirs. Their folders can be filed under by
     * their students' cards, and their groups own the memberships and the recorded practice of
     * everybody in them. Those students keep their decks — unfiled, the same as when a folder is
     * withdrawn any other way — but nothing may be left pointing at a row that is about to go.
     */
    suspend fun purgeUser(userId: Int) = dbQuery {
        val ownedGroups = StudyGroups
            .select(StudyGroups.id)
            .where { StudyGroups.ownerId eq userId }
            .map { it[StudyGroups.id] }

        val ownedCategories = Categories
            .select(Categories.id)
            .where { Categories.userId eq userId }
            .map { it[Categories.id] }

        // ── Groups this person ran, and their own place in anyone else's ──
        if (ownedGroups.isNotEmpty()) {
            PracticeAttempts.deleteWhere {
                (PracticeAttempts.userId eq userId) or (PracticeAttempts.groupId inList ownedGroups)
            }
            Assignments.deleteWhere { Assignments.groupId inList ownedGroups }
            StudyGroupFolders.deleteWhere { StudyGroupFolders.groupId inList ownedGroups }
            StudyGroupMembers.deleteWhere {
                (StudyGroupMembers.userId eq userId) or (StudyGroupMembers.groupId inList ownedGroups)
            }
            StudyGroups.deleteWhere { StudyGroups.ownerId eq userId }
        } else {
            PracticeAttempts.deleteWhere { PracticeAttempts.userId eq userId }
            StudyGroupMembers.deleteWhere { StudyGroupMembers.userId eq userId }
        }

        // ── Rows belonging to other people that point at this person's folders ──
        if (ownedCategories.isNotEmpty()) {
            // Students' decks survive, unfiled: the work that went into them was theirs.
            Flashcards.update({ Flashcards.categoryId inList ownedCategories }) {
                it[Flashcards.categoryId] = null
            }
            Assignments.update({ Assignments.categoryId inList ownedCategories }) {
                it[Assignments.categoryId] = null
            }
            PracticeAttempts.update({ PracticeAttempts.categoryId inList ownedCategories }) {
                it[PracticeAttempts.categoryId] = null
            }
            StudyGroupFolders.deleteWhere { StudyGroupFolders.categoryId inList ownedCategories }
        }

        // ── The account's own rows ──
        PushSubscriptions.deleteWhere { PushSubscriptions.userId eq userId }
        // The request is a record of something that happened, so it is unlinked, not deleted.
        TestingRequests.update({ TestingRequests.userId eq userId }) {
            it[TestingRequests.userId] = null
        }
        // Same for a complaint about generated text: it is about the article, not the reader,
        // and it still has to be acted on after they are gone. Unlinked, not deleted — and it
        // has to be one of the two, because the column references users.id and would otherwise
        // make the account undeletable.
        ContentReports.update({ ContentReports.userId eq userId }) {
            it[ContentReports.userId] = null
        }
        Flashcards.deleteWhere { Flashcards.userId eq userId }
        val savedWordIds = SavedWords
            .select(SavedWords.id)
            .where { SavedWords.userId eq userId }
            .map { it[SavedWords.id] }
        if (savedWordIds.isNotEmpty()) {
            SavedWordCategories.deleteWhere { SavedWordCategories.savedWordId inList savedWordIds }
        }
        SavedWords.deleteWhere { SavedWords.userId eq userId }
        Categories.deleteWhere { Categories.userId eq userId }
        Users.deleteWhere { Users.id eq userId }
    }
}
