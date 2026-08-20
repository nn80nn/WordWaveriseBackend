package n.startapp.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger

/**
 * A private, empty database with the application's schema in it.
 *
 * ⚠️ `TransactionManager.defaultDatabase` is set explicitly, and that is not tidiness. `dbQuery`
 * runs on `Dispatchers.IO`, where there is no current transaction to inherit a database from, so
 * a suspended call resolves against the default — which `Database.connect` does not necessarily
 * move. Without this line a repository under test writes into whichever database happened to be
 * registered first, and the failure reads as "table not found" rather than as a wiring mistake.
 */
object TestDatabase {
    private val counter = AtomicInteger(1)

    fun <T> fresh(label: String, block: () -> T): T {
        val name = "${label}_${counter.getAndIncrement()}_${System.nanoTime()}"
        val db = Database.connect(
            "jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        transaction(db) { SchemaUtils.createMissingTablesAndColumns(*DatabaseFactory.ALL_TABLES) }
        return block()
    }
}
