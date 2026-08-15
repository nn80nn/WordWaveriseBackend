package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.exceptions.BadRequestException
import n.startapp.models.ApiResponse
import n.startapp.services.PushService

@Serializable
data class PushSubscribeRequest(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
)

@Serializable
data class PushUnsubscribeRequest(val endpoint: String)

@Serializable
data class PushConfig(val publicKey: String, val enabled: Boolean)

@Serializable
data class PushStatus(val subscriptions: Long)

@Serializable
data class PushTestResult(val delivered: Int)

/**
 * The browser needs the VAPID public key before it can even ask permission, so that one
 * endpoint is public; everything that stores or sends is behind the JWT.
 */
fun Route.pushRoutes(pushService: PushService) {
    route("/api/push") {

        // Публичный: ключ и так уезжает в каждый браузер, а без него нельзя подписаться.
        get("/config") {
            call.respond(
                ApiResponse.success(PushConfig(pushService.publicKey, pushService.configured)),
            )
        }

        authenticate("auth-jwt") {
            post("/subscribe") {
                if (!pushService.configured) {
                    // Хранить подписку, которую некому доставить, значит копить мусор и
                    // обещать человеку уведомления, которых не будет.
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiResponse.error<Nothing>("Push notifications are not configured"),
                    )
                    return@post
                }

                val body = call.receive<PushSubscribeRequest>()
                if (body.endpoint.isBlank() || body.p256dh.isBlank() || body.auth.isBlank()) {
                    throw BadRequestException("endpoint, p256dh and auth are required")
                }

                pushService.subscribe(
                    owner = call.userId(),
                    endpointUrl = body.endpoint,
                    clientKey = body.p256dh,
                    clientAuth = body.auth,
                    agent = call.request.headers[HttpHeaders.UserAgent],
                )
                call.respond(ApiResponse.success("subscribed"))
            }

            post("/unsubscribe") {
                val body = call.receive<PushUnsubscribeRequest>()
                pushService.unsubscribe(body.endpoint)
                call.respond(ApiResponse.success("unsubscribed"))
            }

            get("/status") {
                call.respond(ApiResponse.success(PushStatus(pushService.countFor(call.userId()))))
            }

            /** Отправляет себе то же уведомление, что придёт вечером. */
            post("/test") {
                val sent = pushService.sendToUser(
                    call.userId(),
                    PushService.Payload(
                        title = "WordWaverise",
                        body = "Проверка уведомлений — всё работает",
                        url = "/flashcards?src=push-test",
                        tag = "ww-test",
                    ),
                )
                call.respond(ApiResponse.success(PushTestResult(sent)))
            }
        }
    }
}
