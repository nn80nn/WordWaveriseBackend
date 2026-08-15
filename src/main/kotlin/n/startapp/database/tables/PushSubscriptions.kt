package n.startapp.database.tables

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Push endpoints a user has granted, one row per browser install.
 *
 * The endpoint is the identity, not the user: the same person on a phone and a laptop is two
 * subscriptions, and reinstalling the app produces a new endpoint while the old one lingers
 * until the push service reports it gone. Hence the unique index on the endpoint and the
 * delete-on-404/410 in [n.startapp.services.PushService].
 */
object PushSubscriptions : IntIdTable("push_subscriptions") {
    val userId = integer("user_id").references(Users.id)

    /** URL of the browser's push service. Long — FCM endpoints run past 200 characters. */
    val endpoint = text("endpoint").uniqueIndex()

    /** Client's public key and auth secret, base64url. Payloads are encrypted to these. */
    val p256dh = varchar("p256dh", 255)
    val auth = varchar("auth", 255)

    val userAgent = varchar("user_agent", 255).nullable()

    /**
     * Guards against a second reminder in the same day when the job runs more than once —
     * a restart re-enters the loop, and nothing is worse for a notification channel than
     * repeating itself.
     */
    val lastNotifiedAt = timestamp("last_notified_at").nullable()

    val createdAt = timestamp("created_at")
}
