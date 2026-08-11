package n.startapp.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import n.startapp.models.ApiResponse
import n.startapp.services.ai.AiCompat
import n.startapp.services.settings.EnvironmentFact
import n.startapp.services.settings.RuntimeSettings
import n.startapp.services.settings.SettingView
import n.startapp.services.settings.SettingsCatalog
import org.slf4j.LoggerFactory

@Serializable
data class SettingsResponse(
    val settings: List<SettingView>,
    val environment: List<EnvironmentFact>
)

@Serializable
data class UpdateSettingRequest(val key: String, val value: String)

private val logger = LoggerFactory.getLogger("AdminSettings")

/**
 * Settings the panel may change while the server runs.
 *
 * The catalogue is a whitelist, not a view of the environment: credentials must never travel
 * back through a browser, and values like the database URL cannot take effect without a restart,
 * so offering them would be offering a lie.
 */
fun Route.adminSettingsRoutes() {
    route("/api/admin/settings") {

        get {
            if (call.rejectedAsNonAdmin()) return@get
            call.respond(
                ApiResponse.success(
                    SettingsResponse(
                        settings = SettingsCatalog.view(),
                        environment = SettingsCatalog.environment()
                    )
                )
            )
        }

        put {
            if (call.rejectedAsNonAdmin()) return@put
            val request = call.receive<UpdateSettingRequest>()

            val spec = SettingsCatalog.spec(request.key)
            if (spec == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<Nothing>("Настройка «${request.key}» не редактируется")
                )
                return@put
            }

            val value = request.value.trim()
            SettingsCatalog.validate(spec, value)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Nothing>(problem))
                return@put
            }

            RuntimeSettings.set(spec.key, value)
            forgetProviderDialectIfNeeded(spec.key)
            call.respond(ApiResponse.success(SettingsCatalog.view()))
        }

        // Removing the override is how a setting goes back to the deployment, which is why the
        // table holds overrides only — there would otherwise be nothing to fall back to.
        delete("/{key}") {
            if (call.rejectedAsNonAdmin()) return@delete
            val key = call.parameters["key"].orEmpty()
            if (SettingsCatalog.spec(key) == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Nothing>("Неизвестная настройка"))
                return@delete
            }
            RuntimeSettings.clear(key)
            forgetProviderDialectIfNeeded(key)
            call.respond(ApiResponse.success(SettingsCatalog.view()))
        }
    }
}

/**
 * A provider's dialect is captured on first use and then only narrowed by its rejections, so it
 * outlives the configuration it was derived from. Changing anything about how the model is
 * addressed has to drop it, or the panel would show one thing while the client sends another.
 */
private fun forgetProviderDialectIfNeeded(key: String) {
    if (key.startsWith("AI_")) AiCompat.reset(logger)
}
