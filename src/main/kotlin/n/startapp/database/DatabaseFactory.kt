package n.startapp.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import n.startapp.database.tables.AppSettings
import n.startapp.database.tables.Assignments
import n.startapp.database.tables.Categories
import n.startapp.database.tables.ContentReports
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.LexicalEntries
import n.startapp.database.tables.LlmCache
import n.startapp.database.tables.PracticeAttempts
import n.startapp.database.tables.PushSubscriptions
import n.startapp.database.tables.SavedWordCategories
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.ScraperCache
import n.startapp.database.tables.StudyGroupFolders
import n.startapp.database.tables.StudyGroupMembers
import n.startapp.database.tables.StudyGroups
import n.startapp.database.tables.TestingRequests
import n.startapp.database.tables.Users
import n.startapp.database.tables.WarmupQueue
import n.startapp.utils.EnvConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Database factory for managing database connections and initialization
 */
object DatabaseFactory {

    /**
     * Every table the application owns, parents before children.
     *
     * A list rather than an inline vararg so the schema test can create exactly what startup
     * creates. Creating tables is the one step of boot with no rollback — a definition Exposed
     * cannot emit takes the server down, and by then the deploy that did it is already live.
     */
    val ALL_TABLES: Array<Table> = arrayOf(
        Users, Categories, SavedWords, SavedWordCategories, Flashcards, ScraperCache, TestingRequests,
        LlmCache, LexicalEntries, AppSettings, WarmupQueue, PushSubscriptions,
        StudyGroups, StudyGroupMembers, StudyGroupFolders, Assignments, PracticeAttempts,
        ContentReports
    )

    fun init() {
        println("🔌 Initializing database connection...")
        println("   URL: ${EnvConfig.dbUrl}")
        println("   User: ${EnvConfig.dbUser}")

        val config = HikariConfig().apply {
            driverClassName = EnvConfig.dbDriver
            jdbcUrl = EnvConfig.dbUrl
            username = EnvConfig.dbUser
            password = EnvConfig.dbPassword
            maximumPoolSize = 10
            minimumIdle = 2
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000

            // Additional connection properties
            addDataSourceProperty("ApplicationName", "WordWaveriseBackend")
            addDataSourceProperty("connectTimeout", "10")

            validate()
        }

        try {
            val dataSource = HikariDataSource(config)
            Database.connect(dataSource)
            println("✅ Database connection pool created successfully")

            // Create tables if they do not exist
            transaction {
                println("📋 Creating database tables if they do not exist...")
                SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES)
                println("✅ Database tables ready")

                // Создать недостающее мало: старый уникальный индекс на (user_id, word)
                // пережил бы создание новой таблицы и продолжил запрещать второе значение
                // слова — фича собралась бы, задеплоилась и не работала.
                SavedWordFolderMigration.run()
            }
        } catch (e: Exception) {
            println("❌ Database initialization failed: ${e.message}")
            throw e
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
