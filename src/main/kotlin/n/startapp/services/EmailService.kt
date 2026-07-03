package n.startapp.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
private data class ResendEmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val html: String
)

class EmailService {
    private val logger = LoggerFactory.getLogger(EmailService::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
        }
    }

    private val apiKey: String get() = EnvConfig.resendApiKey
    private val fromEmail: String get() = EnvConfig.resendFromEmail

    suspend fun sendVerificationCode(email: String, code: String) {
        val html = """
            <p>Здравствуйте!</p>
            <p>Ваш код подтверждения для WordWaverise:</p>
            <p style="font-size: 28px; font-weight: bold; letter-spacing: 4px;">$code</p>
            <p>Код действителен 15 минут. Если вы не запрашивали регистрацию — просто проигнорируйте это письмо.</p>
        """.trimIndent()
        send(email, "Код подтверждения WordWaverise", html)
    }

    suspend fun sendDeletionScheduledEmail(email: String, scheduledFor: Instant) {
        val formatted = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.of("UTC"))
            .format(scheduledFor)
        val html = """
            <p>Здравствуйте!</p>
            <p>Мы получили запрос на удаление вашего аккаунта WordWaverise.</p>
            <p>Аккаунт и все данные будут удалены безвозвратно <strong>$formatted</strong>.</p>
            <p>Если это не вы или вы передумали — просто войдите в аккаунт и отмените удаление в настройках профиля до этой даты.</p>
        """.trimIndent()
        send(email, "Запрос на удаление аккаунта WordWaverise", html)
    }

    private suspend fun send(toEmail: String, subject: String, html: String) {
        if (apiKey.isEmpty()) {
            logger.error("Email not sent to {}: RESEND_API_KEY missing", toEmail)
            return
        }
        try {
            httpClient.post("https://api.resend.com/emails") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(ResendEmailRequest(from = fromEmail, to = listOf(toEmail), subject = subject, html = html))
            }
        } catch (e: Exception) {
            logger.error("Failed to send email to {}: {}", toEmail, e.message)
        }
    }

    fun close() {
        httpClient.close()
    }
}
