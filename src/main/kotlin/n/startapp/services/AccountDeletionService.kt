package n.startapp.services

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PushSubscriptions
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.TestingRequests
import n.startapp.database.tables.Users
import n.startapp.repositories.UserRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
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
     * already drifted from the schema: both deleted cards, words and folders, and neither
     * touched `push_subscriptions` or `testing_requests` — which also reference `users.id`, so a
     * user who had ever granted notifications could not be deleted at all.
     *
     * Order is dictated by the references, not by taste: cards point at words and folders, so
     * they go first; `testing_requests.user_id` is nullable and the request itself is a record of
     * something that happened, so it is unlinked rather than deleted.
     */
    suspend fun purgeUser(userId: Int) = dbQuery {
        PushSubscriptions.deleteWhere { PushSubscriptions.userId eq userId }
        TestingRequests.update({ TestingRequests.userId eq userId }) {
            it[TestingRequests.userId] = null
        }
        Flashcards.deleteWhere { Flashcards.userId eq userId }
        SavedWords.deleteWhere { SavedWords.userId eq userId }
        Categories.deleteWhere { Categories.userId eq userId }
        Users.deleteWhere { Users.id eq userId }
    }
}
