package n.startapp

import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import n.startapp.database.DatabaseFactory
import n.startapp.services.AccountDeletionService
import n.startapp.services.settings.RuntimeSettings
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    // Initialize database
    DatabaseFactory.init()

    // Before anything reads configuration: overrides set from the admin panel have to be in
    // place, or the boot-time reads (warm-up auto-start, the log line below) would report the
    // deployment while the running server obeys something else.
    RuntimeSettings.load()

    logEffectiveAiConfig()

    // Configure application
    configureLogging()
    configureSerialization()
    configureExceptionHandling()
    configureHTTP()
    configureAuthentication()

    val services = ServiceRegistry()
    configureRouting(services)
    monitor.subscribe(ApplicationStopping) { services.close() }

    // Periodically purge accounts whose deletion grace period has elapsed
    val logger = LoggerFactory.getLogger("AccountDeletionJob")
    launch {
        while (isActive) {
            delay(1.hours)
            runCatching { AccountDeletionService().purgeDueAccounts() }
                .onFailure { logger.error("Account deletion sweep failed: {}", it.message) }
        }
    }

    // Daily review reminder.
    //
    // Hourly ticks with an hour check rather than a scheduler: a container restart must not
    // skip the day's send nor cause a second one, and the 20-hour floor inside
    // PushService.sendReviewReminders is what actually makes it idempotent. Waking every hour
    // and doing nothing 23 times costs less than owning a cron.
    val pushLogger = LoggerFactory.getLogger("PushReminderJob")
    launch {
        while (isActive) {
            delay(1.hours)
            if (!EnvConfig.pushRemindersEnabled) continue
            if (java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).hour != EnvConfig.pushReminderHourUtc) continue

            runCatching { services.pushService.sendReviewReminders() }
                .onSuccess { if (it > 0) pushLogger.info("Review reminders sent: {}", it) }
                .onFailure { pushLogger.error("Review reminder sweep failed: {}", it.message) }
        }
    }
}

/**
 * The AI model in use was previously invisible: it lives in a code default, so a missing
 * AI_MODEL meant production quietly ran a different model than anyone assumed. Print the
 * resolved config once at boot so a deploy can be checked from the logs alone.
 */
private fun logEffectiveAiConfig() {
    val logger = LoggerFactory.getLogger("AiConfig")
    val domain = EnvConfig.aiDomen
    logger.info(
        "AI config: domain={} key={} model={} modelFast={} tokenParam={} temperature={} timeoutMs={}",
        domain.ifBlank { "<MISSING>" },
        if (EnvConfig.aiApiKey.isBlank()) "<MISSING>" else "set",
        EnvConfig.aiModel,
        EnvConfig.aiModelFast,
        EnvConfig.aiTokenParam,
        if (EnvConfig.aiSupportsTemperature) "sent" else "omitted",
        EnvConfig.aiTimeoutMs
    )
    if (domain.isBlank() || EnvConfig.aiApiKey.isBlank()) {
        logger.error("AI_DOMEN or AI_API is not set — every AI feature will fail with 503.")
    }
}
