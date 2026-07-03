package n.startapp.services

import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.Users
import n.startapp.repositories.UserRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.slf4j.LoggerFactory
import java.time.Instant

class AccountDeletionService {
    private val logger = LoggerFactory.getLogger(AccountDeletionService::class.java)
    private val userRepository = UserRepository()

    suspend fun purgeDueAccounts() {
        val due = userRepository.findDueForDeletion(Instant.now())
        for (user in due) {
            dbQuery {
                Flashcards.deleteWhere { Flashcards.userId eq user.id }
                SavedWords.deleteWhere { SavedWords.userId eq user.id }
                Categories.deleteWhere { Categories.userId eq user.id }
                Users.deleteWhere { Users.id eq user.id }
            }
            logger.info("Purged account scheduled for deletion: userId={}", user.id)
        }
    }
}
