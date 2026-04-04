package n.startapp.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import n.startapp.database.tables.Categories
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.SavedWords
import n.startapp.database.tables.ScraperCache
import n.startapp.database.tables.Users
import n.startapp.utils.EnvConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Database factory for managing database connections and initialization
 */
object DatabaseFactory {
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

            // Create tables if they don't exist
            transaction {
                println("📋 Creating database tables if they don't exist...")
                SchemaUtils.createMissingTablesAndColumns(Users, Categories, SavedWords, Flashcards, ScraperCache)
                println("✅ Database tables ready")
            }
        } catch (e: Exception) {
            println("❌ Database initialization failed: ${e.message}")
            throw e
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
