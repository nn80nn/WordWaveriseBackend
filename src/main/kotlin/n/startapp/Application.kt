package n.startapp

import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import n.startapp.database.DatabaseFactory
import n.startapp.services.AccountDeletionService
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    // Initialize database
    DatabaseFactory.init()

    // Configure application
    configureLogging()
    configureSerialization()
    configureExceptionHandling()
    configureHTTP()
    configureAuthentication()
    configureRouting()

    // Periodically purge accounts whose deletion grace period has elapsed
    val logger = LoggerFactory.getLogger("AccountDeletionJob")
    launch {
        while (isActive) {
            delay(1.hours)
            runCatching { AccountDeletionService().purgeDueAccounts() }
                .onFailure { logger.error("Account deletion sweep failed: {}", it.message) }
        }
    }
}
