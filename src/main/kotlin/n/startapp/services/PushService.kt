package n.startapp.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.Flashcards
import n.startapp.database.tables.PushSubscriptions
import n.startapp.utils.EnvConfig
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService as WebPushClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.Security
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Web Push: the one thing that makes an installed web app behave like an installed app.
 *
 * On iOS this only reaches a home-screen install — Safari refuses to deliver to a tab — which
 * is most of the argument for installing at all, so the reminder and the install prompt are
 * two halves of the same feature.
 *
 * Fail-closed on configuration: with no VAPID keys nothing sends and nothing is stored, rather
 * than accumulating subscriptions that can never be delivered to.
 */
class PushService {
    private val logger = LoggerFactory.getLogger(PushService::class.java)

    @Serializable
    data class Payload(
        val title: String,
        val body: String,
        val url: String = "/flashcards?src=push",
        val tag: String = "ww-review",
    )

    val publicKey: String get() = EnvConfig.vapidPublicKey
    val configured: Boolean
        get() = EnvConfig.vapidPublicKey.isNotBlank() && EnvConfig.vapidPrivateKey.isNotBlank()

    // ── Subscriptions ────────────────────────────────────────────────────────

    /**
     * Stores an endpoint, replacing any earlier row for it.
     *
     * The same endpoint can move between accounts on a shared device, and the browser hands
     * back the identical string; overwriting is the only way the reminder follows the person
     * actually signed in.
     */
    suspend fun subscribe(
        owner: Int,
        endpointUrl: String,
        clientKey: String,
        clientAuth: String,
        agent: String?,
    ) {
        // Параметры названы не как колонки намеренно: внутри `deleteWhere` получателем стоит
        // сама таблица, и `endpoint eq endpoint` сравнил бы колонку сама с собой.
        dbQuery {
            PushSubscriptions.deleteWhere { endpoint eq endpointUrl }
            PushSubscriptions.insert {
                it[userId] = owner
                it[endpoint] = endpointUrl
                it[p256dh] = clientKey
                it[auth] = clientAuth
                it[userAgent] = agent?.take(255)
                it[createdAt] = Instant.now()
            }
        }
    }

    suspend fun unsubscribe(endpointUrl: String) {
        dbQuery { PushSubscriptions.deleteWhere { endpoint eq endpointUrl } }
    }

    suspend fun countFor(userId: Int): Long =
        dbQuery { PushSubscriptions.selectAll().where { PushSubscriptions.userId eq userId }.count() }

    // ── Delivery ─────────────────────────────────────────────────────────────

    private data class Target(val id: Int, val endpoint: String, val p256dh: String, val auth: String)

    /**
     * Sends to one user's endpoints. Returns how many were accepted.
     *
     * A push service answering 404 or 410 is stating the subscription is dead — the app was
     * uninstalled or the browser rotated it. Keeping such a row means retrying it forever, so
     * it is deleted on the spot; every other failure is transient and left alone.
     */
    suspend fun sendToUser(userId: Int, payload: Payload): Int {
        if (!configured) return 0

        val targets = dbQuery {
            PushSubscriptions.selectAll()
                .where { PushSubscriptions.userId eq userId }
                .map {
                    Target(
                        it[PushSubscriptions.id].value,
                        it[PushSubscriptions.endpoint],
                        it[PushSubscriptions.p256dh],
                        it[PushSubscriptions.auth],
                    )
                }
        }
        if (targets.isEmpty()) return 0

        val body = Json.encodeToString(Payload.serializer(), payload)
        var accepted = 0

        for (target in targets) {
            val status = runCatching {
                client().send(Notification(target.endpoint, target.p256dh, target.auth, body.toByteArray()))
                    .statusLine.statusCode
            }.getOrElse {
                logger.warn("Push to subscription {} failed: {}", target.id, it.message)
                null
            } ?: continue

            when {
                status in 200..299 -> accepted++
                status == 404 || status == 410 -> {
                    logger.info("Subscription {} is gone ({}), removing", target.id, status)
                    val deadId = target.id
                    dbQuery { PushSubscriptions.deleteWhere { id eq deadId } }
                }
                else -> logger.warn("Push to subscription {} returned {}", target.id, status)
            }
        }

        if (accepted > 0) {
            dbQuery {
                PushSubscriptions.update({ PushSubscriptions.userId eq userId }) {
                    it[lastNotifiedAt] = Instant.now()
                }
            }
        }
        return accepted
    }

    /**
     * One reminder a day to everyone who has cards waiting.
     *
     * Users with nothing due are skipped rather than nudged — a notification that opens an
     * empty deck teaches people to ignore the channel, and this is the only channel there is.
     */
    suspend fun sendReviewReminders(): Int {
        if (!configured) return 0

        val cutoff = Instant.now().minus(20, ChronoUnit.HOURS)
        val now = Instant.now()

        val candidates = dbQuery {
            PushSubscriptions.selectAll()
                .where {
                    PushSubscriptions.lastNotifiedAt.isNull() or (PushSubscriptions.lastNotifiedAt lessEq cutoff)
                }
                .map { it[PushSubscriptions.userId] }
                .distinct()
        }

        var sent = 0
        for (userId in candidates) {
            val due = dbQuery {
                Flashcards.selectAll()
                    .where { (Flashcards.userId eq userId) and (Flashcards.nextReview lessEq now) }
                    .count()
            }
            if (due == 0L) continue

            sent += sendToUser(
                userId,
                Payload(
                    title = "Пора повторить",
                    body = if (due == 1L) "1 карточка ждёт повторения" else "$due ${cardsWord(due)} ждут повторения",
                ),
            )
        }

        if (sent > 0) logger.info("Review reminders delivered: {}", sent)
        return sent
    }

    private fun cardsWord(count: Long): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> "карточек"
            mod10 in 2..4 -> "карточки"
            mod10 == 1L -> "карточка"
            else -> "карточек"
        }
    }

    // ── Client ───────────────────────────────────────────────────────────────

    /**
     * Built per call rather than held: the keys are read through [EnvConfig], which the admin
     * panel can change while the server runs, and a cached client would keep signing with the
     * key that was current at boot.
     */
    private fun client(): WebPushClient {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        return WebPushClient(EnvConfig.vapidPublicKey, EnvConfig.vapidPrivateKey, EnvConfig.vapidSubject)
    }
}
